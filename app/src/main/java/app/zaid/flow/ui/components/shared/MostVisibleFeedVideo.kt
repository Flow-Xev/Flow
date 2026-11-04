package app.zaid.flow.ui.components.shared

import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import app.zaid.flow.data.model.Video
import app.zaid.flow.ui.components.shared.card.isPreviewable

/** Picks the eligible video card taking up the most visible area nearest the grid's centre. */
internal fun mostVisibleFeedVideoId(
    layoutInfo: LazyGridLayoutInfo,
    retainedVideoId: String? = null,
    videoAtIndex: (Int) -> Video?,
): String? = mostVisibleFeedVideo(layoutInfo, retainedVideoId, videoAtIndex)?.id

/** [mostVisibleFeedVideoId], returning the video itself so the caller needs no second lookup. */
internal fun mostVisibleFeedVideo(
    layoutInfo: LazyGridLayoutInfo,
    retainedVideoId: String? = null,
    videoAtIndex: (Int) -> Video?,
): Video? {
    val viewportStart = layoutInfo.viewportStartOffset
    val viewportEnd = layoutInfo.viewportEndOffset
    val viewportCenterX = layoutInfo.viewportSize.width / 2
    val viewportCenterY = (viewportStart + viewportEnd) / 2

    return layoutInfo.visibleItemsInfo
        .mapNotNull { item ->
            val video = videoAtIndex(item.index) ?: return@mapNotNull null
            if (!video.isPreviewable() || item.size.height <= 0) return@mapNotNull null
            val itemTop = item.offset.y
            val itemBottom = itemTop + item.size.height
            val visibleHeight = (minOf(itemBottom, viewportEnd) - maxOf(itemTop, viewportStart)).coerceAtLeast(0)
            val visibleFraction = visibleHeight.toFloat() / item.size.height
            val minVisibleFraction =
                if (video.id == retainedVideoId) {
                    MIN_RETAINED_FEED_VIDEO_FRACTION
                } else {
                    MIN_VISIBLE_FEED_VIDEO_FRACTION
                }
            if (visibleFraction < minVisibleFraction) return@mapNotNull null

            val itemCenterX = item.offset.x + item.size.width / 2
            val itemCenterY = itemTop + item.size.height / 2
            val dx = (itemCenterX - viewportCenterX).toLong()
            val dy = (itemCenterY - viewportCenterY).toLong()
            VisibleFeedVideo(video, visibleFraction, dx * dx + dy * dy)
        }.maxWithOrNull(
            compareBy<VisibleFeedVideo> { it.visibleFraction }
                .thenBy { -it.centerDistanceSquared },
        )?.video
}

/**
 * The video with [videoId] when its card still shows any part of itself in the grid's viewport, else null.
 * A preview that is already playing stays up for exactly as long as this is non-null: scrolling a little
 * must not stop it, only the card leaving the screen does.
 */
internal fun onScreenFeedVideo(
    layoutInfo: LazyGridLayoutInfo,
    videoId: String,
    videoAtIndex: (Int) -> Video?,
): Video? {
    val viewportStart = layoutInfo.viewportStartOffset
    val viewportEnd = layoutInfo.viewportEndOffset
    return layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
        val video = videoAtIndex(item.index)?.takeIf { it.id == videoId } ?: return@firstNotNullOfOrNull null
        val itemTop = item.offset.y
        val visibleHeight = (minOf(itemTop + item.size.height, viewportEnd) - maxOf(itemTop, viewportStart))
        video.takeIf { item.size.height > 0 && visibleHeight > 0 }
    }
}

private data class VisibleFeedVideo(
    val video: Video,
    val visibleFraction: Float,
    val centerDistanceSquared: Long,
)

private const val MIN_VISIBLE_FEED_VIDEO_FRACTION = 0.65f
private const val MIN_RETAINED_FEED_VIDEO_FRACTION = 0.35f
