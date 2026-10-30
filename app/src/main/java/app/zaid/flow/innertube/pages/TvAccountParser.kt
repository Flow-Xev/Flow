package app.zaid.flow.innertube.pages

import app.zaid.flow.data.model.Channel
import app.zaid.flow.innertube.models.AccountInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the two account-shaped answers the YouTube TV client gives: who the account is (its accounts list) and
 * which channels it follows (the tab strip of the subscriptions page).
 *
 * Both walks are structural, so a change in how the TV app nests these does not stop them finding the entries.
 */
object TvAccountParser {
    /** The account's own entry in `account/accounts_list`: the selected one, else the first. Null when the list is empty. */
    fun parseAccountInfo(root: JsonObject): AccountInfo? {
        val items = ArrayList<JsonObject>()

        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node["accountItem"] as? JsonObject)?.let { items += it }
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        val item =
            items.firstOrNull { (it["isSelected"] as? JsonPrimitive)?.booleanOrNull == true }
                ?: items.firstOrNull()
                ?: return null
        val name = item["accountName"].text() ?: return null
        return AccountInfo(
            name = name,
            email = item["accountByline"].text()?.takeIf { it.contains('@') },
            channelHandle = item["channelHandle"].text(),
            thumbnailUrl = lastThumbnail(item["accountPhoto"]),
        )
    }

    /**
     * The channels in the TV subscriptions page. Each followed channel is a tab of the page's left strip
     * (`tabRenderer` with a UC browse id); channel tiles are read too, for a layout that lists them as a grid.
     */
    fun parseSubscribedChannels(root: JsonObject): List<Channel> {
        val channels = LinkedHashMap<String, Channel>()

        fun add(
            id: String?,
            name: String?,
            thumbnail: String?,
        ) {
            if (id.isNullOrBlank() || !id.startsWith("UC") || name.isNullOrBlank()) return
            channels.putIfAbsent(
                id,
                Channel(id = id, name = name, thumbnailUrl = thumbnail.orEmpty(), subscriberCount = 0L, isSubscribed = true),
            )
        }

        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node["tabRenderer"] as? JsonObject)?.let { tab ->
                        val id = browseId(tab["endpoint"])
                        add(id, tab["title"].text(), lastThumbnail(tab["thumbnail"]))
                    }
                    (node["tileRenderer"] as? JsonObject)?.let { tile ->
                        if (tile["contentType"].text() == "TILE_CONTENT_TYPE_CHANNEL") {
                            val meta = ((tile["metadata"] as? JsonObject)?.get("tileMetadataRenderer")) as? JsonObject
                            val header = ((tile["header"] as? JsonObject)?.get("tileHeaderRenderer")) as? JsonObject
                            add(
                                browseId(tile["onSelectCommand"]) ?: tile["contentId"].text(),
                                meta?.get("title").text(),
                                lastThumbnail(header?.get("thumbnail")),
                            )
                        }
                    }
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return channels.values.toList()
    }

    private fun browseId(endpoint: JsonElement?): String? = ((endpoint as? JsonObject)?.get("browseEndpoint") as? JsonObject)?.get("browseId").text()

    private fun lastThumbnail(node: JsonElement?): String? =
        (((node as? JsonObject)?.get("thumbnails") as? JsonArray)?.lastOrNull() as? JsonObject)?.get("url").text()

    private fun JsonElement?.text(): String? {
        if (this is JsonPrimitive) return contentOrNull?.takeIf { it.isNotBlank() }
        val obj = this as? JsonObject ?: return null
        (obj["simpleText"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj["runs"] as? JsonArray ?: return null
        return runs
            .joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
            .takeIf { it.isNotBlank() }
    }
}
