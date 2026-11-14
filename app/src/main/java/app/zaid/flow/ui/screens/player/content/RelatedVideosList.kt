package app.zaid.flow.ui.screens.player.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.zaid.flow.data.local.PlayerRelatedCardStyle
import app.zaid.flow.data.model.Video
import app.zaid.flow.ui.components.FeedGridLayout
import app.zaid.flow.ui.components.partialRowIndices
import app.zaid.flow.ui.components.shared.MediaShortsShelf
import app.zaid.flow.ui.components.shared.card.MediaVideoCard
import app.zaid.flow.ui.components.shared.card.VideoCardLayout

/**
 * Related videos content for LazyListScope.
 */
internal fun LazyListScope.relatedVideosContent(
    relatedVideos: List<Video>,
    onVideoClick: (Video) -> Unit,
    cardStyle: PlayerRelatedCardStyle = PlayerRelatedCardStyle.FULL_WIDTH,
    relatedShorts: List<Video> = emptyList(),
) {
    // The Shorts row sits after the second related video, like YouTube's watch page.
    val shortsAt = relatedVideos.size.coerceAtMost(SHORTS_ROW_AFTER)
    items(
        count = shortsAt,
        key = { index -> relatedVideos[index].id },
    ) { index ->
        RelatedVideoItem(relatedVideos[index], cardStyle, onVideoClick)
    }
    relatedShortsRow(relatedShorts, onVideoClick)
    items(
        count = relatedVideos.size - shortsAt,
        key = { index -> relatedVideos[shortsAt + index].id },
    ) { index ->
        RelatedVideoItem(relatedVideos[shortsAt + index], cardStyle, onVideoClick)
    }
}

@androidx.compose.runtime.Composable
private fun RelatedVideoItem(
    video: Video,
    cardStyle: PlayerRelatedCardStyle,
    onVideoClick: (Video) -> Unit,
) {
    when (cardStyle) {
        PlayerRelatedCardStyle.COMPACT -> {
            MediaVideoCard(
                video = video,
                layout = VideoCardLayout.Row,
                onClick = { onVideoClick(video) },
            )
        }

        PlayerRelatedCardStyle.FULL_WIDTH -> {
            MediaVideoCard(
                video = video,
                onClick = { onVideoClick(video) },
            )
        }
    }
}

/** One Shorts strip as a list item; nothing at all when there are no reels to show. */
private fun LazyListScope.relatedShortsRow(
    shorts: List<Video>,
    onShortClick: (Video) -> Unit,
) {
    if (shorts.isEmpty()) return
    item(key = RELATED_SHORTS_KEY, contentType = "related_shorts") {
        MediaShortsShelf(
            shorts = shorts,
            onShortClick = { _, tapped -> onShortClick(tapped) },
            title =
                androidx.compose.ui.res
                    .stringResource(app.zaid.flow.R.string.shorts),
        )
    }
}

/**
 * The related list on a wide single-pane player: the shared feed grid's columns, chunked into rows
 * of a [androidx.compose.foundation.lazy.LazyColumn] so the video details above keep their own
 * padding. A row the grid cannot fill, and every row in the compact style, is a thumbnail-left card
 * whose thumbnail is one grid column wide.
 */
internal fun LazyListScope.relatedVideosGridContent(
    relatedVideos: List<Video>,
    layout: FeedGridLayout,
    onVideoClick: (Video) -> Unit,
    cardStyle: PlayerRelatedCardStyle = PlayerRelatedCardStyle.FULL_WIDTH,
    relatedShorts: List<Video> = emptyList(),
) {
    val columns = if (cardStyle == PlayerRelatedCardStyle.COMPACT) 1 else layout.columns
    val rows = relatedGridRows(relatedVideos.size, columns)

    // The Shorts row goes after the first rows that hold two videos.
    var covered = 0
    var shortsAfterRow = rows.size
    for ((i, row) in rows.withIndex()) {
        covered += row.size
        if (covered >= SHORTS_ROW_AFTER) {
            shortsAfterRow = i + 1
            break
        }
    }

    relatedGridRowItems(relatedVideos, rows, 0, shortsAfterRow, layout, onVideoClick)
    relatedShortsRow(relatedShorts, onVideoClick)
    relatedGridRowItems(relatedVideos, rows, shortsAfterRow, rows.size, layout, onVideoClick)
}

private fun LazyListScope.relatedGridRowItems(
    relatedVideos: List<Video>,
    rows: List<List<Int>>,
    from: Int,
    to: Int,
    layout: FeedGridLayout,
    onVideoClick: (Video) -> Unit,
) {
    items(
        count = (to - from).coerceAtLeast(0),
        key = { index -> rows[from + index].joinToString { relatedVideos[it].id } },
        contentType = { index -> if (rows[from + index].size == 1) "related_row" else "related_grid_row" },
    ) { index ->
        val row = rows[from + index]
        if (row.size == 1) {
            val video = relatedVideos[row.single()]
            MediaVideoCard(
                video = video,
                layout = VideoCardLayout.Row,
                onClick = { onVideoClick(video) },
                thumbnailWidth = layout.thumbnailWidth,
                modifier = Modifier.padding(horizontal = layout.contentPadding),
            )
        } else {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = layout.contentPadding, vertical = GridRowVerticalPadding),
            ) {
                row.forEach { videoIndex ->
                    val video = relatedVideos[videoIndex]
                    Box(modifier = Modifier.weight(1f)) {
                        MediaVideoCard(video = video, onClick = { onVideoClick(video) })
                    }
                }
            }
        }
    }
}

/** Full rows of [columns] indices, then each index a full row cannot hold on a row of its own. */
internal fun relatedGridRows(
    count: Int,
    columns: Int,
): List<List<Int>> {
    val partial = partialRowIndices(List(count) { false }, columns)
    val full = (0 until count).filterNot { it in partial }.chunked(columns.coerceAtLeast(1))
    return full + partial.sorted().map(::listOf)
}

private val GridRowVerticalPadding = 6.dp

/** The Shorts row follows this many related videos. */
private const val SHORTS_ROW_AFTER = 2
private const val RELATED_SHORTS_KEY = "related_shorts_row"
