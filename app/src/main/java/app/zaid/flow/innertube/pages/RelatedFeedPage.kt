package app.zaid.flow.innertube.pages

import app.zaid.flow.innertube.models.response.WatchMetadataResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A filter chip above the related list ("All", "From channel", ...) and the continuation that loads it. */
data class RelatedFeedChip(
    val title: String,
    val token: String?,
    val selected: Boolean,
)

/** One page of the watch page's "up next" column: videos, the token for the next page and the chips. */
data class RelatedFeedPage(
    val videos: List<WatchMetadataResponse.CompactVideo>,
    val continuation: String?,
    val chips: List<RelatedFeedChip>,
    /** A chip switch replaces the list; plain paging appends to it. */
    val replace: Boolean,
)

private val relatedJson = Json { ignoreUnknownKeys = true }

/** The first page, read from an already-fetched `next` response so it costs no request. */
internal fun JsonElement.toRelatedFeedFirstPage(hl: String?): RelatedFeedPage =
    parseRelatedItems(
        items =
            objectOrNull()
                ?.get("contents")
                .objectOrNull()
                ?.get("twoColumnWatchNextResults")
                .objectOrNull()
                ?.get("secondaryResults")
                .objectOrNull()
                ?.get("secondaryResults")
                .objectOrNull()
                ?.get("results")
                .arrayOrNull()
                .orEmpty(),
        hl = hl,
        replace = false,
    )

/** A continuation response: appended items for paging, reloaded items for a chip switch. */
internal fun JsonElement.toRelatedFeedPage(hl: String?): RelatedFeedPage {
    var replace = false
    val items =
        objectOrNull()
            ?.get("onResponseReceivedEndpoints")
            .arrayOrNull()
            .orEmpty()
            .flatMap { endpoint ->
                val obj = endpoint.objectOrNull() ?: return@flatMap emptyList()
                val reload = obj["reloadContinuationItemsCommand"].objectOrNull()
                if (reload != null) replace = true
                (reload ?: obj["appendContinuationItemsAction"].objectOrNull())
                    ?.get("continuationItems")
                    .arrayOrNull()
                    .orEmpty()
            }
    return parseRelatedItems(items, hl, replace)
}

private fun parseRelatedItems(
    items: List<JsonElement>,
    hl: String?,
    replace: Boolean,
): RelatedFeedPage {
    val videos = mutableListOf<WatchMetadataResponse.CompactVideo>()
    var continuation: String? = null
    var chips = emptyList<RelatedFeedChip>()

    fun visit(entry: JsonObject) {
        entry["itemSectionRenderer"].objectOrNull()?.get("contents").arrayOrNull()?.forEach { inner ->
            inner.objectOrNull()?.let(::visit)
        }
        entry["continuationItemRenderer"].objectOrNull()?.continuationToken()?.let { continuation = it }
        entry["relatedChipCloudRenderer"].objectOrNull()?.let { chips = it.toChips() }
        if (entry.containsKey("compactVideoRenderer") || entry.containsKey("lockupViewModel") ||
            entry.containsKey("compactAutoplayRenderer")
        ) {
            runCatching {
                relatedJson.decodeFromJsonElement(WatchMetadataResponse.SecondaryItem.serializer(), entry)
            }.getOrNull()?.videos(hl)?.let(videos::addAll)
        }
    }
    items.forEach { it.objectOrNull()?.let(::visit) }
    return RelatedFeedPage(videos.filter { !it.videoId.isNullOrBlank() }, continuation, chips, replace)
}

private fun JsonObject.toChips(): List<RelatedFeedChip> =
    this["content"]
        .objectOrNull()
        ?.get("chipCloudRenderer")
        .objectOrNull()
        ?.get("chips")
        .arrayOrNull()
        .orEmpty()
        .mapNotNull { chip ->
            val renderer = chip.objectOrNull()?.get("chipCloudChipRenderer").objectOrNull() ?: return@mapNotNull null
            val text = renderer["text"].objectOrNull()
            val title =
                text?.get("simpleText").stringOrNull()
                    ?: text
                        ?.get("runs")
                        .arrayOrNull()
                        ?.firstOrNull()
                        .objectOrNull()
                        ?.get("text")
                        .stringOrNull()
                    ?: return@mapNotNull null
            RelatedFeedChip(
                title = title,
                token =
                    renderer["navigationEndpoint"]
                        .objectOrNull()
                        ?.get("continuationCommand")
                        .objectOrNull()
                        ?.get("token")
                        .stringOrNull(),
                selected = runCatching { renderer["isSelected"]?.jsonPrimitive?.booleanOrNull }.getOrNull() == true,
            )
        }
