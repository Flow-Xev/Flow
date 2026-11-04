package app.zaid.flow.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.zaid.flow.data.local.ContentType
import app.zaid.flow.data.local.SearchFilter
import app.zaid.flow.data.model.DistinctKeyTracker
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.repository.YouTubeRepository
import app.zaid.flow.data.recommendation.FeedExclusions
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.pages.renderer.FeedItem
import app.zaid.flow.innertube.pages.renderer.FeedShelf
import app.zaid.flow.innertube.pages.renderer.FeedShelfStyle
import app.zaid.flow.innertube.pages.renderer.videoOrNull
import app.zaid.flow.innertube.pages.search.SearchHeader
import app.zaid.flow.innertube.pages.search.SearchResultsPage
import app.zaid.flow.innertube.pages.search.SearchSection

/**
 * One InnerTube request per page, and nothing after it, unless a page is too thin to be worth showing
 * ([minResultsPerLoad]) or empty with more to come, in which case up to three more are chained.
 *
 * Every filter and sort is a `params` token the server applies, so a page arrives already narrowed
 * and already ordered; the avatars, badges and verification the old path fetched per video are read
 * out of the same response.
 */
class SearchPagingSource(
    private val query: String,
    private val filter: SearchFilter = SearchFilter.DEFAULT,
    private val shortsEnabled: Boolean = true,
    private val onHeader: (SearchHeader) -> Unit = {},
    private val loadPage: SearchPageLoader = DefaultSearchPageLoader,
    /**
     * A page that is mostly YouTube's own suggestion shelves holds few real results. Below this many
     * videos and Shorts, the next page is fetched in the same load instead of leaving a thin list
     * the user has to scroll into. 0 keeps the strict one-request-per-page behaviour.
     */
    private val minResultsPerLoad: Int = 0,
    /** Keep "People also watch", "For you" and similar suggestion shelves in the results. They never count toward [minResultsPerLoad]. */
    private val keepSuggestionShelves: Boolean = true,
    /**
     * Endless search. YouTube's own pages end after a few hundred results; when they do, the list
     * carries on with the results of related searches (typeahead suggestions of the query, then of
     * those), so scrolling never hits a wall. Results already shown are never repeated.
     */
    private val endless: Boolean = false,
    private val relatedQueries: suspend (String) -> List<String> = DefaultRelatedQueries,
    /**
     * "People also watch": when YouTube's pages end, the watch-next recommendations of the top results
     * come first (one seed video after another), and only then related searches. Takes a seed video id and
     * a continuation (null for the first page) and returns the videos and the next continuation.
     */
    private val recommendedFeed: suspend (String, String?) -> Pair<List<Video>, String?>? = DefaultRecommendedFeed,
    private val exclusions: suspend () -> FeedExclusions = { FeedExclusions.NONE },
) : PagingSource<String, SearchResultItem>() {
    override fun getRefreshKey(state: PagingState<String, SearchResultItem>): String? = null

    private val loadedItemKeys = DistinctKeyTracker()

    private enum class Phase { MAIN, RECOMMENDED, RELATED }

    /** Page keys: a plain InnerTube token, `~rec:<seed>|<token>` in "people also watch", `~rel:<n>|<token>` in related searches. */
    private class Cursor(
        val phase: Phase,
        val index: Int,
        val token: String?,
    ) {
        fun encode(): String? =
            when (phase) {
                Phase.MAIN -> token
                Phase.RECOMMENDED -> "~rec:$index|${token.orEmpty()}"
                Phase.RELATED -> "~rel:$index|${token.orEmpty()}"
            }

        companion object {
            fun decode(key: String?): Cursor {
                fun parse(prefix: String): Pair<Int, String?> = key!!.removePrefix(prefix).let { (it.substringBefore('|').toIntOrNull() ?: 0) to it.substringAfter('|', "").ifEmpty { null } }
                return when {
                    key == null -> Cursor(Phase.MAIN, 0, null)
                    key.startsWith("~rec:") -> parse("~rec:").let { Cursor(Phase.RECOMMENDED, it.first, it.second) }
                    key.startsWith("~rel:") -> parse("~rel:").let { Cursor(Phase.RELATED, it.first, it.second) }
                    else -> Cursor(Phase.MAIN, 0, key)
                }
            }
        }
    }

    private var pageSequence = 0
    private val seeds = mutableListOf<Video>()
    private val related = mutableListOf<String>()
    private val usedQueries = mutableSetOf<String>()
    private var expandedFrom = 0

    /** Recommendations only fit an unfiltered, all-types search; a filtered or Shorts search goes straight to related searches. */
    private val recommendationsFit: Boolean
        get() = filter.contentType == ContentType.ALL && filter.isDefault

    /** The related query at [index], growing the list from suggestions of the last one when it runs out. */
    private suspend fun relatedAt(index: Int): String? {
        if (usedQueries.isEmpty()) usedQueries += query.trim().lowercase()
        var attempts = 0
        while (index >= related.size && related.size < MAX_RELATED && attempts < 3) {
            val seed = if (expandedFrom == 0) query else related.getOrNull(expandedFrom - 1) ?: break
            expandedFrom++
            attempts++
            val fresh =
                runCatching { relatedQueries(seed) }.getOrDefault(emptyList())
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && usedQueries.add(it.lowercase()) }
            related += fresh
        }
        return related.getOrNull(index)
    }

    /** Where to go when the current source has nothing more: the next seed, then related searches, then the next related search. */
    private fun afterExhausted(cursor: Cursor): Cursor =
        when (cursor.phase) {
            Phase.MAIN -> if (recommendationsFit && seeds.isNotEmpty()) Cursor(Phase.RECOMMENDED, 0, null) else Cursor(Phase.RELATED, 0, null)
            Phase.RECOMMENDED -> if (cursor.index + 1 < seeds.size) Cursor(Phase.RECOMMENDED, cursor.index + 1, null) else Cursor(Phase.RELATED, 0, null)
            Phase.RELATED -> Cursor(Phase.RELATED, cursor.index + 1, null)
        }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, SearchResultItem> {
        var cursor = Cursor.decode(params.key)
        val collected = mutableListOf<SearchResultItem>()
        var requests = 0
        var spent = false
        while (true) {
            var items: List<SearchResultItem> = emptyList()
            var next: String? = null
            try {
                when (cursor.phase) {
                    Phase.RECOMMENDED -> {
                        val seed = seeds.getOrNull(cursor.index)
                        val feed = seed?.let { recommendedFeed(it.id, cursor.token) }
                        items = feed?.first.orEmpty().filter { shortsEnabled || !it.isShort }.map { SearchResultItem.VideoResult(it) }
                        next = feed?.second
                    }

                    else -> {
                        val activeQuery = if (cursor.phase == Phase.MAIN) query else relatedAt(cursor.index)
                        if (activeQuery == null) {
                            spent = true
                            break
                        }
                        val page = loadPage(activeQuery, filter.toSearchParams(), cursor.token)
                        if (params.key == null && requests == 0) onHeader(page.header)
                        items =
                            page
                                .toResultItems(shortsEnabled, keepSuggestionShelves)
                                .let { if (filter.contentType == ContentType.SHORTS) it.flattenShorts() else it }
                        next = page.continuation
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what already arrived; only a load that has nothing to show is an error.
                return if (collected.isEmpty()) LoadResult.Error(e) else page(collected, cursor.encode())
            }
            requests++
            val tag = pageSequence++
            val results =
                items
                    // Shelf ids are only unique inside one response; without the page tag, a later page's
                    // "Shorts" shelf has the same id as an earlier one and is thrown away as a duplicate.
                    .map { if (it is SearchResultItem.ShelfResult) it.copy(id = "p$tag:${it.id}") else it }
                    .withoutHidden(exclusions())
            val fresh = loadedItemKeys.filter(results) { it.identityKey() }
            collected += fresh
            if (cursor.phase == Phase.MAIN) {
                fresh.filterIsInstance<SearchResultItem.VideoResult>().map { it.video }.filter { !it.isShort }
                    .forEach { if (seeds.size < MAX_SEEDS) seeds += it }
            }
            cursor =
                when {
                    next != null -> Cursor(cursor.phase, cursor.index, next)
                    endless -> afterExhausted(cursor)
                    else -> Cursor(Phase.MAIN, 0, null).also { spent = true }
                }
            val enough = collected.count { it.isRealResult() } >= minResultsPerLoad
            // Never hand Paging an empty page that still has a next key: it would stall there.
            val mustContinue = collected.isEmpty() && !spent
            if (spent || requests >= MAX_REQUESTS_PER_LOAD || (enough && !mustContinue)) break
        }
        // Spent: YouTube, its recommendations and every related search have run dry.
        return page(collected, if (spent) null else cursor.encode())
    }

    private fun page(
        data: List<SearchResultItem>,
        next: String?,
    ): LoadResult.Page<String, SearchResultItem> = LoadResult.Page(data = data, prevKey = null, nextKey = next)

    private companion object {
        const val MAX_REQUESTS_PER_LOAD = 5
        const val MAX_RELATED = 60
        const val MAX_SEEDS = 8
    }
}

/** The Shorts tab is a grid of single Shorts: a Shorts shelf is opened up so every Short in it is a cell. */
internal fun List<SearchResultItem>.flattenShorts(): List<SearchResultItem> =
    flatMap { item ->
        if (item is SearchResultItem.ShelfResult && item.kind == SearchShelfKind.SHORTS) {
            item.videos.map { SearchResultItem.VideoResult(it) }
        } else {
            listOf(item)
        }
    }

private fun SearchResultItem.isRealResult(): Boolean =
    when (this) {
        is SearchResultItem.VideoResult -> true
        is SearchResultItem.ChannelResult -> true
        is SearchResultItem.PlaylistResult -> true
        is SearchResultItem.ShelfResult -> kind == SearchShelfKind.SHORTS
    }

fun interface SearchPageLoader {
    suspend operator fun invoke(
        query: String,
        params: String?,
        continuation: String?,
    ): SearchResultsPage
}

private val DefaultRecommendedFeed: suspend (String, String?) -> Pair<List<Video>, String?>? = { videoId, token ->
    val repo = YouTubeRepository.getInstance()
    val result = if (token == null) repo.relatedFeedFirstPage(videoId) else repo.relatedFeedPage(token)
    result?.let { it.videos to it.continuation }
}

private val DefaultRelatedQueries: suspend (String) -> List<String> = { q ->
    YouTube.videoSearchSuggestions(q).getOrDefault(emptyList()).map { it.text }
}

private val DefaultSearchPageLoader =
    SearchPageLoader { query, params, continuation ->
        YouTube.videoSearch(query, params, continuation).getOrThrow()
    }

/**
 * YouTube returns the creator's "Latest from" strip as its own shelf directly after the channel
 * card and renders the two as one block, so they are folded together here rather than reaching the
 * grid as two rows that have to find each other again.
 */
internal fun SearchResultsPage.toResultItems(
    shortsEnabled: Boolean,
    keepSuggestionShelves: Boolean = true,
): List<SearchResultItem> {
    val items = mutableListOf<SearchResultItem>()
    var index = 0
    while (index < sections.size) {
        val item =
            when (val section = sections[index]) {
                is SearchSection.Result -> section.item.toResultItem(shortsEnabled)
                is SearchSection.Strip -> section.shelf.toShelfItem(shortsEnabled)
            }
        val latest = (item as? SearchResultItem.ChannelResult)?.let { sections.latestStripAfter(index) }
        if (latest != null) {
            items +=
                (item as SearchResultItem.ChannelResult).copy(
                    latestTitle = latest.title,
                    latestVideos = latest.videos,
                )
            index += 2
            continue
        }
        // A videos shelf that did not fold into a channel card is YouTube suggesting, not matching.
        val isSuggestion = item is SearchResultItem.ShelfResult && item.kind == SearchShelfKind.VIDEOS
        if (!(isSuggestion && !keepSuggestionShelves)) item?.let(items::add)
        index++
    }
    return items
}

/**
 * Drops everything a blocked creator put in the results, the way the home feed already drops them
 * before ranking: their own card, their videos, and their videos inside a strip. A video marked not
 * interested goes too. A strip left with nothing goes, rather than staying as a heading over a gap.
 *
 * Only what the viewer hid outright applies: a search is an explicit ask, so the engine's inferred
 * suppressions and blocked topics do not filter it.
 *
 * Community posts carry no channel id in the response, so a blocked creator's post survives here.
 */
internal fun List<SearchResultItem>.withoutHidden(exclusions: FeedExclusions): List<SearchResultItem> {
    if (exclusions.isEmpty) return this

    return mapNotNull { item ->
        when (item) {
            is SearchResultItem.VideoResult -> {
                item.takeUnless { exclusions.hides(it.video) }
            }

            is SearchResultItem.ChannelResult -> {
                item.takeUnless { exclusions.hidesChannel(it.channel.id) }
            }

            is SearchResultItem.PlaylistResult -> {
                item
            }

            is SearchResultItem.ShelfResult -> {
                val videos = item.videos.filterNot { exclusions.hides(it) }
                when {
                    videos.isNotEmpty() -> item.copy(videos = videos)
                    item.posts.isNotEmpty() -> item
                    else -> null
                }
            }
        }
    }
}

/** The videos strip that immediately follows [index], if that is what the next section holds. */
private fun List<SearchSection>.latestStripAfter(index: Int): SearchResultItem.ShelfResult? =
    (getOrNull(index + 1) as? SearchSection.Strip)
        ?.shelf
        ?.toShelfItem(shortsEnabled = true)
        ?.let { it as? SearchResultItem.ShelfResult }
        ?.takeIf { it.kind == SearchShelfKind.VIDEOS }

private fun FeedItem.toResultItem(shortsEnabled: Boolean): SearchResultItem? =
    when (this) {
        is FeedItem.VideoItem -> SearchResultItem.VideoResult(video)
        is FeedItem.ShortItem -> SearchResultItem.VideoResult(video).takeIf { shortsEnabled }
        is FeedItem.PlaylistItem -> SearchResultItem.PlaylistResult(playlist)
        is FeedItem.RelatedChannelItem -> SearchResultItem.ChannelResult(channel)
        is FeedItem.PostItem -> null
    }

private fun FeedShelf.toShelfItem(shortsEnabled: Boolean): SearchResultItem? {
    val posts = items.filterIsInstance<FeedItem.PostItem>().map { it.post }
    if (posts.isNotEmpty()) {
        return SearchResultItem.ShelfResult(id, title, SearchShelfKind.POSTS, posts = posts)
    }
    val videos = items.mapNotNull { it.videoOrNull() }
    if (videos.isEmpty()) return null
    // A reel shelf is Shorts even when it arrives as a carousel, not a grid.
    val allShorts = items.isNotEmpty() && items.all { it is FeedItem.ShortItem }
    val kind = if (style == FeedShelfStyle.Grid || allShorts) SearchShelfKind.SHORTS else SearchShelfKind.VIDEOS
    if (kind == SearchShelfKind.SHORTS && !shortsEnabled) return null
    return SearchResultItem.ShelfResult(
        id = id,
        title = title,
        kind = kind,
        videos = videos,
        collapsedItemCount = collapsedItemCount,
    )
}

private fun SearchResultItem.identityKey(): String =
    when (this) {
        is SearchResultItem.VideoResult -> video.id.prefixed("video")
        is SearchResultItem.ChannelResult -> channel.id.prefixed("channel")
        is SearchResultItem.PlaylistResult -> playlist.id.prefixed("playlist")
        is SearchResultItem.ShelfResult -> "shelf:$id"
    }

private fun String.prefixed(type: String): String = takeIf(String::isNotBlank)?.let { "$type:$it" }.orEmpty()
