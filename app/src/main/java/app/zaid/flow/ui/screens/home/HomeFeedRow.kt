package app.zaid.flow.ui.screens.home

import app.zaid.flow.data.model.Video
import app.zaid.flow.innertube.pages.renderer.CommunityPost

internal data class HomePostChannel(val id: String, val name: String, val thumbnail: String)

/** One channel's community posts, shown as one Home row. */
internal data class HomePostRow(
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String,
    val posts: List<CommunityPost>,
)

/** One titled shelf from the account's Home; [dismissToken] is what tells YouTube it is not wanted. */
internal data class HomeAccountShelf(
    val title: String,
    val videos: List<Video>,
    val dismissToken: String?,
) {
    val id: String get() = "yt_shelf_" + title.lowercase().hashCode()
}

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

    /** One channel's posts, a single row. */
    data class Posts(
        val index: Int,
        val row: HomePostRow,
    ) : HomeFeedRow {
        override val key: String get() = "posts_shelf_${row.channelId}_$index"
    }

    /** One account shelf (Watch again, News...), a single row with a cross to dismiss it. */
    data class AccountShelf(
        val shelf: HomeAccountShelf,
    ) : HomeFeedRow {
        override val key: String get() = shelf.id
    }

    val spansRow: Boolean get() = this !is VideoCard
}

private const val ACCOUNT_SHELF_EVERY = 3

/** Account shelves (Watch again, News...) in the whole feed. */
internal const val MAX_ACCOUNT_SHELF_ROWS = 3
internal const val SHORTS_PER_ROW = 6
internal const val MIN_SHORTS_PER_ROW = 4

/** Splits reels into one-row shelves of [SHORTS_PER_ROW]; a row with fewer than [MIN_SHORTS_PER_ROW] is dropped. */
internal fun chunkShortsRows(shorts: List<Video>): List<List<Video>> =
    shorts.distinctBy { it.id }.chunked(SHORTS_PER_ROW).filter { it.size >= MIN_SHORTS_PER_ROW }

/** Shelf kinds a [FeedStep] can put after its video runs. */
private enum class ShelfKind { SHORTS, POSTS }

/** [runs] grid rows of videos, then one [shelf] row. */
private data class FeedStep(
    val runs: Int,
    val shelf: ShelfKind,
)

/** The opening: V S V P V S V P V S. */
private val FEED_PREFIX =
    listOf(
        FeedStep(1, ShelfKind.SHORTS),
        FeedStep(2, ShelfKind.SHORTS),
        FeedStep(1, ShelfKind.SHORTS),
    )

/** Then, forever: VV S (two video runs, then a Shorts row). Posts never repeat. */
private val FEED_CYCLE =
    listOf(
        FeedStep(2, ShelfKind.SHORTS),
    )

/** Posts rows in the whole feed; only the two in the opening pattern. */
internal const val MAX_HOME_POSTS_ROWS = 0

/**
 * Layout (a run is one grid row of videos):
 * V, Shorts, V, Posts, V, Shorts, V, Posts, V, Shorts, then VV, Shorts repeating.
 * Continue-watching sits right after the first run. Shelves span every column, so they only go in at
 * whole-row boundaries. A shelf with nothing to show is skipped, never swapped for the other kind.
 */
internal fun homeFeedRows(
    videos: List<Video>,
    columns: Int,
    showContinueWatching: Boolean,
    shorts: List<Video>,
    postRows: List<HomePostRow> = emptyList(),
    accountShelves: List<HomeAccountShelf> = emptyList(),
): List<HomeFeedRow> {
    if (videos.isEmpty()) return emptyList()
    val cols = columns.coerceAtLeast(1)
    val shortRows = chunkShortsRows(shorts)
    val posts = postRows.take(MAX_HOME_POSTS_ROWS)
    // Only the first few shelves show; dismissing one lets the next take its place.
    val shelves = accountShelves.take(MAX_ACCOUNT_SHELF_ROWS)
    return buildList {
        var cursor = 0
        var shortIdx = 0
        var postIdx = 0
        var accountIdx = 0
        var step = 0
        while (cursor < videos.size) {
            val spec = if (step < FEED_PREFIX.size) FEED_PREFIX[step] else FEED_CYCLE[(step - FEED_PREFIX.size) % FEED_CYCLE.size]
            repeat(spec.runs) {
                if (cursor >= videos.size) return@repeat
                val end = (cursor + cols).coerceAtMost(videos.size)
                for (i in cursor until end) add(HomeFeedRow.VideoCard(videos[i]))
                if (cursor == 0 && showContinueWatching) add(HomeFeedRow.ContinueWatching)
                cursor = end
            }
            var shortsAdded = false
            when (spec.shelf) {
                ShelfKind.SHORTS ->
                    if (shortIdx < shortRows.size) {
                        add(HomeFeedRow.Shorts(shortIdx, shortRows[shortIdx++]))
                        shortsAdded = true
                    }
                ShelfKind.POSTS -> if (postIdx < posts.size) add(HomeFeedRow.Posts(postIdx, posts[postIdx++]))
            }
            // YouTube's own shelves follow the Shorts rows: right after the first, then every third.
            // With no Shorts at all they sit after the video runs instead, so they are never lost.
            val afterShorts = shortsAdded && (shortIdx - 1) % ACCOUNT_SHELF_EVERY == 0
            val noShorts = shortRows.isEmpty() && step % ACCOUNT_SHELF_EVERY == 0
            if ((afterShorts || noShorts) && accountIdx < shelves.size) add(HomeFeedRow.AccountShelf(shelves[accountIdx++]))
            step++
        }
    }
}

/**
 * How many Shorts shelves the feed puts among the first [videoCount] videos at [columns] columns; mirrors the
 * step loop in [homeFeedRows]. The view model uses it to fetch only the Shorts those shelves will need.
 */
internal fun shortsSlotsUpTo(
    videoCount: Int,
    columns: Int,
): Int {
    val cols = columns.coerceAtLeast(1)
    var cursor = 0
    var step = 0
    var slots = 0
    while (cursor < videoCount) {
        val spec = if (step < FEED_PREFIX.size) FEED_PREFIX[step] else FEED_CYCLE[(step - FEED_PREFIX.size) % FEED_CYCLE.size]
        repeat(spec.runs) { if (cursor < videoCount) cursor = (cursor + cols).coerceAtMost(videoCount) }
        if (spec.shelf == ShelfKind.SHORTS) slots++
        step++
    }
    return slots
}
