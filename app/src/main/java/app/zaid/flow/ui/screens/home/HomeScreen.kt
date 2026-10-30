package app.zaid.flow.ui.screens.home

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.data.local.HomeFeedColumns
import app.zaid.flow.data.local.HomeViewMode
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.VideoHistoryEntry
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.shorts.queue.ShortsQueueSource
import app.zaid.flow.player.DeepFlowManager
import app.zaid.flow.ui.TabScrollEventBus
import app.zaid.flow.ui.components.ai.FlowAiButton
import app.zaid.flow.ui.components.layout.topbar.FlowTopBar
import app.zaid.flow.ui.components.layout.topbar.FlowTopBarDefaults
import app.zaid.flow.ui.components.rememberFeedGridLayout
import app.zaid.flow.ui.components.shared.FeedGridSkeleton
import app.zaid.flow.ui.components.shared.FlowErrorState
import app.zaid.flow.ui.components.shared.FlowPullToRefreshBox
import app.zaid.flow.ui.screens.home.chips.HomeChipContent
import app.zaid.flow.ui.screens.home.chips.HomeChipRow
import app.zaid.flow.ui.screens.home.chips.HomeChipRowHeight
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private const val IMPRESSION_DEBOUNCE_MS = 500L
private const val SKELETON_CARD_COUNT = 12
private const val MILLIS_PER_SECOND = 1000L

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun HomeScreen(
    onVideoClick: (Video) -> Unit,
    onShortClick: (ShortsQueueSource) -> Unit,
    onSearchClick: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    onOpenShortsFeed: () -> Unit = {},
    onPlayMix: (videos: List<Video>, title: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences = remember(context) { PlayerPreferences(context) }
    val homeViewMode by preferences.homeViewMode.collectAsStateWithLifecycle(initialValue = HomeViewMode.GRID)
    val homeFeedColumns by preferences.homeFeedColumns.collectAsStateWithLifecycle(initialValue = HomeFeedColumns.AUTO)
    val homeFeedEnabled by preferences.homeFeedEnabled.collectAsStateWithLifecycle(initialValue = true)
    val refreshHomeOnReselect by preferences.refreshHomeOnReselect.collectAsStateWithLifecycle(initialValue = true)
    val showAppLogoIcon by preferences.showAppLogoIcon.collectAsStateWithLifecycle(initialValue = true)
    val selectedAppIcon by preferences.selectedAppIcon.collectAsStateWithLifecycle(initialValue = null)
    val deepFlowActive by preferences.deepFlowActive.collectAsStateWithLifecycle(initialValue = false)

    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    // "Live" from the paused-video pill: apply a request made before this screen was composed, then any later ones.
    LaunchedEffect(Unit) {
        HomeChipRequests.consume()?.let(viewModel::selectChip)
        HomeChipRequests.events.collect {
            HomeChipRequests.consume()?.let(viewModel::selectChip)
        }
    }
    // Each chip keeps its own scroll position for the session.
    val chipGridStates = remember { HashMap<String, LazyGridState>() }
    val activeGridState = if (chips.isAll) gridState else chipGridStates.getOrPut(chips.selected) { LazyGridState() }
    // One state drives the whole header (bar + chips): it hides on scroll down and returns on scroll up.
    val headerState = rememberTopAppBarState()
    val headerBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(headerState)
    val currentChips by rememberUpdatedState(chips)

    LifecycleStartEffect(viewModel, homeFeedEnabled) {
        if (homeFeedEnabled) {
            viewModel.onHomeVisible()
        } else {
            viewModel.onHomeHidden()
        }
        onStopOrDispose { viewModel.onHomeHidden() }
    }

    val videoIndexById =
        remember(uiState.videos) {
            buildMap(uiState.videos.size) {
                uiState.videos.forEachIndexed { index, video -> put(video.id, index) }
            }
        }
    // Pairing the index with the scroll state matters: a user parked on the last item keeps the
    // same index, so an index-only flow can never re-arm a prefetch that came back empty. Each
    // further scroll attempt toggles isScrollInProgress and gives the feed another chance.
    LaunchedEffect(gridState, videoIndexById) {
        snapshotFlow {
            var lastVisibleVideoIndex = -1
            gridState.layoutInfo.visibleItemsInfo.forEach { item ->
                val index = videoIndexById[item.key as? String] ?: return@forEach
                if (index > lastVisibleVideoIndex) lastVisibleVideoIndex = index
            }
            lastVisibleVideoIndex to gridState.isScrollInProgress
        }.distinctUntilChanged()
            .collect { (lastVisibleVideoIndex, _) ->
                viewModel.onHomeViewportChanged(lastVisibleVideoIndex)
            }
    }

    // A finished refresh swaps the feed; show it from the top instead of leaving the old scroll position.
    var lastSeenRefresh by remember { mutableStateOf(uiState.lastRefreshTime) }
    LaunchedEffect(uiState.lastRefreshTime) {
        if (uiState.lastRefreshTime != lastSeenRefresh && !uiState.isLoading) {
            lastSeenRefresh = uiState.lastRefreshTime
            gridState.scrollToItem(0)
        }
    }

    // Viewport impressions: only items dwelt in view are recorded as "shown".
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .debounce(IMPRESSION_DEBOUNCE_MS)
            .collect { viewModel.recordImpressions(it) }
    }

    LaunchedEffect(refreshHomeOnReselect) {
        TabScrollEventBus.scrollToTopEvents
            .filter { it == "home" }
            .collectLatest {
                // Down the feed a reselect only scrolls back up; at the top it refreshes (#1171).
                val grid = if (currentChips.isAll) gridState else chipGridStates[currentChips.selected] ?: return@collectLatest
                val atTop = grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0
                if (!atTop) {
                    headerState.heightOffset = 0f
                    grid.animateScrollToItem(0)
                } else if (refreshHomeOnReselect) {
                    if (currentChips.isAll) viewModel.refreshFeed() else viewModel.refreshSelectedChip()
                }
            }
    }

    val haptic = LocalHapticFeedback.current
    val deepFlowLabel = stringResource(R.string.deep_flow_mode_title)
    val toggleDeepFlow: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope.launch { DeepFlowManager.toggle(context) }
    }

    val showChipRow = homeFeedEnabled && chips.chips.size > 1
    val headerHeight = FlowTopBarDefaults.Height + if (showChipRow) HomeChipRowHeight else 0.dp

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        HomeCollapsingHeader(state = headerState, expandedHeight = headerHeight) {
            FlowTopBar(
                title = {
                    HomeBrand(
                        showLogo = showAppLogoIcon,
                        isDeepFlowActive = deepFlowActive,
                        iconSuffix = selectedAppIcon,
                        deepFlowLabel = deepFlowLabel,
                        onToggleDeepFlow = toggleDeepFlow,
                    )
                },
                actions = {
                    FlowAiButton()
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                },
            )
            if (showChipRow) {
                HomeChipRow(
                    state = chips,
                    onSelect = { key ->
                        headerState.heightOffset = 0f
                        viewModel.selectChip(key)
                    },
                )
            }
        }
        PlayablesHost()
        ResettableHomePullToRefreshBox(
            resetKey = uiState.isLoading && uiState.videos.isEmpty(),
            isRefreshing = if (chips.isAll) uiState.isRefreshing else chips.isRefreshing,
            onRefresh = { if (chips.isAll) viewModel.refreshFeed() else viewModel.refreshSelectedChip() },
            // The header's limit is always set (the bar is always there), so the behaviour only ever
            // collapses what exists and never swallows the feed's scroll.
            modifier = Modifier.fillMaxSize().nestedScroll(headerBehavior.nestedScrollConnection),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isListView = homeViewMode == HomeViewMode.LIST
                val feedLayout = rememberFeedGridLayout(maxWidth, homeFeedColumns)

                when {
                    !homeFeedEnabled -> {
                        HomeFeedDisabledState(modifier = Modifier.fillMaxSize())
                    }

                    !chips.isAll -> {
                        HomeChipContent(
                            state = chips,
                            feedLayout = feedLayout,
                            isListView = isListView,
                            gridState = activeGridState,
                            onVideoClick = onVideoClick,
                            onPlayMix = { mix -> onPlayMix(mix.videos, context.getString(R.string.home_mix_title, mix.seed.title)) },
                            onRefresh = viewModel::refreshSelectedChip,
                            onEnrichVideo = viewModel::enrichChipVideo,
                        )
                    }

                    uiState.isLoading && uiState.videos.isEmpty() -> {
                        FeedGridSkeleton(
                            layout = feedLayout,
                            listMode = isListView,
                            stackedInset = !feedLayout.isCompact,
                            placeholderCount = SKELETON_CARD_COUNT,
                            compactRowSpacing = if (isListView) 0.dp else feedLayout.cardSpacing,
                        )
                    }

                    uiState.error != null && uiState.videos.isEmpty() -> {
                        FlowErrorState(
                            error = uiState.error ?: stringResource(R.string.error_occurred),
                            onRetry = { viewModel.retry() },
                        )
                    }

                    else -> {
                        ReportDrawnWhen {
                            uiState.videos.isNotEmpty() ||
                                uiState.shorts.isNotEmpty() ||
                                uiState.continueWatchingVideos.isNotEmpty()
                        }

                        LaunchedEffect(feedLayout.columns) { viewModel.onHomeColumnsChanged(feedLayout.columns) }
                        HomeFeedGrid(
                            uiState = uiState,
                            feedLayout = feedLayout,
                            isListView = isListView,
                            gridState = gridState,
                            onVideoClick = onVideoClick,
                            onEnrichChannelMetadata = viewModel::enrichChannelMetadataIfMissing,
                            onContinueWatchingClick = { entry -> onVideoClick(entry.toResumeVideo()) },
                            onContinueWatchingRemove = viewModel::removeContinueWatchingEntry,
                            onShortClick = { shelf, tapped ->
                                onShortClick(viewModel.shortsShelfSource(shelf, tapped))
                            },
                            onSeeAllHistory = onNavigateToHistory,
                            onOpenShortsFeed = onOpenShortsFeed,
                            onShortsShown = viewModel::recordShelfImpressions,
                            onRefresh = { viewModel.refreshFeed() },
                            onPostComment = viewModel::openPostComments,
                        )
                        val postComments by viewModel.postCommentsState.collectAsStateWithLifecycle()
                        HomePostCommentsSheet(
                            activePost = postComments.activePost,
                            comments = postComments.comments,
                            isLoading = postComments.isLoadingComments,
                            isLoadingMore = postComments.isLoadingMoreComments,
                            hasMore = postComments.commentsContinuation != null,
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
}

// Deliberately not data/model's VideoHistoryEntry.toVideo(): that one carries isShort, which would
// reroute a resumed short to the Shorts player instead of the video player.
internal fun VideoHistoryEntry.toResumeVideo(): Video =
    Video(
        id = videoId,
        title = title,
        channelName = channelName,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = (duration / MILLIS_PER_SECOND).toInt(),
        viewCount = 0L,
        uploadDate = "",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResettableHomePullToRefreshBox(
    resetKey: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    key(resetKey) {
        FlowPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            content = content,
        )
    }
}
