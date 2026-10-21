package app.zaid.flow.data.account

import android.util.Log
import app.zaid.flow.data.model.Channel
import app.zaid.flow.data.model.Video
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.pages.booleanOrNull
import app.zaid.flow.innertube.pages.objectOrNull
import app.zaid.flow.innertube.pages.renderer.FeedItem
import app.zaid.flow.innertube.pages.renderer.FeedItemOwner
import app.zaid.flow.innertube.pages.renderer.toFeedItem
import app.zaid.flow.innertube.pages.stringOrNull
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
    constructor() {
        val isActive: Boolean
            get() = YouTube.innerTube.isSignedIn

        suspend fun home(continuation: String? = null): AccountPage? = fetch(HOME, continuation)

        /**
         * YouTube's home feed with its chip bar: the signed-in account's own, or an anonymous visitor's
         * when nobody is signed in. Null when YouTube could not be reached.
         */
        suspend fun youtubeHome(): AccountPage? = fetch(HOME, null, anonymousFallback = true)

        /** The feed behind one of YouTube's home chips, or the page after one, by its token. */
        suspend fun youtubeChipPage(token: String): AccountPage? = fetch(null, token, anonymousFallback = true)

        suspend fun history(continuation: String? = null): AccountPage? = fetch(HISTORY, continuation)

        suspend fun subscribedChannels(continuation: String? = null): AccountPage? = fetch(CHANNELS, continuation)

        suspend fun likedVideos(continuation: String? = null): AccountPage? = fetch(LIKED, continuation)

        private suspend fun fetch(
            browseId: String?,
            continuation: String?,
            anonymousFallback: Boolean = false,
        ): AccountPage? {
            if (!isActive && !anonymousFallback) return null
            return try {
                val response =
                    if (isActive) {
                        YouTube.accountBrowse(browseId = browseId, continuation = continuation).getOrThrow()
                    } else {
                        YouTube.anonymousBrowse(browseId = browseId, continuation = continuation).getOrThrow()
                    }
                response
                    .also {
                        if (isActive) {
                            app.zaid.flow.account.AccountActions
                                .rememberFeedbackTokens(it)
                        }
                    }.let(::parseAccountPage)
                    .also {
                        Log.w(TAG, "Account $browseId: ${it.items.size} items, ${it.videos.size} videos, more=${it.continuation != null}")
                        if (it.videos.isEmpty()) Log.w(TAG, "Account $browseId returned no videos (session expired or empty page)")
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "Account $browseId failed: ${error.message}")
                null
            }
        }

        private companion object {
            const val TAG = "AccountFeed"
            const val HOME = "FEwhat_to_watch"
            const val HISTORY = "FEhistory"
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

    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
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
    return AccountPage(items, continuation, chips)
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
