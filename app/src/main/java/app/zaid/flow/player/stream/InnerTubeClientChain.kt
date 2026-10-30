package app.zaid.flow.player.stream

import android.util.Log
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.models.YouTubeClient
import app.zaid.flow.innertube.models.YouTubeLocale
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.player.error.PlayerDiagnostics
import app.zaid.flow.utils.FlowTrace
import app.zaid.flow.utils.cipher.CipherDeobfuscator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Every InnerTube client Flow knows how to speak, in the order a *metadata* chain should try them,
 * plus the shared health bookkeeping that keeps a broken client from being asked again and again.
 *
 * Stream playback has its own, stricter ladder in [InnerTubeVideoStreamExtractor] (it has to care
 * about PO Tokens, SABR and GVS gating). This registry feeds that ladder's *extended* tail and the
 * metadata chain in [InnerTubeClientChain], where nothing is streamed and every client that can
 * describe a video is fair game.
 */
object InnerTubeClients {
    /**
     * Clients that answer `/player` anonymously. Order is "least likely to be walled first": the
     * app-family clients answer without attestation for /player metadata, then the web family, then
     * the TV / embedded / creator clients that cover age-gated and kids content.
     */
    val METADATA_CHAIN: List<YouTubeClient> =
        listOf(
            YouTubeClient.VISIONOS,
            YouTubeClient.ANDROID_VR_1_65_10,
            YouTubeClient.IPADOS,
            YouTubeClient.IOS,
            YouTubeClient.ANDROID,
            YouTubeClient.MWEB,
            YouTubeClient.WEB,
            YouTubeClient.TV_TIZEN,
            YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            YouTubeClient.ANDROID_VR_1_61_48,
            YouTubeClient.ANDROID_VR_1_43_32,
            YouTubeClient.ANDROID_VR_NO_AUTH,
            YouTubeClient.ANDROID_CREATOR,
            YouTubeClient.WEB_CREATOR,
            YouTubeClient.TVHTML5,
            YouTubeClient.WEB_REMIX,
        )

    /**
     * The tail appended to the stream ladder after every existing step has failed. These are the
     * clients the ladder did not ask before. Order matters: embedded and iOS-family clients first
     * because they serve restricted videos unattested; creator / TV clients last because they
     * normally need a signed-in session and are only worth the round-trip as a final attempt.
     */
    val STREAM_EXTENDED_TAIL: List<YouTubeClient> =
        listOf(
            YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            YouTubeClient.IPADOS,
            YouTubeClient.IOS,
            YouTubeClient.ANDROID,
            YouTubeClient.ANDROID_CREATOR,
            YouTubeClient.WEB_CREATOR,
            YouTubeClient.TVHTML5,
            YouTubeClient.WEB_REMIX,
        )

    /** Stable key for health bookkeeping; two ANDROID_VR builds are different clients to the chain. */
    fun keyOf(client: YouTubeClient): String = "${client.clientName}@${client.clientVersion}"
}

/**
 * Process-wide per-client health: a circuit breaker with exponential cool-down plus a "last winner"
 * memory so the client that worked last time is asked first next time.
 *
 * Deliberately separate from [ClientGateTracker]: that registry records *GVS refusing a URL*
 * (attestation), this one records *`/player` itself failing* (timeouts, non-OK status, empty body),
 * which is a different failure with a different, much shorter, memory.
 */
object InnerTubeClientHealth {
    private const val BASE_COOLDOWN_MS = 20_000L
    private const val MAX_COOLDOWN_MS = 10 * 60_000L
    private const val FAILURES_BEFORE_OPEN = 2

    private class State(
        var consecutiveFailures: Int = 0,
        var openUntilMs: Long = 0L,
        var lastSuccessMs: Long = 0L,
    )

    private val states = ConcurrentHashMap<String, State>()

    @Volatile
    private var lastWinnerKey: String? = null

    fun isOpen(
        client: YouTubeClient,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val state = states[InnerTubeClients.keyOf(client)] ?: return false
        synchronized(state) { return state.openUntilMs > nowMs }
    }

    fun recordSuccess(
        client: YouTubeClient,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val key = InnerTubeClients.keyOf(client)
        val state = states.getOrPut(key) { State() }
        synchronized(state) {
            state.consecutiveFailures = 0
            state.openUntilMs = 0L
            state.lastSuccessMs = nowMs
        }
        lastWinnerKey = key
    }

    fun recordFailure(
        client: YouTubeClient,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val state = states.getOrPut(InnerTubeClients.keyOf(client)) { State() }
        synchronized(state) {
            state.consecutiveFailures += 1
            if (state.consecutiveFailures >= FAILURES_BEFORE_OPEN) {
                val exponent = (state.consecutiveFailures - FAILURES_BEFORE_OPEN).coerceAtMost(5)
                val cooldown = (BASE_COOLDOWN_MS shl exponent).coerceAtMost(MAX_COOLDOWN_MS)
                // Jitter keeps every client from reopening in the same instant after a shared outage.
                state.openUntilMs = nowMs + cooldown + (cooldown * Math.random() * 0.2).toLong()
            }
        }
    }

    /**
     * [clients] with the clients currently cooling down or GVS-gated removed, and the previous
     * winner promoted to the front. If that leaves nothing, the original order is returned: a
     * client that might fail beats no attempt at all.
     */
    fun rank(
        clients: List<YouTubeClient>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<YouTubeClient> {
        val usable = clients.filterNot { isOpen(it, nowMs) || ClientGateTracker.isGated(it.clientName) }
        // Everything is cooling down: ask the one that reopens soonest first instead of the static order.
        if (usable.isEmpty()) return clients.sortedBy { states[InnerTubeClients.keyOf(it)]?.openUntilMs ?: 0L }
        val winner = lastWinnerKey ?: return usable
        val promoted = usable.firstOrNull { InnerTubeClients.keyOf(it) == winner } ?: return usable
        return listOf(promoted) + usable.filterNot { it === promoted }
    }

    /** True for failures that say the device is offline, not that the client is broken. */
    fun isNetworkDown(error: Throwable): Boolean =
        error is java.net.UnknownHostException ||
            error is java.net.ConnectException ||
            error is java.net.NoRouteToHostException

    /** A new network invalidates every verdict about a client: forgive them all. */
    fun onNetworkChanged() = reset()

    /** For diagnostics and tests. */
    fun reset() {
        states.clear()
        lastWinnerKey = null
    }
}

/** Why a chain step produced nothing; drives whether the chain keeps walking. */
enum class ChainFailure {
    TIMEOUT,
    EMPTY,
    NOT_PLAYABLE,

    /** The video is gone on this client; trying more InnerTube clients will not help. */
    TERMINAL,
    EXCEPTION,
}

/**
 * Walks [InnerTubeClients.METADATA_CHAIN] to answer "what is this video?" without NewPipe, and
 * only when every InnerTube client has failed hands over to the caller's [nativeFallback] (the
 * NewPipe extractor).
 *
 * Three things make it more than a loop:
 *  - **Ranking**: clients cooling down or gated are skipped, the last winner goes first.
 *  - **Hedging**: if the current client has not answered within [HEDGE_DELAY_MS] the next one is
 *    started *in parallel* and the first usable answer wins, so one slow client costs ~1s, not a
 *    full timeout.
 *  - **Terminal detection**: a `UNPLAYABLE`/`ERROR` answer that names the video as removed stops
 *    the walk, instead of spending a dozen requests proving a deleted video is deleted.
 */
object InnerTubeClientChain {
    private const val TAG = "InnerTubeClientChain"
    const val PER_CLIENT_TIMEOUT_MS = 5_000L
    const val HEDGE_DELAY_MS = 1_200L
    private const val MAX_IN_FLIGHT = 3

    data class Answer(
        val response: PlayerResponse,
        val client: YouTubeClient,
    )

    /**
     * @param accept decides whether a response is good enough (default: playability OK with
     *   `videoDetails`). A response that is not accepted counts as a failure for that client.
     * @param nativeFallback last resort, run only after every InnerTube client failed.
     */
    suspend fun <T : Any> resolve(
        videoId: String,
        clients: List<YouTubeClient> = InnerTubeClients.METADATA_CHAIN,
        accept: (PlayerResponse) -> Boolean = { it.playabilityStatus.status == "OK" && it.videoDetails != null },
        transform: suspend (Answer) -> T?,
        nativeFallback: (suspend () -> T?)? = null,
    ): T? {
        val ranked = InnerTubeClientHealth.rank(clients)
        FlowTrace.i(FlowTrace.INNERTUBE) {
            "resolve $videoId: clients ranked ${ranked.joinToString {
                it.clientName
            }} (of ${clients.size}), nativeFallback=${nativeFallback != null}"
        }
        val signatureTimestamp =
            try {
                CipherDeobfuscator.ensureSignatureTimestamp()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

        val fromInnerTube =
            try {
                hedgedWalk(videoId, ranked, signatureTimestamp, accept, transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "InnerTube chain crashed for $videoId: ${e.message}")
                null
            }
        if (fromInnerTube != null) return fromInnerTube

        if (nativeFallback == null) return null
        Log.w(TAG, "All InnerTube clients failed for $videoId — falling back to NewPipe")
        PlayerDiagnostics.logWarning(TAG, "chain exhausted for $videoId — NewPipe last resort")
        return try {
            nativeFallback()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "NewPipe last resort failed for $videoId: ${e.message}")
            null
        }
    }

    private suspend fun <T : Any> hedgedWalk(
        videoId: String,
        clients: List<YouTubeClient>,
        signatureTimestamp: Int?,
        accept: (PlayerResponse) -> Boolean,
        transform: suspend (Answer) -> T?,
    ): T? =
        coroutineScope {
            if (clients.isEmpty()) return@coroutineScope null

            val winner = CompletableDeferred<T?>()
            val launched = mutableListOf<Job>()
            var nextIndex = 0
            var finished = 0
            val lock = Any()

            fun launchNext(scope: CoroutineScope) {
                val client: YouTubeClient
                synchronized(lock) {
                    if (nextIndex >= clients.size || launched.count { it.isActive } >= MAX_IN_FLIGHT) return
                    client = clients[nextIndex++]
                }
                val job =
                    scope.launch {
                        val outcome = askClient(videoId, client, signatureTimestamp, accept, transform)
                        if (outcome.value != null) {
                            winner.complete(outcome.value)
                        } else if (outcome.failure == ChainFailure.TERMINAL) {
                            // The video itself is gone; more clients cannot change that.
                            winner.complete(null)
                        }
                        val allDone: Boolean
                        synchronized(lock) {
                            finished += 1
                            allDone = finished >= clients.size
                        }
                        if (outcome.value == null && !winner.isCompleted) {
                            // The next client starts the moment this one fails; the hedge timer
                            // below only covers the case where it is merely slow.
                            launchNext(this@coroutineScope)
                        }
                        if (allDone && !winner.isCompleted) winner.complete(null)
                    }
                synchronized(lock) { launched += job }
            }

            launchNext(this)
            val hedge =
                launch {
                    while (!winner.isCompleted) {
                        delay(HEDGE_DELAY_MS)
                        if (winner.isCompleted) break
                        val exhausted = synchronized(lock) { nextIndex >= clients.size }
                        if (exhausted) break
                        launchNext(this@coroutineScope)
                    }
                }

            val result = winner.await()
            hedge.cancel()
            synchronized(lock) { launched.toList() }.forEach { it.cancel() }
            result
        }

    private data class Outcome<T>(
        val value: T?,
        val failure: ChainFailure?,
    )

    private suspend fun <T : Any> askClient(
        videoId: String,
        client: YouTubeClient,
        signatureTimestamp: Int?,
        accept: (PlayerResponse) -> Boolean,
        transform: suspend (Answer) -> T?,
    ): Outcome<T> {
        val askedAt = android.os.SystemClock.elapsedRealtime()
        FlowTrace.d(FlowTrace.INNERTUBE) {
            "player $videoId via ${client.clientName} ${client.clientVersion} (sigTimestamp=${if (client.useSignatureTimestamp) signatureTimestamp else "n/a"})"
        }
        val result =
            withTimeoutOrNull(PER_CLIENT_TIMEOUT_MS) {
                suspend fun ask() =
                    YouTube.player(
                        videoId = videoId,
                        client = client,
                        signatureTimestamp = if (client.useSignatureTimestamp) signatureTimestamp else null,
                        poToken = null,
                        localeOverride = YouTubeLocale.EXTRACTION,
                        apiUrl = YouTubeClient.API_URL_YOUTUBE,
                    )
                var attempt = ask()
                val err = attempt.exceptionOrNull()
                // One quick retry for a dropped connection; a refusal or offline error is not retried.
                if (err is java.io.IOException && !InnerTubeClientHealth.isNetworkDown(err)) {
                    delay(250)
                    attempt = ask()
                }
                attempt
            }
        if (result == null) {
            FlowTrace.w(FlowTrace.INNERTUBE) { "${client.clientName} timed out for $videoId after ${PER_CLIENT_TIMEOUT_MS} ms" }
            InnerTubeClientHealth.recordFailure(client)
            return Outcome(null, ChainFailure.TIMEOUT)
        }
        val response =
            result.getOrElse { error ->
                if (error is CancellationException) throw error
                if (!InnerTubeClientHealth.isNetworkDown(error)) InnerTubeClientHealth.recordFailure(client)
                Log.d(TAG, "${client.clientName} failed for $videoId: ${error.message}")
                FlowTrace.w(FlowTrace.INNERTUBE, error) { "${client.clientName} threw for $videoId: ${error.javaClass.simpleName}" }
                return Outcome(null, ChainFailure.EXCEPTION)
            }
        FlowTrace.d(FlowTrace.INNERTUBE) {
            val streams = response.streamingData
            "${client.clientName} answered $videoId in ${android.os.SystemClock.elapsedRealtime() - askedAt} ms: " +
                "status=${response.playabilityStatus.status} reason=${FlowTrace.clip(response.playabilityStatus.reason)} " +
                "videoDetails=${response.videoDetails != null} muxed=${streams?.formats?.size ?: 0} adaptive=${streams?.adaptiveFormats?.size ?: 0}"
        }

        if (!accept(response)) {
            val status = response.playabilityStatus.status
            // UNPLAYABLE/ERROR on a client that was not the problem means the video is gone; a
            // LOGIN_REQUIRED or bot wall is about the client or the viewer and must keep walking.
            val reason = response.playabilityStatus.reason.orEmpty()
            FlowTrace.w(
                FlowTrace.INNERTUBE,
            ) { "${client.clientName} not accepted for $videoId: status=$status reason=${FlowTrace.clip(reason)}" }
            val gone =
                (status == "ERROR" || status == "UNPLAYABLE") &&
                    !PlayabilityVerdict.isBotWall(reason) &&
                    reason.isNotBlank() &&
                    isRemovalReason(reason)
            return if (gone) {
                Outcome(null, ChainFailure.TERMINAL)
            } else {
                // A non-OK status is not the client being unhealthy, so the breaker is not charged.
                Outcome(null, if (status == "OK") ChainFailure.EMPTY else ChainFailure.NOT_PLAYABLE)
            }
        }

        val value =
            try {
                transform(Answer(response, client))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "${client.clientName} transform failed for $videoId: ${e.message}")
                null
            }
        return if (value != null) {
            InnerTubeClientHealth.recordSuccess(client)
            Outcome(value, null)
        } else {
            InnerTubeClientHealth.recordFailure(client)
            Outcome(null, ChainFailure.EMPTY)
        }
    }

    // Reasons are localized, but the extraction locale is pinned to en-US, so matching is stable.
    private fun isRemovalReason(reason: String): Boolean =
        reason.contains("removed", ignoreCase = true) ||
            reason.contains("no longer available", ignoreCase = true) ||
            reason.contains("does not exist", ignoreCase = true) ||
            reason.contains("account associated", ignoreCase = true) ||
            reason.contains("been terminated", ignoreCase = true)
}
