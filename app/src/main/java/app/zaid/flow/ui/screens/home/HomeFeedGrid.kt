package app.zaid.flow.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.zaid.flow.R
import app.zaid.flow.data.local.VideoHistoryEntry
import app.zaid.flow.data.model.Video
import app.zaid.flow.ui.components.FeedGridLayout
import app.zaid.flow.ui.components.home.ContinueWatchingShelf
import app.zaid.flow.ui.components.home.ContinueWatchingShelfDefaults
import app.zaid.flow.ui.components.layout.flowBottomContentPadding
import app.zaid.flow.ui.components.shared.FeedInlinePreviewEffect
import app.zaid.flow.ui.components.shared.FlowFeedProgress
import app.zaid.flow.ui.components.shared.MediaShortsShelf
import app.zaid.flow.ui.components.shared.card.InlineVideoPreviewSurface
import app.zaid.flow.ui.components.shared.card.LocalInlineVideoPreviewController
import app.zaid.flow.ui.components.shared.card.MediaVideoCard
import app.zaid.flow.ui.components.shared.card.VideoCardLayout
import app.zaid.flow.ui.components.shared.feedStripCardWidth
import app.zaid.flow.ui.components.shared.rememberFeedGridPlan

private const val FEED_FOOTER_MIN_VIDEOS = 100
private val FeedTopPadding = 4.dp

@Composable
internal fun HomeFeedGrid(
    uiState: HomeUiState,
    feedLayout: FeedGridLayout,
    isListView: Boolean,
    gridState: LazyGridState,
    onVideoClick: (Video) -> Unit,
    onEnrichChannelMetadata: (Video) -> Unit,
    onContinueWatchingClick: (VideoHistoryEntry) -> Unit,
    onContinueWatchingRemove: (String) -> Unit,
    onShortClick: (List<Video>, Video) -> Unit,
    onSeeAllHistory: () -> Unit,
    onOpenShortsFeed: () -> Unit,
    onShortsShown: (ids: List<String>) -> Unit,
    onRefresh: () -> Unit,
    onPostClick: (HomePostRow, Int, Int) -> Unit = { _, _, _ -> },
    onPostComment: (app.zaid.flow.innertube.pages.renderer.CommunityPost) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val videos = uiState.videos
    var postViewer by remember { mutableStateOf<PostViewerTarget?>(null) }
    postViewer?.let { HomePostViewer(it, onDismiss = { postViewer = null }, onComment = onPostComment) }
    val channelMemoryReason = stringResource(R.string.home_reason_channel_memory)
    val rows =
        remember(videos, feedLayout.columns, uiState.continueWatchingVideos.isNotEmpty(), uiState.shorts, uiState.postRows) {
            homeFeedRows(
                videos = videos,
                columns = feedLayout.columns,
                showContinueWatching = uiState.continueWatchingVideos.isNotEmpty(),
                shorts = uiState.shorts,
                postRows = uiState.postRows,
            )
        }
    val plan =
        rememberFeedGridPlan(
            layout = feedLayout,
            listMode = isListView,
            itemCount = rows.size,
            spansOwnRow = { rows[it].spansRow },
            includeLastRun = !uiState.hasMorePages,
            itemsKey = rows,
            compactRowSpacing = if (isListView) 0.dp else feedLayout.cardSpacing,
        )
    val stripCardWidth = feedStripCardWidth(feedLayout.rowWidth, ContinueWatchingShelfDefaults.CardWidth)
    val previewController = LocalInlineVideoPreviewController.current
    FeedInlinePreviewEffect(gridState, previewController) { index ->
        (rows.getOrNull(index) as? HomeFeedRow.VideoCard)?.video
    }
    val activePreviewId = previewController?.activeVideoId

    LazyVerticalGrid(
        columns = plan.cells,
        modifier =
            modifier
                .fillMaxSize()
                .testTag("home_feed"),
        state = gridState,
        contentPadding = plan.contentPadding(top = FeedTopPadding, bottom = flowBottomContentPadding()),
        verticalArrangement = Arrangement.spacedBy(plan.rowSpacing),
    ) {
        itemsIndexed(
            items = rows,
            key = { _, row -> row.key },
            contentType = { _, row -> row::class },
            span = { index, row -> plan.span(index, row.spansRow, maxLineSpan) },
        ) { index, row ->
            when (row) {
                is HomeFeedRow.VideoCard -> {
                    val isPreviewActive = activePreviewId == row.video.id
                    LaunchedEffect(row.video.id, row.video.channelId, row.video.channelThumbnailUrl) {
                        // A card flung past during a scroll leaves composition and cancels this before it asks
                        // YouTube for anything; only a card that stays on screen gets its lookup.
                        kotlinx.coroutines.delay(CARD_LOOKUP_SETTLE_MS)
                        onEnrichChannelMetadata(row.video)
                    }
                    MediaVideoCard(
                        video = row.video,
                        layout =
                            if (plan.isListCard(index) && !isPreviewActive) {
                                VideoCardLayout.Row
                            } else {
                                VideoCardLayout.Stacked
                            },
                        onClick = { onVideoClick(row.video) },
                        useInternalPadding = !feedLayout.isCompact,
                        thumbnailWidth = plan.listThumbnailWidth,
                        reason = if (row.video.id in uiState.channelMemoryVideoIds) channelMemoryReason else null,
                        modifier = Modifier.testTag("home_video_card"),
                        previewActive = isPreviewActive,
                        thumbnailPreview =
                            if (isPreviewActive && previewController != null) {
                                { InlineVideoPreviewSurface(previewController, row.video.id) }
                            } else {
                                null
                            },
                    )
                }

                HomeFeedRow.ContinueWatching -> {
                    ContinueWatchingShelf(
                        entries = uiState.continueWatchingVideos,
                        onVideoClick = { videoId ->
                            uiState.continueWatchingVideos
                                .find { it.videoId == videoId }
                                ?.let(onContinueWatchingClick)
                        },
                        onRemove = onContinueWatchingRemove,
                        onSeeAllClick = onSeeAllHistory,
                        cardWidth = stripCardWidth,
                        modifier = Modifier.testTag("home_continue_watching_shelf"),
                    )
                }

                is HomeFeedRow.Posts -> {
                    HomePostsShelf(
                        row = row.row,
                        onPostClick = { r, p, i ->
                            postViewer = PostViewerTarget(r, p, i)
                            onPostClick(r, p, i)
                        },
                        onComment = onPostComment,
                        onVideoClick = onVideoClick,
                        modifier = Modifier.testTag("home_posts_shelf"),
                    )
                }

                is HomeFeedRow.Shorts -> {
                    MediaShortsShelf(
                        shorts = row.shorts,
                        onShortClick = onShortClick,
                        onSeeAllClick = onOpenShortsFeed,
                        onShortsShown = onShortsShown,
                        modifier = Modifier.testTag("home_shorts_shelf"),
                    )
                }
            }
        }

        if (uiState.isLoadingMore) {
            item(
                key = "loading_indicator",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                FlowFeedProgress()
            }
        }

        if (!uiState.hasMorePages && videos.size > FEED_FOOTER_MIN_VIDEOS && !uiState.isLoadingMore) {
            item(
                key = "feed_footer",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                FlowFeedFooter(
                    videoCount = videos.size,
                    onRefresh = onRefresh,
                )
            }
        }
    }
}

/** How long a card must stay composed before it triggers a network lookup, so fast scrolling fetches nothing. */
private const val CARD_LOOKUP_SETTLE_MS = 600L
