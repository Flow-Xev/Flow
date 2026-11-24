package app.zaid.flow.data.shorts.queue

import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.PlaylistRepository
import app.zaid.flow.data.model.ShortVideo
import app.zaid.flow.data.model.toShortVideo
import app.zaid.flow.data.recommendation.FeedExclusions
import app.zaid.flow.data.shorts.ShortsFeedRepository
import app.zaid.flow.data.shorts.spreadChannels
import app.zaid.flow.data.subscriptions.SubscriptionFeedRepository
import app.zaid.flow.data.subscriptions.SubscriptionWatchedVideos
import kotlinx.coroutines.flow.first

/**
 * The algorithmic reel feed, optionally opened on one short.
 *
 * The pager behind [ShortsFeedRepository] never reports an end — its explore chain has none — so
 * a page can come back empty (every candidate filtered) without the queue giving up on it.
 */
class AlgorithmicFeedLoader(
    private val repository: ShortsFeedRepository,
    private val seedVideoId: String? = null,
) : ShortsQueueLoader {
    override suspend fun initial(): ShortsQueuePage = page(repository.openFeed(seedVideoId))

    override suspend fun more(cursor: String?): ShortsQueuePage = page(repository.nextPage())

    private fun page(items: List<ShortVideo>): ShortsQueuePage =
        ShortsQueuePage(
            items = items,
            cursor = MORE.takeUnless { repository.isExhausted },
            exhausted = repository.isExhausted,
        )

    private companion object {
        const val MORE = "more"
    }
}

/**
 * The user's saved Shorts, in saved order.
 *
 * Reads the flow's current value **once** with [first]. The pre-queue version held an open `collect`,
 * so bookmarking or un-bookmarking anything rebuilt the list and reset the pager position mid-watch.
 */
class SavedShortsLoader(
    private val playlistRepository: PlaylistRepository,
) : ShortsQueueLoader {
    override suspend fun initial(): ShortsQueuePage {
        val saved = playlistRepository.getSavedShortsFlow().first()
        return ShortsQueuePage(
            items = saved.map { it.toShortVideo() },
            cursor = null,
            exhausted = true,
        )
    }

    override suspend fun more(cursor: String?): ShortsQueuePage = exhaustedPage()
}

/**
 * [exclusions] keeps reels marked not interested and blocked channels out, as the Subscriptions feed
 * does. The tapped reel is exempt from every filter: the user just chose it.
 */
class SubscriptionShortsLoader(
    private val subscriptionFeedRepository: SubscriptionFeedRepository,
    private val playerPreferences: PlayerPreferences,
    private val watchedVideos: SubscriptionWatchedVideos,
    private val anchorVideoId: String?,
    private val exclusions: suspend () -> FeedExclusions = { FeedExclusions.NONE },
) : ShortsQueueLoader {
    override suspend fun initial(): ShortsQueuePage {
        val excludedChannelIds = playerPreferences.subscriptionShortsExcludedChannels.first()
        val watchedIds = watchedVideos.shortIds.first()
        val hidden = exclusions()
        val items =
            subscriptionFeedRepository
                .observeFeed()
                .first()
                .asSequence()
                .filter { it.id.isNotBlank() }
                .filter {
                    it.id == anchorVideoId ||
                        (it.isShort && it.channelId !in excludedChannelIds && !hidden.hides(it) && it.id !in watchedIds)
                }.sortedByDescending { it.timestamp }
                .map { it.toShortVideo() }
                .toList()
        return ShortsQueuePage(spreadChannels(items, ShortVideo::channelId), cursor = null, exhausted = true)
    }

    override suspend fun more(cursor: String?): ShortsQueuePage = exhaustedPage()
}

/** Fetches whatever a paged source has loaded since the player last asked, loading another page if nothing new. */
fun interface SnapshotContinuation {
    suspend fun more(): ShortsQueuePage
}

/**
 * A list a screen already had in memory, handed over via [ShortsQueueHandoff].
 *
 * Finite unless the screen is still paging it: then [continuation] keeps the queue growing from the same
 * source. Without one, continuing past the end is the controller's job.
 */
class SnapshotLoader(
    private val items: List<ShortVideo>,
    private val continuation: SnapshotContinuation? = null,
) : ShortsQueueLoader {
    override suspend fun initial(): ShortsQueuePage =
        ShortsQueuePage(
            items,
            cursor = if (continuation != null) MORE else null,
            exhausted = continuation == null,
        )

    override suspend fun more(cursor: String?): ShortsQueuePage = continuation?.more() ?: exhaustedPage()

    private companion object {
        const val MORE = "more"
    }
}

/**
 * A loader the user can switch off — today, the algorithmic feed that trails a finished queue.
 *
 * [enabled] is read when the hand-over would actually happen rather than when the queue is built, so
 * flipping the setting mid-watch decides what the *next* swipe past the end does. Switched off it
 * reports itself exhausted, which is the queue's existing way of saying "this is the end".
 */
class GatedShortsLoader(
    private val enabled: suspend () -> Boolean,
    private val delegate: ShortsQueueLoader,
) : ShortsQueueLoader {
    override suspend fun initial(): ShortsQueuePage = if (enabled()) delegate.initial() else exhaustedPage()

    override suspend fun more(cursor: String?): ShortsQueuePage = if (enabled()) delegate.more(cursor) else exhaustedPage()
}

private fun exhaustedPage() = ShortsQueuePage(emptyList(), cursor = null, exhausted = true)
