package app.zaid.flow.ui.screens.shorts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.zaid.flow.R
import app.zaid.flow.data.local.DataSaver
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.model.toVideo
import app.zaid.flow.data.shorts.queue.ShortsQueueSource
import app.zaid.flow.player.GlobalPlayerState
import app.zaid.flow.player.quality.AutoQualityLadder
import app.zaid.flow.player.shorts.ShortsPlayerPool
import app.zaid.flow.ui.components.layout.LocalFlowBottomInsets
import app.zaid.flow.ui.components.shared.CommentSortFilter
import app.zaid.flow.ui.components.shared.FlowCommentsBottomSheet
import app.zaid.flow.ui.components.shared.FlowDescriptionBottomSheet
import app.zaid.flow.ui.components.shared.FlowErrorState
import app.zaid.flow.ui.components.shared.FlowLoadingIndicator
import app.zaid.flow.ui.components.shared.applyVideoCommentFilters
import app.zaid.flow.ui.components.shared.rememberVideoShareAction
import app.zaid.flow.ui.components.shared.videoCommentSortFor
import app.zaid.flow.ui.components.shorts.SHORTS_SHEET_HEIGHT_FRACTION
import app.zaid.flow.ui.components.shorts.ShortsDownloadDialog
import app.zaid.flow.ui.components.shorts.ShortsReelActions
import app.zaid.flow.ui.components.shorts.ShortsReelPage
import app.zaid.flow.ui.components.shorts.ShortsSettingsSheet
import app.zaid.flow.ui.components.shorts.ShortsSettingsSheetState
import app.zaid.flow.ui.components.shorts.ShortsTopBar
import app.zaid.flow.ui.components.shorts.rememberShortsReelSettings
import app.zaid.flow.ui.components.shorts.rememberShortsSheetInsetState
import app.zaid.flow.ui.theme.PlayerScrim
import app.zaid.flow.ui.utils.rememberIsOnWifi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private val SnackbarBottomPadding = 80.dp
private const val PAUSE_LABEL_DELAY_MS = 350L
private const val PAUSE_POLL_MS = 100L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(
    source: ShortsQueueSource,
    onBack: () -> Unit,
    onChannelClick: (String) -> Unit,
    onSoundClick: (browseId: String, params: String, title: String, videoId: String) -> Unit = { _, _, _, _ -> },
    onSearchClick: () -> Unit = {},
    bottomNavOverlayPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
    viewModel: ShortsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playerPreferences = remember(context) { PlayerPreferences(context) }
    val reelSettings = rememberShortsReelSettings(playerPreferences)
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val shareVideo = rememberVideoShareAction()

    val isInPip by GlobalPlayerState.isInPipMode.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            viewModel.clearSnackbar()
        }
    }

    val isWifi = rememberIsOnWifi()
    // Read before anything below resolves a Short, so the pager and the prefetch agree on the height.
    remember { refreshShortsAutoHeight(context) }
    // Bumped only when a per-short speed sample changes the Auto height.
    var speedRevision by remember { mutableIntStateOf(0) }
    val dataSaverOn by remember(playerPreferences) { playerPreferences.dataSaverEnabled }.collectAsState(initial = DataSaver.enabled)
    // Null until DataStore has actually emitted. Resolving against a placeholder height would key
    // the playback-stream cache differently from the ViewModel's prefetch (which reads the real
    // preference), guaranteeing a miss — and the correction would then re-resolve and reload all
    // three pooled players on every entry to the screen.
    val shortsQualityPair by remember(playerPreferences) {
        playerPreferences.effectiveShortsQualityWifi.combine(playerPreferences.effectiveShortsQualityCellular, ::Pair)
    }.collectAsState(initial = null)
    val shortsTargetHeight by remember(isWifi, shortsQualityPair, dataSaverOn, speedRevision) {
        derivedStateOf { shortsQualityPair?.let { (wifi, cellular) -> shortsTargetHeight(isWifi, wifi, cellular) } }
    }

    var showCommentsSheet by remember { mutableStateOf(false) }
    var showDescriptionSheet by remember { mutableStateOf(false) }
    val settingsSheet = remember { ShortsSettingsSheetState() }
    var commentSortFilter by remember { mutableStateOf(CommentSortFilter.TOP) }
    var commentsTimedOnly by remember { mutableStateOf(false) }
    val comments by viewModel.commentsState.collectAsState()
    val isLoadingComments by viewModel.isLoadingComments.collectAsState()
    val commentSortOptions by viewModel.commentSortOptions.collectAsState()
    val commentTotalText by viewModel.commentTotalText.collectAsState()
    val visibleComments =
        remember(comments, commentSortFilter, commentsTimedOnly) {
            applyVideoCommentFilters(comments, commentSortFilter, commentsTimedOnly)
        }

    LaunchedEffect(source) { viewModel.load(source) }

    // Release the pool on the way out — unless a later Shorts screen has claimed it in the meantime.
    // An external /shorts/ link arriving while the Shorts tab is open pushes a second destination,
    // and the outgoing screen's dispose runs after the incoming one has already prepared its players.
    DisposableEffect(Unit) {
        val playerPool = ShortsPlayerPool.getInstance()
        val hostToken = playerPool.acquireHost()
        viewModel.onScreenVisible()
        onDispose {
            viewModel.onScreenHidden()
            playerPool.releaseIfHost(hostToken)
        }
    }

    val sheetInsets = rememberShortsSheetInsetState()
    val density = LocalDensity.current
    // The Live pill is offered only while the viewer has the current short paused.
    var currentShortPaused by remember { mutableStateOf(false) }
    var shortsScrolling by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(PlayerScrim),
    ) {
        val canShrinkReel = maxHeight > maxWidth
        val sheetExpandedHeight = (maxHeight * SHORTS_SHEET_HEIGHT_FRACTION).takeIf { canShrinkReel }
        val sheetExpandedHeightPx = with(density) { (sheetExpandedHeight ?: 0.dp).toPx() }
        SideEffect {
            sheetInsets.containerHeightPx = constraints.maxHeight.toFloat()
            sheetInsets.shrinkEnabled = canShrinkReel
        }
        val screenSheetOpen = showCommentsSheet || showDescriptionSheet || settingsSheet.isOpen

        when {
            uiState.isLoading && uiState.shorts.isEmpty() -> {
                FlowLoadingIndicator()
            }

            uiState.error != null && uiState.shorts.isEmpty() -> {
                FlowErrorState(
                    error = uiState.error ?: stringResource(R.string.error_short_load),
                    onRetry = { viewModel.retry(source) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            uiState.shorts.isNotEmpty() -> {
                val pagerState = rememberPagerState(initialPage = uiState.currentIndex, pageCount = { uiState.shorts.size })
                ShortsPipActionEffect(pagerState = pagerState, isInPip = isInPip)

                LaunchedEffect(pagerState.currentPage) { viewModel.updateCurrentIndex(pagerState.currentPage) }
                // Any scroll hides the label at once, so it never lingers on the next reel.
                SideEffect { shortsScrolling = pagerState.isScrollInProgress }
                LaunchedEffect(pagerState.settledPage, uiState.shorts) {
                    val id = uiState.shorts.getOrNull(pagerState.settledPage)?.id
                    val pool = ShortsPlayerPool.getInstance()
                    // A new reel starts out not playing for a moment before it is activated. Only a
                    // pause that holds counts, so that moment never flashes the label.
                    currentShortPaused = false
                    var pausedSinceMs = 0L
                    while (true) {
                        val owned = pool.ownedPlayer(pagerState.settledPage, id)
                        val pausedNow =
                            owned != null && !owned.isPlaying && !owned.playWhenReady &&
                                owned.playbackState == androidx.media3.common.Player.STATE_READY
                        val now = android.os.SystemClock.uptimeMillis()
                        if (pausedNow) {
                            if (pausedSinceMs == 0L) pausedSinceMs = now
                            if (now - pausedSinceMs >= PAUSE_LABEL_DELAY_MS) currentShortPaused = true
                        } else {
                            pausedSinceMs = 0L
                            currentShortPaused = false
                        }
                        kotlinx.coroutines.delay(PAUSE_POLL_MS)
                    }
                }
                LaunchedEffect(pagerState.settledPage) {
                    if (settingsSheet.isOpen && settingsSheet.targetIndex != pagerState.settledPage) settingsSheet.close()
                }

                ShortsPagerPlaybackEffects(
                    pagerState = pagerState,
                    shorts = uiState.shorts,
                    targetHeight = shortsTargetHeight,
                    playerPreferences = playerPreferences,
                    viewModel = viewModel,
                    onAutoHeightChanged = { speedRevision++ },
                )

                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    key = { uiState.shorts[it].id },
                ) { page ->
                    val short = uiState.shorts[page]
                    ShortsReelPage(
                        short = short,
                        isActive = page == pagerState.currentPage,
                        pageIndex = page,
                        viewModel = viewModel,
                        settings = reelSettings,
                        sheetInsets = sheetInsets,
                        screenSheetOpen = screenSheetOpen,
                        bottomNavOverlayPadding = bottomNavOverlayPadding,
                        actions =
                            ShortsReelActions(
                                onChannelClick = { onChannelClick(short.channelId) },
                                onCommentsClick = {
                                    viewModel.loadComments(short.id)
                                    showCommentsSheet = true
                                },
                                onDescriptionClick = {
                                    viewModel.loadShortDescription(short.id)
                                    showDescriptionSheet = true
                                },
                                onSoundClick = { onSoundClick(short.soundBrowseId, short.soundParams, short.soundTitle, short.id) },
                                onMoreClick = { settingsSheet.open(page, short.id) },
                                onVideoEnded = {
                                    scope.launch {
                                        if (page < pagerState.pageCount - 1) pagerState.animateScrollToPage(page + 1)
                                    }
                                },
                            ),
                    )
                }

                if (uiState.isLoadingMore && !isInPip) {
                    LinearProgressIndicator(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                    )
                }
            }
        }

        if (showCommentsSheet) {
            DisposableEffect(Unit) { onDispose { sheetInsets.release() } }
            // The app's navigation bar is drawn over the bottom of this page, so the comment box has
            // to start above it. With the keyboard open the bar is covered and the keyboard already
            // lifts the box, so nothing extra is added then.
            val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
            val barClearance = if (keyboardOpen) 0.dp else LocalFlowBottomInsets.current.barBottom
            FlowCommentsBottomSheet(
                modifier = Modifier.padding(bottom = barClearance),
                comments = visibleComments,
                isLoading = isLoadingComments,
                selectedFilter = commentSortFilter,
                totalText = commentTotalText,
                timedOnly = commentsTimedOnly,
                onTimedChange = { commentsTimedOnly = it },
                onFilterChanged = { filter ->
                    commentSortFilter = filter
                    videoCommentSortFor(commentSortOptions, filter)?.let { sort ->
                        uiState.shorts
                            .getOrNull(uiState.currentIndex)
                            ?.id
                            ?.let { videoId -> viewModel.selectCommentSort(videoId, sort) }
                    }
                },
                onLoadReplies = { viewModel.loadCommentReplies(it) },
                onReload = {
                    uiState.shorts
                        .getOrNull(uiState.currentIndex)
                        ?.id
                        ?.let { videoId -> viewModel.reloadComments(videoId) }
                },
                onPostComment =
                    app.zaid.flow.ui.components.shared.rememberCommentPoster { text, done ->
                        uiState.shorts
                            .getOrNull(uiState.currentIndex)
                            ?.id
                            ?.let { viewModel.postComment(it, text, done) }
                    },
                onAuthorClick = { authorChannelRef ->
                    showCommentsSheet = false
                    onChannelClick(authorChannelRef)
                },
                expandedHeight = sheetExpandedHeight,
                onSheetProgressChange = { progress -> sheetInsets.follow(sheetExpandedHeightPx * progress) },
                dismissOnOutsideTap = true,
                onDismiss = { showCommentsSheet = false },
            )
        }

        if (showDescriptionSheet && uiState.shorts.isNotEmpty()) {
            DisposableEffect(Unit) { onDispose { sheetInsets.release() } }
            val safeIndex = uiState.currentIndex.coerceIn(0, uiState.shorts.size - 1)
            FlowDescriptionBottomSheet(
                video = uiState.shorts[safeIndex].toVideo(),
                expandedHeight = sheetExpandedHeight,
                onSheetProgressChange = { progress -> sheetInsets.follow(sheetExpandedHeightPx * progress) },
                dismissOnOutsideTap = true,
                onDismiss = { showDescriptionSheet = false },
            )
        }

        val settingsShort = settingsSheet.targetId?.let { id -> uiState.shorts.firstOrNull { it.id == id } }
        if (settingsShort != null) {
            DisposableEffect(Unit) { onDispose { sheetInsets.release() } }
            ShortsSettingsSheet(
                short = settingsShort,
                settings = reelSettings,
                state = settingsSheet,
                playerPool = ShortsPlayerPool.getInstance(),
                viewModel = viewModel,
                playerPreferences = playerPreferences,
                onWantMore = { viewModel.wantMoreLikeThis(settingsShort) },
                onNotInterested = { viewModel.notInterested(settingsShort) },
                onBlockChannel = { viewModel.blockChannel(settingsShort) },
                onDownload = { scope.launch { settingsSheet.prepareDownload(settingsShort, viewModel) } },
                onShare = { shareVideo(settingsShort.id, settingsShort.title, true) },
                onDismiss = settingsSheet::close,
                expandedHeight = sheetExpandedHeight,
                onSheetProgressChange = { progress -> sheetInsets.follow(sheetExpandedHeightPx * progress) },
                bottomContentPadding = bottomNavOverlayPadding,
            )
        }
        ShortsDownloadDialog(state = settingsSheet, style = reelSettings.downloadDialogStyle)

        ShortsTopBar(
            visible = uiState.shorts.isNotEmpty() && !isInPip,
            showBackButton = source != ShortsQueueSource.Feed,
            // The "Shorts" title stays off the picture while a reel plays and returns on pause.
            showTitle = currentShortPaused && !shortsScrolling,
            onBack = onBack,
            onSearch = onSearchClick,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (isInPip) return@BoxWithConstraints
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SnackbarBottomPadding),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.medium,
            )
        }
    }
}
