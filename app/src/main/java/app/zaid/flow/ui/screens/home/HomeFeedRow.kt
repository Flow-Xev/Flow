package app.zaid.flow.ui.screens.home

import app.zaid.flow.data.model.Video

/** One slot of the home grid: a video card, or one of the two shelves that own a whole row. */
internal sealed interface HomeFeedRow {
    val key: String

    data class VideoCard(
        val video: Video,
    ) : HomeFeedRow {
        override val key: String get() = video.id
    }

    data object ContinueWatching : HomeFeedRow {
        override val key: String = "continue_watching_shelf"
    }

    /** One Shorts shelf, a single row; [index] keeps the key stable as pages append. */
    data class Shorts(
        val index: Int,
        val shorts: List<Video>,
    ) : HomeFeedRow {
        override val key: String get() = "shorts_shelf_$index"
    }

    val spansRow: Boolean get() = this !is VideoCard
}

internal const val SHORTS_PER_ROW = 6
internal const val MIN_SHORTS_PER_ROW = 4

/** Splits reels into one-row shelves of [SHORTS_PER_ROW]; a row with fewer than [MIN_SHORTS_PER_ROW] is dropped. */
internal fun chunkShortsRows(shorts: List<Video>): List<List<Video>> =
    shorts.distinctBy { it.id }.chunked(SHORTS_PER_ROW).filter { it.size >= MIN_SHORTS_PER_ROW }

/**
 * Layout: one grid row of videos, a Shorts row, then two grid rows of videos, a Shorts row, and so on.
 * Continue-watching sits right after the first run. Shelves span every column, so they only go in at
 * whole-row boundaries.
 */
internal fun homeFeedRows(
    videos: List<Video>,
    columns: Int,
    showContinueWatching: Boolean,
    shorts: List<Video>,
): List<HomeFeedRow> {
    if (videos.isEmpty()) return emptyList()
    val cols = columns.coerceAtLeast(1)
    val shortRows = chunkShortsRows(shorts)
    return buildList {
        var cursor = 0
        var shelf = 0
        var run = cols
        while (cursor < videos.size) {
            val end = (cursor + run).coerceAtMost(videos.size)
            for (i in cursor until end) add(HomeFeedRow.VideoCard(videos[i]))
            if (cursor == 0 && showContinueWatching) add(HomeFeedRow.ContinueWatching)
            if (shelf < shortRows.size) add(HomeFeedRow.Shorts(shelf, shortRows[shelf++]))
            cursor = end
            run = cols * 2
        }
    }
}
