package app.zaid.flow.account

import android.util.Log
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.utils.cipher.CipherDeobfuscator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Reports what happens in the app to the signed-in Google account, so its watch history, likes and
 * recommendations on YouTube follow what was actually watched here.
 *
 * Every entry point is a no-op when nobody is signed in, and a failed request is logged and dropped:
 * reporting must never get in the way of playing or liking. It has no Android context on purpose, so
 * the context-keyed stores can call it from where they already write.
 */
object YouTubeAccountReporter {
    private const val TAG = "YouTubeAccountReporter"
    private const val WATCHTIME_INTERVAL_MS = 30_000L
    private const val MAX_SESSIONS = 40
    private const val SIGNATURE_TIMESTAMP_WAIT_MS = 10_000L
    private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, WatchSession>()

    private class WatchSession {
        val cpn = (1..16).map { CPN_ALPHABET[Random.nextInt(CPN_ALPHABET.length)] }.joinToString("")
        val lock = Mutex()
        var tracking: PlayerResponse.PlaybackTracking? = null
        var started = false
        var retryAfterMs = 0L
        var lastPingPositionMs = 0L
    }

    private val signedIn: Boolean
        get() = runCatching { YouTube.innerTube.isSignedIn }.getOrDefault(false)

    // ── Likes ────────────────────────────────────────────────────────────────

    fun reportLike(videoId: String) = send("like", videoId) { YouTube.rateVideo("like/like", videoId) }

    fun reportDislike(videoId: String) = send("dislike", videoId) { YouTube.rateVideo("like/dislike", videoId) }

    /** Clears a like or dislike; YouTube uses the one endpoint for both. */
    fun reportRemoveLike(videoId: String) = send("remove rating", videoId) { YouTube.rateVideo("like/removelike", videoId) }

    // ── Subscriptions, "Not interested", comments ────────────────────────────

    fun reportSubscribe(
        channelId: String,
        subscribe: Boolean,
    ) = sendAction(if (subscribe) "subscribe" else "unsubscribe", channelId) { AccountActions.setSubscribed(channelId, subscribe) }

    fun reportNotInterested(videoId: String) = sendAction("not interested", videoId) { AccountActions.notInterested(videoId) }

    /** Posts [text] as the account; the caller decides what to show on failure. */
    suspend fun postComment(
        videoId: String,
        text: String,
    ): Result<Unit> =
        if (!signedIn) Result.failure(IllegalStateException("Sign in to comment")) else AccountActions.postComment(videoId, text)

    /** [postComment] that also returns the comment as YouTube recorded it, when the response described it. */
    suspend fun postCommentForResult(
        videoId: String,
        text: String,
    ): Result<app.zaid.flow.data.model.Comment?> =
        if (!signedIn) Result.failure(IllegalStateException("Sign in to comment")) else AccountActions.postCommentForResult(videoId, text)

    suspend fun postReply(
        commentId: String,
        text: String,
    ): Result<Unit> =
        if (!signedIn) Result.failure(IllegalStateException("Sign in to reply")) else AccountActions.postReply(commentId, text)

    suspend fun editComment(
        commentId: String,
        text: String,
    ): Result<Unit> =
        if (!signedIn) Result.failure(IllegalStateException("Sign in to edit")) else AccountActions.editComment(commentId, text)

    suspend fun deleteComment(commentId: String): Result<Unit> =
        if (!signedIn) Result.failure(IllegalStateException("Sign in to delete")) else AccountActions.deleteComment(commentId)

    private fun sendAction(
        what: String,
        id: String,
        call: suspend () -> Result<*>,
    ) {
        if (!signedIn || id.isBlank()) return
        scope.launch {
            call()
                .onSuccess { Log.d(TAG, "Sent $what for $id") }
                .onFailure { Log.w(TAG, "Could not send $what for $id: ${it.message}") }
        }
    }

    private fun send(
        what: String,
        videoId: String,
        call: suspend () -> Result<*>,
    ) {
        if (!signedIn || videoId.isBlank()) return
        scope.launch {
            call()
                .onSuccess { Log.d(TAG, "Sent $what for $videoId") }
                .onFailure { Log.w(TAG, "Could not send $what for $videoId: ${it.message}") }
        }
    }

    // ── Watching ─────────────────────────────────────────────────────────────

    /**
     * Called as playback progress is saved. The first call for a video registers the view with the
     * account (which is what puts it in its history); later calls add watch time about every 30 s.
     */
    fun onWatchProgress(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (AccountIncognito.enabled || !signedIn || videoId.isBlank()) return
        if (sessions.size >= MAX_SESSIONS) sessions.clear()
        val session = sessions.getOrPut(videoId) { WatchSession() }
        scope.launch {
            session.lock.withLock {
                if (!session.started) {
                    if (System.currentTimeMillis() < session.retryAfterMs) return@withLock
                    // Same timestamp the real player sends; without it the response is UNPLAYABLE
                    // and carries no tracking URLs.
                    val sts = withTimeoutOrNull(SIGNATURE_TIMESTAMP_WAIT_MS) { CipherDeobfuscator.ensureSignatureTimestamp() }
                    val tracking = YouTube.playbackTrackingFor(videoId, sts)
                    val playbackUrl = tracking?.videostatsPlaybackUrl?.baseUrl
                    if (playbackUrl == null) {
                        // Try again on a later progress save instead of losing the whole watch.
                        session.retryAfterMs = System.currentTimeMillis() + 60_000L
                        Log.w(TAG, "No playback tracking for $videoId; will retry")
                        return@withLock
                    }
                    session.started = true
                    session.tracking = tracking
                    session.lastPingPositionMs = positionMs
                    YouTube
                        .pingPlaybackStats(playbackUrl, session.cpn, mapOf("cmt" to seconds(positionMs), "len" to seconds(durationMs)))
                        .onSuccess { Log.i(TAG, "Reported watch of $videoId to the account") }
                        .onFailure {
                            Log.w(TAG, "Playback ping failed for $videoId: ${it.message}")
                            session.started = false
                            session.retryAfterMs = System.currentTimeMillis() + 60_000L
                        }
                    return@withLock
                }
                if (positionMs - session.lastPingPositionMs >= WATCHTIME_INTERVAL_MS) {
                    pingWatchTime(videoId, session, positionMs, durationMs, final = false)
                }
            }
        }
    }

    /** Called when a video plays to its end, so the account records it as finished. */
    fun onWatchCompleted(
        videoId: String,
        durationMs: Long,
    ) {
        if (AccountIncognito.enabled || !signedIn) return
        val session = sessions[videoId] ?: return
        scope.launch {
            session.lock.withLock {
                if (session.started) pingWatchTime(videoId, session, durationMs, durationMs, final = true)
            }
        }
    }

    private suspend fun pingWatchTime(
        videoId: String,
        session: WatchSession,
        positionMs: Long,
        durationMs: Long,
        final: Boolean,
    ) {
        val url = session.tracking?.videostatsWatchtimeUrl?.baseUrl ?: return
        val extra =
            buildMap {
                put("cmt", seconds(positionMs))
                put("len", seconds(durationMs))
                put("st", seconds(session.lastPingPositionMs))
                put("et", seconds(positionMs))
                put("state", if (final) "paused" else "playing")
                if (final) put("final", "1")
            }
        session.lastPingPositionMs = positionMs
        YouTube
            .pingPlaybackStats(url, session.cpn, extra)
            .onFailure { Log.w(TAG, "Watch-time ping failed for $videoId: ${it.message}") }
    }

    private fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.3f", ms.coerceAtLeast(0L) / 1000.0)
}
