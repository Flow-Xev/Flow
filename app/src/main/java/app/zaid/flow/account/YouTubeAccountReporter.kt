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
    private const val WATCHTIME_INTERVAL_MS = 10_000L
    private const val WATCHTIME_LONG_INTERVAL_MS = 30_000L
    private const val EARLY_PINGS = 3
    private const val MAX_SEGMENT_MS = 35_000L
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
        var latestPositionMs = 0L
        var lengthMs = 0L
        var pings = 0
    }

    /** True for any account, web or YouTube TV: likes, subscriptions and watches are sent through whichever client it signed in with. */
    private val signedIn: Boolean
        get() = runCatching { YouTube.innerTube.hasAccount }.getOrDefault(false)

    /**
     * Comments, replies and their edits are sent through whichever client the account signed in with: the web
     * session, or the TV client's bearer token. (Kept under the old name; it is true for any account.)
     */
    private val webSignedIn: Boolean
        get() = signedIn

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
        if (!webSignedIn) Result.failure(IllegalStateException(commentsNeedWeb("comment"))) else AccountActions.postComment(videoId, text)

    /** [postComment] that also returns the comment as YouTube recorded it, when the response described it. */
    suspend fun postCommentForResult(
        videoId: String,
        text: String,
    ): Result<app.zaid.flow.data.model.Comment?> =
        if (!webSignedIn) Result.failure(IllegalStateException(commentsNeedWeb("comment"))) else AccountActions.postCommentForResult(videoId, text)

    suspend fun postReply(
        commentId: String,
        text: String,
    ): Result<Unit> =
        if (!webSignedIn) Result.failure(IllegalStateException(commentsNeedWeb("reply"))) else AccountActions.postReply(commentId, text)

    suspend fun editComment(
        commentId: String,
        text: String,
    ): Result<Unit> =
        if (!webSignedIn) Result.failure(IllegalStateException(commentsNeedWeb("edit"))) else AccountActions.editComment(commentId, text)

    suspend fun deleteComment(commentId: String): Result<Unit> =
        if (!webSignedIn) Result.failure(IllegalStateException(commentsNeedWeb("delete"))) else AccountActions.deleteComment(commentId)

    private fun commentsNeedWeb(verb: String): String =
        "Sign in to $verb"

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
     * account (which is what puts it in its history); later calls add watch time about every
     * [WATCHTIME_INTERVAL_MS].
     */
    fun onWatchProgress(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (!canReport(videoId)) return
        val session = sessionFor(videoId)
        scope.launch {
            session.lock.withLock {
                session.latestPositionMs = positionMs
                if (!ensureStarted(videoId, session, positionMs, durationMs)) return@withLock
                if (positionMs < session.lastPingPositionMs) {
                    // Sought backwards: measure from the new spot so the next segment is not negative.
                    session.lastPingPositionMs = positionMs
                } else if (positionMs - session.lastPingPositionMs >=
                    (if (session.pings < EARLY_PINGS) WATCHTIME_INTERVAL_MS else WATCHTIME_LONG_INTERVAL_MS)
                ) {
                    pingWatchTime(videoId, session, positionMs, durationMs, state = "playing")
                }
            }
        }
    }

    /**
     * Playback stopped at [positionMs] without finishing: the video was left, a Short was swiped
     * away, or the player closed. Sends the watched stretch since the last ping and ends the
     * session, so watching the video again registers as a new view.
     */
    fun onWatchStopped(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
    ) = flush(videoId, positionMs, durationMs, end = true)

    /** The app went to the background: reports where we are now, keeping the session open. */
    fun onWatchPaused(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
    ) = flush(videoId, positionMs, durationMs, end = false)

    /** Called when a video plays to its end, so the account records it as finished. */
    fun onWatchCompleted(
        videoId: String,
        durationMs: Long,
    ) = flush(videoId, durationMs, durationMs, end = true, completed = true)

    /** Playback paused or resumed at [positionMs]; the account gets the stretch watched so far and the new state. */
    fun onPlayStateChanged(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
    ) {
        if (!canReport(videoId) || durationMs <= 0L) return
        val session = sessionFor(videoId)
        scope.launch {
            session.lock.withLock {
                session.latestPositionMs = positionMs
                if (!ensureStarted(videoId, session, positionMs, durationMs)) return@withLock
                if (playing) {
                    // Resuming: nothing was watched while paused, so measure from here.
                    session.lastPingPositionMs = positionMs
                    pingWatchTime(videoId, session, positionMs, durationMs, state = "playing")
                } else {
                    pingWatchTime(videoId, session, positionMs, durationMs, state = "paused")
                }
            }
        }
    }

    /** The viewer jumped from [fromMs] to [toMs]: what played up to the jump is reported, then watching restarts at [toMs]. */
    fun onSeek(
        videoId: String,
        fromMs: Long,
        toMs: Long,
        durationMs: Long,
    ) {
        if (!canReport(videoId) || durationMs <= 0L) return
        val session = sessionFor(videoId)
        scope.launch {
            session.lock.withLock {
                session.latestPositionMs = toMs
                if (!ensureStarted(videoId, session, fromMs, durationMs)) return@withLock
                if (fromMs > session.lastPingPositionMs) {
                    pingWatchTime(videoId, session, fromMs, durationMs, state = "playing")
                }
                session.lastPingPositionMs = toMs
                pingWatchTime(videoId, session, toMs, durationMs, state = "playing")
            }
        }
    }

    private fun flush(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        end: Boolean,
        completed: Boolean = false,
    ) {
        if (!canReport(videoId)) return
        // A stop with no earlier progress is a skip (opened and left within the first save). It still
        // goes to the account, which registers the view and how little of it was watched. Pausing
        // with nothing open has nothing to report.
        val session = if (end) sessionFor(videoId) else sessions[videoId] ?: return
        scope.launch {
            session.lock.withLock {
                // Position 0 means the caller had nothing better than "unknown"; keep the last known one.
                val position = if (positionMs > 0L) positionMs else session.latestPositionMs
                session.latestPositionMs = position
                // A watch shorter than the first save (a Short flicked past) still has to register.
                if (!ensureStarted(videoId, session, position, durationMs)) return@withLock
                if (position != session.lastPingPositionMs || completed) {
                    pingWatchTime(videoId, session, position, durationMs, state = if (end) "paused" else "playing", final = end)
                }
                if (end) sessions.remove(videoId, session)
            }
        }
    }

    private fun canReport(videoId: String) = !AccountIncognito.enabled && signedIn && videoId.isNotBlank()

    private fun sessionFor(videoId: String): WatchSession {
        if (sessions.size >= MAX_SESSIONS && !sessions.containsKey(videoId)) sessions.clear()
        return sessions.getOrPut(videoId) { WatchSession() }
    }

    /** Registers the view with the account the first time; true once it is registered. Call under the session lock. */
    private suspend fun ensureStarted(
        videoId: String,
        session: WatchSession,
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        if (durationMs > 0L) session.lengthMs = durationMs
        if (session.started) return true
        if (System.currentTimeMillis() < session.retryAfterMs) return false
        // Same timestamp the real player sends; without it the response is UNPLAYABLE
        // and carries no tracking URLs.
        val sts = withTimeoutOrNull(SIGNATURE_TIMESTAMP_WAIT_MS) { CipherDeobfuscator.ensureSignatureTimestamp() }
        val tracking = YouTube.playbackTrackingFor(videoId, sts)
        val playbackUrl = tracking?.videostatsPlaybackUrl?.baseUrl
        if (playbackUrl == null) {
            // Try again on a later progress save instead of losing the whole watch.
            session.retryAfterMs = System.currentTimeMillis() + 60_000L
            Log.w(TAG, "No playback tracking for $videoId; will retry")
            return false
        }
        session.tracking = tracking
        // The view starts where this watch began, which is the earliest position we know of, not "now".
        val startMs = minOf(positionMs, session.latestPositionMs.takeIf { it > 0L } ?: positionMs)
        session.lastPingPositionMs = startMs
        val result =
            YouTube.pingPlaybackStats(playbackUrl, session.cpn, mapOf("cmt" to seconds(startMs), "len" to seconds(session.lengthMs)))
        result
            .onSuccess {
                session.started = true
                Log.i(TAG, "Reported watch of $videoId to the account")
            }.onFailure {
                Log.w(TAG, "Playback ping failed for $videoId: ${it.message}")
                session.retryAfterMs = System.currentTimeMillis() + 60_000L
            }
        return session.started
    }

    private suspend fun pingWatchTime(
        videoId: String,
        session: WatchSession,
        positionMs: Long,
        durationMs: Long,
        state: String,
        final: Boolean = false,
    ) {
        val url = session.tracking?.videostatsWatchtimeUrl?.baseUrl ?: return
        if (durationMs > 0L) session.lengthMs = durationMs
        // A long gap between pings means a seek or a pause, not watching: only count the recent stretch.
        val startMs = maxOf(session.lastPingPositionMs, positionMs - MAX_SEGMENT_MS)
        val extra =
            buildMap {
                put("cmt", seconds(positionMs))
                put("len", seconds(session.lengthMs))
                put("st", seconds(startMs))
                put("et", seconds(positionMs))
                put("state", state)
                if (final) put("final", "1")
            }
        session.lastPingPositionMs = positionMs
        session.pings++
        YouTube
            .pingPlaybackStats(url, session.cpn, extra)
            .onFailure { Log.w(TAG, "Watch-time ping failed for $videoId: ${it.message}") }
    }

    private fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.3f", ms.coerceAtLeast(0L) / 1000.0)
}
