package app.zaid.flow.account

import app.zaid.flow.innertube.YouTube
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Syncs a channel's notification bell (All / Personalized / None) to the account.
 *
 * The three option tokens live in the channel page's subscribe menu, so the first change for a channel
 * costs one browse request to read them; later changes reuse the cache and cost only the change itself.
 */
object AccountChannelBell {
    enum class Level { ALL, PERSONALIZED, NONE }

    private val tokens = ConcurrentHashMap<String, Map<Level, String>>()

    suspend fun set(
        channelId: String,
        level: Level,
    ): Result<Unit> =
        runCatching {
            check(YouTube.innerTube.isSignedIn) { "Sign in to sync notifications" }
            val map = tokens[channelId] ?: load(channelId).also { tokens[channelId] = it }
            val token = map[level] ?: error("YouTube offered no such notification option for this channel")
            val response = YouTube.innerTube.accountModifyChannelPreference(token)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        }

    private suspend fun load(channelId: String): Map<Level, String> {
        val response = YouTube.innerTube.accountBrowse(browseId = channelId)
        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        val found = mutableListOf<Pair<String?, String>>() // icon type, params

        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val params =
                        (((node["serviceEndpoint"] as? JsonObject)?.get("modifyChannelNotificationPreferenceEndpoint")) as? JsonObject)
                            ?.get("params")
                            ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (params != null) {
                        val icon = ((node["icon"] as? JsonObject)?.get("iconType") as? JsonPrimitive)?.contentOrNull
                        found += icon to params
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
        walk(Json.parseToJsonElement(response.bodyAsText()))
        val distinct = found.distinctBy { it.second }
        val order = Level.entries
        return buildMap {
            distinct.forEachIndexed { index, (icon, params) ->
                val level =
                    when (icon) {
                        "NOTIFICATIONS_ACTIVE" -> Level.ALL
                        "NOTIFICATIONS_NONE" -> Level.PERSONALIZED
                        "NOTIFICATIONS_OFF" -> Level.NONE
                        else -> order.getOrNull(index)
                    }
                if (level != null) putIfAbsent(level, params)
            }
        }
    }
}
