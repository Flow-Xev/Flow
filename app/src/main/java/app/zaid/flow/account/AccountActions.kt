package app.zaid.flow.account

import app.zaid.flow.data.model.Comment
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.pages.toPostedComment
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * What a signed-in account needs to act on YouTube: the per-video token that posts a comment and the
 * per-video token that sends "Not interested". Both are handed out inside responses the app already
 * fetches, so they are remembered as those pass by instead of costing a request of their own.
 */
object AccountActions {
    private const val MAX_ENTRIES = 600
    private val commentParams = ConcurrentHashMap<String, String>()
    private val replyParams = ConcurrentHashMap<String, String>()
    private val notInterestedTokens = ConcurrentHashMap<String, String>()
    private val dontRecommendChannelTokens = ConcurrentHashMap<String, String>()
    private val editParams = ConcurrentHashMap<String, String>()
    private val deleteTokens = ConcurrentHashMap<String, String>()
    private val voteTokens = ConcurrentHashMap<String, CommentVoteTokens>()
    private val historyRemoveTokens = ConcurrentHashMap<String, String>()

    /** The feedback token that removes [videoId] from YouTube watch history, if its history entry was loaded. */
    fun historyRemoveToken(videoId: String): String? = historyRemoveTokens[videoId]

    /** The four like/dislike action tokens YouTube hands out for one comment; any may be missing. */
    data class CommentVoteTokens(
        val like: String? = null,
        val unlike: String? = null,
        val dislike: String? = null,
        val undislike: String? = null,
    )

    enum class CommentVote { LIKE, UNLIKE, DISLIKE, UNDISLIKE }

    /**
     * Remembers the like/dislike action tokens in a comments response, keyed by comment id. They sit under
     * the like and dislike buttons' commands (the toggled variant undoes the vote), so the path to each
     * `performCommentActionEndpoint` says which action it is. No request of its own is needed.
     */
    fun rememberCommentVoteActions(root: JsonElement) {
        fun walk(
            node: JsonElement,
            path: String,
        ) {
            when (node) {
                is JsonObject -> {
                    (node["performCommentActionEndpoint"] as? JsonObject)?.let { endpoint ->
                        val token = (endpoint["action"] as? JsonPrimitive)?.contentOrNull
                        val p = path.lowercase()
                        val undo = "toggled" in p || "undo" in p || "unlike" in p || "undislike" in p
                        val kind =
                            when {
                                "dislike" in p -> if (undo) CommentVote.UNDISLIKE else CommentVote.DISLIKE
                                "like" in p -> if (undo) CommentVote.UNLIKE else CommentVote.LIKE
                                else -> null
                            }
                        val id = token?.let(::commentIdOf)
                        if (token != null && kind != null && id != null) {
                            val cur = voteTokens[id] ?: CommentVoteTokens()
                            if (voteTokens.size >= MAX_ENTRIES * 2) voteTokens.clear()
                            voteTokens[id] =
                                when (kind) {
                                    CommentVote.LIKE -> cur.copy(like = token)
                                    CommentVote.UNLIKE -> cur.copy(unlike = token)
                                    CommentVote.DISLIKE -> cur.copy(dislike = token)
                                    CommentVote.UNDISLIKE -> cur.copy(undislike = token)
                                }
                        }
                    }
                    node.forEach { (k, v) -> walk(v, "$path/$k") }
                }

                is JsonArray -> {
                    node.forEach { walk(it, path) }
                }

                else -> {
                    Unit
                }
            }
        }
        walk(root, "")
    }

    /** Runs one action token as the signed-in account (a post like, a poll vote); the token says what it does. */
    suspend fun sendAction(token: String): Result<Unit> =
        runCatching {
            val response = YouTube.innerTube.accountCommentAction(token)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            check(!response.bodyAsText().contains("\"errors\"")) { "YouTube rejected that" }
        }

    /** True when YouTube gave this session a like action for the comment, i.e. voting can work. */
    fun canVoteComment(commentId: String): Boolean = lookup(voteTokens, commentId) { it.like ?: it.unlike } != null

    /** Sends the vote to YouTube as the signed-in account. */
    suspend fun voteComment(
        commentId: String,
        vote: CommentVote,
    ): Result<Unit> =
        runCatching {
            val tokens = lookup(voteTokens, commentId) { true } ?: error("This comment can't be rated")
            val token =
                when (vote) {
                    CommentVote.LIKE -> tokens.like
                    CommentVote.UNLIKE -> tokens.unlike
                    CommentVote.DISLIKE -> tokens.dislike
                    CommentVote.UNDISLIKE -> tokens.undislike
                } ?: error("This comment can't be rated")
            val response = YouTube.innerTube.accountCommentAction(token)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            check(!response.bodyAsText().contains("\"errors\"")) { "YouTube rejected the vote" }
        }

    private fun <T> lookup(
        map: ConcurrentHashMap<String, T>,
        commentId: String,
        accept: (T) -> Any?,
    ): T? =
        (
            map[commentId] ?: map.entries
                .firstOrNull { (k, _) ->
                    k.startsWith(commentId) && k.length <= commentId.length + 3
                }?.value
        )?.takeIf {
            accept(it) !=
                null
        }

    fun rememberCommentParams(
        videoId: String,
        root: JsonElement,
    ) {
        findString(root, "createCommentParams")?.let { put(commentParams, videoId, it) }
    }

    /**
     * Remembers every "reply" token in a comments response. The token carries the id of the comment
     * it answers inside its encoded bytes, so each one is filed under that id.
     */
    fun rememberReplyParams(root: JsonElement) {
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node["createReplyParams"] as? JsonPrimitive)?.contentOrNull?.let { token ->
                        commentIdOf(token)?.let { put(replyParams, it, token) }
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
    }

    /**
     * Remembers the edit and delete tokens in a comments response. YouTube only puts them in the menu of
     * the signed-in account's own comments, so having one for a comment id is what says it can be changed.
     */
    fun rememberOwnCommentActions(root: JsonElement) {
        fun deleteItem(node: JsonObject): Boolean {
            val icon = ((node["icon"] as? JsonObject)?.get("iconType") as? JsonPrimitive)?.contentOrNull
            return icon == "DELETE" || icon == "DELETE_FOREVER" || icon == "REMOVE"
        }

        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node["updateCommentParams"] as? JsonPrimitive)?.contentOrNull?.let { token ->
                        commentIdOf(token)?.let { put(editParams, it, token) }
                    }
                    if (deleteItem(node)) {
                        val endpoint = findObject(node, "performCommentActionEndpoint")
                        (endpoint?.get("action") as? JsonPrimitive)?.contentOrNull?.let { token ->
                            commentIdOf(token)?.let { put(deleteTokens, it, token) }
                        }
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
    }

    private fun findObject(
        node: JsonElement,
        key: String,
    ): JsonObject? =
        when (node) {
            is JsonObject -> {
                (node[key] as? JsonObject) ?: node.values.firstNotNullOfOrNull { findObject(it, key) }
            }

            is JsonArray -> {
                node.firstNotNullOfOrNull { findObject(it, key) }
            }

            else -> {
                null
            }
        }

    private fun lookup(
        map: ConcurrentHashMap<String, String>,
        commentId: String,
    ): String? = map[commentId] ?: map.entries.firstOrNull { (k, _) -> k.startsWith(commentId) && k.length <= commentId.length + 3 }?.value

    fun canEditComment(commentId: String): Boolean = lookup(editParams, commentId) != null

    fun canDeleteComment(commentId: String): Boolean = lookup(deleteTokens, commentId) != null

    suspend fun editComment(
        commentId: String,
        text: String,
    ): Result<Unit> =
        runCatching {
            val params = lookup(editParams, commentId) ?: error("This comment can't be edited")
            val response = YouTube.innerTube.accountUpdateComment(params, text)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            check(!response.bodyAsText().contains("\"errors\"")) { "YouTube rejected the edit" }
        }

    suspend fun deleteComment(commentId: String): Result<Unit> =
        runCatching {
            val token = lookup(deleteTokens, commentId) ?: error("This comment can't be deleted")
            val response = YouTube.innerTube.accountCommentAction(token)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            check(!response.bodyAsText().contains("\"errors\"")) { "YouTube rejected the delete" }
            deleteTokens.remove(commentId)
            editParams.remove(commentId)
        }

    private val commentIdRegex = Regex("Ug[A-Za-z0-9_-]{18,}(?:\\.[A-Za-z0-9_-]+)?")

    private fun commentIdOf(token: String): String? =
        runCatching {
            val clean =
                java.net.URLDecoder
                    .decode(token, "UTF-8")
                    .replace('-', '+')
                    .replace('_', '/')
            val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            commentIdRegex.find(String(bytes, Charsets.ISO_8859_1))?.value
        }.getOrNull()

    private fun replyParamsFor(commentId: String): String? =
        replyParams[commentId]
            ?: replyParams.entries.firstOrNull { (k, _) -> k.startsWith(commentId) && k.length <= commentId.length + 3 }?.value

    /** Walks a feed response for every video card that carries a "Not interested" menu entry. */
    fun rememberFeedbackTokens(root: JsonElement) {
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val id = (node["videoId"] as? JsonPrimitive)?.contentOrNull
                    val menu = node["menu"]
                    if (id != null && menu != null) {
                        notInterestedToken(menu)?.let { put(notInterestedTokens, id, it) }
                        dontRecommendChannelToken(menu)?.let { put(dontRecommendChannelTokens, id, it) }
                        findHistoryRemoveToken(menu)?.let { put(historyRemoveTokens, id, it) }
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
    }

    /** A history entry's menu item that deletes it carries a feedback token (playlist removals use another endpoint). */
    private fun findHistoryRemoveToken(menu: JsonElement): String? {
        var found: String? = null

        fun walk(node: JsonElement) {
            if (found != null) return
            when (node) {
                is JsonObject -> {
                    val icon = ((node["icon"] as? JsonObject)?.get("iconType") as? JsonPrimitive)?.contentOrNull
                    if (icon == "DELETE" || icon == "REMOVE") {
                        found = findString(node, "feedbackToken")
                        if (found != null) return
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
        walk(menu)
        return found
    }

    /** The menu entry "Don't recommend channel" (a circled minus) and the feedback token behind it. */
    private fun dontRecommendChannelToken(menu: JsonElement): String? {
        var found: String? = null

        fun walk(node: JsonElement) {
            if (found != null) return
            when (node) {
                is JsonObject -> {
                    val icon = ((node["icon"] as? JsonObject)?.get("iconType") as? JsonPrimitive)?.contentOrNull
                    if (icon == "REMOVE_CIRCLE") {
                        found = findString(node, "feedbackToken")
                        if (found != null) return
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
        walk(menu)
        return found
    }

    private fun notInterestedToken(menu: JsonElement): String? {
        var found: String? = null

        fun walk(node: JsonElement) {
            if (found != null) return
            when (node) {
                is JsonObject -> {
                    val icon = node["icon"]?.let { (it as? JsonObject)?.get("iconType") as? JsonPrimitive }?.contentOrNull
                    if (icon == "NOT_INTERESTED") {
                        found = findString(node, "feedbackToken")
                        if (found != null) return
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
        walk(menu)
        return found
    }

    private fun findString(
        node: JsonElement,
        key: String,
    ): String? =
        when (node) {
            is JsonObject -> {
                (node[key] as? JsonPrimitive)?.contentOrNull
                    ?: node.values.firstNotNullOfOrNull { findString(it, key) }
            }

            is JsonArray -> {
                node.firstNotNullOfOrNull { findString(it, key) }
            }

            else -> {
                null
            }
        }

    private fun put(
        map: ConcurrentHashMap<String, String>,
        key: String,
        value: String,
    ) {
        if (map.size >= MAX_ENTRIES) map.keys.take(MAX_ENTRIES / 4).forEach(map::remove)
        map[key] = value
    }

    suspend fun postComment(
        videoId: String,
        text: String,
    ): Result<Unit> = postCommentForResult(videoId, text).map { }

    /**
     * Posts a comment and returns it as YouTube recorded it. The response also carries the new comment's
     * edit and delete tokens, which are remembered here so it can be changed straight away.
     */
    suspend fun postCommentForResult(
        videoId: String,
        text: String,
    ): Result<Comment?> =
        runCatching {
            val params = commentParams[videoId] ?: loadCommentParams(videoId)
            val response = YouTube.innerTube.accountCreateComment(params, text)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val body = response.bodyAsText()
            check(!body.contains("\"errors\"")) { "YouTube rejected the comment" }
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return@runCatching null
            rememberOwnCommentActions(root)
            rememberReplyParams(root)
            runCatching { root.toPostedComment(videoId) }.getOrNull()
        }

    /** Fetches the watch page and the first comments page as the account when the token was not seen yet. */
    private suspend fun loadCommentParams(videoId: String): String {
        val next = YouTube.watchNextJson(videoId).getOrThrow()
        rememberCommentParams(videoId, next)
        commentParams[videoId]?.let { return it }
        YouTube.commentsContinuation(next)?.let { YouTube.comments(it, videoId).getOrThrow() }
        return commentParams[videoId] ?: error("YouTube did not offer comments for this video (check you are signed in)")
    }

    suspend fun postReply(
        commentId: String,
        text: String,
    ): Result<Unit> =
        runCatching {
            val params = replyParamsFor(commentId) ?: error("Replies are not available for this comment")
            val response = YouTube.innerTube.accountCreateReply(params, text)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val body = response.bodyAsText()
            check(!body.contains("\"errors\"")) { "YouTube rejected the reply" }
            runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let {
                rememberOwnCommentActions(it)
                rememberReplyParams(it)
            }
        }

    suspend fun setSubscribed(
        channelId: String,
        subscribe: Boolean,
    ): Result<Unit> =
        runCatching {
            val response = YouTube.innerTube.accountSubscribe(channelId, subscribe)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        }

    /** What YouTube said to "Don't recommend channel": [undoToken] reverses it, when YouTube sent one. */
    data class DontRecommendResult(
        val undoToken: String?,
    )

    private val undoTokenRegex = Regex("\"undo(?:Feedback)?Token\"\\s*:\\s*\"([^\"]+)\"")

    /** Null when YouTube never offered "Don't recommend channel" for this video (it was not in a signed-in feed). */
    suspend fun dontRecommendChannel(videoId: String): Result<DontRecommendResult?> =
        runCatching {
            val token = dontRecommendChannelTokens.remove(videoId) ?: return@runCatching null
            val response = YouTube.innerTube.accountFeedback(token)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val body = response.bodyAsText()
            check(!body.contains("\"errors\"")) { "YouTube rejected that" }
            DontRecommendResult(undoTokenRegex.find(body)?.groupValues?.get(1))
        }

    /** Sends the undo token YouTube handed back for an earlier feedback action. */
    suspend fun undoFeedback(undoToken: String): Result<Unit> =
        runCatching {
            val response = YouTube.innerTube.accountFeedback(undoToken)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        }

    /** False when YouTube never gave this video a "Not interested" option (it was not in a signed-in feed). */
    suspend fun notInterested(videoId: String): Result<Boolean> =
        runCatching {
            val token = notInterestedTokens.remove(videoId) ?: return@runCatching false
            val response = YouTube.innerTube.accountFeedback(token)
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            true
        }
}
