package app.zaid.flow.innertube

import app.zaid.flow.network.SelfHealingManager
import app.zaid.flow.account.AccountIncognito
import android.util.Log
import app.zaid.flow.data.model.VideoCollaborator
import app.zaid.flow.innertube.models.AccountInfo
import app.zaid.flow.innertube.models.AlbumItem
import app.zaid.flow.innertube.models.Artist
import app.zaid.flow.innertube.models.ArtistItem
import app.zaid.flow.innertube.models.BrowseEndpoint
import app.zaid.flow.innertube.models.GridRenderer
import app.zaid.flow.innertube.models.MediaInfo
import app.zaid.flow.innertube.models.MusicCarouselShelfRenderer
import app.zaid.flow.innertube.models.MusicShelfRenderer
import app.zaid.flow.innertube.models.PlaylistItem
import app.zaid.flow.innertube.models.ReturnYouTubeDislikeResponse
import app.zaid.flow.innertube.models.Run
import app.zaid.flow.innertube.models.Runs
import app.zaid.flow.innertube.models.SearchSuggestions
import app.zaid.flow.innertube.models.SongItem
import app.zaid.flow.innertube.models.WatchEndpoint
import app.zaid.flow.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import app.zaid.flow.innertube.models.YouTubeClient
import app.zaid.flow.innertube.models.YouTubeClient.Companion.WEB
import app.zaid.flow.innertube.models.YouTubeClient.Companion.MWEB
import app.zaid.flow.innertube.models.YouTubeClient.Companion.TVHTML5
import app.zaid.flow.innertube.models.YouTubeClient.Companion.WEB_REMIX
import app.zaid.flow.innertube.models.YouTubeLocale
import app.zaid.flow.innertube.models.body.BrowseBody
import app.zaid.flow.innertube.models.getContinuation
import app.zaid.flow.innertube.models.getItems
import app.zaid.flow.innertube.models.oddElements
import app.zaid.flow.innertube.models.response.AccountMenuResponse
import app.zaid.flow.innertube.models.response.BrowseResponse
import app.zaid.flow.innertube.models.response.ChannelVideosResponse
import app.zaid.flow.innertube.models.response.CreatePlaylistResponse
import app.zaid.flow.innertube.models.response.EditPlaylistResponse
import app.zaid.flow.innertube.models.response.GetQueueResponse
import app.zaid.flow.innertube.models.response.GetSearchSuggestionsResponse
import app.zaid.flow.innertube.models.response.GetTranscriptResponse
import app.zaid.flow.innertube.models.response.ImageUploadResponse
import app.zaid.flow.innertube.models.response.NextResponse
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.innertube.models.response.ResolveUrlResponse
import app.zaid.flow.innertube.models.response.SearchResponse
import app.zaid.flow.innertube.models.response.channelVideoCountText
import app.zaid.flow.innertube.pages.AccountSessionRejectedException
import app.zaid.flow.innertube.pages.AlbumPage
import app.zaid.flow.innertube.pages.ArtistItemsContinuationPage
import app.zaid.flow.innertube.pages.ArtistItemsPage
import app.zaid.flow.innertube.pages.ArtistPage
import app.zaid.flow.innertube.pages.BrowseResult
import app.zaid.flow.innertube.pages.ChartsPage
import app.zaid.flow.innertube.pages.ExplorePage
import app.zaid.flow.innertube.pages.HistoryPage
import app.zaid.flow.innertube.pages.isLoggedInResponse
import app.zaid.flow.innertube.pages.HomePage
import app.zaid.flow.innertube.pages.LibraryContinuationPage
import app.zaid.flow.innertube.pages.LibraryPage
import app.zaid.flow.innertube.pages.MoodAndGenres
import app.zaid.flow.innertube.pages.NewReleaseAlbumPage
import app.zaid.flow.innertube.pages.NextPage
import app.zaid.flow.innertube.pages.NextResult
import app.zaid.flow.innertube.pages.PlaylistContinuationPage
import app.zaid.flow.innertube.pages.PlaylistPage
import app.zaid.flow.innertube.pages.RelatedPage
import app.zaid.flow.innertube.pages.SearchPage
import app.zaid.flow.innertube.pages.SearchResult
import app.zaid.flow.innertube.pages.SearchSuggestionPage
import app.zaid.flow.innertube.pages.SearchSummaryPage
import app.zaid.flow.innertube.pages.VideoCommentsPage
import app.zaid.flow.innertube.pages.VideoDescriptionPage
import app.zaid.flow.innertube.pages.VideoPlaylistPage
import app.zaid.flow.innertube.pages.channel.ChannelAbout
import app.zaid.flow.innertube.pages.channel.ChannelHeader
import app.zaid.flow.innertube.pages.channel.ChannelPage
import app.zaid.flow.innertube.pages.channel.ChannelProduct
import app.zaid.flow.innertube.pages.channel.ChannelShortsPage
import app.zaid.flow.innertube.pages.channel.ChannelSortOption
import app.zaid.flow.innertube.pages.channel.ChannelTabContent
import app.zaid.flow.innertube.pages.channel.ChannelTabKind
import app.zaid.flow.innertube.pages.channel.channelAboutContinuation
import app.zaid.flow.innertube.pages.channel.channelSortOptions
import app.zaid.flow.innertube.pages.channel.toChannelAbout
import app.zaid.flow.innertube.pages.channel.toChannelHeader
import app.zaid.flow.innertube.pages.channel.toChannelShortsPage
import app.zaid.flow.innertube.pages.channel.toChannelStorePage
import app.zaid.flow.innertube.pages.channel.toChannelTabContent
import app.zaid.flow.innertube.pages.channel.toChannelTabs
import app.zaid.flow.innertube.pages.explore.ExploreDestinationPage
import app.zaid.flow.innertube.pages.explore.MusicVideoChart
import app.zaid.flow.innertube.pages.explore.exploreShelves
import app.zaid.flow.innertube.pages.explore.ranked
import app.zaid.flow.innertube.pages.explore.toExploreDestinationShell
import app.zaid.flow.innertube.pages.explore.trendingVideoChart
import app.zaid.flow.innertube.pages.objectOrNull
import app.zaid.flow.innertube.pages.parseYouTubeViewCount
import app.zaid.flow.innertube.pages.reel.ReelLockup
import app.zaid.flow.innertube.pages.reel.ReelOverlay
import app.zaid.flow.innertube.pages.reel.ReelParams
import app.zaid.flow.innertube.pages.reel.ReelSequencePage
import app.zaid.flow.innertube.pages.reel.SoundContinuation
import app.zaid.flow.innertube.pages.reel.SoundPage
import app.zaid.flow.innertube.pages.reel.soundShortsTotal
import app.zaid.flow.innertube.pages.reel.toReelLockups
import app.zaid.flow.innertube.pages.reel.toReelOverlay
import app.zaid.flow.innertube.pages.reel.toReelSequencePage
import app.zaid.flow.innertube.pages.renderer.CommunityCommentsPage
import app.zaid.flow.innertube.pages.renderer.CommunityPostsPage
import app.zaid.flow.innertube.pages.renderer.FeedItemOwner
import app.zaid.flow.innertube.pages.renderer.FeedShelf
import app.zaid.flow.innertube.pages.renderer.collaboratorDialog
import app.zaid.flow.innertube.pages.renderer.lockupDateAndViews
import app.zaid.flow.innertube.pages.renderer.toCommunityCommentsPage
import app.zaid.flow.innertube.pages.renderer.toCommunityPostsPage
import app.zaid.flow.innertube.pages.search.SearchResultsPage
import app.zaid.flow.innertube.pages.search.SearchSuggestion
import app.zaid.flow.innertube.pages.search.parseSearchSuggestions
import app.zaid.flow.innertube.pages.search.toSearchResultsPage
import app.zaid.flow.innertube.pages.stringOrNull
import app.zaid.flow.innertube.pages.toCommentRepliesPage
import app.zaid.flow.innertube.pages.toRelatedFeedFirstPage
import app.zaid.flow.innertube.pages.toRelatedFeedPage
import app.zaid.flow.innertube.pages.toVideoCommentsPage
import app.zaid.flow.innertube.pages.toVideoDescriptionPage
import app.zaid.flow.innertube.pages.toVideoPlaylistPage
import app.zaid.flow.innertube.pages.videoCommentsContinuation
import app.zaid.flow.utils.PerformanceDispatcher
import app.zaid.flow.utils.avatarImageIdentityKey
import app.zaid.flow.utils.relativedate.RelativeUploadDateParser
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.net.Proxy
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Parse useful data with [InnerTube] sending requests.
 */
object YouTube {
    /** The shared client; exposed so DI hands account code the same instance (and cookie state). */
    val innerTube = InnerTube()
    private const val CHANNEL_VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
    private const val CHANNEL_LIVE_PARAMS = "EgdzdHJlYW1z8gYECgJ6AA%3D%3D"
    private const val CHANNEL_POSTS_PARAMS = "EgVwb3N0c_IGBAoCSgA="
    private const val CHANNEL_SHORTS_PARAMS = "EgZzaG9ydHPyBgUKA5oBAA=="

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
        }
    var visitorData: String?
        get() = innerTube.visitorData
        set(value) {
            innerTube.visitorData = value
        }
    var onVisitorDataChanged: ((String?) -> Unit)?
        get() = innerTube.onVisitorDataChanged
        set(value) {
            innerTube.onVisitorDataChanged = value
        }
    var dataSyncId: String?
        get() = innerTube.dataSyncId
        set(value) {
            innerTube.dataSyncId = value
        }
    var cookie: String?
        get() = innerTube.cookie
        set(value) {
            innerTube.cookie = value
        }
    var proxy: Proxy?
        get() = innerTube.proxy
        set(value) {
            innerTube.proxy = value
        }

    var proxyAuth: String?
        get() = innerTube.proxyAuth
        set(value) {
            innerTube.proxyAuth = value
        }
    var cacheDirectory: java.io.File?
        get() = innerTube.cacheDirectory
        set(value) {
            innerTube.cacheDirectory = value
        }
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) {
            innerTube.useLoginForBrowse = value
        }

    suspend fun searchSuggestions(query: String): Result<SearchSuggestions> =
        runCatching {
            val response = innerTube.getSearchSuggestions(WEB_REMIX, query).body<GetSearchSuggestionsResponse>()
            SearchSuggestions(
                queries =
                    response.contents
                        ?.getOrNull(0)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull { content ->
                            content.searchSuggestionRenderer
                                ?.suggestion
                                ?.runs
                                ?.joinToString(separator = "") { it.text }
                        }.orEmpty(),
                recommendedItems =
                    response.contents
                        ?.getOrNull(1)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull {
                            it.musicResponsiveListItemRenderer?.let { renderer ->
                                SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer)
                            }
                        }.orEmpty(),
            )
        }

    suspend fun searchSummary(query: String): Result<SearchSummaryPage> =
        runCatching {
            SearchSummaryPage.fromSearchResponse(innerTube.search(WEB_REMIX, query).body<SearchResponse>())
        }

    suspend fun search(
        query: String,
        filter: SearchFilter,
    ): Result<SearchResult> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, query, filter.value).body<SearchResponse>()
            SearchResult(
                items =
                    response.contents
                        ?.tabbedSearchResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.lastOrNull()
                        ?.musicShelfRenderer
                        ?.contents
                        ?.getItems()
                        ?.mapNotNull {
                            SearchPage.toYTItem(it)
                        }.orEmpty(),
                continuation =
                    response.contents
                        ?.tabbedSearchResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.lastOrNull()
                        ?.musicShelfRenderer
                        ?.continuations
                        ?.getContinuation(),
            )
        }

    /** Reels matching [query], through the Shorts filter the search dialog itself sends. */
    suspend fun searchShorts(query: String): Result<List<ReelLockup>> =
        runCatching {
            ensureVisitorData()
            healedWebSearch(operation = "searchShorts", query = query, params = ReelParams.SEARCH_SHORTS_FILTER, continuation = null)
                .toReelLockups()
        }.onFailure { Log.w("SearchShorts", "query='$query' failed: ${it.javaClass.simpleName}") }

    /** Typeahead suggestions for the video search bar, in the app's content language. */
    suspend fun videoSearchSuggestions(query: String): Result<List<SearchSuggestion>> =
        runCatching {
            parseSearchSuggestions(innerTube.searchSuggestions(query).bodyAsText())
        }

    /** One page of video search. Filters and sorting ride in [params]; paging rides in [continuation]. */
    suspend fun videoSearch(
        query: String,
        params: String? = null,
        continuation: String? = null,
    ): Result<SearchResultsPage> =
        runCatching {
            ensureVisitorData()
            healedWebSearch(operation = "videoSearch", query = query, params = params, continuation = continuation)
                .toSearchResultsPage()
        }

    /**
     * Web search under [SelfHealingManager]: an unreadable body, timeout or rate limit is retried after
     * its remedy, then a different web client is tried, then the last good answer for the same request.
     * Signed in and not incognito, the search is made as the account; otherwise it is anonymous.
     */
    private suspend fun healedWebSearch(
        operation: String,
        query: String,
        params: String?,
        continuation: String?,
    ): JsonObject {
        val asAccount = innerTube.isSignedIn && !AccountIncognito.enabled

        // A YouTube TV sign-in searches through the TV client, so the search belongs to the account. Its answer is
        // used only when it comes in the shapes the search parser reads; otherwise (and for a page token that
        // belongs to the other client) the normal route below serves, and TV search is not tried again this session.
        if (innerTube.isTvAccount && !AccountIncognito.enabled && tvSearchUsable) {
            val viaTv =
                runCatching { innerTube.tvSearch(query.takeIf { continuation == null }, params?.takeIf { continuation == null }, continuation).body<JsonObject>() }
                    .getOrNull()
            if (viaTv != null && viaTv.toString().let { "\"videoRenderer\"" in it || "\"lockupViewModel\"" in it }) return viaTv
            if (continuation == null) tvSearchUsable = false
        }

        suspend fun attempt(client: YouTubeClient): JsonObject =
            innerTube
                .webSearch(
                    client = client,
                    query = query.takeIf { continuation == null },
                    params = params?.takeIf { continuation == null },
                    continuation = continuation,
                    anonymous = !asAccount,
                    includeVisitorData = !asAccount,
                ).body<JsonObject>()

        return SelfHealingManager.runJson(
            operation = operation,
            key = "$operation|${query.trim().lowercase()}|$params|$continuation",
            backups =
                listOf(
                    SelfHealingManager.Backup("mweb") { attempt(MWEB) },
                    SelfHealingManager.Backup("tv") { attempt(TVHTML5) },
                ),
            primary = { attempt(currentWebClient()) },
        )
    }

    @Volatile
    private var tvSearchUsable = true

    private suspend fun ensureVisitorData() {
        if (!visitorData.isNullOrBlank()) return
        visitorData()
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { visitorData = it }
    }

    private suspend fun currentWebClient(): YouTubeClient =
        withContext(Dispatchers.IO) {
            WEB.copy(
                clientVersion =
                    runCatching { YoutubeParsingHelper.getClientVersion() }
                        .getOrDefault(WEB.clientVersion),
            )
        }

    suspend fun searchContinuation(continuation: String): Result<SearchResult> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, continuation = continuation).body<SearchResponse>()
            SearchResult(
                items =
                    response.continuationContents
                        ?.musicShelfContinuation
                        ?.contents
                        ?.mapNotNull {
                            SearchPage.toYTItem(it.musicResponsiveListItemRenderer)
                        }!!,
                continuation =
                    response.continuationContents
                        ?.musicShelfContinuation
                        ?.continuations
                        ?.getContinuation(),
            )
        }

    suspend fun videoAvatarStack(videoId: String): Result<List<String>> =
        runCatching {
            val rawBody = innerTube.next(WEB, videoId, null, null, null, null, null).bodyAsText()
            val root =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }.parseToJsonElement(rawBody)
            root.findVideoOwnerAvatarStackUrls()
        }

    suspend fun videoCollaborators(videoId: String): Result<List<VideoCollaborator>> =
        runCatching {
            val rawBody = innerTube.next(WEB, videoId, null, null, null, null, null).bodyAsText()
            val root =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }.parseToJsonElement(rawBody)
            root.findVideoOwnerCollaborators()
        }

    private fun JsonElement.collectAvatarImageUrls(): List<String> {
        val urls = mutableListOf<String>()

        fun collect(element: JsonElement) {
            when (element) {
                is JsonArray -> {
                    element.forEach(::collect)
                }

                is JsonObject -> {
                    val url = (element["url"] as? JsonPrimitive)?.contentOrNull
                    if (!url.isNullOrBlank() && url.contains("yt3.ggpht.com")) {
                        urls += url
                    }
                    element.values.forEach(::collect)
                }

                else -> {
                    Unit
                }
            }
        }

        collect(this)
        return urls
            .distinctBy { it.avatarImageIdentityKey() }
            .take(5)
    }

    private fun JsonElement.findVideoOwnerAvatarStackUrls(): List<String> =
        when (this) {
            is JsonObject -> {
                val owner = this["videoOwnerRenderer"] as? JsonObject
                val ownerStack =
                    owner
                        ?.get("avatarStack")
                        ?.collectAvatarImageUrls()
                        .orEmpty()
                        .take(2)
                if (ownerStack.size > 1) {
                    ownerStack
                } else {
                    values
                        .firstNotNullOfOrNull { child ->
                            child.findVideoOwnerAvatarStackUrls().takeIf { it.size > 1 }
                        }.orEmpty()
                }
            }

            is JsonArray -> {
                firstNotNullOfOrNull { child ->
                    child.findVideoOwnerAvatarStackUrls().takeIf { it.size > 1 }
                }.orEmpty()
            }

            else -> {
                emptyList()
            }
        }

    private fun JsonElement.findVideoOwnerCollaborators(): List<VideoCollaborator> =
        when (this) {
            is JsonObject -> {
                val owner = this["videoOwnerRenderer"] as? JsonObject
                val collaborators = owner?.get("navigationEndpoint").collaboratorDialog()
                if (collaborators.size > 1) {
                    collaborators
                } else {
                    values
                        .firstNotNullOfOrNull { child ->
                            child.findVideoOwnerCollaborators().takeIf { it.size > 1 }
                        }.orEmpty()
                }
            }

            is JsonArray -> {
                firstNotNullOfOrNull { child ->
                    child.findVideoOwnerCollaborators().takeIf { it.size > 1 }
                }.orEmpty()
            }

            else -> {
                emptyList()
            }
        }

    // ── Channel (native InnerTube) ─────────────────────

    /**
     * A channel's landing page: header, the tabs it actually has, and the tab YouTube returned with
     * it. [idOrHandle] must be a channel id in practice: browse answers 400 to an @handle, which
     * would need a resolve request first.
     */
    suspend fun channel(idOrHandle: String): Result<ChannelPage> =
        runCatching {
            val response = channelBrowseJson(browseId = idOrHandle)
            val about =
                response
                    .channelAboutContinuation()
                    ?.let { token -> runCatching { channelBrowseJson(continuation = token).toChannelAbout() }.getOrNull() }
            response.toChannelPage(response.toChannelHeader(idOrHandle).mergedWith(about))
        }

    private val resolvedChannelIds = ConcurrentHashMap<String, String>()

    /**
     * The channel id behind an @handle, `/c/` or `/user/` link, which browse cannot open directly.
     * Kept for the process: a channel page and its Shorts feed both resolve the same link.
     */
    suspend fun resolveChannelId(url: String): Result<String> =
        runCatching {
            resolvedChannelIds[url]?.let { return@runCatching it }
            val channelId =
                innerTube
                    .resolveUrl(WEB, url)
                    .body<ResolveUrlResponse>()
                    .endpoint
                    ?.browseEndpoint
                    ?.browseId
                    ?.takeIf { it.startsWith("UC") }
                    ?: error("No channel behind $url")
            resolvedChannelIds[url] = channelId
            channelId
        }

    /** [channel] without the About request, for callers that only need the header and the tabs. */
    suspend fun channelLanding(channelId: String): Result<ChannelPage> =
        runCatching {
            val response = channelBrowseJson(browseId = channelId)
            response.toChannelPage(response.toChannelHeader(channelId))
        }

    /**
     * [channelLanding] plus the creator-declared keywords and full description, read from the same
     * response, so channel metadata needs no second request and no NewPipe.
     */
    suspend fun channelLandingWithExtras(
        channelId: String,
    ): Result<Pair<ChannelPage, app.zaid.flow.innertube.pages.channel.ChannelLandingExtras>> =
        runCatching {
            val response = channelBrowseJson(browseId = channelId)
            response.toChannelPage(response.toChannelHeader(channelId)) to response.toChannelLandingExtras()
        }

    private fun JsonElement.toChannelLandingExtras(): app.zaid.flow.innertube.pages.channel.ChannelLandingExtras {
        val renderer =
            ((this as? JsonObject)?.get("metadata") as? JsonObject)
                ?.get("channelMetadataRenderer") as? JsonObject
        val rawKeywords = (renderer?.get("keywords") as? JsonPrimitive)?.contentOrNull.orEmpty()
        // Multi-word keywords arrive quoted: `"video games" gaming "let's play"`.
        val keywords =
            Regex("\"([^\"]+)\"|(\\S+)")
                .findAll(rawKeywords)
                .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        val description = (renderer?.get("description") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return app.zaid.flow.innertube.pages.channel
            .ChannelLandingExtras(keywords = keywords, description = description)
    }

    private fun JsonElement.toChannelPage(header: ChannelHeader): ChannelPage {
        val tabs = toChannelTabs()
        return ChannelPage(
            header = header,
            tabs = tabs,
            initialTab =
                tabs
                    .firstOrNull { it.selected }
                    ?.let { tab -> toChannelTabContent(tab.kind, header.toOwner()) },
        )
    }

    suspend fun channelTab(
        browseId: String,
        params: String,
        owner: FeedItemOwner = FeedItemOwner(id = browseId),
        kind: ChannelTabKind = ChannelTabKind.Unknown,
    ): Result<ChannelTabContent> =
        runCatching {
            channelBrowseJson(browseId = browseId, params = params).toChannelTabContent(kind, owner)
        }

    /**
     * The products on a channel's Store tab, following continuations up to [maxPages]. A page that
     * fails after the first still returns what was gathered, so a flaky second request does not
     * discard a good first one; only a failed first page is an error.
     */
    suspend fun channelStore(
        browseId: String,
        params: String = ChannelTabKind.Store.defaultParams.orEmpty(),
        maxPages: Int = 5,
    ): Result<List<ChannelProduct>> =
        runCatching {
            val first = channelBrowseJson(browseId = browseId, params = params).toChannelStorePage()
            val all = first.products.toMutableList()
            var token = first.continuation
            var pages = 1
            while (token != null && pages < maxPages) {
                val next =
                    runCatching { channelBrowseJson(continuation = token).toChannelStorePage() }.getOrNull() ?: break
                all += next.products
                token = next.continuation.takeIf { next.products.isNotEmpty() && it != token }
                pages++
            }
            all.distinctBy { it.url }
        }

    /** Serves paging and sort switching alike — a sort chip's token is just another continuation. */
    suspend fun channelTabContinuation(
        continuation: String,
        owner: FeedItemOwner,
        kind: ChannelTabKind = ChannelTabKind.Unknown,
    ): Result<ChannelTabContent> =
        runCatching {
            channelBrowseJson(continuation = continuation).toChannelTabContent(kind, owner)
        }

    private suspend fun channelBrowseJson(
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ): JsonElement {
        // Channel rows carry server-rendered times ("Scheduled for 9/16/26, 6:45 PM"); the WEB
        // client's zero offset would print them in UTC.
        val response =
            innerTube.channelBrowse(
                client = currentWebClient().copy(utcOffsetMinutes = localUtcOffsetMinutes()),
                channelId = browseId,
                params = params,
                continuation = continuation,
            )
        return Json.parseToJsonElement(response.bodyAsText())
    }

    private fun localUtcOffsetMinutes(): Int =
        ZoneId
            .systemDefault()
            .rules
            .getOffset(Instant.now())
            .totalSeconds / 60

    /**
     * The landing response carries a one-line description and nothing else about the channel; the
     * About panel is a separate continuation and is where the links, country, join date and totals
     * live.
     */
    private fun ChannelHeader.mergedWith(about: ChannelAbout?): ChannelHeader =
        if (about == null) {
            this
        } else {
            copy(
                description = about.description ?: description,
                subscriberCountText = about.subscriberCountText ?: subscriberCountText,
                videoCountText = about.videoCountText ?: videoCountText,
                joinedDateText = about.joinedDateText,
                viewCountText = about.viewCountText,
                countryText = about.countryText,
                canonicalUrl = about.canonicalUrl ?: canonicalUrl,
                links = about.links,
            )
        }

    private fun ChannelHeader.toOwner() = FeedItemOwner(id = id, name = title, avatarUrl = avatarUrl)

    // ── Channel-scoped video search (YouTube.com WEB API) ─────────────────────

    data class ChannelVideoSearchResult(
        val videos: List<app.zaid.flow.data.model.Video>,
        val continuation: String?,
        val channelVideoCountText: String? = null,
        /** The tab's Latest/Popular/Oldest bar. Empty on a continuation, which carries no header. */
        val sorts: List<ChannelSortOption> = emptyList(),
    )

    /**
     * Search for videos within [channelId] matching [query].
     * Uses the YouTube.com WEB Innertube endpoint with a channel-scoped params filter.
     */
    suspend fun channelSearch(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        query: String,
    ): Result<ChannelVideoSearchResult> =
        runCatching {
            val httpResponse = innerTube.channelSearch(currentWebClient(), channelId, query)
            val rawBody = httpResponse.bodyAsText()
            val lenientJson =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            val response = lenientJson.decodeFromString<app.zaid.flow.innertube.models.response.ChannelSearchResponse>(rawBody)
            parseChannelSearchResponse(response, channelId, channelName, channelThumbnailUrl)
        }

    suspend fun channelSearchContinuation(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        continuation: String,
    ): Result<ChannelVideoSearchResult> =
        runCatching {
            val httpResponse =
                innerTube.channelSearch(
                    currentWebClient(),
                    channelId,
                    query = "",
                    continuation = continuation,
                )
            val rawBody = httpResponse.bodyAsText()
            val lenientJson =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            val response = lenientJson.decodeFromString<app.zaid.flow.innertube.models.response.ChannelSearchResponse>(rawBody)

            val videos = mutableListOf<app.zaid.flow.data.model.Video>()
            var nextContinuation: String? = null

            val appendedItems =
                response.onResponseReceivedActions
                    ?.firstOrNull { it.appendContinuationItemsAction != null }
                    ?.appendContinuationItemsAction
                    ?.continuationItems
                    .orEmpty()
            if (appendedItems.isNotEmpty()) {
                appendedItems.forEach { richItem ->
                    richItem.richItemRenderer
                        ?.content
                        ?.videoRenderer
                        ?.let { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                        ?.let { videos.add(it) }
                    richItem.itemSectionRenderer?.contents?.forEach { sectionItem ->
                        sectionItem.videoRenderer
                            ?.let { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                            ?.let { videos.add(it) }
                    }
                    richItem.continuationItemRenderer
                        ?.continuationEndpoint
                        ?.continuationCommand
                        ?.token
                        ?.let { nextContinuation = it }
                }
            }

            if (videos.isEmpty()) {
                val sectionContents =
                    response.continuationContents
                        ?.sectionListContinuation
                        ?.contents
                        .orEmpty()
                sectionContents
                    .mapNotNull { it.itemSectionRenderer?.contents }
                    .flatten()
                    .mapNotNull { it.videoRenderer }
                    .mapNotNull { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                    .forEach { videos.add(it) }
                if (nextContinuation == null) {
                    nextContinuation = response.continuationContents
                        ?.sectionListContinuation
                        ?.continuations
                        ?.firstOrNull()
                        ?.nextContinuationData
                        ?.continuation
                        ?: sectionContents
                            .mapNotNull { it.continuationItemRenderer }
                            .firstOrNull()
                            ?.continuationEndpoint
                            ?.continuationCommand
                            ?.token
                }
            }

            if (videos.isEmpty()) {
                response.continuationContents?.richGridContinuation?.contents?.forEach { richItem ->
                    richItem.richItemRenderer
                        ?.content
                        ?.videoRenderer
                        ?.let { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                        ?.let { videos.add(it) }
                    richItem.continuationItemRenderer
                        ?.continuationEndpoint
                        ?.continuationCommand
                        ?.token
                        ?.let { nextContinuation = it }
                }
            }

            ChannelVideoSearchResult(videos = videos, continuation = nextContinuation)
        }

    suspend fun channelVideos(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<ChannelVideoSearchResult> = channelVideosPage(channelId, channelName, channelThumbnailUrl, CHANNEL_VIDEOS_PARAMS, false)

    suspend fun channelVideosContinuation(
        continuation: String,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<ChannelVideoSearchResult> = channelVideosPage(channelId, channelName, channelThumbnailUrl, null, false, continuation)

    suspend fun channelLiveStreams(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<ChannelVideoSearchResult> = channelVideosPage(channelId, channelName, channelThumbnailUrl, CHANNEL_LIVE_PARAMS, true)

    suspend fun channelLiveStreamsContinuation(
        continuation: String,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<ChannelVideoSearchResult> = channelVideosPage(channelId, channelName, channelThumbnailUrl, null, true, continuation)

    /**
     * A channel's Shorts tab, including its sort bar. Unlike the Videos and Live tabs this goes
     * through the native client rather than NewPipe, because NewPipe's `ChannelTabInfo` has no
     * notion of the Latest/Popular/Oldest chips and the tab is useless without them (#547).
     */
    suspend fun channelShorts(channelId: String): Result<ChannelShortsPage> = channelShortsPage(channelId = channelId)

    /** Serves both paging and sort switching — a sort chip's token is just another continuation. */
    suspend fun channelShortsContinuation(continuation: String): Result<ChannelShortsPage> = channelShortsPage(continuation = continuation)

    private suspend fun channelShortsPage(
        channelId: String? = null,
        continuation: String? = null,
    ): Result<ChannelShortsPage> =
        runCatching {
            val client = currentWebClient()
            val response =
                innerTube.channelBrowse(
                    client = client,
                    channelId = channelId,
                    params = CHANNEL_SHORTS_PARAMS.takeIf { continuation == null },
                    continuation = continuation,
                )
            Json
                .parseToJsonElement(response.bodyAsText())
                .jsonObject
                .toChannelShortsPage()
        }

    /**
     * An explore destination's landing page, emitted as it is mapped: the tabs first, then one more
     * shelf each time. `FEtrending` and `FEexplore` are dead — see
     * [app.zaid.flow.innertube.pages.explore.ExploreDestination].
     *
     * The whole page arrives in a single browse response, so this cannot paint before the body
     * lands; what it takes off first paint is the mapping of every shelf after the first, which on
     * a destination runs to hundreds of items. That mapping and the parse it walks both stay off
     * the collector's thread.
     */
    fun exploreDestination(
        browseId: String,
        params: String? = null,
    ): Flow<ExploreDestinationPage> =
        flow {
            val response = channelBrowseJson(browseId = browseId, params = params)
            val shell = response.toExploreDestinationShell()
            emit(shell)
            val shelves = mutableListOf<FeedShelf>()
            response.exploreShelves(shell.owner).forEach { shelf ->
                shelves += shelf
                emit(shell.copy(shelves = shelves.toList()))
            }
        }.flowOn(PerformanceDispatcher.parsing)

    /**
     * A country's trending music videos: YouTube Music's charts page for [country] names the chart
     * playlist, and the playlist browse gives its rows. Two requests, both on hosts that serve
     * anonymous clients.
     */
    suspend fun musicVideoChart(country: String): Result<MusicVideoChart> =
        runCatching {
            val playlist =
                getChartsPage(country.uppercase()).getOrThrow().trendingVideoChart()
                    ?: return@runCatching MusicVideoChart(title = "")
            val page = videoPlaylistPage(playlist.id).getOrThrow()
            MusicVideoChart(title = playlist.title, entries = page.videos.ranked())
        }

    suspend fun communityPosts(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<CommunityPostsPage> = communityPostsPage(channelId, channelName, channelThumbnailUrl)

    suspend fun communityPostsContinuation(
        continuation: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<CommunityPostsPage> =
        communityPostsPage(
            channelId = "",
            channelName = channelName,
            channelThumbnailUrl = channelThumbnailUrl,
            continuation = continuation,
        )

    suspend fun communityPostComments(
        postId: String,
        params: String?,
    ): Result<CommunityCommentsPage> =
        runCatching {
            val initial = communityPostCommentsPage(postId = postId, params = params)
            if (initial.comments.isNotEmpty() || initial.continuation == null) {
                initial
            } else {
                val commentsPage = communityPostCommentsPage(continuation = initial.continuation)
                commentsPage.copy(
                    commentCountText = commentsPage.commentCountText ?: initial.commentCountText,
                )
            }
        }

    suspend fun communityPostCommentsContinuation(continuation: String): Result<CommunityCommentsPage> =
        runCatching {
            communityPostCommentsPage(continuation = continuation)
        }

    /** A YouTube playlist's first page when [continuation] is null, otherwise the page it points to. */
    suspend fun videoPlaylistPage(
        playlistId: String,
        continuation: String? = null,
    ): Result<VideoPlaylistPage> =
        runCatching {
            val response =
                innerTube.channelBrowse(
                    client = currentWebClient(),
                    channelId = "VL$playlistId".takeIf { continuation == null },
                    params = null,
                    continuation = continuation,
                )
            Json.parseToJsonElement(response.bodyAsText()).toVideoPlaylistPage()
        }

    private suspend fun communityPostsPage(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        continuation: String? = null,
    ): Result<CommunityPostsPage> =
        runCatching {
            val client = currentWebClient()
            val response =
                innerTube.channelBrowse(
                    client = client,
                    channelId = channelId.takeIf { continuation == null },
                    params = CHANNEL_POSTS_PARAMS.takeIf { continuation == null },
                    continuation = continuation,
                )
            Json.parseToJsonElement(response.bodyAsText()).toCommunityPostsPage(
                fallbackAuthorName = channelName,
                fallbackAuthorAvatarUrl = channelThumbnailUrl,
            )
        }

    /** Where the open community post's create-comment token is kept; one post is open at a time. */
    const val COMMUNITY_COMMENT_KEY = "community-post-open"

    private suspend fun communityPostCommentsPage(
        postId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ): CommunityCommentsPage {
        val client = currentWebClient()
        val response =
            innerTube.postCommentsBrowse(
                client = client,
                postId = postId,
                params = params,
                continuation = continuation,
            )
        val root = Json.parseToJsonElement(response.bodyAsText())
        // Signed-in actions on post comments (vote, reply, write, edit, delete) use tokens carried by this response.
        val actions = app.zaid.flow.account.AccountActions
        if (continuation == null) actions.rememberCommentParams(COMMUNITY_COMMENT_KEY, root)
        actions.rememberReplyParams(root)
        actions.rememberOwnCommentActions(root)
        actions.rememberCommentVoteActions(root)
        return root.toCommunityCommentsPage()
    }

    private suspend fun channelVideosPage(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        params: String?,
        isLive: Boolean,
        continuation: String? = null,
    ): Result<ChannelVideoSearchResult> =
        runCatching {
            val client = currentWebClient()
            val httpResponse =
                innerTube.channelBrowse(
                    client = client,
                    channelId = if (continuation == null) channelId else null,
                    params = params,
                    continuation = continuation,
                )
            val rawBody = httpResponse.bodyAsText()
            val lenientJson =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            val response = lenientJson.decodeFromString<ChannelVideosResponse>(rawBody)
            val sorts = Json.parseToJsonElement(rawBody).channelSortOptions()
            parseChannelVideosResponse(response, channelId, channelName, channelThumbnailUrl, isLive)
                .copy(sorts = sorts)
        }

    private fun parseChannelVideosResponse(
        response: ChannelVideosResponse,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        isLive: Boolean,
    ): ChannelVideoSearchResult {
        val metadata = response.metadata?.channelMetadataRenderer
        val resolvedChannelId =
            metadata?.externalChannelId
                ?: metadata?.externalId
                ?: channelId
        val resolvedChannelName = metadata?.title?.takeIf { it.isNotBlank() } ?: channelName
        val resolvedThumbnail =
            metadata
                ?.avatar
                ?.thumbnails
                ?.maxByOrNull { it.width ?: 0 }
                ?.url
                ?: channelThumbnailUrl

        val richItems = mutableListOf<ChannelVideosResponse.RichItem>()
        response.onResponseReceivedActions
            ?.flatMap {
                it.appendContinuationItemsAction?.continuationItems.orEmpty() +
                    it.reloadContinuationItemsCommand?.continuationItems.orEmpty()
            }?.let { richItems += it }

        response.continuationContents
            ?.richGridContinuation
            ?.contents
            ?.let { richItems += it }

        val tabs =
            response.contents
                ?.twoColumnBrowseResultsRenderer
                ?.tabs
                .orEmpty()
        val selectedTab =
            tabs.firstOrNull { it.tabRenderer?.selected == true }?.tabRenderer
                ?: tabs.firstOrNull { it.tabRenderer?.content?.richGridRenderer != null }?.tabRenderer
                ?: tabs.firstOrNull { it.expandableTabRenderer?.content?.richGridRenderer != null }?.expandableTabRenderer
        selectedTab
            ?.content
            ?.richGridRenderer
            ?.contents
            ?.let { richItems += it }

        val videos = mutableListOf<app.zaid.flow.data.model.Video>()
        var nextContinuation: String? = null
        richItems.forEach { richItem ->
            val content = richItem.richItemRenderer?.content
            content
                ?.lockupViewModel
                ?.let { parseLockupViewModel(it, resolvedChannelId, resolvedChannelName, resolvedThumbnail, isLive) }
                ?.let { videos.add(it) }
            content
                ?.videoRenderer
                ?.let { parseBrowseVideoRenderer(it, resolvedChannelId, resolvedChannelName, resolvedThumbnail, isLive) }
                ?.let { videos.add(it) }
            richItem.continuationItemRenderer
                ?.continuationEndpoint
                ?.continuationCommand
                ?.token
                ?.let { nextContinuation = it }
        }

        return ChannelVideoSearchResult(
            videos = videos.distinctBy { it.id },
            continuation = nextContinuation,
            channelVideoCountText = response.channelVideoCountText(),
        )
    }

    private fun parseLockupViewModel(
        lockup: ChannelVideosResponse.LockupViewModel,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        isLive: Boolean,
    ): app.zaid.flow.data.model.Video? {
        val videoId = lockup.contentId ?: return null
        val metadata = lockup.metadata?.lockupMetadataViewModel
        val title = metadata?.title?.content?.takeIf { it.isNotBlank() } ?: return null
        val thumbnail =
            lockup.contentImage
                ?.thumbnailViewModel
                ?.image
                ?.sources
                ?.maxByOrNull { it.width ?: 0 }
                ?.url
                ?: "https://i.ytimg.com/vi/$videoId/hq720.jpg"
        val durationText =
            lockup.contentImage
                ?.thumbnailViewModel
                ?.overlays
                ?.firstNotNullOfOrNull { overlay ->
                    overlay.thumbnailBottomOverlayViewModel
                        ?.badges
                        ?.firstNotNullOfOrNull { it.thumbnailBadgeViewModel?.text }
                }
        val parts =
            metadata
                ?.metadata
                ?.contentMetadataViewModel
                ?.metadataRows
                ?.firstOrNull()
                ?.metadataParts
                ?.mapNotNull { it.text?.content?.takeIf(String::isNotBlank) }
                .orEmpty()
        val (viewsText, uploadText, uploadTimestamp) = lockupDateAndViews(parts, locale.hl)

        return app.zaid.flow.data.model.Video(
            id = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = thumbnail,
            duration = parseLengthText(durationText),
            viewCount = parseYouTubeViewCount(viewsText),
            uploadDate = uploadText,
            timestamp = uploadTimestamp ?: 0L,
            channelThumbnailUrl = channelThumbnailUrl,
            isLive = isLive || viewsText?.contains("watching", ignoreCase = true) == true,
        )
    }

    private fun parseBrowseVideoRenderer(
        r: ChannelVideosResponse.VideoRenderer,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        isLive: Boolean,
    ): app.zaid.flow.data.model.Video? {
        val videoId = r.videoId ?: return null
        val title = r.title?.textValue()?.takeIf { it.isNotBlank() } ?: return null
        val thumbnail =
            r.thumbnail
                ?.thumbnails
                ?.maxByOrNull { it.width ?: 0 }
                ?.url
                ?: "https://i.ytimg.com/vi/$videoId/hq720.jpg"
        val uploadText = r.publishedTimeText?.textValue().orEmpty()
        val viewsText = r.viewCountText?.textValue()
        val avatarUrls = r.channelAvatarUrls(channelThumbnailUrl)
        return app.zaid.flow.data.model.Video(
            id = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = thumbnail,
            duration = parseLengthText(r.lengthText?.textValue()),
            viewCount = parseYouTubeViewCount(viewsText),
            uploadDate = uploadText,
            timestamp = RelativeUploadDateParser.parse(uploadText, locale.hl) ?: 0L,
            channelThumbnailUrl = avatarUrls.firstOrNull().orEmpty(),
            channelThumbnailUrls = avatarUrls,
            isLive = isLive || viewsText?.contains("watching", ignoreCase = true) == true,
        )
    }

    private fun ChannelVideosResponse.SimpleText.textValue(): String? = simpleText ?: runs?.joinToString("") { it.text.orEmpty() }

    private fun ChannelVideosResponse.VideoRenderer.channelAvatarUrls(fallback: String): List<String> {
        val supported = channelThumbnailSupportedRenderers
        val stackAvatars =
            listOfNotNull(
                avatarStackViewModel,
                supported?.avatarStackViewModel,
                supported?.channelThumbnailWithLinkRenderer?.avatarStack?.avatarStackViewModel,
            ).flatMap { stack ->
                stack.avatars.orEmpty().mapNotNull { avatar ->
                    avatar.avatarViewModel
                        ?.image
                        ?.sources
                        ?.maxByOrNull { maxOf(it.width ?: 0, it.height ?: 0) }
                        ?.url
                }
            }
        val linkedAvatar =
            supported
                ?.channelThumbnailWithLinkRenderer
                ?.thumbnail
                ?.thumbnails
                ?.maxByOrNull { maxOf(it.width ?: 0, it.height ?: 0) }
                ?.url

        return (stackAvatars + linkedAvatar + fallback)
            .filter { !it.isNullOrBlank() }
            .distinctBy { it.avatarImageIdentityKey() }
            .take(2)
            .filterNotNull()
    }

    private fun parseChannelSearchResponse(
        response: app.zaid.flow.innertube.models.response.ChannelSearchResponse,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): ChannelVideoSearchResult {
        val tabs =
            response.contents
                ?.twoColumnBrowseResultsRenderer
                ?.tabs
                .orEmpty()
        Log.d("ChannelSearch", "tabs=${tabs.size}")
        tabs.forEachIndexed { i, tab ->
            val url =
                tab.tabRenderer
                    ?.endpoint
                    ?.commandMetadata
                    ?.webCommandMetadata
                    ?.url
            Log.d(
                "ChannelSearch",
                "tab[$i]: url=$url, selected=${tab.tabRenderer?.selected}, " +
                    "hasSection=${tab.tabRenderer?.content?.sectionListRenderer != null}, " +
                    "hasRichGrid=${tab.tabRenderer?.content?.richGridRenderer != null}, " +
                    "isExpandable=${tab.expandableTabRenderer != null}",
            )
        }

        val tabContent =
            tabs
                .firstOrNull {
                    it.tabRenderer?.selected == true && it.tabRenderer.endpoint
                        ?.commandMetadata
                        ?.webCommandMetadata
                        ?.url
                        ?.contains("/search") == true
                }?.tabRenderer
                ?.content
                ?: tabs.firstOrNull { it.expandableTabRenderer?.content != null }?.expandableTabRenderer?.content
                ?: tabs.firstOrNull { it.tabRenderer?.selected == true }?.tabRenderer?.content

        Log.d(
            "ChannelSearch",
            "tabContent=${tabContent != null}, " +
                "hasSection=${tabContent?.sectionListRenderer != null}, " +
                "hasRichGrid=${tabContent?.richGridRenderer != null}",
        )

        val videos = mutableListOf<app.zaid.flow.data.model.Video>()
        var continuation: String? = null

        tabContent?.richGridRenderer?.contents?.forEach { richItem ->
            richItem.richItemRenderer
                ?.content
                ?.videoRenderer
                ?.let { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                ?.let { videos.add(it) }
            richItem.continuationItemRenderer
                ?.continuationEndpoint
                ?.continuationCommand
                ?.token
                ?.let { continuation = it }
        }

        if (videos.isEmpty()) {
            val sectionContents = tabContent?.sectionListRenderer?.contents.orEmpty()
            sectionContents
                .mapNotNull { it.itemSectionRenderer?.contents }
                .flatten()
                .mapNotNull { it.videoRenderer }
                .mapNotNull { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                .forEach { videos.add(it) }
            if (continuation == null) {
                continuation =
                    sectionContents
                        .mapNotNull { it.continuationItemRenderer }
                        .firstOrNull()
                        ?.continuationEndpoint
                        ?.continuationCommand
                        ?.token
            }
        }

        Log.d("ChannelSearch", "videos=${videos.size}, hasContinuation=${continuation != null}")
        return ChannelVideoSearchResult(videos = videos, continuation = continuation)
    }

    private fun parseVideoRenderer(
        r: app.zaid.flow.innertube.models.response.ChannelSearchResponse.VideoRenderer,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): app.zaid.flow.data.model.Video? {
        val videoId = r.videoId ?: return null
        val title =
            r.title
                ?.runs
                ?.joinToString("") { it.text ?: "" }
                ?.takeIf { it.isNotBlank() } ?: return null
        val thumbnail =
            r.thumbnail
                ?.thumbnails
                ?.maxByOrNull { it.width ?: 0 }
                ?.url
                ?: "https://i.ytimg.com/vi/$videoId/hq720.jpg"
        val duration = parseLengthText(r.lengthText?.simpleText)
        val viewCount = parseYouTubeViewCount(r.viewCountText?.simpleText)
        val avatarUrls = r.channelAvatarUrls(channelThumbnailUrl)
        return app.zaid.flow.data.model.Video(
            id = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = thumbnail,
            duration = duration,
            viewCount = viewCount,
            uploadDate = r.publishedTimeText?.simpleText ?: "",
            channelThumbnailUrl = avatarUrls.firstOrNull().orEmpty(),
            channelThumbnailUrls = avatarUrls,
        )
    }

    private fun app.zaid.flow.innertube.models.response.ChannelSearchResponse.VideoRenderer.channelAvatarUrls(
        fallback: String,
    ): List<String> {
        val supported = channelThumbnailSupportedRenderers
        val stackAvatars =
            listOfNotNull(
                avatarStackViewModel,
                supported?.avatarStackViewModel,
                supported?.channelThumbnailWithLinkRenderer?.avatarStack?.avatarStackViewModel,
            ).flatMap { stack ->
                stack.avatars.orEmpty().mapNotNull { avatar ->
                    avatar.avatarViewModel
                        ?.image
                        ?.sources
                        ?.maxByOrNull { maxOf(it.width ?: 0, it.height ?: 0) }
                        ?.url
                }
            }
        val linkedAvatar =
            supported
                ?.channelThumbnailWithLinkRenderer
                ?.thumbnail
                ?.thumbnails
                ?.maxByOrNull { maxOf(it.width ?: 0, it.height ?: 0) }
                ?.url

        return (stackAvatars + linkedAvatar + fallback)
            .filter { !it.isNullOrBlank() }
            .distinctBy { it.avatarImageIdentityKey() }
            .take(2)
            .filterNotNull()
    }

    private fun parseLengthText(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val parts = text.split(":").map { it.trim().toIntOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }

    suspend fun album(
        browseId: String,
        withSongs: Boolean = true,
    ): Result<AlbumPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()
            if (browseId.contains("FEmusic_library_privately_owned_release_detail")) {
                val playlistId =
                    response.header
                        ?.musicDetailHeaderRenderer
                        ?.menu
                        ?.menuRenderer
                        ?.topLevelButtons
                        ?.firstOrNull()
                        ?.buttonRenderer
                        ?.navigationEndpoint
                        ?.watchPlaylistEndpoint
                        ?.playlistId!!
                val albumItem =
                    AlbumItem(
                        browseId = browseId,
                        playlistId = playlistId,
                        title =
                            response.header.musicDetailHeaderRenderer.title.runs
                                ?.firstOrNull()
                                ?.text!!,
                        artists =
                            response.header.musicDetailHeaderRenderer.subtitle.runs?.filter { it.navigationEndpoint != null }?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            },
                        year =
                            response.header.musicDetailHeaderRenderer.subtitle.runs
                                ?.lastOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail =
                            response.header.musicDetailHeaderRenderer.thumbnail.croppedSquareThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()!!
                                .url,
                        explicit = false,
                    )
                return@runCatching AlbumPage(
                    album = albumItem,
                    songs =
                        response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.musicShelfRenderer
                            ?.contents
                            ?.getItems()
                            ?.mapNotNull {
                                AlbumPage.getSong(it, albumItem)
                            }!!
                            .toMutableList(),
                    otherVersions = emptyList(),
                )
            } else {
                val header =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicResponsiveHeaderRenderer
                val playlistId =
                    response.microformat
                        ?.microformatDataRenderer
                        ?.urlCanonical
                        ?.substringAfterLast('=')!!
                val albumItem =
                    AlbumItem(
                        browseId = browseId,
                        playlistId = playlistId,
                        title =
                            response.contents
                                ?.twoColumnBrowseResultsRenderer
                                ?.tabs
                                ?.firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text!!,
                        artists =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.straplineTextOne
                                ?.runs
                                ?.oddElements()
                                ?.map {
                                    Artist(
                                        name = it.text,
                                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                    )
                                }!!,
                        year =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.subtitle
                                ?.runs
                                ?.lastOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.thumbnail
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url!!,
                        explicit = AlbumPage.isExplicit(header),
                    )
                return@runCatching AlbumPage(
                    album = albumItem,
                    songs =
                        if (withSongs) {
                            albumSongs(
                                playlistId,
                                albumItem,
                            ).getOrThrow()
                        } else {
                            emptyList()
                        },
                    otherVersions = AlbumPage.otherVersions(response, albumItem),
                    durationText = AlbumPage.durationText(header),
                )
            }
        }

    suspend fun albumSongs(
        playlistId: String,
        album: AlbumItem? = null,
    ): Result<List<SongItem>> =
        runCatching {
            var response = innerTube.browse(WEB_REMIX, "VL$playlistId").body<BrowseResponse>()

            val twoColumnRenderer = response.contents?.twoColumnBrowseResultsRenderer
            val secondaryContents = twoColumnRenderer?.secondaryContents
            val sectionList = secondaryContents?.sectionListRenderer
            val firstContent = sectionList?.contents?.firstOrNull()
            val playlistShelf = firstContent?.musicPlaylistShelfRenderer

            val songs =
                playlistShelf
                    ?.contents
                    ?.getItems()
                    ?.mapNotNull {
                        AlbumPage.getSong(it, album)
                    }!!
                    .toMutableList()
            var continuation = playlistShelf?.contents?.getContinuation()
            val seenContinuations = mutableSetOf<String>()
            var requestCount = 0
            val maxRequests = 50 // Prevent excessive API calls

            while (continuation != null && requestCount < maxRequests) {
                // Prevent infinite loops by tracking seen continuations
                if (continuation in seenContinuations) {
                    break
                }
                seenContinuations.add(continuation)
                requestCount++

                response =
                    innerTube
                        .browse(
                            client = WEB_REMIX,
                            continuation = continuation,
                        ).body<BrowseResponse>()
                songs +=
                    response.onResponseReceivedActions
                        ?.firstOrNull()
                        ?.appendContinuationItemsAction
                        ?.continuationItems
                        ?.getItems()
                        ?.mapNotNull {
                            AlbumPage.getSong(it, album)
                        }.orEmpty()
                continuation =
                    response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.continuations
                        ?.getContinuation()
            }
            songs
        }

    suspend fun artist(browseId: String): Result<ArtistPage> =
        runCatching {
            ArtistPage.fromBrowseResponse(browseId, innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>())
        }

    suspend fun artistItems(endpoint: BrowseEndpoint): Result<ArtistItemsPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            val sectionContent =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()

            val gridRenderer = sectionContent?.gridRenderer
            val musicCarouselShelfRenderer = sectionContent?.musicCarouselShelfRenderer
            val musicPlaylistShelfRenderer = sectionContent?.musicPlaylistShelfRenderer
            val musicShelfRenderer = sectionContent?.musicShelfRenderer

            when {
                gridRenderer != null -> {
                    ArtistItemsPage(
                        title =
                            gridRenderer.header
                                ?.gridHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                .orEmpty(),
                        items =
                            gridRenderer.items.mapNotNull {
                                it.musicTwoRowItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                                }
                            },
                        continuation = gridRenderer.continuations?.getContinuation(),
                    )
                }

                musicCarouselShelfRenderer != null -> {
                    ArtistItemsPage(
                        title =
                            musicCarouselShelfRenderer.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                .orEmpty(),
                        items =
                            musicCarouselShelfRenderer.contents.mapNotNull { content ->
                                content.musicTwoRowItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                                } ?: content.musicResponsiveListItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicResponsiveListItemRenderer(renderer)
                                }
                            },
                        continuation = null,
                    )
                }

                musicShelfRenderer != null -> {
                    ArtistItemsPage(
                        title =
                            musicShelfRenderer.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: response.header
                                    ?.musicHeaderRenderer
                                    ?.title
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text
                                ?: "",
                        items =
                            musicShelfRenderer.contents?.getItems()?.mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            } ?: emptyList(),
                        continuation = musicShelfRenderer.continuations?.getContinuation(),
                    )
                }

                else -> {
                    ArtistItemsPage(
                        title =
                            response.header
                                ?.musicHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: "",
                        items =
                            musicPlaylistShelfRenderer?.contents?.getItems()?.mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            } ?: emptyList(),
                        continuation = musicPlaylistShelfRenderer?.contents?.getContinuation(),
                    )
                }
            }
        }

    suspend fun artistItemsContinuation(continuation: String): Result<ArtistItemsContinuationPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()

            when {
                response.continuationContents?.gridContinuation != null -> {
                    val gridContinuation = response.continuationContents.gridContinuation
                    ArtistItemsContinuationPage(
                        items =
                            gridContinuation.items.mapNotNull {
                                it.musicTwoRowItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                                }
                            },
                        continuation = gridContinuation.continuations?.getContinuation(),
                    )
                }

                response.continuationContents?.musicPlaylistShelfContinuation != null -> {
                    val musicPlaylistShelfContinuation = response.continuationContents.musicPlaylistShelfContinuation
                    ArtistItemsContinuationPage(
                        items =
                            musicPlaylistShelfContinuation.contents.getItems().mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            },
                        continuation = musicPlaylistShelfContinuation.continuations?.getContinuation(),
                    )
                }

                response.continuationContents?.musicShelfContinuation != null -> {
                    val musicShelfContinuation = response.continuationContents!!.musicShelfContinuation!!
                    ArtistItemsContinuationPage(
                        items =
                            musicShelfContinuation.contents?.getItems()?.mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            } ?: emptyList(),
                        continuation = musicShelfContinuation.continuations?.getContinuation(),
                    )
                }

                else -> {
                    val continuationItems =
                        response.onResponseReceivedActions
                            ?.firstOrNull()
                            ?.appendContinuationItemsAction
                            ?.continuationItems
                    ArtistItemsContinuationPage(
                        items =
                            continuationItems?.getItems()?.mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            } ?: emptyList(),
                        continuation = continuationItems?.getContinuation(),
                    )
                }
            }
        }

    suspend fun playlist(playlistId: String): Result<PlaylistPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VL$playlistId",
                        setLogin = true,
                    ).body<BrowseResponse>()
            val base =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
            val header =
                base?.musicResponsiveHeaderRenderer
                    ?: base?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer

            val editable = base?.musicEditablePlaylistDetailHeaderRenderer != null

            PlaylistPage(
                playlist =
                    PlaylistItem(
                        id = playlistId,
                        title =
                            header
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text!!,
                        author =
                            header.straplineTextOne?.runs?.firstOrNull()?.let {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            },
                        songCountText =
                            header.secondSubtitle
                                ?.runs
                                ?.firstOrNull()
                                ?.text,
                        thumbnail =
                            header.thumbnail
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url!!,
                        playEndpoint = null,
                        shuffleEndpoint =
                            header.buttons
                                .lastOrNull()
                                ?.menuRenderer
                                ?.items
                                ?.firstOrNull()
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint!!,
                        radioEndpoint =
                            header.buttons
                                .getOrNull(2)
                                ?.menuRenderer
                                ?.items
                                ?.find {
                                    it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                                }?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint,
                        isEditable = editable,
                    ),
                songs =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicPlaylistShelfRenderer
                        ?.contents
                        ?.getItems()
                        ?.mapNotNull {
                            PlaylistPage.fromMusicResponsiveListItemRenderer(it)
                        } ?: emptyList(),
                songsContinuation =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicPlaylistShelfRenderer
                        ?.contents
                        ?.getContinuation(),
                continuation =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.continuations
                        ?.getContinuation(),
                trackCount = PlaylistPage.trackCountFrom(header.secondSubtitle),
            )
        }

    suspend fun playlistContinuation(continuation: String): Result<PlaylistContinuationPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        browseId = "",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val mainContents =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.flatMap { it.musicPlaylistShelfRenderer?.contents.orEmpty() }
                    ?: emptyList()

            val appendedContents =
                response.onResponseReceivedActions
                    ?.firstOrNull()
                    ?.appendContinuationItemsAction
                    ?.continuationItems
                    .orEmpty()

            val allContents = mainContents + appendedContents

            val songs =
                allContents
                    .mapNotNull { it.musicResponsiveListItemRenderer }
                    .mapNotNull { PlaylistPage.fromMusicResponsiveListItemRenderer(it) }

            val nextContinuation =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.continuations
                    ?.getContinuation()
                    ?: response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.continuations
                        ?.getContinuation()
                    ?: response.continuationContents
                        ?.musicShelfContinuation
                        ?.continuations
                        ?.getContinuation()
                    ?: response.onResponseReceivedActions
                        ?.firstOrNull()
                        ?.appendContinuationItemsAction
                        ?.continuationItems
                        ?.getContinuation()

            PlaylistContinuationPage(
                songs = songs,
                continuation = nextContinuation,
            )
        }

    suspend fun home(
        continuation: String? = null,
        params: String? = null,
    ): Result<HomePage> =
        runCatching {
            if (continuation != null) {
                return@runCatching homeContinuation(continuation).getOrThrow()
            }

            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_home", params = params).body<BrowseResponse>()
            val continuation =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.continuations
                    ?.getContinuation()
            val sectionListRender =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
            val sections =
                sectionListRender
                    ?.contents!!
                    .mapNotNull { it.musicCarouselShelfRenderer }
                    .mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it)
                    }.toMutableList()
            val chips =
                sectionListRender.header
                    ?.chipCloudRenderer
                    ?.chips
                    ?.mapNotNull { HomePage.Chip.fromChipCloudChipRenderer(it) }
            HomePage(chips, sections, continuation)
        }

    private suspend fun homeContinuation(continuation: String): Result<HomePage> =
        runCatching {
            val response =
                innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()
            val continuation =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.continuations
                    ?.getContinuation()
            HomePage(
                null,
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.mapNotNull { it.musicCarouselShelfRenderer }
                    ?.mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it)
                    }.orEmpty(),
                continuation,
            )
        }

    suspend fun explore(): Result<ExplorePage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_explore").body<BrowseResponse>()
            ExplorePage(
                newReleaseAlbums =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.find {
                            it.musicCarouselShelfRenderer
                                ?.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.moreContentButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId == "FEmusic_new_releases_albums"
                        }?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                        .orEmpty(),
                moodAndGenres =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.find {
                            it.musicCarouselShelfRenderer
                                ?.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.moreContentButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId == "FEmusic_moods_and_genres"
                        }?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull { it.musicNavigationButtonRenderer }
                        ?.mapNotNull(MoodAndGenres.Companion::fromMusicNavigationButtonRenderer)
                        .orEmpty(),
            )
        }

    suspend fun newReleaseAlbums(): Result<List<AlbumItem>> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_new_releases_albums").body<BrowseResponse>()
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.gridRenderer
                ?.items
                ?.mapNotNull { it.musicTwoRowItemRenderer }
                ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                .orEmpty()
        }

    suspend fun moodAndGenres(): Result<List<MoodAndGenres>> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_moods_and_genres").body<BrowseResponse>()
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents!!
                .mapNotNull(MoodAndGenres.Companion::fromSectionListRendererContent)
        }

    suspend fun browse(
        browseId: String,
        params: String?,
    ): Result<BrowseResult> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = browseId, params = params).body<BrowseResponse>()
            BrowseResult(
                title =
                    response.header
                        ?.musicHeaderRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                items =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull { content ->
                            when {
                                content.gridRenderer != null -> {
                                    BrowseResult.Item(
                                        title =
                                            content.gridRenderer.header
                                                ?.gridHeaderRenderer
                                                ?.title
                                                ?.runs
                                                ?.firstOrNull()
                                                ?.text,
                                        items =
                                            content.gridRenderer.items
                                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                                .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                                    )
                                }

                                content.musicCarouselShelfRenderer != null -> {
                                    BrowseResult.Item(
                                        title =
                                            content.musicCarouselShelfRenderer.header
                                                ?.musicCarouselShelfBasicHeaderRenderer
                                                ?.title
                                                ?.runs
                                                ?.firstOrNull()
                                                ?.text,
                                        items =
                                            content.musicCarouselShelfRenderer.contents
                                                .mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
                                                .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                                    )
                                }

                                else -> {
                                    null
                                }
                            }
                        }.orEmpty(),
            )
        }

    suspend fun library(
        browseId: String,
        tabIndex: Int = 0,
    ) = runCatching {
        val response =
            innerTube
                .browse(
                    client = WEB_REMIX,
                    browseId = browseId,
                    setLogin = true,
                ).body<BrowseResponse>()

        val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs

        val contents =
            if (tabs != null && tabs.size >= tabIndex) {
                tabs[tabIndex]
                    .tabRenderer.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
            } else {
                null
            }

        when {
            contents?.gridRenderer != null -> {
                LibraryPage(
                    items =
                        contents.gridRenderer.items
                            .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
                    continuation = contents.gridRenderer.continuations?.getContinuation(),
                )
            }

            else -> { // contents?.musicShelfRenderer != null
                LibraryPage(
                    items =
                        contents
                            ?.musicShelfRenderer
                            ?.contents!!
                            .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) },
                    continuation = contents.musicShelfRenderer.continuations?.getContinuation(),
                )
            }
        }
    }

    suspend fun libraryContinuation(continuation: String) =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val contents = response.continuationContents

            when {
                contents?.gridContinuation != null -> {
                    LibraryContinuationPage(
                        items =
                            contents.gridContinuation.items
                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
                        continuation = contents.gridContinuation.continuations?.getContinuation(),
                    )
                }

                else -> { // contents?.musicShelfContinuation != null
                    LibraryContinuationPage(
                        items =
                            contents
                                ?.musicShelfContinuation
                                ?.contents!!
                                .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                                .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) },
                        continuation = contents.musicShelfContinuation.continuations?.getContinuation(),
                    )
                }
            }
        }

    suspend fun libraryRecentActivity(): Result<LibraryPage> =
        runCatching {
            val continuation = LibraryFilter.FILTER_RECENT_ACTIVITY.value

            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val items =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.firstOrNull()
                    ?.gridRenderer
                    ?.items!!
                    .mapNotNull {
                        it.musicTwoRowItemRenderer?.let { renderer ->
                            LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                        }
                    }.toMutableList()

            items.forEachIndexed { index, item ->
                if (item is ArtistItem) {
                    items[index] = artist(item.id).getOrNull()?.artist!!.copy(thumbnail = item.thumbnail)
                }
            }

            LibraryPage(
                items = items,
                continuation = null,
            )
        }

    suspend fun getChartsPage(
        country: String? = null,
        continuation: String? = null,
    ): Result<ChartsPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_charts",
                        params = "ggMGCgQIgAQ%3D",
                        continuation = continuation,
                        formData = country?.let { BrowseBody.FormData(selectedValues = listOf(it)) },
                    ).body<BrowseResponse>()
            ChartsPage.fromBrowseResponse(response, country)
        }

    suspend fun musicHistory() =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_history",
                        setLogin = true,
                    ).body<BrowseResponse>()

            HistoryPage(
                sections =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull {
                            it.musicShelfRenderer?.let { musicShelfRenderer ->
                                HistoryPage.fromMusicShelfRenderer(musicShelfRenderer)
                            }
                        },
            )
        }

    suspend fun likeVideo(
        videoId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likeVideo(WEB_REMIX, videoId)
        } else {
            innerTube.unlikeVideo(WEB_REMIX, videoId)
        }
    }

    suspend fun dislikeVideo(videoId: String) = rateVideo("like/dislike", videoId)

    /** Sends a rating as the signed-in account; a non-2xx answer is a failure, not a silent success. */
    suspend fun rateVideo(
        path: String,
        videoId: String,
    ): Result<Unit> =
        runCatching {
            val response = innerTube.accountRate(path, videoId)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        }

    /**
     * The stats-ping URLs a signed-in player is given for [videoId]. Clients are tried in turn because
     * a given client can be refused for a given video; the first response that carries a playback URL wins.
     */
    suspend fun playbackTrackingFor(
        videoId: String,
        signatureTimestamp: Int?,
    ): PlayerResponse.PlaybackTracking? {
        // The site's own player call as the account first, then the app's other clients.
        runCatching { innerTube.accountPlayer(videoId, signatureTimestamp).body<PlayerResponse>() }
            .onSuccess { response ->
                val tracking = response.playbackTracking
                if (tracking?.videostatsPlaybackUrl?.baseUrl != null) return tracking
                Log.w(
                    "PlaybackTracking",
                    "Account player for $videoId had no tracking URLs (playability: ${response.playabilityStatus.status} ${response.playabilityStatus.reason.orEmpty()})",
                )
            }.onFailure { Log.w("PlaybackTracking", "Account player call failed for $videoId: ${it.message}") }
        for (client in listOf(WEB_REMIX, YouTubeClient.MOBILE, YouTubeClient.ANDROID)) {
            val tracking =
                player(videoId, client = client, signatureTimestamp = signatureTimestamp)
                    .getOrNull()
                    ?.playbackTracking
            if (tracking?.videostatsPlaybackUrl?.baseUrl != null) return tracking
        }
        return null
    }

    suspend fun pingPlaybackStats(
        url: String,
        cpn: String,
        extra: Map<String, String> = emptyMap(),
    ): Result<Unit> =
        runCatching {
            val response = innerTube.pingPlaybackStats(url, cpn, extra)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        }

    suspend fun likePlaylist(
        playlistId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likePlaylist(WEB_REMIX, playlistId)
        } else {
            innerTube.unlikePlaylist(WEB_REMIX, playlistId)
        }
    }

    suspend fun subscribeChannel(
        channelId: String,
        subscribe: Boolean,
    ) = runCatching {
        if (subscribe) {
            innerTube.subscribeChannel(WEB_REMIX, channelId)
        } else {
            innerTube.unsubscribeChannel(WEB_REMIX, channelId)
        }
    }

    suspend fun getChannelId(browseId: String): String {
        artist(browseId).onSuccess {
            return it.artist.channelId ?: ""
        }
        return ""
    }

    suspend fun addToPlaylist(
        playlistId: String,
        videoId: String,
    ) = runCatching {
        innerTube.addToPlaylist(WEB_REMIX, playlistId, videoId)
    }

    suspend fun addPlaylistToPlaylist(
        playlistId: String,
        addPlaylistId: String,
    ) = runCatching {
        innerTube.addPlaylistToPlaylist(WEB_REMIX, playlistId, addPlaylistId)
    }

    suspend fun removeFromPlaylist(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = runCatching {
        innerTube.removeFromPlaylist(WEB_REMIX, playlistId, videoId, setVideoId)
    }

    suspend fun moveSongPlaylist(
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = runCatching {
        innerTube.moveSongPlaylist(WEB_REMIX, playlistId, setVideoId, successorSetVideoId)
    }

    fun createPlaylist(title: String) =
        runBlocking {
            innerTube.createPlaylist(WEB_REMIX, title).body<CreatePlaylistResponse>().playlistId
        }

    suspend fun renamePlaylist(
        playlistId: String,
        name: String,
    ) = runCatching {
        innerTube.renamePlaylist(WEB_REMIX, playlistId, name)
    }

    suspend fun uploadCustomThumbnailLink(
        playlistId: String,
        image: ByteArray,
    ) = runCatching {
        val uploadUrl = innerTube.getUploadCustomThumbnailLink(WEB_REMIX, image.size).headers["x-guploader-uploadid"]
        val blobReq =
            innerTube.uploadCustomThumbnail(
                WEB_REMIX,
                uploadUrl!!,
                image,
            )
        val blobId = Json.decodeFromString<ImageUploadResponse>(blobReq.bodyAsText()).encryptedBlobId
        innerTube
            .setThumbnailPlaylist(WEB_REMIX, playlistId, blobId)
            .body<EditPlaylistResponse>()
            .newHeader
            ?.musicEditablePlaylistDetailHeaderRenderer
            ?.header
            ?.musicResponsiveHeaderRenderer
            ?.thumbnail
            ?.musicThumbnailRenderer
            ?.getThumbnailUrl()
    }

    suspend fun removeThumbnailPlaylist(playlistId: String) =
        runCatching {
            innerTube
                .removeThumbnailPlaylist(WEB_REMIX, playlistId)
                .body<EditPlaylistResponse>()
                .newHeader
                ?.musicEditablePlaylistDetailHeaderRenderer
                ?.header
                ?.musicResponsiveHeaderRenderer
                ?.thumbnail
                ?.musicThumbnailRenderer
                ?.getThumbnailUrl()
        }

    suspend fun deletePlaylist(playlistId: String) =
        runCatching {
            innerTube.deletePlaylist(WEB_REMIX, playlistId)
        }

    /** See [InnerTube.warmPlayerConnection]. */
    suspend fun warmPlayerConnection() = innerTube.warmPlayerConnection()

    suspend fun player(
        videoId: String,
        playlistId: String? = null,
        client: YouTubeClient,
        signatureTimestamp: Int? = null,
        poToken: String? = null,
        localeOverride: YouTubeLocale? = null,
        apiUrl: String? = null,
    ): Result<PlayerResponse> =
        runCatching {
            innerTube.player(client, videoId, playlistId, signatureTimestamp, poToken, localeOverride, apiUrl).body<PlayerResponse>()
        }

    suspend fun playerWeb(
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        visitorData: String?,
        locale: YouTubeLocale,
        cpn: String?,
        reloadToken: String? = null,
        client: YouTubeClient = YouTubeClient.WEB,
        login: Boolean = false,
    ): Result<PlayerResponse> =
        runCatching {
            innerTube
                .playerWeb(videoId, signatureTimestamp, poToken, visitorData, locale, cpn, reloadToken, client, login)
                .body<PlayerResponse>()
        }

    /**
     * The creator-declared category for [videoId].
     *
     * MWEB carries a `microformat` where the direct-URL clients the player path uses do not, and it
     * fills it without a `signatureTimestamp` — the response is UNPLAYABLE and carries no streams,
     * which is the point: it costs ~8 KB and no base.js fetch, against ~215 KB for WEB.
     */
    suspend fun videoCategory(videoId: String): Result<String?> =
        runCatching {
            innerTube
                .player(
                    YouTubeClient.MWEB,
                    videoId,
                    playlistId = null,
                    signatureTimestamp = null,
                    localeOverride = YouTubeLocale.EXTRACTION,
                    apiUrl = YouTubeClient.API_URL_YOUTUBE,
                ).body<PlayerResponse>()
                .microformat
                ?.playerMicroformatRenderer
                ?.category
                ?.takeIf { it.isNotBlank() }
        }

    /**
     * The raw web watch response for [videoId].
     *
     * The comment section, the attributed description and the related lane all read this one
     * response, so callers go through the cache that wraps it rather than requesting it each.
     */
    /**
     * The comment box's token for [videoId] as the signed-in TV account sees it: from the TV /next answer, or from
     * the comments page that answer points to. Remembered in [app.zaid.flow.account.AccountActions]; false when the
     * TV client offered no comment box for this video.
     */
    suspend fun tvRememberCommentParams(videoId: String): Boolean {
        val actions = app.zaid.flow.account.AccountActions
        val next = Json.parseToJsonElement(innerTube.tvNext(videoId = videoId).bodyAsText())
        actions.rememberCommentParams(videoId, next)
        if (actions.hasCommentParams(videoId)) return true
        val continuation = next.videoCommentsContinuation() ?: return false
        val page = Json.parseToJsonElement(innerTube.tvNext(continuation = continuation).bodyAsText())
        actions.rememberCommentParams(videoId, page)
        actions.rememberReplyParams(page)
        return actions.hasCommentParams(videoId)
    }

    suspend fun watchNextJson(videoId: String): Result<JsonElement> =
        runCatching {
            Json.parseToJsonElement(innerTube.nextWatch(videoId = videoId).bodyAsText())
        }

    /**
     * Debug builds only: the unparsed WEB `/player` body for [videoId], so in-player product overlays
     * (which the typed [PlayerResponse] model drops) can be inspected. Never called in release builds.
     */
    suspend fun playerRawForDebug(videoId: String): Result<String> =
        runCatching {
            innerTube.player(YouTubeClient.WEB, videoId, null, null).bodyAsText()
        }

    /** The description, its typed spans and the figures beside it, from an already-fetched watch response. */
    fun videoDescription(
        watchNext: JsonElement,
        videoId: String,
    ): VideoDescriptionPage = watchNext.toVideoDescriptionPage(videoId)

    /** The continuation that opens [videoId]'s comment section, from an already-fetched watch response. */
    fun commentsContinuation(watchNext: JsonElement): String? = watchNext.videoCommentsContinuation()

    /** The first page of the "up next" column, from an already-fetched watch response. */
    fun relatedFeedFirstPage(watchNext: JsonElement): app.zaid.flow.innertube.pages.RelatedFeedPage =
        watchNext.toRelatedFeedFirstPage(locale.hl)

    /** A later page of the "up next" column, or the list a chip selects. */
    suspend fun relatedFeedPage(continuation: String): Result<app.zaid.flow.innertube.pages.RelatedFeedPage> =
        runCatching {
            Json
                .parseToJsonElement(innerTube.nextWatch(continuation = continuation).bodyAsText())
                .toRelatedFeedPage(locale.hl)
        }

    suspend fun comments(
        continuation: String,
        ownVideoId: String?,
    ): Result<VideoCommentsPage> =
        runCatching {
            val root = Json.parseToJsonElement(innerTube.nextWatch(continuation = continuation).bodyAsText())
            if (ownVideoId != null) {
                app.zaid.flow.account.AccountActions
                    .rememberCommentParams(ownVideoId, root)
            }
            app.zaid.flow.account.AccountActions
                .rememberReplyParams(root)
            app.zaid.flow.account.AccountActions
                .rememberOwnCommentActions(root)
            app.zaid.flow.account.AccountActions
                .rememberCommentVoteActions(root)
            root.toVideoCommentsPage(ownVideoId)
        }

    suspend fun commentReplies(
        continuation: String,
        ownVideoId: String?,
    ): Result<VideoCommentsPage> =
        runCatching {
            val root = Json.parseToJsonElement(innerTube.nextWatch(continuation = continuation).bodyAsText())
            app.zaid.flow.account.AccountActions
                .rememberReplyParams(root)
            app.zaid.flow.account.AccountActions
                .rememberOwnCommentActions(root)
            app.zaid.flow.account.AccountActions
                .rememberCommentVoteActions(root)
            root.toCommentRepliesPage(ownVideoId)
        }

    suspend fun liveChatContinuation(videoId: String): Result<String?> =
        runCatching {
            innerTube
                .nextForLiveChat(videoId)
                .body<app.zaid.flow.innertube.models.response.LiveChatSeedResponse>()
                .seedContinuation()
        }

    suspend fun liveChat(
        continuation: String,
        offsetMs: Long? = null,
    ): Result<app.zaid.flow.innertube.models.response.GetLiveChatResponse> =
        runCatching {
            innerTube
                .getLiveChat(continuation, offsetMs)
                .body<app.zaid.flow.innertube.models.response.GetLiveChatResponse>()
        }

    suspend fun liveChatReplay(
        continuation: String,
        offsetMs: Long? = null,
    ): Result<app.zaid.flow.innertube.models.response.GetLiveChatResponse> =
        runCatching {
            innerTube
                .getLiveChatReplay(continuation, offsetMs)
                .body<app.zaid.flow.innertube.models.response.GetLiveChatResponse>()
        }

    /** Seed token for [videoId]'s chat, and whether it is a recording (replay) rather than a live chat. */
    suspend fun liveChatSeed(videoId: String): Result<Pair<String?, Boolean>> =
        runCatching {
            val seed =
                innerTube
                    .nextForLiveChat(videoId)
                    .body<app.zaid.flow.innertube.models.response.LiveChatSeedResponse>()
            seed.seedContinuation() to seed.isReplay()
        }

    suspend fun watchMetadata(videoId: String): Result<app.zaid.flow.innertube.models.response.WatchMetadataResponse> =
        runCatching {
            val primary =
                innerTube
                    .next(WEB, videoId, null, null, null, null, null)
                    .body<app.zaid.flow.innertube.models.response.WatchMetadataResponse>()
            if (primary.relatedVideos().isNotEmpty()) {
                primary
            } else {
                val webWatch =
                    runCatching {
                        innerTube
                            .nextForLiveChat(videoId)
                            .body<app.zaid.flow.innertube.models.response.WatchMetadataResponse>()
                    }.getOrNull()
                if (webWatch != null && webWatch.relatedVideos().size > primary.relatedVideos().size) {
                    webWatch
                } else {
                    primary
                }
            }
        }

    suspend fun watchMetadataLite(videoId: String): Result<app.zaid.flow.innertube.models.response.WatchMetadataResponse> =
        runCatching {
            innerTube
                .next(WEB, videoId, null, null, null, null, null)
                .body<app.zaid.flow.innertube.models.response.WatchMetadataResponse>()
        }

    suspend fun registerPlayback(
        playlistId: String? = null,
        playbackTracking: String,
    ) = runCatching {
        val cpn =
            (1..16)
                .map {
                    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[
                        Random.Default.nextInt(
                            0,
                            64,
                        ),
                    ]
                }.joinToString("")

        val playbackUrl =
            playbackTracking.replace(
                "https://s.youtube.com",
                "https://music.youtube.com",
            )

        innerTube.registerPlayback(
            url = playbackUrl,
            playlistId = playlistId,
            cpn = cpn,
        )
    }

    suspend fun next(
        endpoint: WatchEndpoint,
        continuation: String? = null,
    ): Result<NextResult> =
        runCatching {
            val response =
                innerTube
                    .next(
                        WEB_REMIX,
                        endpoint.videoId,
                        endpoint.playlistId,
                        endpoint.playlistSetVideoId,
                        endpoint.index,
                        endpoint.params,
                        continuation,
                    ).body<NextResponse>()
            // YouTube inserts/reorders watch-next tabs (a Comments tab appeared at
            // index 2 in 2026), so lyrics/related must be found by browseId prefix,
            // never by position.
            val watchNextTabs =
                response.contents.singleColumnMusicWatchNextResultsRenderer
                    ?.tabbedRenderer
                    ?.watchNextTabbedResultsRenderer
                    ?.tabs
            val lyricsBrowseEndpoint =
                watchNextTabs?.firstNotNullOfOrNull { tab ->
                    tab.tabRenderer.endpoint
                        ?.browseEndpoint
                        ?.takeIf { it.browseId.startsWith("MPLYt") }
                }
            val relatedBrowseEndpoint =
                watchNextTabs?.firstNotNullOfOrNull { tab ->
                    tab.tabRenderer.endpoint
                        ?.browseEndpoint
                        ?.takeIf { it.browseId.startsWith("MPTRt") }
                }
            val playlistPanelRenderer =
                response.continuationContents?.playlistPanelContinuation
                    ?: response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.get(0)
                        ?.tabRenderer
                        ?.content
                        ?.musicQueueRenderer
                        ?.content
                        ?.playlistPanelRenderer!!
            val title =
                response.contents.singleColumnMusicWatchNextResultsRenderer
                    ?.tabbedRenderer
                    ?.watchNextTabbedResultsRenderer
                    ?.tabs
                    ?.get(0)
                    ?.tabRenderer
                    ?.content
                    ?.musicQueueRenderer
                    ?.header
                    ?.musicQueueHeaderRenderer
                    ?.subtitle
                    ?.runs
                    ?.firstOrNull()
                    ?.text
            val items =
                playlistPanelRenderer.contents.mapNotNull { content ->
                    content.playlistPanelVideoRenderer
                        ?.let(NextPage::fromPlaylistPanelVideoRenderer)
                        ?.let { it to content.playlistPanelVideoRenderer.selected }
                }
            val songs = items.map { it.first }
            val currentIndex = items.indexOfFirst { it.second }.takeIf { it != -1 }

            // load automix items
            playlistPanelRenderer.contents
                .lastOrNull()
                ?.automixPreviewVideoRenderer
                ?.content
                ?.automixPlaylistVideoRenderer
                ?.navigationEndpoint
                ?.watchPlaylistEndpoint
                ?.let { watchPlaylistEndpoint ->
                    return@runCatching next(watchPlaylistEndpoint).getOrThrow().let { result ->
                        result.copy(
                            title = title,
                            items = songs + result.items,
                            lyricsEndpoint = lyricsBrowseEndpoint,
                            relatedEndpoint = relatedBrowseEndpoint,
                            currentIndex = currentIndex,
                            endpoint = watchPlaylistEndpoint,
                        )
                    }
                }
            NextResult(
                title = title,
                items = songs,
                currentIndex = currentIndex,
                lyricsEndpoint = lyricsBrowseEndpoint,
                relatedEndpoint = relatedBrowseEndpoint,
                continuation = playlistPanelRenderer.continuations?.getContinuation(),
                endpoint = endpoint,
            )
        }

    suspend fun lyrics(endpoint: BrowseEndpoint): Result<String?> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            response.contents
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.musicDescriptionShelfRenderer
                ?.description
                ?.runs
                ?.firstOrNull()
                ?.text
        }

    suspend fun related(endpoint: BrowseEndpoint): Result<RelatedPage> =
        runCatching {
            RelatedPage.fromBrowseResponse(innerTube.browse(WEB_REMIX, endpoint.browseId).body<BrowseResponse>())
        }

    suspend fun queue(
        videoIds: List<String>? = null,
        playlistId: String? = null,
    ): Result<List<SongItem>> =
        runCatching {
            if (videoIds != null) {
                assert(videoIds.size <= MAX_GET_QUEUE_SIZE) // Max video limit
            }
            innerTube
                .getQueue(WEB_REMIX, videoIds, playlistId)
                .body<GetQueueResponse>()
                .queueDatas
                .mapNotNull {
                    it.content.playlistPanelVideoRenderer?.let { renderer ->
                        NextPage.fromPlaylistPanelVideoRenderer(renderer)
                    }
                }
        }

    suspend fun transcript(videoId: String): Result<String> =
        runCatching {
            val response = innerTube.getTranscript(WEB_REMIX, videoId).body<GetTranscriptResponse>()
            response.actions
                ?.firstOrNull()
                ?.updateEngagementPanelAction
                ?.content
                ?.transcriptRenderer
                ?.body
                ?.transcriptBodyRenderer
                ?.cueGroups
                ?.joinToString(separator = "\n") { group ->
                    val time =
                        group.transcriptCueGroupRenderer.cues[0]
                            .transcriptCueRenderer.startOffsetMs
                    val text =
                        group.transcriptCueGroupRenderer.cues[0]
                            .transcriptCueRenderer.cue.simpleText
                            .trim('♪')
                            .trim(' ')
                    "[%02d:%02d.%03d]$text".format(time / 60000, (time / 1000) % 60, time % 1000)
                }!!
        }

    suspend fun visitorData(): Result<String> =
        runCatching {
            Json
                .parseToJsonElement(innerTube.getSwJsData().bodyAsText().substring(5))
                .jsonArray[0]
                .jsonArray[2]
                .jsonArray
                .first {
                    (it as? JsonPrimitive)?.contentOrNull?.let { candidate ->
                        VISITOR_DATA_REGEX.containsMatchIn(candidate)
                    } ?: false
                }.jsonPrimitive.content
        }

    suspend fun accountInfo(): Result<AccountInfo> =
        runCatching {
            innerTube
                .accountMenu(WEB_REMIX)
                .body<AccountMenuResponse>()
                .actions[0]
                .openPopupAction.popup.multiPageMenuRenderer
                .header
                ?.activeAccountHeaderRenderer
                ?.toAccountInfo()!!
        }

    /** The TV-signed-in account's name, email, handle and avatar, from the accounts list the YouTube TV app reads. */
    suspend fun tvAccountInfo(): Result<AccountInfo> =
        runCatching {
            val response = innerTube.accountsList()
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            app.zaid.flow.innertube.pages.TvAccountParser
                .parseAccountInfo(response.body<JsonObject>()) ?: error("No account in the TV accounts list")
        }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> =
        runCatching {
            return innerTube.getMediaInfo(videoId)
        }

    /** How many people are watching a live stream right now, straight from YouTube. */
    suspend fun liveStats(videoId: String): Result<app.zaid.flow.innertube.pages.LiveStats> =
        runCatching {
            app.zaid.flow.innertube.pages
                .parseLiveStats(innerTube.updatedMetadata(videoId).body<JsonObject>())
        }

    suspend fun returnYouTubeDislike(videoId: String): Result<ReturnYouTubeDislikeResponse> =
        runCatching {
            innerTube.returnYouTubeDislike(videoId).body<ReturnYouTubeDislikeResponse>()
        }

    @JvmInline
    value class SearchFilter(
        val value: String,
    ) {
        companion object {
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_FEATURED_PLAYLIST = SearchFilter("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D")
            val FILTER_COMMUNITY_PLAYLIST = SearchFilter("EgeKAQQoAEABagoQAxAEEAoQCRAF")
        }
    }

    @JvmInline
    value class LibraryFilter(
        val value: String,
    ) {
        companion object {
            val FILTER_RECENT_ACTIVITY = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCaEFCb0FZQg%3D%3D")
            val FILTER_RECENTLY_PLAYED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCUkFCb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_ALPHABETICAL = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBUkFBb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_RECENTLY_SAVED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D")
        }
    }

    const val MAX_GET_QUEUE_SIZE = 1000

    /**
     * One page of the reel feed: the seedless first page for a null token, else the page a
     * previous response's continuation names. Entries carry ids and tokens only; see [reelOverlay].
     *
     * IOS first because its page is a few kilobytes per entry; ANDROID's carries a serialized
     * prefetch of every reel's player response and runs to several megabytes for the same ids.
     */
    suspend fun shorts(sequenceParams: String? = null): Result<ReelSequencePage> =
        runCatching {
            ensureVisitorData()
            val params = sequenceParams ?: ReelParams.INITIAL_SEQUENCE
            if (innerTube.isTvAccount) {
                // A YouTube TV sign-in: the sequence is the account's own, asked of the TV client with its bearer token.
                val personal =
                    runCatching { reelSequence(innerTube.tvClient(), params, login = true) }
                        .onFailure { app.zaid.flow.utils.HomeFeedLog.fail("tv", "shorts: ${it.javaClass.simpleName} ${it.message}") }
                        .getOrNull()
                        ?.takeIf { it.entries.isNotEmpty() }
                if (personal != null) return@runCatching personal
            }
            if (innerTube.isSignedIn) {
                // Signed in, the sequence is the account's own: asked of the WEB client with its
                // cookie. If that fails or comes back empty the anonymous path below still serves.
                val personal =
                    runCatching { reelSequence(YouTubeClient.WEB, params, login = true) }
                        .getOrNull()
                        ?.takeIf { it.entries.isNotEmpty() }
                if (personal != null) return@runCatching personal
            }
            runCatching { reelSequence(YouTubeClient.IOS, params) }
                .getOrNull()
                ?.takeIf { it.entries.isNotEmpty() }
                ?: reelSequence(YouTubeClient.ANDROID, params)
        }

    private suspend fun reelSequence(
        client: YouTubeClient,
        sequenceParams: String,
        login: Boolean = false,
    ): ReelSequencePage =
        innerTube.reel(client = client, sequenceParams = sequenceParams, login = login).body<JsonObject>().toReelSequencePage()

    /**
     * A youtube.com browse page as an anonymous visitor sees it, raw. Used for the home feed and its
     * chips when nobody is signed in.
     */
    suspend fun anonymousBrowse(
        browseId: String? = null,
        continuation: String? = null,
    ): Result<JsonObject> =
        runCatching {
            ensureVisitorData()
            channelBrowseJson(browseId = browseId, continuation = continuation).jsonObject
        }

    /** Which request identity YouTube last confirmed as the account's on a first page; continuations reuse it. */
    @Volatile
    private var confirmedBrowseIdentity: String? = null

    /** The account page's session values, with the cookie they were read for; refreshed when the cookie changes. */
    @Volatile
    private var accountWebSession: Pair<String, AccountWebSession?>? = null
    private val accountWebSessionLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Forgets the cached account page session (its visitor id and live client version) so the next account
     * browse re-reads it. A viewer's reload uses this: the same cached identity made YouTube answer the same
     * Home again, so Reload looked like it did nothing.
     */
    fun resetAccountWebSession() {
        accountWebSession = null
        confirmedBrowseIdentity = null
    }

    /**
     * The signed-in account's session as youtube.com's own page reports it (visitor id, brand identity, live client
     * version), read once per cookie like YouTube.js' session bootstrap. Null when nobody is signed in or the page
     * could not be read in time; callers then keep their previous behaviour.
     */
    private suspend fun ensureAccountWebSession(): AccountWebSession? {
        val cookie = innerTube.cookie?.takeIf { it.isNotBlank() } ?: return null
        accountWebSession?.takeIf { it.first == cookie }?.let { return it.second }
        return accountWebSessionLock.withLock {
            accountWebSession?.takeIf { it.first == cookie }?.let { return@withLock it.second }
            val session =
                kotlinx.coroutines.withTimeoutOrNull(ACCOUNT_PAGE_TIMEOUT_MS) {
                    runCatching { parseAccountWebSession(innerTube.accountHomeHtml(currentWebClient())) }.getOrNull()
                }
            app.zaid.flow.utils.HomeFeedLog.d(
                "session",
                "account page: visitorData=${!session?.visitorData.isNullOrBlank()} delegated=${session?.delegatedId != null} " +
                    "loggedIn=${session?.loggedIn} clientVersion=${session?.clientVersion}",
            )
            // A failed read is not cached for good: the next Home load asks again.
            if (session != null) accountWebSession = cookie to session
            session
        }
    }

    /** One way of identifying the account's request: which visitor id travels with the cookie. */
    private data class BrowseIdentity(
        val label: String,
        val visitorData: String?,
    )

    /** A youtube.com browse page as the signed-in account sees it, raw; see [innerTube]'s accountBrowse. */
    suspend fun accountBrowse(
        browseId: String? = null,
        continuation: String? = null,
        mobile: Boolean = false,
    ): Result<JsonObject> =
        runCatching {
            ensureVisitorData()
            val session = ensureAccountWebSession()
            // The live site version and the device's own offset, as every other web call here sends.
            // The compiled-in WEB version ages out, and a stale one can be answered with a generic page.
            val client =
                currentWebClient().let { web ->
                    // The newest version known: the account's own page reports the one the site is serving right now,
                    // the extractor's value can be its own stale fallback, and the compiled-in one is the floor.
                    val newest = newestClientVersion(session?.clientVersion, web.clientVersion, WEB.clientVersion) ?: web.clientVersion
                    val live = web.copy(clientVersion = newest)
                    // MWEB carries the live site version too; its compiled-in one is stale.
                    (if (mobile) YouTubeClient.MWEB.copy(clientVersion = live.clientVersion) else live)
                        .copy(utcOffsetMinutes = localUtcOffsetMinutes())
                }
            // The request the browser makes: the account's cookie with the visitor id the account's own page
            // carries. YouTube answers a signed-in request that has no matching visitor id with a generic,
            // non-personalised feed (the anonymous trending videos) while still flagging it as signed in, so that
            // identity goes first, then the cookie alone, then the app's shared anonymous visitor id.
            val identities =
                buildList {
                    session?.visitorData?.let { add(BrowseIdentity("account-page", it)) }
                    // The cookie alone, the way the Shorts feed is asked: the account is the identity and no visitor id
                    // can pull the answer elsewhere. Before the app's own anonymous visitor id, which is unrelated to it.
                    add(BrowseIdentity("none", null))
                    visitorData
                        ?.takeIf { it.isNotBlank() && it != session?.visitorData }
                        ?.let { add(BrowseIdentity("app-visitor", it)) }
                }
            // A continuation must be asked the way the first page was confirmed as the account's: another
            // identity can answer it as the anonymous trending feed without any flag saying so.
            val order =
                if (continuation != null) {
                    identities.filter { it.label == confirmedBrowseIdentity }.ifEmpty { identities }
                } else {
                    identities
                }
            var lastState: Boolean? = null
            for (identity in order) {
                val started = System.currentTimeMillis()
                app.zaid.flow.utils.HomeFeedLog.d(
                    "browse",
                    "request ${browseId ?: "continuation"} client=${client.clientName} v${client.clientVersion} " +
                        "identity=${identity.label} delegated=${session?.delegatedId != null} ${innerTube.cookieSummary()}",
                )
                val response =
                    innerTube.accountBrowse(
                        browseId = browseId,
                        continuation = continuation,
                        client = client,
                        visitorOverride = identity.visitorData,
                        delegatedId = session?.delegatedId,
                    )
                // An error page parses as valid JSON and would look like an empty feed; fail loudly instead.
                check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
                val json = response.body<JsonObject>()
                lastState = json.isLoggedInResponse()
                app.zaid.flow.utils.HomeFeedLog.d(
                    "browse",
                    "response HTTP ${response.status.value} loggedIn=$lastState keys=${json.keys.take(8)} in ${System.currentTimeMillis() - started} ms",
                )
                Log.w("AccountFeed", "browse ${browseId ?: "continuation"} identity=${identity.label} loggedIn=$lastState")
                if (lastState == true && continuation == null) confirmedBrowseIdentity = identity.label
                if (lastState != false) return@runCatching json
            }
            app.zaid.flow.utils.HomeFeedLog.warn("browse", "${client.clientName} served ${browseId ?: "continuation"} as a signed-out visitor")
            throw AccountSessionRejectedException("YouTube served $browseId as a signed-out visitor; the saved session is no longer accepted")
        }.onFailure { app.zaid.flow.utils.HomeFeedLog.fail("browse", "${browseId ?: "continuation"} mobile=$mobile: ${it.javaClass.simpleName} ${it.message}") }

    /** The signed-in TV account's browse answer (TVHTML5 client, OAuth bearer), as raw JSON. */
    suspend fun tvBrowse(
        browseId: String?,
        continuation: String? = null,
    ): Result<JsonObject> =
        runCatching {
            val response = innerTube.tvBrowse(browseId, continuation)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            response.body<JsonObject>()
        }.onFailure { app.zaid.flow.utils.HomeFeedLog.fail("tv", "${browseId ?: "continuation"}: ${it.javaClass.simpleName} ${it.message}") }

    /** The reels that follow [videoId]. The response never contains the seed itself. */
    suspend fun shortsFromVideo(videoId: String): Result<ReelSequencePage> = shorts(ReelParams.seedSequenceParams(videoId))

    /** The sound page YouTube links a reel to (its /source/ page), when the reel's own pivot has no usable link. */
    suspend fun soundEndpointForVideo(videoId: String): Result<Pair<String, String?>> =
        runCatching {
            ensureVisitorData()
            val endpoint =
                innerTube
                    .resolveUrl(WEB, "https://www.youtube.com/source/$videoId/shorts")
                    .body<ResolveUrlResponse>()
                    .endpoint
                    ?.browseEndpoint
                    ?: error("No sound page for $videoId")
            endpoint.browseId to endpoint.params
        }

    /**
     * The `bp` value of youtube.com/feed/sfv_audio_pivot, built the way yt-dlp builds it: field 94 holds a
     * message whose field 2 holds the video id three times (tags 1, 2 and 3).
     */
    private fun audioPivotParams(videoId: String): String {
        val id = videoId.toByteArray()

        fun field(
            tag: Int,
            body: ByteArray,
        ) = byteArrayOf(tag.toByte(), body.size.toByte()) + body
        val triple = field(0x0A, id) + field(0x12, id) + field(0x1A, id)
        val message = field(0x0A, field(0x12, triple))
        val bytes = byteArrayOf(0xF2.toByte(), 0x05, message.size.toByte()) + message
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /** The reels listed on youtube.com's own sound page, read from the page's embedded ytInitialData. */
    suspend fun soundShortsFromPage(
        videoId: String,
        feed: Boolean = false,
    ): Result<SoundPage> =
        runCatching {
            val url =
                if (feed) {
                    "https://www.youtube.com/feed/sfv_audio_pivot?bp=" + java.net.URLEncoder.encode(audioPivotParams(videoId), "UTF-8")
                } else {
                    "https://www.youtube.com/source/$videoId/shorts"
                }
            val html = innerTube.soundPageHtml(WEB, url)
            val marker = html.indexOf("ytInitialData")
            check(marker >= 0) { "No page data for $videoId" }
            val start = html.indexOf('{', marker)
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            for (i in start until html.length) {
                val c = html[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '"') {
                        inString = false
                    }
                } else if (c == '"') {
                    inString = true
                } else if (c == '{') {
                    depth++
                } else if (c == '}' && --depth == 0) {
                    end = i
                    break
                }
            }
            check(end > start) { "Unreadable page data" }
            soundPageOf(Json.parseToJsonElement(html.substring(start, end + 1)).jsonObject)
        }

    /**
     * The sfv_audio_pivot feed (Shorts that use a video's audio) browsed directly. Its params are not
     * documented, so a few plausible encodings of the video id are tried, then none at all.
     */
    suspend fun soundShortsViaAudioPivot(videoId: String): Result<SoundPage> =
        runCatching {
            val id = videoId.toByteArray()

            fun field(
                tag: Int,
                body: ByteArray,
            ) = byteArrayOf(tag.toByte(), body.size.toByte()) + body

            // yt-dlp's audio pivot: field 94 holds one message that names the video id three times.
            // The exact tags are uncertain, so several layouts are tried and each outcome is logged.
            fun wrap(inner: ByteArray) = byteArrayOf(0xF2.toByte(), 0x05, (2 + inner.size).toByte()) + field(0x0A, inner)
            val layouts =
                linkedMapOf<String, ByteArray>(
                    "yt-dlp exact" to android.util.Base64.decode(audioPivotParams(videoId), android.util.Base64.NO_WRAP),
                    "ids 1,2,3" to wrap(field(0x0A, id) + field(0x12, id) + field(0x1A, id)),
                    "ids 2,3,4" to wrap(field(0x12, id) + field(0x1A, id) + field(0x22, id)),
                    "ids 2,3,4 +flag" to wrap(byteArrayOf(0x08, 0x01) + field(0x12, id) + field(0x1A, id) + field(0x22, id)),
                    "id only" to field(0x0A, id),
                    "id nested" to field(0x0A, field(0x0A, id)),
                )
            var best = SoundPage(emptyList())
            val attempts: List<Pair<String, ByteArray?>> = layouts.map { it.key to it.value } + listOf("no params" to null)
            for ((name, bytes) in attempts) {
                val params = bytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                val result = soundShorts("FEsfv_audio_pivot", params)
                val page = result.getOrNull()
                val reels = page?.reels.orEmpty()
                synchronized(soundShape) {
                    soundShape.append(
                        "  pivot [$name]: ${result.exceptionOrNull()?.let {
                            "${it.javaClass.simpleName} ${it.message?.take(
                                70,
                            )}"
                        } ?: "${reels.size} shorts"}\n",
                    )
                }
                if (page != null && reels.size > best.reels.size) best = page
                // One layout that works is enough; trying the rest only delays the screen.
                if (best.reels.size > 1) break
            }
            best
        }

    /**
     * One page of a sound feed: its reels, the total YouTube states for the sound, and the token for the
     * page after it. Later pages are fetched on demand with [soundMore], never all up front.
     */
    private fun soundPageOf(
        response: JsonObject,
        viaNext: Boolean = false,
        log: Boolean = true,
    ): SoundPage {
        val reels = response.toReelLockups().ifEmpty { response.looseReelLockups() }.distinctBy { it.id }
        // Later pages are not logged: nothing drains the log while the person scrolls, so it would only grow.
        if (log) recordSoundShape(response, reels.size)
        val total = if (log) response.soundShortsTotal() else null
        if (total != null) {
            synchronized(soundShape) { soundShape.append("  total: ${total.count} from \"${total.text}\"\n") }
        }
        return SoundPage(
            reels = reels,
            next = response.nextPageToken()?.let { SoundContinuation(it, viaNext) },
            total = total?.count,
        )
    }

    /** The page after one [soundPageOf] returned. The total is only stated on the first page, so it is null here. */
    suspend fun soundMore(next: SoundContinuation): Result<SoundPage> =
        runCatching {
            val response =
                if (next.viaNext) {
                    innerTube.next(WEB, null, null, null, null, null, next.token).body<JsonObject>()
                } else {
                    channelBrowseJson(continuation = next.token).jsonObject
                }
            soundPageOf(response, next.viaNext, log = false)
        }

    private val soundShape = StringBuilder()

    /** What the last sound request's response was made of, for the on-screen log; read once, then cleared. */
    fun takeSoundShape(): String =
        synchronized(soundShape) {
            soundShape.toString().also { soundShape.clear() }
        }

    private fun recordSoundShape(
        response: JsonObject,
        parsed: Int,
    ) {
        val counts = HashMap<String, Int>()

        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    e.forEach { (k, v) ->
                        if (k.endsWith("Renderer") || k.endsWith("ViewModel") || k.endsWith("Endpoint")) counts.merge(k, 1, Int::plus)
                        walk(v)
                    }
                }

                is JsonArray -> {
                    e.forEach(::walk)
                }

                else -> {
                    Unit
                }
            }
        }
        walk(response)
        val top =
            counts.entries
                .sortedByDescending { it.value }
                .take(14)
                .joinToString { "${it.key}x${it.value}" }
        synchronized(soundShape) {
            soundShape.append("  response keys: ${response.keys.take(8)}; parsed=$parsed; $top\n")
        }
    }

    /** Reels found by any `reelWatchEndpoint` or `/shorts/<id>` link, for feeds that use a renderer the strict parser skips. */
    private fun JsonObject.looseReelLockups(): List<ReelLockup> {
        val ids = LinkedHashSet<String>()
        val shortsLink = Regex("/shorts/([A-Za-z0-9_-]{11})")

        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    e["reelWatchEndpoint"]
                        .objectOrNull()
                        ?.get("videoId")
                        .stringOrNull()
                        ?.let(ids::add)
                    e.values.forEach(::walk)
                }

                is JsonArray -> {
                    e.forEach(::walk)
                }

                is kotlinx.serialization.json.JsonPrimitive -> {
                    if (e.isString) {
                        shortsLink
                            .find(e.content)
                            ?.groupValues
                            ?.get(1)
                            ?.let(ids::add)
                    }
                }
            }
        }
        walk(this)
        return ids.map { id ->
            ReelLockup(
                id = id,
                title = "",
                viewCount = 0,
                thumbnailUrl =
                    app.zaid.flow.innertube.pages.reel
                        .reelPosterUrl(id),
            )
        }
    }

    private fun JsonElement.nextPageToken(): String? =
        when (this) {
            is JsonObject -> {
                this["continuationItemRenderer"]
                    .objectOrNull()
                    ?.get("continuationEndpoint")
                    .objectOrNull()
                    ?.get("continuationCommand")
                    .objectOrNull()
                    ?.get("token")
                    .stringOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: values.firstNotNullOfOrNull { it.nextPageToken() }
            }

            is JsonArray -> {
                firstNotNullOfOrNull { it.nextPageToken() }
            }

            else -> {
                null
            }
        }

    /**
     * Video ids the reel's sound button points at. A reel that reuses someone else's audio is keyed by that
     * source video, not by itself; asking for the reel's own id then lists only the reel. The button's raw
     * text is also written to the sound log so its real shape can be read.
     */
    suspend fun soundSourceIds(videoId: String): List<String> =
        runCatching {
            val response = innerTube.reelItemWatch(client = WEB, videoId = videoId).body<JsonObject>()

            fun find(e: JsonElement): JsonElement? =
                when (e) {
                    is JsonObject -> e["pivotButtonRenderer"] ?: e["pivotButtonViewModel"] ?: e.values.firstNotNullOfOrNull(::find)
                    is JsonArray -> e.firstNotNullOfOrNull(::find)
                    else -> null
                }
            val node = find(response)
            val text = node?.toString().orEmpty()
            synchronized(soundShape) {
                soundShape.append("  sound button: ${if (node == null) "NOT IN RESPONSE" else text.take(1800)}\n")
            }
            // The sound page link (/source/<id>/shorts) can sit anywhere in the response, so search all of it first.
            val everywhere =
                Regex("/source/([A-Za-z0-9_-]{11})").findAll(response.toString()).map { it.groupValues[1] }.toList()
            synchronized(soundShape) { soundShape.append("  /source/ links in whole response: ${everywhere.distinct()}\n") }
            // The reel's own web page carries the same button; read it when the details response lacks the link.
            val fromPage =
                if (everywhere.any { it != videoId }) {
                    emptyList()
                } else {
                    runCatching {
                        Regex("/source/([A-Za-z0-9_-]{11})")
                            .findAll(innerTube.soundPageHtml(WEB, "https://www.youtube.com/shorts/$videoId"))
                            .map { it.groupValues[1] }
                            .toList()
                    }.getOrDefault(emptyList())
                }
            synchronized(soundShape) { soundShape.append("  /source/ links on the shorts page: ${fromPage.distinct()}\n") }
            (everywhere + fromPage + Regex("\"videoId\":\"([A-Za-z0-9_-]{11})").findAll(text).map { it.groupValues[1] })
                .filter { it != videoId }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())

    /**
     * Shorts using a reel's sound, read from the panel its sound button opens. For a song or reused audio the
     * button does not link to a page: it opens an engagement panel whose content loads from a continuation
     * token (or is embedded). Each token is tried against browse and next; outcomes go to the sound log.
     */
    suspend fun soundShortsFromPanel(videoId: String): Result<SoundPage> =
        runCatching {
            ensureVisitorData()
            val response = innerTube.reelItemWatch(client = WEB, videoId = videoId).body<JsonObject>()

            fun findButton(e: JsonElement): JsonElement? =
                when (e) {
                    is JsonObject -> e["pivotButtonRenderer"] ?: e["pivotButtonViewModel"] ?: e.values.firstNotNullOfOrNull(::findButton)
                    is JsonArray -> e.firstNotNullOfOrNull(::findButton)
                    else -> null
                }

            fun tokens(e: JsonElement): List<String> =
                when (e) {
                    is JsonObject -> {
                        listOfNotNull(e["continuationCommand"].objectOrNull()?.get("token").stringOrNull()) +
                            e.values.flatMap(::tokens)
                    }

                    is JsonArray -> {
                        e.flatMap(::tokens)
                    }

                    else -> {
                        emptyList()
                    }
                }
            // The button only names its panel ("engagement-panel-shorts-audio-pivot"); the panel itself, with the
            // token or reels that fill it, sits elsewhere in the response or on the reel's web page.
            val pageData =
                runCatching { parseInitialData(innerTube.soundPageHtml(WEB, "https://www.youtube.com/shorts/$videoId")) }.getOrNull()
            val ids = LinkedHashSet<String>()

            fun panels(e: JsonElement): List<JsonObject> =
                when (e) {
                    is JsonObject -> {
                        val id = e["panelIdentifier"].stringOrNull() ?: e["targetId"].stringOrNull()
                        if (id != null) ids.add(id)
                        (if (id?.contains("audio-pivot") == true) listOf(e) else emptyList()) + e.values.flatMap(::panels)
                    }

                    is JsonArray -> {
                        e.flatMap(::panels)
                    }

                    else -> {
                        emptyList()
                    }
                }
            val audioPanels = listOfNotNull<JsonElement>(response, pageData).flatMap(::panels)

            fun note(line: String) = synchronized(soundShape) { soundShape.append("  panel: $line\n") }
            note("ids seen in response and page: ${ids.take(12)}; pageData=${pageData != null}")
            note("audio pivot panels found: ${audioPanels.size}")
            val found = audioPanels.flatMap(::tokens).distinct()
            note("continuation tokens in them: ${found.size}")
            var best = SoundPage(audioPanels.flatMap { it.toReelLockups() }.distinctBy { it.id })
            if (best.reels.isNotEmpty()) note("embedded reels: ${best.reels.size}")
            for (token in found) {
                val viaBrowse = runCatching { soundPageOf(channelBrowseJson(continuation = token).jsonObject) }
                note(
                    "browse token -> ${viaBrowse.exceptionOrNull()?.let {
                        "${it.javaClass.simpleName} ${it.message?.take(
                            60,
                        )}"
                    } ?: "${viaBrowse.getOrNull()?.reels?.size} reels"}",
                )
                viaBrowse.getOrNull()?.takeIf { it.reels.size > best.reels.size }?.let { best = it }
                if (best.reels.size > 1) break
                val viaNext =
                    runCatching {
                        soundPageOf(innerTube.next(WEB, null, null, null, null, null, token).body<JsonObject>(), viaNext = true)
                    }
                note(
                    "next token -> ${viaNext.exceptionOrNull()?.let {
                        "${it.javaClass.simpleName} ${it.message?.take(
                            60,
                        )}"
                    } ?: "${viaNext.getOrNull()?.reels?.size} reels"}",
                )
                viaNext.getOrNull()?.takeIf { it.reels.size > best.reels.size }?.let { best = it }
                if (best.reels.size > 1) break
            }
            best
        }

    /** The ytInitialData object embedded in a youtube.com page. */
    private fun parseInitialData(html: String): JsonObject {
        val marker = html.indexOf("ytInitialData")
        check(marker >= 0) { "No page data" }
        val start = html.indexOf('{', marker)
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else if (c == '"') {
                inString = true
            } else if (c == '{') {
                depth++
            } else if (c == '}' && --depth == 0) {
                end = i
                break
            }
        }
        check(end > start) { "Unreadable page data" }
        return Json.parseToJsonElement(html.substring(start, end + 1)).jsonObject
    }

    /** The first page of the reels that use one sound, from the sound page the reel's pivot button opens. */
    suspend fun soundShorts(
        browseId: String,
        params: String?,
    ): Result<SoundPage> =
        runCatching {
            ensureVisitorData()
            soundPageOf(channelBrowseJson(browseId = browseId, params = params?.takeIf { it.isNotBlank() }).jsonObject)
        }

    /** Title, channel, counts and sound for one reel — everything the sequence leaves out. */
    suspend fun reelOverlay(videoId: String): Result<ReelOverlay?> =
        runCatching {
            innerTube.reelItemWatch(client = WEB, videoId = videoId).body<JsonObject>().toReelOverlay()
        }

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")

    /** How long Home waits for the account page's session values before using the shared visitor id instead. */
    private const val ACCOUNT_PAGE_TIMEOUT_MS = 6_000L
}
