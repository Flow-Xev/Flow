package app.zaid.flow.innertube.pages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What YouTube's `updated_metadata` endpoint reports for a stream on air: how many people are
 * watching right now and the like button's text. Either can be missing.
 */
data class LiveStats(
    val viewers: Long?,
    val likeText: String?,
)

/** Reads the actions of an `updated_metadata` response; unknown actions are ignored. */
internal fun parseLiveStats(root: JsonObject): LiveStats {
    val actions = root["actions"] as? JsonArray ?: return LiveStats(null, null)
    var viewers: Long? = null
    var like: String? = null
    for (action in actions) {
        val obj = action as? JsonObject ?: continue
        (obj["updateViewershipAction"] as? JsonObject)
            ?.let { it.obj("viewCount")?.obj("videoViewCountRenderer") }
            ?.let { renderer ->
                viewers =
                    renderer["originalViewCount"].text()?.toLongOrNull()
                        ?: renderer
                            .obj("viewCount")
                            ?.get("simpleText")
                            .text()
                            ?.digitsToLong()
            }
        (obj["updateToggleButtonTextAction"] as? JsonObject)?.let { toggle ->
            if (toggle["buttonId"].text()?.contains("LIKE", ignoreCase = true) == true) {
                like = toggle.obj("defaultText")?.get("simpleText").text()
            }
        }
    }
    return LiveStats(viewers, like)
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun String.digitsToLong(): Long? = filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toLongOrNull()
