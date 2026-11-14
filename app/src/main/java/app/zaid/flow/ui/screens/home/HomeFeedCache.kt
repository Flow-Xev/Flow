package app.zaid.flow.ui.screens.home

import app.zaid.flow.data.model.Video

/**
 * Process-lifetime in-memory cache for the Home feed.
 *
 * Survives ViewModel recreation (which happens when the user navigates away
 * from Home and comes back via the bottom nav), preventing an unwanted
 * network reload on every tab switch. The cache expires after [CACHE_TTL_MS]
 * (2 minutes) and is explicitly cleared when the user pulls-to-refresh.
 */
internal object HomeFeedCache {
    private const val CACHE_TTL_MS = 2 * 60 * 1000L // 2 minutes: enough to bridge a tab switch, never a stale feed

    @Volatile var videos: List<Video> = emptyList()
        private set

    @Volatile var shorts: List<Video> = emptyList()
        private set

    @Volatile var timestamp: Long = 0L
        private set

    /** Posts rows the feed on screen carried, so a tab switch does not lose them. */
    @Volatile var postRows: List<HomePostRow> = emptyList()

    /** The account home's next-page token, so a restored feed keeps loading more of the account's Home. */
    @Volatile var continuation: String? = null

    /**
     * The account whose Home these videos came from, or null for the on-device mix. A cache made for
     * one source must never stand in for another, or a feed built before sign-in (or while the
     * account's Home failed) hides the account's own feed for the whole TTL.
     */
    @Volatile var accountId: String? = null

    /** Home is never served from cache: every open and every refresh loads the feed fresh. */
    @Suppress("UNUSED_PARAMETER")
    fun isFresh(forAccountId: String? = null): Boolean = false

    fun update(
        newVideos: List<Video>,
        newShorts: List<Video>,
    ) {
        videos = newVideos
        shorts = newShorts
        timestamp = System.currentTimeMillis()
    }

    fun clear() {
        videos = emptyList()
        shorts = emptyList()
        timestamp = 0L
        accountId = null
        postRows = emptyList()
        continuation = null
    }

    /**
     * Remove videos by blocked channel/topic from the cached feed without
     * requiring a network refetch, keeping the cache TTL alive.
     */
    fun filterOut(
        channelId: String? = null,
        videoId: String? = null,
    ) {
        if (channelId != null) {
            videos = videos.filter { it.channelId != channelId }
            shorts = shorts.filter { it.channelId != channelId }
        }
        if (videoId != null) {
            videos = videos.filter { it.id != videoId }
            shorts = shorts.filter { it.id != videoId }
        }
    }
}
