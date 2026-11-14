package app.zaid.flow.innertube.pages

import app.zaid.flow.data.model.Video
import app.zaid.flow.innertube.pages.renderer.FeedItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the YouTube TV app's home (and its continuations) into feed items.
 *
 * The TV client lays its home out as shelves of `tileRenderer`s. The walk is structural rather than
 * path-based, so a change in how the shelves are nested does not stop it finding the tiles.
 */
object TvHomeParser {
    data class Page(
        val items: List<FeedItem>,
        val continuation: String?,
        /** Share of each video the account has watched (0-100), where its tile showed a resume bar. */
        val watchedPercent: Map<String, Int> = emptyMap(),
    )

    fun parse(root: JsonObject): Page {
        val items = ArrayList<FeedItem>()
        val seen = HashSet<String>()
        val watchedPercent = LinkedHashMap<String, Int>()
        var continuation: String? = null

        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    node["tileRenderer"]?.let { tile ->
                        (tile as? JsonObject)?.let(::tileToItem)?.let { item ->
                            val id = (item as? FeedItem.VideoItem)?.video?.id ?: (item as? FeedItem.ShortItem)?.video?.id
                            if (id != null && seen.add(id)) {
                                items += item
                                watchedPercentOf(tile as JsonObject)?.let { watchedPercent[id] = it }
                            }
                        }
                        return
                    }
                    (node["nextContinuationData"] as? JsonObject)?.get("continuation")?.str()?.let { continuation = it }
                    (node["continuationCommand"] as? JsonObject)?.get("token")?.str()?.let { continuation = it }
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return Page(items, continuation, watchedPercent)
    }

    /** The resume bar on a tile's thumbnail: how much of the video the account has already watched. */
    private fun watchedPercentOf(tile: JsonObject): Int? {
        val header = (tile["header"] as? JsonObject)?.get("tileHeaderRenderer") as? JsonObject
        val overlays = header?.get("thumbnailOverlays") as? JsonArray ?: return null
        overlays.forEach { overlay ->
            val resume = (overlay as? JsonObject)?.get("thumbnailOverlayResumePlaybackRenderer") as? JsonObject ?: return@forEach
            resume["percentDurationWatched"].str()?.toIntOrNull()?.let { return it.coerceIn(0, 100) }
        }
        return null
    }

    private fun tileToItem(tile: JsonObject): FeedItem? {
        val select = tile["onSelectCommand"] as? JsonObject
        val reel = select?.get("reelWatchEndpoint") as? JsonObject
        val watch = select?.get("watchEndpoint") as? JsonObject
        val contentType = tile["contentType"].str()
        val videoId =
            reel?.get("videoId").str()
                ?: watch?.get("videoId").str()
                ?: tile["contentId"].str()?.takeIf { contentType == null || contentType == "TILE_CONTENT_TYPE_VIDEO" }
                ?: return null
        // Playlists, channels and mixes carry other ids; a video id is always 11 characters.
        if (videoId.length != 11) return null

        val header = (tile["header"] as? JsonObject)?.get("tileHeaderRenderer") as? JsonObject
        val meta = (tile["metadata"] as? JsonObject)?.get("tileMetadataRenderer") as? JsonObject
        val title = meta?.get("title")?.text().orEmpty()
        if (title.isBlank()) return null

        val lines = linesOf(meta)
        val thumbnail =
            ((header?.get("thumbnail") as? JsonObject)?.get("thumbnails") as? JsonArray)
                ?.lastOrNull()
                .let { (it as? JsonObject)?.get("url").str() }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        val overlays = header?.get("thumbnailOverlays") as? JsonArray
        var durationText: String? = null
        var live = false
        overlays?.forEach { overlay ->
            val status = (overlay as? JsonObject)?.get("thumbnailOverlayTimeStatusRenderer") as? JsonObject ?: return@forEach
            durationText = status["text"]?.text()
            if (status["style"].str() == "LIVE") live = true
        }

        val video =
            Video(
                id = videoId,
                title = title,
                channelName = lines.getOrNull(0)?.firstOrNull().orEmpty(),
                channelId = firstChannelId(tile).orEmpty(),
                thumbnailUrl = thumbnail,
                duration = parseDuration(durationText),
                viewCount = parseCount(lines.getOrNull(1)?.firstOrNull()),
                uploadDate = lines.getOrNull(1)?.takeIf { it.size > 1 }?.last().orEmpty(),
                isLive = live,
                isShort = reel != null,
            )
        return if (reel != null) FeedItem.ShortItem(video) else FeedItem.VideoItem(video)
    }

    /** The text of each item of each line under the tile's metadata. */
    private fun linesOf(meta: JsonObject?): List<List<String>> =
        (meta?.get("lines") as? JsonArray).orEmpty().map { line ->
            val items = ((line as? JsonObject)?.get("lineRenderer") as? JsonObject)?.get("items") as? JsonArray
            items
                .orEmpty()
                .mapNotNull { ((it as? JsonObject)?.get("lineItemRenderer") as? JsonObject)?.get("text")?.text() }
                .filter { it.isNotBlank() }
        }

    /** The first channel id (UC...) anywhere under the tile, e.g. in its long-press menu. */
    private fun firstChannelId(node: JsonElement): String? =
        when (node) {
            is JsonObject -> {
                val id = (node["browseEndpoint"] as? JsonObject)?.get("browseId").str()?.takeIf { it.startsWith("UC") }
                id ?: node.values.firstNotNullOfOrNull(::firstChannelId)
            }
            is JsonArray -> node.firstNotNullOfOrNull(::firstChannelId)
            else -> null
        }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonElement.text(): String? {
        val obj = this as? JsonObject ?: return str()
        obj["simpleText"].str()?.let { return it }
        val runs = obj["runs"] as? JsonArray ?: return null
        return runs.joinToString("") { (it as? JsonObject)?.get("text").str().orEmpty() }.takeIf { it.isNotBlank() }
    }

    /** "12:34" or "1:02:03" in seconds; 0 when absent or not a clock (LIVE, SHORTS). */
    internal fun parseDuration(text: String?): Int {
        val parts = text?.trim()?.split(':') ?: return 0
        if (parts.size !in 2..3 || parts.any { it.toIntOrNull() == null }) return 0
        return parts.fold(0) { total, part -> total * 60 + part.toInt() }
    }

    /** "1.2M views" / "845K watching" / "1,234 views" as a number; 0 when there is none. */
    internal fun parseCount(text: String?): Long {
        val match = Regex("""([\d.,]+)\s*([KkMmBb])?""").find(text.orEmpty()) ?: return 0L
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0L
        val scale =
            when (match.groupValues[2].uppercase()) {
                "K" -> 1_000.0
                "M" -> 1_000_000.0
                "B" -> 1_000_000_000.0
                else -> 1.0
            }
        return (number * scale).toLong()
    }
}
