package app.zaid.flow.data.account

import android.util.Log
import app.zaid.flow.data.model.Channel
import app.zaid.flow.data.model.Video
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.pages.AccountSessionRejectedException
import app.zaid.flow.innertube.pages.booleanOrNull
import app.zaid.flow.innertube.pages.isLoggedInResponse
import app.zaid.flow.innertube.pages.objectOrNull
import app.zaid.flow.innertube.pages.renderer.FeedItem
import app.zaid.flow.innertube.pages.renderer.FeedItemOwner
import app.zaid.flow.innertube.pages.renderer.toFeedItem
import app.zaid.flow.innertube.pages.stringOrNull
import app.zaid.flow.utils.HomeFeedLog
import app.zaid.flow.innertube.pages.youtubeText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** One chip of YouTube's own chip bar above the home feed, and the token that opens its feed. */
data class AccountChip(
    val title: String,
    val token: String,
)

/** One page of an account list: what it held and the token for the next page, null at the end. */
data class AccountPage(
    val items: List<FeedItem>,
    val continuation: String?,
    val chips: List<AccountChip> = emptyList(),
    /** True only when YouTube itself flagged this response as the signed-in account's; null when it said nothing. */
    val loggedIn: Boolean? = null,
    /** Share of each video the account has watched (0-100), where the page showed a progress bar. */
    val watchedPercent: Map<String, Int> = emptyMap(),
) {
    /** Videos and Shorts, each as the [Video] it is; Shorts carry isShort. */
    val videos: List<Video>
        get() =
            items
                .mapNotNull {
                    when (it) {
                        is FeedItem.VideoItem -> it.video
                        is FeedItem.ShortItem -> it.video.copy(isShort = true)
                        else -> null
                    }
                }.distinctBy { it.id }

    /** Community posts YouTube mixed into this page of the account's home. */
    val posts: List<app.zaid.flow.innertube.pages.renderer.CommunityPost>
        get() = items.filterIsInstance<FeedItem.PostItem>().map { it.post }.distinctBy { it.id }

    val channels: List<Channel>
        get() = items.filterIsInstance<FeedItem.RelatedChannelItem>().map { it.channel }.distinctBy { it.id }
}

/**
 * What the signed-in Google account holds, read from youtube.com as that account: its personalised
 * home feed, watch history, subscriptions and liked videos. Every call returns null when nobody is
 * signed in or the request fails, so callers fall back to the on-device feed.
 */
@Singleton
class AccountFeedRepository
    @Inject
    constructor(
        private val webHome: WebViewHomeFetcher,
    ) {
        /** True when an account is in use, signed in either with Google's web login or the YouTube TV code. */
        val isActive: Boolean
            get() = YouTube.innerTube.isSignedIn || YouTube.innerTube.isTvSignedIn

        /** True when the account in use signed in with the YouTube TV code: everything it shows and sends goes through the TV client. */
        val isTv: Boolean
            get() = YouTube.innerTube.isTvSignedIn && !YouTube.innerTube.isSignedIn

        private val cookieActive: Boolean
            get() = YouTube.innerTube.isSignedIn

        /**
         * True once YouTube has answered the account's last request as a signed-out visitor: the saved
         * session is no longer accepted, so what it would serve is the anonymous feed, never the
         * account's. Cleared by the next request YouTube does recognise.
         */
        @Volatile
        var sessionRejected: Boolean = false
            private set

        /** Which client served the first Home page: its continuation tokens only work with the same client. */
        @Volatile
        private var homeMobile = false

        /**
         * The account's Home. The desktop web client (WEB) is tried first, the mobile web client (MWEB) is
         * the fallback when WEB answers empty or as signed out. Pages after the first use the client that
         * served the first.
         */
        suspend fun home(continuation: String? = null): AccountPage? {
            if (isTv) return tvHome(continuation)
            if (continuation != null) {
                HomeFeedLog.d("home", "next page via ${if (homeMobile) "MWEB" else "WEB"} client")
                return fetch(HOME, continuation, mobile = homeMobile)
            }
            HomeFeedLog.d("home", "trying WEB client first")
            val web = fetch(HOME, null, mobile = false)
            if (web != null && web.videos.isNotEmpty() && web.loggedIn == true) {
                HomeFeedLog.d("home", "WEB accepted: ${web.videos.size} videos, loggedIn=${web.loggedIn}")
                homeMobile = false
                return web
            }
            Log.w(TAG, "WEB home unusable (loggedIn=${web?.loggedIn}, videos=${web?.videos?.size}); using MWEB client")
            HomeFeedLog.warn("home", "WEB unusable (page=${web != null}, videos=${web?.videos?.size}, loggedIn=${web?.loggedIn}); falling back to MWEB client")
            homeMobile = true
            return fetch(HOME, null, mobile = true) ?: web.also { homeMobile = false }
        }

        /**
         * The first page of the account's Home, from the first source that YouTube confirms as the signed-in
         * account's own, in this fixed order:
         *  1. the desktop web client (WEB),
         *  2. the mobile web client (MWEB),
         *  3. youtube.com itself, read in a hidden WebView ([homeViaWebView]).
         * A page counts only when it holds videos and YouTube flagged it as the account's (`loggedIn == true`):
         * an unflagged or signed-out page is the anonymous trending feed, never the account's. Null when no
         * source gave such a page.
         *
         * @param includeWebView false skips step 3, for quick retries that must not pay for a WebView load.
         */
        suspend fun firstHomePage(
            includeWebView: Boolean = true,
            fresh: Boolean = false,
        ): AccountPage? {
            if (!isActive) return null
            if (isTv) return tvHome(null)
            // A viewer's reload must not reuse the cached session identity, or YouTube can answer the same page.
            if (fresh) YouTube.resetAccountWebSession()
            fun AccountPage?.confirmed() = this != null && videos.isNotEmpty() && loggedIn == true

            val api = home()
            HomeFeedLog.d("source", "InnerTube WEB/MWEB: confirmed=${api.confirmed()} loggedIn=${api?.loggedIn} videos=${api?.videos?.size}")
            if (api.confirmed()) {
                HomeFeedLog.d("source", "chose InnerTube ${if (homeMobile) "MWEB" else "WEB"} (confirmed)")
                return api
            }
            if (!includeWebView) return null

            HomeFeedLog.warn("source", "WEB and MWEB gave no confirmed page: trying the WebView")
            val web = homeViaWebView()
            HomeFeedLog.d("source", "WebView: confirmed=${web.confirmed()} loggedIn=${web?.loggedIn} videos=${web?.videos?.size}")
            return web?.takeIf { it.confirmed() }?.also { HomeFeedLog.d("source", "chose WebView (confirmed)") }
        }

        /**
         * The account's Home as youtube.com itself shows it, read from a hidden WebView signed in with the
         * account's cookie. This is the last source of [firstHomePage], after WEB and MWEB. Null when
         * not signed in or the page could not be read.
         */
        suspend fun homeViaWebView(): AccountPage? {
            if (!isActive) return null
            val cookie = YouTube.innerTube.cookie?.takeIf { it.isNotBlank() } ?: return null
            return try {
                webHome.fetch(cookie)?.also {
                    // The page is the desktop site's, so its continuation tokens belong to the WEB client.
                    homeMobile = false
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "WebView home failed: ${error.message}")
                null
            }
        }

        /**
         * The Home of an account that signed in with the YouTube TV code: the personalised shelves the YouTube TV
         * app shows, read through the TV client. Its continuation tokens only work with the same client.
         */
        suspend fun tvHome(continuation: String? = null): AccountPage? {
            val json = YouTube.tvBrowse(if (continuation == null) HOME else null, continuation).getOrNull() ?: return null
            app.zaid.flow.account.AccountActions
                .rememberTvFeedbackTokens(json)
            val page = app.zaid.flow.innertube.pages.TvHomeParser.parse(json)
            HomeFeedLog.d("tv", "TV home: ${page.items.size} items, more=${page.continuation != null}")
            if (page.items.isEmpty()) return null
            // The answer came from the account's own token, so it is the account's feed, not a visitor's.
            return AccountPage(items = page.items, continuation = page.continuation, loggedIn = true)
        }

        /**
         * YouTube's home feed with its chip bar: the signed-in account's own, or an anonymous visitor's
         * when nobody is signed in. Null when YouTube could not be reached.
         */
        suspend fun youtubeHome(): AccountPage? = fetch(HOME, null, anonymousFallback = true)

        /** The feed behind one of YouTube's home chips, or the page after one, by its token. */
        suspend fun youtubeChipPage(token: String): AccountPage? = fetch(null, token, anonymousFallback = true)

        suspend fun history(continuation: String? = null): AccountPage? = if (isTv) tvFeed(TV_HISTORY, continuation, fromHistory = true) else fetch(HISTORY, continuation)

        suspend fun subscribedChannels(continuation: String? = null): AccountPage? = if (isTv) tvSubscribedChannels() else fetch(CHANNELS, continuation)

        suspend fun likedVideos(continuation: String? = null): AccountPage? = if (isTv) tvFeed(LIKED, continuation) else fetch(LIKED, continuation)

        /**
         * One page of a library list (watch history, liked videos) as the YouTube TV app reads it: tiles, paged by
         * continuation. Null when the TV session answered nothing, so the caller keeps what it already has.
         */
        private suspend fun tvFeed(
            browseId: String,
            continuation: String?,
            fromHistory: Boolean = false,
        ): AccountPage? {
            val json = YouTube.tvBrowse(if (continuation == null) browseId else null, continuation).getOrNull() ?: return null
            app.zaid.flow.account.AccountActions
                .rememberTvFeedbackTokens(json, fromHistory)
            val page = app.zaid.flow.innertube.pages.TvHomeParser.parse(json)
            HomeFeedLog.d("tv", "TV ${browseId.takeIf { continuation == null } ?: "continuation"}: ${page.items.size} items, more=${page.continuation != null}")
            return AccountPage(items = page.items, continuation = page.continuation, loggedIn = true, watchedPercent = page.watchedPercent)
        }

        /** The channels the account follows, from the tab strip of the TV subscriptions page; it has no further pages. */
        private suspend fun tvSubscribedChannels(): AccountPage? {
            val json = YouTube.tvBrowse(TV_SUBSCRIPTIONS, null).getOrNull() ?: return null
            val channels = app.zaid.flow.innertube.pages.TvAccountParser.parseSubscribedChannels(json)
            HomeFeedLog.d("tv", "TV subscriptions: ${channels.size} channels")
            if (channels.isEmpty()) return null
            return AccountPage(items = channels.map { FeedItem.RelatedChannelItem(it) }, continuation = null, loggedIn = true)
        }

        private suspend fun fetch(
            browseId: String?,
            continuation: String?,
            anonymousFallback: Boolean = false,
            mobile: Boolean = false,
        ): AccountPage? {
            if (!cookieActive && !anonymousFallback) return null
            return try {
                val response =
                    if (cookieActive) {
                        YouTube.accountBrowse(browseId = browseId, continuation = continuation, mobile = mobile).getOrThrow()
                    } else {
                        YouTube.anonymousBrowse(browseId = browseId, continuation = continuation).getOrThrow()
                    }
                sessionRejected = false
                val loggedIn = if (cookieActive) response.isLoggedInResponse() else null
                response
                    .also {
                        if (cookieActive) {
                            app.zaid.flow.account.AccountActions
                                .rememberFeedbackTokens(it)
                        }
                    }.let(::parseAccountPage)
                    .copy(loggedIn = loggedIn)
                    .also {
                        Log.w(TAG, "Account $browseId loggedIn=${it.loggedIn} first=${it.videos.take(4).joinToString(" | ") { v -> v.title.take(30) }}")
                        HomeFeedLog.d(
                            "parse",
                            "${browseId ?: "continuation"} mobile=$mobile: items=${it.items.size} videos=${it.videos.size} " +
                                "shorts=${it.videos.count { v -> v.isShort }} posts=${it.posts.size} channels=${it.channels.size} " +
                                "chips=${it.chips.size} more=${it.continuation != null} loggedIn=${it.loggedIn}",
                        )
                        HomeFeedLog.d("parse", "first: " + it.videos.take(6).joinToString(" | ") { v -> "${v.title.take(32)} (${v.channelName.take(16)})" })
                        Log.w(TAG, "Account $browseId: ${it.items.size} items, ${it.videos.size} videos, more=${it.continuation != null}")
                        if (it.videos.isEmpty()) Log.w(TAG, "Account $browseId returned no videos (session expired or empty page)")
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                HomeFeedLog.fail("fetch", "${browseId ?: "continuation"} mobile=$mobile: ${error.javaClass.simpleName} ${error.message}")
                if (cookieActive && generateSequence<Throwable>(error) { it.cause }.any { it is AccountSessionRejectedException }) {
                    sessionRejected = true
                }
                Log.w(TAG, "Account $browseId failed: ${error.message}")
                null
            }
        }

        private companion object {
            const val TAG = "AccountFeed"
            const val HOME = "FEwhat_to_watch"
            const val HISTORY = "FEhistory"
            const val TV_HISTORY = "FEhistory"
            const val TV_SUBSCRIPTIONS = "FEsubscriptions"
            const val CHANNELS = "FEchannels"
            const val LIKED = "VLLL"
        }
    }

/**
 * Collects every item the existing feed parsers recognise, wherever the page nests them (a rich
 * grid, an item section, a continuation action), plus the first continuation token. Anything the
 * parsers do not know is skipped, so a new renderer costs items, never the whole page.
 */
internal fun parseAccountPage(root: JsonElement): AccountPage {
    val items = ArrayList<FeedItem>()
    val chips = ArrayList<AccountChip>()
    var continuation: String? = null
    val owner = FeedItemOwner()

    val unparsedPostKeys = LinkedHashSet<String>()
    val watchedPercent = LinkedHashMap<String, Int>()

    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                (node["videoId"].stringOrNull() ?: node["contentId"].stringOrNull())?.let { id ->
                    if (id !in watchedPercent) findWatchedPercent(node)?.let { watchedPercent[id] = it }
                }
                node.keys.forEach { key ->
                    if (key.contains("post", ignoreCase = true) && key != "postId" && node[key] is JsonObject) unparsedPostKeys += key
                }
                node.toFeedItem(owner)?.let {
                    items += it
                    return
                }
                node["chipCloudChipRenderer"].objectOrNull()?.toAccountChip()?.let { chip ->
                    if (chips.none { it.title == chip.title }) chips += chip
                    return
                }
                if (continuation == null) {
                    continuation =
                        node["continuationItemRenderer"]
                            .objectOrNull()
                            ?.get("continuationEndpoint")
                            .objectOrNull()
                            ?.get("continuationCommand")
                            .objectOrNull()
                            ?.get("token")
                            .stringOrNull()
                }
                node.values.forEach(::walk)
            }

            is JsonArray -> {
                node.forEach(::walk)
            }

            else -> {
                Unit
            }
        }
    }

    walk(root)
    if (items.none { it is FeedItem.PostItem } && unparsedPostKeys.isNotEmpty()) {
        HomeFeedLog.warn("parse", "post-like nodes seen but no post parsed: ${unparsedPostKeys.take(12)}")
    }
    return AccountPage(items, continuation, chips, watchedPercent = watchedPercent)
}

/**
 * The progress bar YouTube draws over a thumbnail: the classic resume-playback overlay carries
 * percentDurationWatched, the newer lockup carries a progress bar with startPercent.
 */
private fun findWatchedPercent(node: JsonElement): Int? {
    when (node) {
        is JsonObject -> {
            node["percentDurationWatched"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }?.let { return it }
            (node["thumbnailOverlayProgressBarViewModel"] as? JsonObject)?.let { bar ->
                findWatchedPercent(bar)?.let { return it }
            }
            node["startPercent"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }?.let { return it }
            node.values.forEach { child -> findWatchedPercent(child)?.let { return it } }
        }

        is JsonArray -> {
            node.forEach { child -> findWatchedPercent(child)?.let { return it } }
        }

        else -> {
            Unit
        }
    }
    return null
}

/**
 * A chip that opens another feed. The chip already showing (the unfiltered "All") and one without a
 * continuation to open are skipped.
 */
private fun JsonObject.toAccountChip(): AccountChip? {
    if (this["isSelected"].booleanOrNull() == true) return null
    val title = this["text"].youtubeText()?.trim()?.takeIf(String::isNotBlank) ?: return null
    val token =
        (this["navigationEndpoint"] ?: this["serviceEndpoint"])
            .objectOrNull()
            ?.get("continuationCommand")
            .objectOrNull()
            ?.get("token")
            .stringOrNull()
            ?.takeIf(String::isNotBlank) ?: return null
    return AccountChip(title, token)
}
