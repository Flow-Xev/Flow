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

/**
 * The signed-in account's YouTube watch history: removing one video, clearing it all, pausing it.
 *
 * Each action is a `feedback` call with a token YouTube embeds in its own history page. Removing a
 * video uses the token seen when that video was listed in a loaded history page (see
 * [AccountActions.rememberFeedbackTokens]); clear and pause read the history page once and cache
 * the tokens, so a change costs at most one extra request.
 */
object AccountHistory {
    enum class Control { CLEAR_ALL, PAUSE, RESUME }

    private var controlTokens: Map<Control, String>? = null

    /** Removes [videoId] from the account's YouTube history; fails if that entry was never loaded this session. */
    suspend fun remove(videoId: String): Result<Unit> =
        runCatching {
            val token = AccountActions.historyRemoveToken(videoId) ?: error("Not in the loaded YouTube history")
            send(token)
        }

    suspend fun clearAll(): Result<Unit> = perform(Control.CLEAR_ALL)

    suspend fun setPaused(paused: Boolean): Result<Unit> = perform(if (paused) Control.PAUSE else Control.RESUME)

    private suspend fun perform(control: Control): Result<Unit> =
        runCatching {
            check(YouTube.innerTube.isSignedIn) { "Sign in to change YouTube history" }
            val tokens = controlTokens ?: load().also { controlTokens = it }
            val token = tokens[control] ?: error("YouTube didn't offer this history option for the account")
            send(token)
            // Pause and resume swap each other's tokens, so the cache is stale after either.
            if (control != Control.CLEAR_ALL) controlTokens = null
        }

    private suspend fun send(token: String) {
        val response = YouTube.innerTube.accountFeedback(token)
        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        check(!response.bodyAsText().contains("\"errors\"")) { "YouTube rejected the change" }
    }

    /** Reads the history page and sorts its feedback tokens by what the control says and its icon. */
    private suspend fun load(): Map<Control, String> {
        val response = YouTube.innerTube.accountBrowse(browseId = "FEhistory")
        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        val found = mutableMapOf<Control, String>()

        fun texts(
            node: JsonElement,
            out: StringBuilder,
        ) {
            when (node) {
                is JsonObject -> {
                    node.forEach { (k, v) ->
                        if (k == "simpleText" || k == "text" || k == "content" || k == "label" || k == "iconType") {
                            (v as? JsonPrimitive)?.contentOrNull?.let { out.append(it.lowercase()).append(' ') }
                        }
                        texts(v, out)
                    }
                }

                is JsonArray -> {
                    node.forEach { texts(it, out) }
                }

                else -> {
                    Unit
                }
            }
        }

        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val token =
                        ((node["feedbackEndpoint"] as? JsonObject)?.get("feedbackToken") as? JsonPrimitive)?.contentOrNull
                    if (token != null) {
                        val sb = StringBuilder()
                        texts(node, sb)
                        val label = sb.toString()
                        val control =
                            when {
                                "clear all watch history" in label || "clear_all" in label -> Control.CLEAR_ALL
                                "pause watch history" in label || "turn off watch history" in label -> Control.PAUSE
                                "turn on watch history" in label || "resume watch history" in label -> Control.RESUME
                                else -> null
                            }
                        if (control != null) found.putIfAbsent(control, token)
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
        return found
    }
}
