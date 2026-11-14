package app.zaid.flow.ui.screens.shorts

import android.util.Log
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import app.zaid.flow.data.local.DataSaver
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.ShortsPreloadSettings
import app.zaid.flow.data.model.ShortVideo
import app.zaid.flow.player.shorts.ShortsPlayerPool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "ShortsPagerEffects"

/**
 * Gap between look-ahead Shorts. What is preloaded, how far ahead, and how Data Saver and metered
 * connections change that all come from [ShortsPreloadSettings]. Only small responses are fetched,
 * never video bytes.
 */
private const val METADATA_PRELOAD_GAP_MS = 400L

/** A reel the user swiped past in under a second was never really shown. */
private const val SHOWN_DWELL_MS = 1_000L

/**
 * Prepares the settled reel and its neighbours whenever the pager rests, and re-resolves them when
 * the quality preference changes underneath a running reel.
 */
@Composable
internal fun ShortsPagerPlaybackEffects(
    pagerState: PagerState,
    shorts: List<ShortVideo>,
    targetHeight: Int?,
    playerPreferences: PlayerPreferences,
    viewModel: ShortsViewModel,
    onAutoHeightChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val settledPage = pagerState.settledPage
    val settledShortId = shorts.getOrNull(settledPage)?.id
    val preparedShortId = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settledPage, settledShortId, targetHeight) {
        val height = targetHeight ?: return@LaunchedEffect
        val current = shorts.getOrNull(settledPage) ?: return@LaunchedEffect
        preparedShortId.value = null
        val playerPool = ShortsPlayerPool.getInstance()
        playerPool.initialize(context)
        playerPool.setCurrentVideo(current)
        val preferredLang = playerPreferences.preferredAudioLanguage.first()

        playerPool.activatePlayer(settledPage, current.id)
        prepareReel(playerPool, viewModel, settledPage, current, height, preferredLang, shouldPlay = true)
        viewModel.loadShortDetails(current.id)
        launch {
            delay(SHOWN_DWELL_MS)
            viewModel.onReelShown(current.id)
        }

        playerPool.releaseUnusedPlayers(settledPage)
        preparedShortId.value = current.id
    }

    // Keyed on the neighbours' ids as well as the page: enrichment can reorder the reels after the
    // current one, and the pool would otherwise keep buffering whichever short used to sit there.
    // Kept apart from the effect above so a neighbour change never re-activates a paused reel.
    val previousShortId = shorts.getOrNull(settledPage - 1)?.id
    val nextShortId = shorts.getOrNull(settledPage + 1)?.id
    val preloadSettings by remember(playerPreferences) { playerPreferences.shortsPreloadSettings }
        .collectAsState(initial = ShortsPreloadSettings())
    val dataSaverOn by remember(playerPreferences) { playerPreferences.dataSaverEnabled }
        .collectAsState(initial = DataSaver.enabled)
    val metered =
        remember {
            (context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                ?.isActiveNetworkMetered == true
        }
    val plan = remember(preloadSettings, dataSaverOn, metered) { preloadSettings.planFor(dataSaverOn, metered) }
    val preloadShortIds = (2 until 2 + plan.ahead).mapNotNull { shorts.getOrNull(settledPage + it)?.id }
    LaunchedEffect(settledPage, settledShortId, previousShortId, nextShortId, preloadShortIds, targetHeight, plan) {
        val height = targetHeight ?: return@LaunchedEffect
        val currentId = settledShortId ?: return@LaunchedEffect
        val settled = settledPage
        val playerPool = ShortsPlayerPool.getInstance()
        // Each resolve mints a BotGuard PoToken, and those serialise on one process-wide WebView, so
        // the neighbours wait for the short the user is looking at rather than queue ahead of it.
        snapshotFlow { preparedShortId.value }.first { it == currentId }
        val preferredLang = playerPreferences.preferredAudioLanguage.first()

        shorts.getOrNull(settled + 1)?.let { next ->
            viewModel.preloadShortExtras(next.id, details = plan.details, description = plan.description)
            launch { prepareReel(playerPool, viewModel, settled + 1, next, height, preferredLang, shouldPlay = false) }
        }
        // The short behind is not prepared: viewers swipe forward, and one just left is still held by its
        // pooled player, so swiping back resumes at once without fetching or buffering anything new.
        // Further ahead: metadata resolved only, not handed to a player. One at a time with a short gap,
        // after the neighbours, so it never competes with the visible short or bursts on the network.
        launch {
            for (offset in 2 until 2 + plan.ahead) {
                val preload = shorts.getOrNull(settled + offset) ?: break
                delay(METADATA_PRELOAD_GAP_MS)
                if (plan.streams) runCatching { viewModel.getPlaybackStreams(preload.id, height, preferredLang) }
                viewModel.preloadShortExtras(preload.id, details = plan.details, description = plan.description)
            }
        }
    }

    // A short that errored, stalled or froze: its URLs may have expired, so resolve new ones.
    LaunchedEffect(targetHeight) {
        val height = targetHeight ?: return@LaunchedEffect
        val playerPool = ShortsPlayerPool.getInstance()
        playerPool.streamFailures.collect { failure ->
            launch {
                val preferredLang = playerPreferences.preferredAudioLanguage.first()
                val fresh =
                    runCatching { viewModel.refreshPlaybackStreams(failure.videoId, height, preferredLang) }.getOrNull()
                val replaced =
                    fresh != null &&
                        playerPool.replaceStreams(
                            failure.index,
                            failure.videoId,
                            fresh.videoUrl,
                            fresh.audioUrl,
                            fresh.videoDashManifest,
                            fresh.audioDashManifest,
                        )
                if (!replaced) playerPool.recoverInPlace(failure.index, failure.videoId)
            }
        }
    }

    val previousTargetHeight = remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(targetHeight) {
        val height = targetHeight ?: return@LaunchedEffect
        val previous = previousTargetHeight.value
        previousTargetHeight.value = height
        // The first non-null value is the preference loading, not the user changing it. The settle
        // effect above already prepares at that height.
        if (previous == null || height == previous) return@LaunchedEffect

        val settled = pagerState.settledPage
        val playerPool = ShortsPlayerPool.getInstance()
        val preferredLang = playerPreferences.preferredAudioLanguage.first()

        shorts.getOrNull(settled)?.let { current -> reloadReel(playerPool, viewModel, settled, current, height, preferredLang) }
        shorts.getOrNull(settled + 1)?.let { next ->
            launch { reloadReel(playerPool, viewModel, settled + 1, next, height, preferredLang) }
        }
    }
}

private suspend fun prepareReel(
    playerPool: ShortsPlayerPool,
    viewModel: ShortsViewModel,
    index: Int,
    short: ShortVideo,
    targetHeight: Int,
    preferredLang: String,
    shouldPlay: Boolean,
) {
    try {
        val streams = viewModel.getPlaybackStreams(short.id, targetHeight, preferredLang)
        if (streams == null) {
            Log.w(TAG, "No stream URL resolved for ${short.id}")
            return
        }
        playerPool.prepare(
            index = index,
            videoId = short.id,
            videoUrl = streams.videoUrl,
            audioUrl = streams.audioUrl,
            shouldPlay = shouldPlay,
            videoDashManifest = streams.videoDashManifest,
            audioDashManifest = streams.audioDashManifest,
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to prepare player for ${short.id}", e)
    }
}

private suspend fun reloadReel(
    playerPool: ShortsPlayerPool,
    viewModel: ShortsViewModel,
    index: Int,
    short: ShortVideo,
    targetHeight: Int,
    preferredLang: String,
) {
    try {
        val streams = viewModel.getPlaybackStreams(short.id, targetHeight, preferredLang) ?: return
        playerPool.reloadWithVideoUrl(index, short.id, streams.videoUrl, streams.videoDashManifest)
    } catch (e: Exception) {
        Log.e(TAG, "Quality change: failed to reload ${short.id}", e)
    }
}
