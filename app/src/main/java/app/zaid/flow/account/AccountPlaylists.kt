package app.zaid.flow.account

import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.models.body.AccountPlaylistAction
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The signed-in account's real YouTube playlists (Watch later included), as the site's "Save to"
 * menu offers them. One request lists them with a per-video flag; edits are one request each.
 */
object AccountPlaylists {
    data class Option(
        val id: String,
        val title: String,
        val containsVideo: Boolean,
    )

    suspend fun options(videoId: String): Result<List<Option>> =
        runCatching {
            val response = YouTube.innerTube.accountGetAddToPlaylist(videoId)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val out = mutableListOf<Option>()

            fun walk(node: JsonElement) {
                when (node) {
                    is JsonObject -> {
                        (node["playlistAddToOptionRenderer"] as? JsonObject)?.let { r ->
                            val id = (r["playlistId"] as? JsonPrimitive)?.contentOrNull ?: return@let
                            val title = text(r["title"]).orEmpty()
                            val contains = (r["containsSelectedVideos"] as? JsonPrimitive)?.contentOrNull == "ALL"
                            out += Option(id, title, contains)
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
            out.distinctBy { it.id }
        }

    suspend fun add(
        playlistId: String,
        videoId: String,
    ): Result<Unit> = edit(playlistId, AccountPlaylistAction("ACTION_ADD_VIDEO", addedVideoId = videoId))

    suspend fun remove(
        playlistId: String,
        videoId: String,
    ): Result<Unit> = edit(playlistId, AccountPlaylistAction("ACTION_REMOVE_VIDEO_BY_VIDEO_ID", removedVideoId = videoId))

    /** Creates a private playlist holding [videoId]; returns its id. */
    suspend fun create(
        title: String,
        videoId: String,
    ): Result<String> =
        runCatching {
            val response = YouTube.innerTube.accountCreatePlaylist(title, videoId)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val root = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            (root?.get("playlistId") as? JsonPrimitive)?.contentOrNull ?: error("YouTube didn't return the new playlist")
        }

    private suspend fun edit(
        playlistId: String,
        action: AccountPlaylistAction,
    ): Result<Unit> =
        runCatching {
            val response = YouTube.innerTube.accountEditPlaylist(playlistId, action)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val root = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            check((root?.get("status") as? JsonPrimitive)?.contentOrNull != "STATUS_FAILED") { "YouTube rejected the change" }
        }

    private fun text(node: JsonElement?): String? {
        val obj = node as? JsonObject ?: return null
        (obj["simpleText"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        return (obj["runs"] as? JsonArray)?.joinToString(
            "",
        ) { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
    }
}
