package app.zaid.flow.ui.screens.home

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.zaid.flow.ui.components.rememberFeedGridLayout
import app.zaid.flow.ui.components.shared.FeedGridSkeleton
import app.zaid.flow.ui.components.shared.FlowErrorState
import app.zaid.flow.ui.components.shared.FlowPullToRefreshBox
import app.zaid.flow.ui.screens.home.chips.HomeChipContent
import app.zaid.flow.ui.screens.home.chips.HomeChipRow
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
    val chipRowState = rememberTopAppBarState()
    val chipRowBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(chipRowState)
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            FlowTopBar(
                title = {
                    Text(
                        stringResource(R.string.app_name_uppercase),
                        modifier = Modifier.deepFlowLongPress(deepFlowLabel, toggleDeepFlow),
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp,
                            ),
                    )
                },
                leading =
                    if (showAppLogoIcon) {
                        {
                            FlowHeaderLogoIcon(
                                isDeepFlowActive = deepFlowActive,
                                iconSuffix = selectedAppIcon,
                                size = 30.dp,
                                modifier = Modifier.deepFlowLongPress(deepFlowLabel, toggleDeepFlow),
                            )
                        }
                    } else {
                        null
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
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
        ) {
            if (homeFeedEnabled && chips.chips.size > 1) {
                HomeChipRow(
                    state = chips,
                    scrollState = chipRowState,
                    onSelect = { key ->
                        chipRowState.heightOffset = 0f
                        viewModel.selectChip(key)
                    },
                )
            }
            ResettableHomePullToRefreshBox(
                resetKey = uiState.isLoading && uiState.videos.isEmpty(),
                isRefreshing = if (chips.isAll) uiState.isRefreshing else chips.isRefreshing,
                onRefresh = { if (chips.isAll) viewModel.refreshFeed() else viewModel.refreshSelectedChip() },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(
                            // Without the chip row its height limit is never set, and the behaviour
                            // would swallow every scroll as header collapse, leaving the feed stuck.
                            if (homeFeedEnabled && chips.chips.size > 1) {
                                Modifier.nestedScroll(chipRowBehavior.nestedScrollConnection)
                            } else {
                                Modifier
                            },
                        ),
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
                            )
                        }
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
