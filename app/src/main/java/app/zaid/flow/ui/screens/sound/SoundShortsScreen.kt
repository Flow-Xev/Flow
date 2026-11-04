package app.zaid.flow.ui.screens.sound

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zaid.flow.R
import app.zaid.flow.data.model.ShortVideo
import app.zaid.flow.data.model.toShortVideo
import app.zaid.flow.data.shorts.queue.ShortsQueueHandoff
import app.zaid.flow.data.shorts.queue.ShortsQueuePage
import app.zaid.flow.data.shorts.queue.ShortsQueueSource
import app.zaid.flow.data.shorts.queue.SnapshotContinuation
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.pages.reel.SoundContinuation
import app.zaid.flow.innertube.pages.reel.SoundPage
import app.zaid.flow.utils.formatViewCount
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import javax.inject.Inject

data class SoundShortsState(
    val loading: Boolean = true,
    val shorts: List<ShortVideo> = emptyList(),
    /** The total YouTube states for the whole sound; null when the page states none. */
    val total: Long? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val debug: String = "",
) {
    /** YouTube's total, never below what is already on screen. */
    val count: Long get() = maxOf(total ?: 0L, shorts.size.toLong())

    /** Only a lower bound is known: no stated total and more pages still to come. */
    val countIsLowerBound: Boolean get() = total == null && hasMore
}

/** One line per attempt, saying plainly whether YouTube rejected it, the network failed, or it came back empty. */
internal fun describeSoundAttempt(
    name: String,
    error: Throwable?,
    count: Int,
): String =
    when {
        error is io.ktor.client.plugins.ResponseException -> {
            val code = error.response.status.value
            val kind = if (code in 500..599) "YOUTUBE SERVER ERROR" else "REJECTED BY YOUTUBE"
            "$name: $kind (HTTP $code ${error.response.status.description})"
        }

        error is java.io.IOException -> {
            "$name: NETWORK FAILED (${error.javaClass.simpleName}: ${error.message?.take(80)})"
        }

        error != null -> {
            "$name: FAILED (${error.javaClass.simpleName}: ${error.message?.take(80)})"
        }

        count == 0 -> {
            "$name: OK but YouTube returned 0 shorts"
        }

        else -> {
            "$name: OK, $count shorts"
        }
    }

internal fun soundLogHeader(
    videoId: String,
    browseId: String,
    params: String,
): String {
    val locale = YouTube.locale
    return buildString {
        appendLine("FLOW SOUND SCREEN")
        appendLine("Video: $videoId")
        appendLine(
            "Sound link in short: ${if (browseId.isBlank()) "none" else browseId}, params ${if (params.isBlank()) "none" else "present"}",
        )
        appendLine("Signed in: ${YouTube.cookie != null}")
        appendLine("Locale: hl=${locale.hl}, gl=${locale.gl}")
        appendLine("Visitor data present: ${!YouTube.visitorData.isNullOrBlank()}")
        appendLine()
    }
}

@HiltViewModel
class SoundShortsViewModel
    @Inject
    constructor(
        private val handoff: ShortsQueueHandoff,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SoundShortsState())
        val state: StateFlow<SoundShortsState> = _state
        private var loadedKey: String? = null
        var debug: String = ""
            private set

        // Paging state. Everything below is only touched while holding [pageLock].
        private val pageLock = Mutex()
        private var next: SoundContinuation? = null
        private val seen = HashSet<String>()
        private val usedTokens = HashSet<String>()

        fun load(
            browseId: String,
            params: String,
            videoId: String,
        ) {
            val key = "$browseId|$params|$videoId"
            if (key == loadedKey) return
            loadedKey = key
            viewModelScope.launch {
                val first = withContext(Dispatchers.IO) { firstPage(browseId, params, videoId) }
                pageLock.withLock {
                    seen.clear()
                    usedTokens.clear()
                    seen.addAll(first.reels.map { it.id })
                    first.next?.let { usedTokens.add(it.token) }
                    next = first.next
                }
                _state.value =
                    SoundShortsState(
                        loading = false,
                        shorts = first.reels.map { it.toShortVideo() },
                        total = first.total,
                        hasMore = first.next != null,
                        debug = debug,
                    )
            }
        }

        /**
         * Only the first page: the grid fills as the person scrolls ([loadMore]) instead of walking every
         * page before showing anything.
         */
        private suspend fun firstPage(
            browseId: String,
            params: String,
            videoId: String,
        ): SoundPage {
            // Try each source in turn; a lone reel usually means the source only returned the seed, so the
            // next one gets a chance too.
            val log = StringBuilder(soundLogHeader(videoId, browseId, params))

            fun Result<SoundPage>.mapped(name: String): SoundPage {
                val page = getOrNull() ?: SoundPage(emptyList())
                log.appendLine(describeSoundAttempt(name, exceptionOrNull(), page.reels.size))
                YouTube.takeSoundShape().takeIf { it.isNotBlank() }?.let { log.append(it) }
                return page
            }
            val sourceIds = if (videoId.isBlank()) emptyList() else YouTube.soundSourceIds(videoId)
            log.append(YouTube.takeSoundShape())
            log.appendLine("Audio source ids: ${sourceIds.ifEmpty { listOf("none, using the short itself") }}")
            val candidateIds = (sourceIds + listOfNotNull(videoId.takeIf { it.isNotBlank() })).distinct()
            val sources: List<suspend () -> SoundPage> =
                buildList {
                    if (videoId.isNotBlank()) add { YouTube.soundShortsFromPanel(videoId).mapped("panel") }
                    for (id in candidateIds) {
                        add { YouTube.soundShortsFromPage(id, feed = true).mapped("feed page $id") }
                        add { YouTube.soundShortsViaAudioPivot(id).mapped("pivot $id") }
                        add {
                            val source = YouTube.soundEndpointForVideo(id).getOrNull()
                            if (source == null) {
                                log.appendLine("resolve $id: FAILED (YouTube gave no sound page)")
                                SoundPage(emptyList())
                            } else {
                                YouTube.soundShorts(source.first, source.second).mapped("resolve $id")
                            }
                        }
                        add { YouTube.soundShortsFromPage(id).mapped("page $id") }
                    }
                    if (browseId.isNotBlank()) add { YouTube.soundShorts(browseId, params).mapped("link") }
                    if (videoId.isNotBlank()) {
                        add {
                            val overlay = YouTube.reelOverlay(videoId).getOrNull()
                            val id = overlay?.soundBrowseId.orEmpty()
                            if (id.isBlank()) {
                                log.appendLine("overlay: FAILED (short details contain no sound link)")
                                SoundPage(emptyList())
                            } else {
                                YouTube.soundShorts(id, overlay?.soundParams).mapped("overlay")
                            }
                        }
                    }
                }
            var best = SoundPage(emptyList())
            for (source in sources) {
                val page = source()
                if (page.reels.size > best.reels.size) best = page
                if (best.reels.size > 1) break
            }
            // Still only the seed but YouTube has a next page: pull a few so the screen is not a lone tile.
            var extra = 0
            while (best.reels.size < 2 && best.next != null && extra++ < MAX_FILL_PAGES) {
                val more = YouTube.soundMore(best.next ?: break).getOrNull() ?: break
                best = more.copy(reels = (best.reels + more.reels).distinctBy { it.id }, total = best.total)
            }
            log.appendLine("First page: ${best.reels.size} shorts, total ${best.total ?: "not stated"}, more pages: ${best.next != null}")
            debug = log.toString()
            android.util.Log.w("SoundShorts", debug)
            return best
        }

        /** Called by the grid when the person nears the end of what is loaded. */
        fun loadMore() {
            val s = _state.value
            if (s.loading || !s.hasMore || s.loadingMore || s.moreFailed) return
            _state.update { it.copy(loadingMore = true) }
            viewModelScope.launch { fetchMore() }
        }

        fun retryMore() {
            _state.update { it.copy(moreFailed = false) }
            loadMore()
        }

        /**
         * Appends the next page that has anything new. Serialised so the grid and the Shorts player (which
         * keeps pulling from this same list) never fetch the same page twice or skip one. Returns false when
         * the request failed, leaving the page to be retried.
         */
        private suspend fun fetchMore(): Boolean =
            pageLock.withLock {
                var cont: SoundContinuation =
                    next?.takeIf { _state.value.hasMore } ?: run {
                        _state.update { it.copy(loadingMore = false, hasMore = false) }
                        return@withLock true
                    }
                _state.update { it.copy(loadingMore = true, moreFailed = false) }
                var emptyPages = 0
                var succeeded: Boolean? = null
                while (succeeded == null) {
                    val request = cont
                    val page = withContext(Dispatchers.IO) { YouTube.soundMore(request) }.getOrNull()
                    if (page == null) {
                        android.util.Log.w("SoundShorts", "next page failed")
                        _state.update { it.copy(loadingMore = false, moreFailed = true) }
                        succeeded = false
                        continue
                    }
                    val fresh = page.reels.filter { seen.add(it.id) }.map { it.toShortVideo() }
                    // A repeated token would loop forever on the same page.
                    val following = page.next?.takeIf { usedTokens.add(it.token) }
                    next = following
                    if (fresh.isNotEmpty() || following == null || ++emptyPages >= MAX_EMPTY_PAGES) {
                        if (fresh.isEmpty()) next = null
                        val hasMore = next != null
                        _state.update { it.copy(shorts = it.shorts + fresh, hasMore = hasMore, loadingMore = false) }
                        succeeded = true
                    } else {
                        cont = following
                    }
                }
                succeeded == true
            }

        fun sourceFor(startId: String): ShortsQueueSource {
            val snapshot = _state.value
            var delivered = snapshot.shorts.size
            // The player keeps pulling from this list, so a sound with more Shorts than are loaded does not
            // end the queue at whatever the grid happened to have.
            val more =
                if (snapshot.hasMore) {
                    SnapshotContinuation {
                        var fresh = _state.value.shorts.drop(delivered)
                        if (fresh.isEmpty() && _state.value.hasMore) {
                            fetchMore()
                            fresh = _state.value.shorts.drop(delivered)
                        }
                        delivered += fresh.size
                        val hasMore = _state.value.hasMore
                        ShortsQueuePage(fresh, cursor = if (hasMore) "more" else null, exhausted = !hasMore)
                    }
                } else {
                    null
                }
            return ShortsQueueSource.Snapshot(handoff.offer(snapshot.shorts, more), startId)
        }

        private companion object {
            const val MAX_FILL_PAGES = 3
            const val MAX_EMPTY_PAGES = 3
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundShortsScreen(
    browseId: String,
    params: String,
    title: String,
    videoId: String = "",
    onBack: () -> Unit,
    onOpen: (ShortsQueueSource) -> Unit,
    viewModel: SoundShortsViewModel = hiltViewModel(),
) {
    LaunchedEffect(browseId, params, videoId) { viewModel.load(browseId, params, videoId) }
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    // Ask for the next page a few rows before the end. Keyed on the item count too, so a short page that
    // leaves the grid still near its end keeps loading until the screen is full.
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.distinctUntilChanged()
            .filter { (last, total) -> total > 0 && last >= total - PREFETCH_ITEMS }
            .collect { viewModel.loadMore() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title.ifBlank { stringResource(R.string.sound_original) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.shorts.isEmpty() -> {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sound_empty))
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SoundHeader(
                                title = title.ifBlank { stringResource(R.string.sound_original) },
                                coverUrl = state.shorts.first().thumbnailUrl,
                                count = state.count,
                                countIsLowerBound = state.countIsLowerBound,
                                onPlayAll = { onOpen(viewModel.sourceFor(state.shorts.first().id)) },
                            )
                        }
                        items(state.shorts, key = { it.id }) { short ->
                            Box(
                                Modifier
                                    .aspectRatio(9f / 16f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpen(viewModel.sourceFor(short.id)) },
                            ) {
                                AsyncImage(
                                    model = short.thumbnailUrl,
                                    contentDescription = short.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (short.viewCount > 0) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier =
                                            Modifier
                                                .align(Alignment.BottomStart)
                                                .fillMaxWidth()
                                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        Text(
                                            formatViewCount(short.viewCount),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                        if (state.hasMore || state.loadingMore) {
                            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (state.moreFailed) {
                                        TextButton(onClick = viewModel::retryMore) { Text(stringResource(R.string.retry)) }
                                    } else {
                                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val PREFETCH_ITEMS = 9

private fun formatSoundCount(count: Long): String =
    if (count < 10_000L) NumberFormat.getInstance().format(count) else formatViewCount(count)

@Composable
private fun SoundHeader(
    title: String,
    coverUrl: String,
    count: Long,
    countIsLowerBound: Boolean,
    onPlayAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    countIsLowerBound -> stringResource(R.string.sound_shorts_count_more, formatSoundCount(count))
                    count == 1L -> stringResource(R.string.sound_only_one)
                    count < 1_000L -> pluralStringResource(R.plurals.sound_shorts_count, count.toInt(), count.toInt())
                    else -> stringResource(R.string.sound_shorts_total, formatSoundCount(count))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPlayAll, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.sound_play_all), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
