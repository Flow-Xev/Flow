package app.zaid.flow.player.stream

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.models.AttestationPlatform
import app.zaid.flow.innertube.models.YouTubeClient
import app.zaid.flow.innertube.models.YouTubeLocale
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.innertube.pages.NewPipeExtractor
import app.zaid.flow.player.error.PlayerDiagnostics
import app.zaid.flow.player.sabr.SabrRoutingPolicy
import app.zaid.flow.player.sabr.core.SabrCpn
import app.zaid.flow.player.sabr.integration.SabrClientIdentity
import app.zaid.flow.player.sabr.integration.SabrStreamInfo
import app.zaid.flow.player.sabr.integration.SabrUrlResolver
import app.zaid.flow.utils.cipher.CipherDeobfuscator
import app.zaid.flow.utils.cipher.PipePipeNsigDecoder
import app.zaid.flow.utils.potoken.PoTokenResult
import app.zaid.flow.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object InnerTubeVideoStreamExtractor {
    private const val TAG = "InnerTubeVideoExtractor"
    private const val PER_CLIENT_TIMEOUT_MS = 6000L

    // One /player ask is hedged: a second identical request is opened on a fresh connection when the
    // first has not answered in time, and the first answer wins. A request stuck on a stale or
    // half-open connection (the cold-start pattern) no longer costs a full timeout plus a retry.
    private const val FAST_HEDGE_DELAY_MS = 1800L
    private const val DEFAULT_HEDGE_DELAY_MS = 2800L
    private const val HEDGED_TOTAL_TIMEOUT_MS = 10_000L
    private const val MAX_HEDGED_ATTEMPTS = 3

    // The durable web/SABR stage mints a BotGuard token in a WebView; cold, that can take a long
    // time and used to hold the whole ladder with no log line. Past this it is abandoned and the
    // remaining clients get their turn.
    private const val SABR_STAGE_TIMEOUT_MS = 12_000L

    // Whole-ladder retry for failures that look transient (timeouts), only while there is budget.
    private const val MAX_EXTRACTION_ROUNDS = 3
    private const val ROUND_RETRY_CUTOFF_MS = 14_000L
    private const val VISITOR_DATA_WAIT_MS = 2500L
    private const val WEB_PLAYER_TIMEOUT_MS = 10000L

    // A cold player script is a 3 MB download. Past this the web request goes without a signature
    // timestamp and the download keeps running for the next extraction.
    private const val SIGNATURE_TIMESTAMP_WAIT_MS = 4000L

    // How long a confirmed winner will wait for the streaming pot before shipping its
    // URLs pot-less. The mint starts alongside the ladder, so a warm session is ready well before
    // any winner and pays nothing here; this only bounds the slow tail (cold WebView, the
    // low-trust re-attest loop) so first frame is never held open by BotGuard. A pot-less ship is
    // the pre-fix behavior: the URLs still start instantly, and if GVS cuts them ~60s in the
    // existing 403 -> reload path re-extracts with the (by then cached) token and recovers.
    private const val STREAM_POT_ATTACH_GRACE_MS = 2500L
    private val N_PARAM_REGEX = Regex("""(?:^|[?&])n=([^&]+)""")
    private val POT_PARAM_REGEX = Regex("""[?&]pot=""")

    @Volatile
    private var lastSeenIdentityGeneration = WebPoTokenSession.identityGeneration
    private val extractionCoalescer =
        InFlightRequestCoalescer<ExtractionKey, VideoExtractionResult?>(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    private data class ExtractionKey(
        val videoId: String,
        val forceSabr: Boolean,
        val audioOnly: Boolean,
    )

    // The token-free direct client. VISIONOS alone: it serves direct adaptive URLs with no PO Token
    // and no `n` parameter, so first frame costs neither an attestation nor an nsig decode. GVS
    // honours them for the whole video unless it has walled this visitor, which it does a minute in
    // on every video (#921); then the ladder goes to TV_TIZEN and the visitor is re-rolled.
    private val FAST_CLIENTS: List<YouTubeClient> =
        listOf(
            YouTubeClient.VISIONOS,
        )

    /**
     * Direct clients GVS cuts off without a PO Token Flow cannot mint (DroidGuard). The gate is
     * bound to the byte offset, not wall-clock time, and measured between media position 60s and
     * 67s. Never promote these above [SABR_CLIENTS]. 1.65.10 leads as the only build still
     * answering; the others are kept because a bot wall is reputation-bound, not structural.
     */
    private val GATED_FALLBACK_CLIENTS: List<YouTubeClient> =
        listOf(
            YouTubeClient.ANDROID_VR_1_65_10,
            YouTubeClient.ANDROID_VR_1_61_48,
            YouTubeClient.ANDROID_VR_NO_AUTH,
            YouTubeClient.ANDROID_VR_1_43_32,
        )

    // Last-resort token-free client, tried after everything else.
    private val LAST_RESORT_CLIENTS: List<YouTubeClient> =
        listOf(
            YouTubeClient.MOBILE,
        )

    // MWEB first: it carries every dubbed audio track and is web-family, so its GVS token is one
    // Flow's own BotGuard session can mint.
    private val SABR_CLIENTS: List<YouTubeClient> =
        listOf(
            YouTubeClient.MWEB,
            YouTubeClient.WEB,
        )

    // ANDROID_VR leads: on a live broadcast it is the only client that returns a DASH manifest
    // alongside the HLS one, and VISIONOS returns HLS only.
    private val LIVE_MANIFEST_CLIENTS: List<YouTubeClient> =
        listOf(
            YouTubeClient.ANDROID_VR_1_65_10,
            YouTubeClient.VISIONOS,
        )

    data class VideoExtractionResult(
        val videoFormats: List<PlayerResponse.StreamingData.Format>,
        val audioFormats: List<PlayerResponse.StreamingData.Format>,
        val playerResponse: PlayerResponse,
        val usedClient: YouTubeClient,
        val sabrInfo: SabrStreamInfo?,
        val isLive: Boolean = false,
        val liveHlsUrl: String? = null,
        val liveDashUrl: String? = null,
    )

    /** Who is waiting on the answer. Background work must never slow the video a person just opened. */
    enum class ExtractionPriority { FOREGROUND, BACKGROUND }

    private val backgroundGate = Semaphore(1)
    private val foregroundInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * @param audioOnly the caller plays only [VideoExtractionResult.audioFormats], so a capped
     *   video ladder is not worth the SABR quality upgrade's wait.
     * @param priority [ExtractionPriority.BACKGROUND] (feed previews) runs one at a time and waits
     *   while any foreground extraction is in flight, so ten thumbnails cannot starve the tapped video.
     */
    @OptIn(UnstableApi::class)
    suspend fun extract(
        videoId: String,
        forceSabr: Boolean = false,
        audioOnly: Boolean = false,
        priority: ExtractionPriority = ExtractionPriority.FOREGROUND,
    ): VideoExtractionResult? {
        val key = ExtractionKey(videoId, forceSabr, audioOnly)
        if (priority == ExtractionPriority.BACKGROUND) {
            return backgroundGate.withPermit {
                while (foregroundInFlight.get() > 0) delay(250)
                extractionCoalescer.run(key) { selectStreamsResilient(videoId, forceSabr, audioOnly) }
            }
        }
        foregroundInFlight.incrementAndGet()
        try {
            return extractionCoalescer.run(key) { selectStreamsResilient(videoId, forceSabr, audioOnly) }
        } finally {
            foregroundInFlight.decrementAndGet()
        }
    }

    /**
     * Runs the ladder, and runs it again while the failure looks transient. A video YouTube reports
     * as gone, or a viewer it has refused, ends the loop at once; a timeout or an empty answer does
     * not, because on a cold connection the second pass is warm and succeeds.
     */
    private suspend fun selectStreamsResilient(
        videoId: String,
        forceSabr: Boolean,
        audioOnly: Boolean,
    ): VideoExtractionResult? {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var result = selectStreams(videoId, forceSabr, audioOnly)
        var round = 1
        while (result == null && round < MAX_EXTRACTION_ROUNDS && !forceSabr) {
            if (isGone(videoId) || blockOf(videoId) != null) break
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            if (elapsed > ROUND_RETRY_CUTOFF_MS) break
            round += 1
            PlayerDiagnostics.logWarning(TAG, "extract round $round for $videoId after ${elapsed}ms (previous round failed transiently)")
            delay(350L * round)
            result = selectStreams(videoId, forceSabr, audioOnly)
        }
        return result
    }

    /** A visitor identity that has not arrived yet makes the first /player request anonymous and slow. */
    private suspend fun ensureVisitorData() {
        if (!YouTube.visitorData.isNullOrEmpty()) return
        withTimeoutOrNull(VISITOR_DATA_WAIT_MS) {
            YouTube.visitorData().onSuccess { data ->
                if (!data.isNullOrEmpty() && YouTube.visitorData.isNullOrEmpty()) YouTube.visitorData = data
            }
        }
    }

    /**
     * One /player ask, hedged. [request] is started once and, while it has not answered within
     * [hedgeDelayMs] (or it failed), again on a fresh attempt up to [MAX_HEDGED_ATTEMPTS]; the first
     * successful answer wins and the stragglers are cancelled. Null when nothing answered in
     * [HEDGED_TOTAL_TIMEOUT_MS], which callers treat exactly like the timeout it is.
     */
    private suspend fun <T : Any> hedgedRequest(
        hedgeDelayMs: Long,
        request: suspend () -> Result<T>,
    ): Result<T>? =
        withTimeoutOrNull(HEDGED_TOTAL_TIMEOUT_MS) {
            coroutineScope {
                val answers = Channel<Result<T>>(Channel.UNLIMITED)
                val attempts = mutableListOf<Job>()
                var launched = 0
                var failed = 0

                fun launchAttempt() {
                    launched += 1
                    attempts +=
                        launch {
                            val outcome =
                                try {
                                    request()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Result.failure(e)
                                }
                            answers.trySend(outcome)
                        }
                }

                launchAttempt()
                var winner: Result<T>? = null
                while (winner == null) {
                    val answer = withTimeoutOrNull(hedgeDelayMs) { answers.receive() }
                    if (answer == null) {
                        // Slow, not failed: race a second request rather than keep waiting on a
                        // connection that may already be dead.
                        if (launched < MAX_HEDGED_ATTEMPTS) launchAttempt()
                        continue
                    }
                    if (answer.isSuccess) {
                        winner = answer
                        continue
                    }
                    // A refusal or a parse error is the same on every attempt; only a dropped or
                    // stalled connection is worth asking again.
                    if (answer.exceptionOrNull() !is java.io.IOException) {
                        winner = answer
                        continue
                    }
                    failed += 1
                    if (failed >= launched) {
                        if (launched >= MAX_HEDGED_ATTEMPTS) winner = answer else launchAttempt()
                    }
                }
                attempts.forEach { it.cancel() }
                winner
            }
        }

    private suspend fun selectStreams(
        videoId: String,
        forceSabr: Boolean,
        audioOnly: Boolean,
    ): VideoExtractionResult? =
        withContext(Dispatchers.IO) {
            Log.w(TAG, "Extraction start for $videoId (forceSabr=$forceSabr)")
            PlayerDiagnostics.logWarning(TAG, "extract start $videoId forceSabr=$forceSabr")
            dropGatesOnIdentityChange()
            blockedVideoIds.remove(videoId)
            ensureVisitorData()
            val failureReasons = mutableListOf<String>()
            val liveDetected = booleanArrayOf(false)

            if (forceSabr) {
                trySabrClients(videoId, failureReasons)?.let {
                    Log.w(TAG, "Extraction OK for $videoId via ${it.usedClient.clientName} (mode=SABR/forced)")
                    PlayerDiagnostics.logWarning(TAG, "extract OK $videoId mode=SABR/forced via ${it.usedClient.clientName}")
                    return@withContext it
                }
                Log.e(TAG, "Forced SABR extraction failed for $videoId. Reasons: ${failureReasons.joinToString(" | ")}")
                PlayerDiagnostics.logError(TAG, "forced SABR FAILED $videoId: ${failureReasons.joinToString(" | ")}")
                recordBlock(videoId, failureReasons)
                return@withContext null
            }

            // 1) Fast path: token-free clients with direct URLs. Skipped while GVS is enforcing
            // attestation on them — without that memory the ladder walks into the same refusal on
            // every single video, which is what "every video stops at a minute" looks like.
            val fastClients = FAST_CLIENTS.ungated()
            if (fastClients.isEmpty()) {
                Log.w(TAG, "Fast clients demoted for $videoId (gated: ${ClientGateTracker.gatedClients()}) — trying TV_TIZEN")
                PlayerDiagnostics.logWarning(
                    TAG,
                    "fast path SKIPPED $videoId — gated clients: ${ClientGateTracker.gatedClients().joinToString()}",
                )
                // The walled visitor's other app clients and token-backed web clients stop at the
                // same minute; TV_TIZEN is the measured way past it (#921).
                TizenStreamResolver.resolve(videoId)?.let {
                    Log.w(TAG, "Extraction OK for $videoId via TV_TIZEN (mode=DIRECT/walled)")
                    PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via TV_TIZEN mode=DIRECT/walled")
                    return@withContext it
                }
            } else {
                // A cold start pays DNS and TLS inside the first request, which can outlast the
                // per-client timeout; one retry on the warm connection beats falling to the web path.
                tryDirectClients(
                    videoId,
                    fastClients,
                    failureReasons,
                    liveDetected = liveDetected,
                    retryTimeoutOnce = true,
                )?.let { direct ->
                    val result = if (audioOnly) direct else maybeUpgradeToSabr(videoId, direct, failureReasons)
                    Log.w(TAG, "Extraction OK for $videoId via ${result.usedClient.clientName} (mode=${resultMode(result)})")
                    PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via ${result.usedClient.clientName} mode=${resultMode(result)}")
                    return@withContext result
                }
            }

            if (liveDetected[0]) {
                tryLiveClients(videoId, failureReasons)?.let {
                    Log.w(TAG, "Live manifest for $videoId via ${it.usedClient.clientName} (live-clients)")
                    return@withContext it
                }
            }

            // 2) Durable path: web client + BotGuard PoToken + SABR. Ranked above every remaining
            // direct client because it is the only other path whose token Flow can actually mint —
            // the clients below it are all served unattested and can be cut off mid-playback.
            withTimeoutOrNull(SABR_STAGE_TIMEOUT_MS) { trySabrClients(videoId, failureReasons) }?.let {
                Log.w(TAG, "Extraction OK for $videoId via ${it.usedClient.clientName} (mode=SABR)")
                PlayerDiagnostics.logWarning(TAG, "extract OK $videoId mode=SABR (durable) via ${it.usedClient.clientName}")
                return@withContext if (liveDetected[0] && !it.isLive) it.copy(isLive = true) else it
            }

            // 2b) Label and kids videos are UNPLAYABLE on the app clients, so once the web clients
            // fail the TV_TIZEN client is their only source. Skipped when step 1 already asked it.
            if (fastClients.isNotEmpty() && !liveDetected[0]) {
                TizenStreamResolver.resolve(videoId)?.let {
                    Log.w(TAG, "Extraction OK for $videoId via TV_TIZEN (mode=DIRECT/web-fallback)")
                    PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via TV_TIZEN mode=DIRECT/web-fallback")
                    return@withContext it
                }
            }

            // 3) Gated direct clients. Playable, but GVS stops serving them ~60s in, so they rank
            // below anything attested and are only reached when the paths above are unavailable.
            tryDirectClients(videoId, GATED_FALLBACK_CLIENTS.ungated(), failureReasons, liveDetected = liveDetected)?.let { direct ->
                val result = if (audioOnly) direct else maybeUpgradeToSabr(videoId, direct, failureReasons)
                Log.w(TAG, "Extraction OK for $videoId via ${result.usedClient.clientName} (mode=${resultMode(result)}/gated)")
                PlayerDiagnostics.logWarning(
                    TAG,
                    "extract OK $videoId via ${result.usedClient.clientName} mode=${resultMode(result)}/GATED — " +
                        "unattested, GVS may stop serving ~60s in",
                )
                return@withContext result
            }

            // 4) Last resort: remaining token-free clients
            tryDirectClients(
                videoId,
                LAST_RESORT_CLIENTS.ungated(),
                failureReasons,
                allowUntransformedN = true,
                liveDetected = liveDetected,
            )?.let {
                Log.w(TAG, "Extraction OK for $videoId via ${it.usedClient.clientName} (mode=DIRECT/last-resort)")
                PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via ${it.usedClient.clientName} mode=DIRECT/last-resort")
                return@withContext if (liveDetected[0] && !it.isLive) it.copy(isLive = true) else it
            }

            // 4b) Extended tail: every remaining InnerTube client Flow can speak (embedded, iOS-family,
            // Android, creator, TV). Only reached once everything above has failed, so its cost is
            // paid only by videos that would otherwise not play at all. Ranked by recent health so a
            // client that has just been failing is not asked first.
            tryDirectClients(
                videoId,
                InnerTubeClientHealth.rank(
                    InnerTubeClients.STREAM_EXTENDED_TAIL.filterNot { tail ->
                        (FAST_CLIENTS + GATED_FALLBACK_CLIENTS + LAST_RESORT_CLIENTS).any {
                            InnerTubeClients.keyOf(it) == InnerTubeClients.keyOf(tail)
                        }
                    },
                ),
                failureReasons,
                allowUntransformedN = true,
                liveDetected = liveDetected,
            )?.let {
                Log.w(TAG, "Extraction OK for $videoId via ${it.usedClient.clientName} (mode=DIRECT/extended-tail)")
                PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via ${it.usedClient.clientName} mode=DIRECT/extended-tail")
                return@withContext if (liveDetected[0] && !it.isLive) it.copy(isLive = true) else it
            }

            // 5) Every attested path is gone and the demotion has left nothing to try. A client
            // that plays a minute beats a client that plays nothing, so the demotions are ignored
            // for one pass rather than returning a failure the user cannot act on.
            val demoted = (FAST_CLIENTS + GATED_FALLBACK_CLIENTS + LAST_RESORT_CLIENTS).filter { ClientGateTracker.isGated(it.clientName) }
            if (demoted.isNotEmpty()) {
                Log.w(TAG, "Nothing playable for $videoId without the demoted clients — retrying them: ${demoted.map { it.clientName }}")
                PlayerDiagnostics.logWarning(TAG, "retrying DEMOTED clients for $videoId: ${demoted.joinToString { it.clientName }}")
                tryDirectClients(videoId, demoted, failureReasons, allowUntransformedN = true, liveDetected = liveDetected)?.let {
                    PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via ${it.usedClient.clientName} mode=DIRECT/demoted-retry")
                    return@withContext if (liveDetected[0] && !it.isLive) it.copy(isLive = true) else it
                }
            }

            // 6) Every InnerTube client has failed. NewPipe is the final fallback: it has its own
            // player-script handling and sometimes still plays what the clients above cannot.
            // A video YouTube reports as gone is not worth the extra request.
            if (!PlayabilityVerdict.isGone(failureReasons)) {
                NewPipeStreamFallback.resolve(videoId)?.let {
                    Log.w(TAG, "Extraction OK for $videoId via NewPipe (mode=DIRECT/newpipe-last-resort)")
                    return@withContext it
                }
            }

            Log.e(TAG, "All clients failed for $videoId (forceSabr=$forceSabr). Reasons: ${failureReasons.joinToString(" | ")}")
            PlayerDiagnostics.logError(TAG, "ALL clients failed $videoId: ${failureReasons.joinToString(" | ")}")
            if (PlayabilityVerdict.isGone(failureReasons)) goneVideoIds += videoId
            recordBlock(videoId, failureReasons)
            null
        }

    /** Videos YouTube reported as removed or unplayable on every client; a queue skips them. */
    private val goneVideoIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    fun isGone(videoId: String): Boolean = videoId in goneVideoIds

    private val blockedVideoIds = java.util.concurrent.ConcurrentHashMap<String, PlaybackBlock>()

    /** Why the last extraction of [videoId] failed, when YouTube refused the viewer rather than the video. */
    fun blockOf(videoId: String): PlaybackBlock? = blockedVideoIds[videoId]

    private fun recordBlock(
        videoId: String,
        failureReasons: List<String>,
    ) {
        PlayabilityVerdict.block(failureReasons)?.let { blockedVideoIds[videoId] = it }
    }

    /** The clients GVS is not currently refusing to serve. See [ClientGateTracker]. */
    private fun List<YouTubeClient>.ungated(): List<YouTubeClient> = filterNot { ClientGateTracker.isGated(it.clientName) }

    /**
     * A demotion is a verdict GVS reached about one visitor identity. Once that identity has been
     * replaced the verdict says nothing, so the ladder gets its fast clients back immediately
     * instead of waiting out the registry's timer.
     */
    private fun dropGatesOnIdentityChange() {
        val generation = WebPoTokenSession.identityGeneration
        if (generation == lastSeenIdentityGeneration) return
        lastSeenIdentityGeneration = generation
        if (ClientGateTracker.gatedClients().isEmpty()) return
        Log.w(TAG, "Visitor identity rotated — clearing client demotions")
        PlayerDiagnostics.logWarning(TAG, "visitor identity rotated — client demotions cleared")
        ClientGateTracker.clear()
    }

    private fun resultMode(r: VideoExtractionResult): String =
        when {
            r.isLive -> "LIVE"
            r.sabrInfo != null -> "SABR-upgrade"
            else -> "DIRECT"
        }

    /**
     * Guarded SABR upgrade. YouTube increasingly serves the high rungs of the ladder as
     * SABR-only (no direct URL), so a token-free client can succeed while only exposing e.g.
     * 360p — leaving the user stuck low with "no other quality". When the direct ladder is
     * quality-incomplete ([SabrRoutingPolicy.shouldAttemptSabrUpgrade]), try the
     * WEB+PoToken+SABR path and prefer it ONLY if it beats the direct ceiling. If SABR is
     * unavailable (bot-walled, no PoToken) the original direct result is returned unchanged, so
     * a video that plays today can never regress.
     */
    private suspend fun maybeUpgradeToSabr(
        videoId: String,
        direct: VideoExtractionResult,
        failureReasons: MutableList<String>,
    ): VideoExtractionResult {
        if (direct.isLive || direct.sabrInfo != null) return direct

        val directMaxHeight = direct.videoFormats.maxOfOrNull { it.height ?: 0 } ?: 0
        if (!SabrRoutingPolicy.shouldAttemptSabrUpgrade(directMaxHeight)) return direct

        Log.w(
            TAG,
            "Direct ladder for $videoId capped at ${directMaxHeight}p (< ${SabrRoutingPolicy.QUALITY_UPGRADE_FLOOR}p); attempting SABR upgrade",
        )
        val sabr =
            withTimeoutOrNull(SabrRoutingPolicy.UPGRADE_BUDGET_MS) { trySabrClients(videoId, failureReasons) }
                ?: return direct.also {
                    PlayerDiagnostics.logWarning(TAG, "SABR upgrade unavailable or out of budget for $videoId, playing direct")
                }
        val sabrHeight = sabr.sabrInfo?.videoHeight
        return if (SabrRoutingPolicy.upgradeReplacesDirect(directMaxHeight, sabrHeight)) {
            Log.w(TAG, "Upgraded $videoId: ${directMaxHeight}p direct → ${sabrHeight}p SABR")
            sabr
        } else {
            direct
        }
    }

    /**
     * @param client the client that opened the session being reloaded. A reload must stay on it —
     *   re-resolving an MWEB session as WEB would contradict the player response the server is
     *   already tracking — so this deliberately does not walk [SABR_CLIENTS].
     */
    suspend fun resolveSabrDownload(
        videoId: String,
        targetHeight: Int = 0,
        preferredCodec: String? = null,
        cpn: String = SabrCpn.generate(),
        reloadToken: String? = null,
        client: YouTubeClient = YouTubeClient.WEB,
    ): SabrStreamInfo? =
        withContext(Dispatchers.IO) {
            val failureReasons = mutableListOf<String>()
            tryWebSabr(
                videoId = videoId,
                failureReasons = failureReasons,
                targetHeight = targetHeight,
                preferredCodec = preferredCodec,
                cpn = cpn,
                reloadToken = reloadToken,
                client = client,
            )?.sabrInfo.also { sabrInfo ->
                if (sabrInfo == null) {
                    Log.w(TAG, "SABR download resolve failed for $videoId: ${failureReasons.joinToString(" | ")}")
                }
            }
        }

    @OptIn(UnstableApi::class)
    private suspend fun tryDirectClients(
        videoId: String,
        clients: List<YouTubeClient>,
        failureReasons: MutableList<String>,
        allowUntransformedN: Boolean = false,
        liveDetected: BooleanArray? = null,
        retryTimeoutOnce: Boolean = false,
    ): VideoExtractionResult? =
        coroutineScope {
            val sts: Int? =
                if (clients.any { it.useSignatureTimestamp }) {
                    CipherDeobfuscator.ensureSignatureTimestamp()
                } else {
                    null
                }

            // Attested player requests yield direct URLs that survive GVS enforcement; unattested
            // ones get cut off roughly a minute in (served briefly, then 403 once the buffer drains).
            // Skipped when no client here can carry the token: minting only to cancel it left the
            // shared BotGuard session needing a fresh attestation on the next open.
            val mintable = clients.any { it.attestation == AttestationPlatform.WEB }
            val mint = if (mintable) async { WebPoTokenSession.mintBounded(videoId) } else null
            var mintLogged = false

            suspend fun awaitMint(): PoTokenResult? {
                val result = mint?.await()
                if (!mintLogged) {
                    mintLogged = true
                    if (result == null) {
                        Log.w(TAG, "Direct clients for $videoId running without a PoToken (mint unavailable in time)")
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "fast-path UNATTESTED $videoId (no PoToken in time) — direct URLs typically 403 ~60s in",
                        )
                    } else {
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "fast-path attested $videoId (playerPot len=${result.playerRequestPoToken.length}, " +
                                "streamingPot len=${result.streamingDataPoToken.length})",
                        )
                    }
                }
                return result
            }

            for (client in clients) {
                try {
                    Log.d(TAG, "Trying ${client.clientName} v${client.clientVersion}")

                    // Only web-family clients get the BotGuard token. Injecting it into an
                    // ANDROID_VR/IOS /player request makes YT reject it outright (non-OK / empty
                    // streamingData), and stamping it on their URLs is worse than sending nothing:
                    // it is a claim GVS validates and refuses. Those clients are usable only for as
                    // long as YouTube serves them unattested.
                    val webAttested = client.attestation == AttestationPlatform.WEB
                    val clientPoToken = if (webAttested) awaitMint()?.playerRequestPoToken else null

                    suspend fun requestPlayer(): Result<PlayerResponse>? =
                        hedgedRequest(if (retryTimeoutOnce) FAST_HEDGE_DELAY_MS else DEFAULT_HEDGE_DELAY_MS) {
                            // Force en-US extraction locale so the response is deterministic across regions.
                            // Route video extraction to www.youtube.com (not the music host): the main site
                            // serves usable ANDROID_VR direct adaptive formats that survive GVS enforcement,
                            // instead of the SABR-only responses the music endpoint returns for these clients.
                            YouTube.player(
                                videoId,
                                client = client,
                                signatureTimestamp = if (client.useSignatureTimestamp) sts else null,
                                poToken = clientPoToken,
                                localeOverride = YouTubeLocale.EXTRACTION,
                                apiUrl = YouTubeClient.API_URL_YOUTUBE,
                            )
                        }

                    val attempt = requestPlayer()
                    if (attempt.isTimeout()) {
                        PlayerDiagnostics.logWarning(TAG, "${client.clientName} gave no answer for $videoId after hedged attempts")
                    }
                    val playerResponse = attempt?.getOrNull()

                    if (playerResponse == null) {
                        InnerTubeClientHealth.recordFailure(client)
                        failureReasons.add("${client.clientName}: timeout or null response")
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "skip ${client.clientName} v${client.clientVersion}: null/timeout pot=${clientPoToken != null}",
                        )
                        continue
                    }

                    val status = playerResponse.playabilityStatus.status
                    if (status != "OK") {
                        val reason = playerResponse.playabilityStatus.reason
                        val tag = if (PlayabilityVerdict.isBotWall(reason)) "BOT_WALL" else "status=$status"
                        failureReasons.add("${client.clientName}: $tag, reason=$reason")
                        Log.w(TAG, "${client.clientName}: $tag, reason=$reason")
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "skip ${client.clientName} v${client.clientVersion}: $tag reason=$reason pot=${clientPoToken != null}",
                        )
                        continue
                    }

                    if (playerResponse.isLiveNow()) {
                        // A genuine live manifest wins outright. Live playback never consumes the
                        // mint, so cancel it rather than letting coroutineScope wait it out.
                        playerResponse.toLiveResultOrNull(client)?.let {
                            mint?.cancel()
                            return@coroutineScope it
                        }

                        if (playerResponse.videoDetails?.isLive == true) {
                            liveDetected?.set(0, true)
                            failureReasons.add("${client.clientName}: live but no hls/dash manifest")
                            PlayerDiagnostics.logWarning(
                                TAG,
                                "skip ${client.clientName} v${client.clientVersion}: live (broadcasting), no hls/dash pot=${clientPoToken != null}",
                            )
                            continue
                        }
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "${client.clientName} v${client.clientVersion}: live-flag but VOD (post-live-DVR/premiere) — using adaptive formats",
                        )
                    }

                    val adaptiveFormats = playerResponse.streamingData?.adaptiveFormats
                    if (adaptiveFormats.isNullOrEmpty()) {
                        failureReasons.add("${client.clientName}: no adaptive formats")
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "skip ${client.clientName} v${client.clientVersion}: no adaptive formats pot=${clientPoToken != null}",
                        )
                        continue
                    }

                    val deciphered = adaptiveFormats.decipherWhilePossible(videoId)
                    primeRemoteNsigIfNeeded(videoId, deciphered)

                    val formatsWithUrl = deciphered.mapNotNull { it.withPlayableUrl(videoId, allowUntransformedN) }
                    // Capability probe : logs, per client,
                    // how many adaptive formats carried a direct URL vs how many resolved to a playable
                    // stream, plus whether the response is SABR-capable. Makes "why was this client skipped"
                    // visible in the in-app diagnostics instead of only when every client fails.
                    val rawUrlCount = adaptiveFormats.count { !it.url.isNullOrEmpty() }
                    val sabrPresent = !playerResponse.streamingData?.serverAbrStreamingUrl.isNullOrEmpty()
                    PlayerDiagnostics.logWarning(
                        TAG,
                        "probe ${client.clientName} v${client.clientVersion}: adaptive=${adaptiveFormats.size} " +
                            "hasUrl=$rawUrlCount resolvable=${formatsWithUrl.size} sabr=$sabrPresent " +
                            "pot=${clientPoToken != null} attest=${playerResponse.attestationDemand()}",
                    )
                    if (formatsWithUrl.isEmpty()) {
                        failureReasons.add("${client.clientName}: ${adaptiveFormats.size} formats, none resolvable (SABR-only)")
                        continue
                    }

                    val videoFormats = formatsWithUrl.filter { !it.isAudio && it.height != null && it.width != null }
                    val audioFormats = formatsWithUrl.filter { it.isAudio }
                    if (videoFormats.isEmpty()) {
                        failureReasons.add("${client.clientName}: no video formats with direct URLs")
                        continue
                    }
                    if (audioFormats.isEmpty()) {
                        failureReasons.add("${client.clientName}: no audio formats with direct URLs")
                        continue
                    }

                    val heights = videoFormats.mapNotNull { it.height }.distinct().sorted()
                    Log.i(
                        TAG,
                        "Success with ${client.clientName}: ${videoFormats.size} video (${heights.joinToString()}p), ${audioFormats.size} audio (direct URLs)",
                    )

                    val urlPot =
                        if (webAttested) {
                            withTimeoutOrNull(STREAM_POT_ATTACH_GRACE_MS) { awaitMint() }?.streamingDataPoToken
                        } else {
                            null
                        }
                    if (urlPot == null && webAttested) {
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "${client.clientName} winner for $videoId shipping pot-less (mint not ready in " +
                                "${STREAM_POT_ATTACH_GRACE_MS}ms) — direct URLs may 403 ~60s in",
                        )
                    }

                    mint?.cancel()
                    InnerTubeClientHealth.recordSuccess(client)

                    return@coroutineScope VideoExtractionResult(
                        videoFormats = if (urlPot != null) videoFormats.map { it.withUrlPoToken(urlPot) } else videoFormats,
                        audioFormats = if (urlPot != null) audioFormats.map { it.withUrlPoToken(urlPot) } else audioFormats,
                        playerResponse = playerResponse,
                        usedClient = client,
                        sabrInfo = null,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    InnerTubeClientHealth.recordFailure(client)
                    failureReasons.add("${client.clientName}: exception=${e.javaClass.simpleName}: ${e.message}")
                    Log.w(TAG, "${client.clientName} failed: ${e.message}")
                    PlayerDiagnostics.logWarning(
                        TAG,
                        "skip ${client.clientName} v${client.clientVersion}: exception=${e.javaClass.simpleName}: ${e.message}",
                    )
                }
            }
            mint?.cancel()
            null
        }

    /**
     * Walks [SABR_CLIENTS] until one resolves a SABR session, so a bot wall on WEB alone does not
     * end the durable path.
     */
    private suspend fun trySabrClients(
        videoId: String,
        failureReasons: MutableList<String>,
        targetHeight: Int = 0,
        preferredCodec: String? = null,
        cpn: String = SabrCpn.generate(),
        reloadToken: String? = null,
    ): VideoExtractionResult? {
        val clients = SABR_CLIENTS.ungated()
        if (clients.isEmpty()) {
            // Returning null rather than retrying a refused client is what lets the forced-SABR
            // reload fall through to the full ladder in PlaybackLoadResolver, instead of re-minting
            // a BotGuard token for a client GVS has already refused twice.
            failureReasons.add("all SABR clients demoted: ${ClientGateTracker.gatedClients().joinToString()}")
            PlayerDiagnostics.logWarning(TAG, "SABR clients demoted for $videoId — falling through to the direct ladder")
            return null
        }
        for (client in clients) {
            tryWebSabr(
                videoId = videoId,
                failureReasons = failureReasons,
                targetHeight = targetHeight,
                preferredCodec = preferredCodec,
                cpn = cpn,
                reloadToken = reloadToken,
                client = client,
            )?.let { return it }
        }
        return null
    }

    /**
     * A web-like [client] + a WebView BotGuard PoToken + forced en-US locale. Produces a SABR
     * session (the response is SABR-only). Returns null when no visitorData / PoToken is available.
     */
    private suspend fun tryWebSabr(
        videoId: String,
        failureReasons: MutableList<String>,
        targetHeight: Int = 0,
        preferredCodec: String? = null,
        cpn: String = SabrCpn.generate(),
        reloadToken: String? = null,
        client: YouTubeClient = YouTubeClient.WEB,
        login: Boolean = false,
    ): VideoExtractionResult? {
        val label = client.clientName
        try {
            val visitorData = WebPoTokenSession.sessionVisitorData()
            if (visitorData.isNullOrEmpty()) {
                failureReasons.add("$label: no visitorData")
                Log.w(TAG, "$label+SABR: no visitorData available")
                return null
            }
            val poToken = WebPoTokenSession.mintForVisitorData(videoId, visitorData)
            if (poToken == null) {
                failureReasons.add("$label: PoToken unavailable (WebView missing/broken?)")
                Log.w(TAG, "$label+SABR: PoToken mint returned null (WebView missing/broken?)")
                return null
            }
            val sts = withTimeoutOrNull(SIGNATURE_TIMESTAMP_WAIT_MS) { CipherDeobfuscator.ensureSignatureTimestamp() }

            val playerResponse =
                withTimeoutOrNull(WEB_PLAYER_TIMEOUT_MS) {
                    YouTube
                        .playerWeb(
                            videoId = videoId,
                            signatureTimestamp = sts,
                            poToken = poToken.playerRequestPoToken,
                            visitorData = visitorData,
                            locale = YouTubeLocale.EXTRACTION,
                            cpn = cpn,
                            reloadToken = reloadToken,
                            client = client,
                            login = login,
                        ).getOrNull()
                }
            if (playerResponse == null) {
                failureReasons.add("$label: timeout or null response")
                Log.w(TAG, "$label+SABR: player request timeout/null")
                return null
            }

            val status = playerResponse.playabilityStatus.status
            if (status != "OK") {
                val reason = playerResponse.playabilityStatus.reason
                val tag = if (PlayabilityVerdict.isBotWall(reason)) "BOT_WALL" else "status=$status"
                failureReasons.add("$label: $tag, reason=$reason")
                Log.w(TAG, "$label: $tag, reason=$reason")
                // Members-only: ask again as the signed-in account, which is the only viewer allowed in.
                val membersWall =
                    reason?.let {
                        it.contains("member", ignoreCase = true) || it.contains("join this channel", ignoreCase = true)
                    } == true || status == "LOGIN_REQUIRED"
                if (!login && membersWall && YouTube.innerTube.isSignedIn) {
                    PlayerDiagnostics.logWarning(TAG, "$label: members-only wall for $videoId — retrying signed in")
                    return tryWebSabr(videoId, failureReasons, targetHeight, preferredCodec, cpn, reloadToken, client, login = true)
                }
                return null
            }

            // StreamerContext.po_token carries the videoId-bound content token, which is what GVS
            // accepts here in practice.
            //
            // The visitor-bound token used to draw sabr.media_serving_enforcement_id_error, and the
            // reason is now understood: it was minted against the whole visitorData blob rather than
            // the 11-char visitor ID GVS actually binds to (see [VisitorId]). That binding is fixed,
            // so the streaming token is a candidate here too — but this path is the durable fallback
            // and the videoId-bound token is the one measured to work, so switching wants an
            // on-device A/B rather than an assumption.
            val resolved =
                if (targetHeight > 0) {
                    SabrUrlResolver.resolveForQuality(
                        playerResponse,
                        targetHeight = targetHeight,
                        preferredCodec = preferredCodec,
                        injectedPoToken = poToken.playerRequestPoToken,
                        injectedVisitorData = visitorData,
                    )
                } else {
                    SabrUrlResolver.resolve(
                        playerResponse,
                        preferredCodec = preferredCodec,
                        injectedPoToken = poToken.playerRequestPoToken,
                        injectedVisitorData = visitorData,
                    )
                }
            if (resolved == null) {
                failureReasons.add("$label: SABR resolve failed (no serverAbrStreamingUrl / formats)")
                Log.w(TAG, "$label+SABR: resolve failed — no serverAbrStreamingUrl/formats (pot/ustreamer present?)")
                return null
            }

            val withTransformedN =
                try {
                    val transformedUrl =
                        transformNParamInUrlOrNull(
                            videoId = videoId,
                            rawUrl = resolved.streamingUrl,
                            label = "SABR",
                        )
                    if (transformedUrl == null) {
                        Log.w(TAG, "$label+SABR: n-transform unavailable; using the server SABR endpoint unchanged")
                        resolved.copy(cpn = cpn)
                    } else {
                        resolved.copy(streamingUrl = transformedUrl, cpn = cpn)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "$label+SABR: n-transform threw; using the server SABR endpoint unchanged: ${e.message}")
                    resolved.copy(cpn = cpn)
                }

            // Carried on the session so the GVS streaming request and any later reload report the
            // same client that minted this response.
            val sabrInfo =
                withTransformedN.copy(
                    clientNameId = SabrClientIdentity.clientNameId(client),
                    clientVersion = client.clientVersion,
                    clientUserAgent = client.userAgent,
                )

            // The SABR session is the point of this path, but these formats are what playback falls
            // back to whenever it is not the preferred source — and they used to be handed over
            // exactly as the response carried them: signature-ciphered, n-untransformed and
            // carrying no `pot`. Anything that reached them answered 403 on the first byte range,
            // which is the "stream URLs keep expiring" loop. Resolve them the way the direct path
            // resolves its own, so the fallback is a real fallback.
            val rawFormats = playerResponse.streamingData?.adaptiveFormats.orEmpty()
            val decipheredFormats = rawFormats.decipherWhilePossible(videoId)
            primeRemoteNsigIfNeeded(videoId, decipheredFormats)
            val streamingPot = poToken.streamingDataPoToken.takeIf { it.isNotEmpty() }
            val playableFormats =
                decipheredFormats
                    .mapNotNull { it.withPlayableUrl(videoId, allowUntransformedN = false) }
                    .let { formats ->
                        if (streamingPot == null) formats else formats.map { it.withUrlPoToken(streamingPot) }
                    }
            val videoFormats = playableFormats.filter { !it.isAudio && it.height != null }
            val audioFormats = playableFormats.filter { it.isAudio }

            val heights = videoFormats.mapNotNull { it.height }.distinct().sorted()
            Log.w(
                TAG,
                "$label+PoToken (SABR) resolved: ${videoFormats.size} video (${heights.joinToString()}p), " +
                    "${audioFormats.size} audio, sabr=true, fallbackAttested=${streamingPot != null} " +
                    "(${playableFormats.size}/${rawFormats.size} formats playable)",
            )
            PlayerDiagnostics.logWarning(
                TAG,
                "$label+SABR $videoId: session ready, direct fallback ${playableFormats.size}/${rawFormats.size} " +
                    "formats pot=${streamingPot != null}",
            )

            // A SABR session on its own cannot play — the server never sends an init segment, so
            // ExoPlayer has nothing to sniff. Its direct formats are the whole value of this path,
            // and without them the ladder is better off trying the next client than returning a
            // result that only looks playable.
            if (videoFormats.isEmpty() || audioFormats.isEmpty()) {
                failureReasons.add("$label: SABR resolved but no format survived decipher/n-transform")
                PlayerDiagnostics.logWarning(
                    TAG,
                    "$label+SABR $videoId: session resolved but 0 playable formats — falling through to the next client",
                )
                return null
            }

            return VideoExtractionResult(
                videoFormats = videoFormats,
                audioFormats = audioFormats,
                playerResponse = playerResponse,
                usedClient = client,
                sabrInfo = sabrInfo,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failureReasons.add("$label: exception=${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "$label+SABR failed: ${e.message}")
            return null
        }
    }

    private suspend fun tryLiveClients(
        videoId: String,
        failureReasons: MutableList<String>,
    ): VideoExtractionResult? {
        val sts: Int? =
            if (LIVE_MANIFEST_CLIENTS.any { it.useSignatureTimestamp }) {
                CipherDeobfuscator.ensureSignatureTimestamp()
            } else {
                null
            }

        for (client in LIVE_MANIFEST_CLIENTS) {
            try {
                val playerResponse =
                    withTimeoutOrNull(PER_CLIENT_TIMEOUT_MS) {
                        YouTube
                            .player(
                                videoId,
                                client = client,
                                signatureTimestamp = if (client.useSignatureTimestamp) sts else null,
                                localeOverride = YouTubeLocale.EXTRACTION,
                                apiUrl = YouTubeClient.API_URL_YOUTUBE,
                            ).getOrNull()
                    }
                if (playerResponse == null) {
                    failureReasons.add("${client.clientName}(live): timeout or null response")
                    continue
                }
                if (playerResponse.playabilityStatus.status != "OK") {
                    failureReasons.add("${client.clientName}(live): status=${playerResponse.playabilityStatus.status}")
                    continue
                }
                playerResponse.toLiveResultOrNull(client)?.let {
                    Log.w(TAG, "Live manifest via ${client.clientName} (hls=${it.liveHlsUrl != null}, dash=${it.liveDashUrl != null})")
                    return it
                }
                failureReasons.add("${client.clientName}(live): no hls/dash manifest")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failureReasons.add("${client.clientName}(live): exception=${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    /** A status refusal is an answer; only a request that never came back is worth repeating. */
    private fun Result<PlayerResponse>?.isTimeout(): Boolean = this == null || exceptionOrNull() is TimeoutCancellationException

    private fun PlayerResponse.isLiveNow(): Boolean =
        LiveDetectionRules.isLiveNow(
            isLive = videoDetails?.isLive,
            isPostLiveDvr = videoDetails?.isPostLiveDvr,
            hasLiveStreamability = playabilityStatus.liveStreamability != null,
            hasHlsManifest = !streamingData?.hlsManifestUrl.isNullOrBlank(),
            hasAdaptiveFormats = !streamingData?.adaptiveFormats.isNullOrEmpty(),
        )

    private fun PlayerResponse.toLiveResultOrNull(client: YouTubeClient): VideoExtractionResult? {
        val hls = streamingData?.hlsManifestUrl?.takeIf { it.isNotBlank() }
        val dash = streamingData?.dashManifestUrl?.takeIf { it.isNotBlank() }
        if (hls == null && dash == null) return null
        return VideoExtractionResult(
            videoFormats = emptyList(),
            audioFormats = emptyList(),
            playerResponse = this,
            usedClient = client,
            sabrInfo = null,
            isLive = true,
            liveHlsUrl = hls,
            liveDashUrl = dash,
        )
    }

    /**
     * Whether YouTube asked this client to attest for this video, and under what shape.
     *
     * Reported next to the format counts because the two together are the question that matters
     * when a client's URLs are refused: was the response served freely, or did the server already
     * signal that it wanted proof this client could not give?
     */
    internal fun PlayerResponse.attestationDemand(): String {
        val renderer = attestation?.playerAttestationRenderer ?: return "none"
        val challenge = renderer.challenge ?: return "empty"
        val shared = if (renderer.useSharedChallenge == true) ",shared" else ""
        return "yes(${challenge.length}c$shared)"
    }

    /**
     * Gives every format of a ladder a real `url`, solving its `signatureCipher` first. Done for the
     * whole ladder before any of it is n-transformed, because [primeRemoteNsigIfNeeded] can only
     * sample formats that already have a URL.
     *
     * Resolution order (NewPipe is deliberately last):
     *  1. the in-app player-script signature function ([CipherDeobfuscator]);
     *  2. one remote batch for whatever the local function could not solve ([PipePipeNsigDecoder]);
     *  3. NewPipe's own decoder, per format, only for formats still unsolved after 1 and 2.
     */
    private suspend fun List<PlayerResponse.StreamingData.Format>.decipherWhilePossible(
        videoId: String,
    ): List<PlayerResponse.StreamingData.Format> {
        val ciphers =
            filter { it.url.isNullOrEmpty() }
                .mapNotNull { format -> SignatureCipher.parse(format.signatureCipher ?: format.cipher)?.let { format to it } }
                .toMap()
        val solved = if (ciphers.isEmpty()) emptyMap() else solveSignatures(videoId, ciphers.values.map { it.signature })
        var newPipeUsable = true
        return mapNotNull { format ->
            when {
                !format.url.isNullOrEmpty() -> {
                    format
                }

                else -> {
                    val inApp =
                        ciphers[format]?.let { cipher -> solved[cipher.signature]?.let { format.copy(url = cipher.signedUrl(it)) } }
                    if (inApp != null) {
                        inApp
                    } else if (newPipeUsable && (!format.signatureCipher.isNullOrEmpty() || !format.cipher.isNullOrEmpty())) {
                        newPipeResolvedUrl(format, videoId).also {
                            // NewPipe failing once means its player script is unreadable too; stop
                            // asking per format so a dead decoder cannot burn the extraction budget.
                            if (it == null) newPipeUsable = false
                        }
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * The player script's own signature function first, then one remote batch for whatever it
     * left. Whether the local function works is a property of the script, not of the format, so
     * the first local failure sends the rest remote instead of retrying each (measured 2026-09-22:
     * ~30 failing WebView round trips blew the extraction budget).
     *
     * The remote decoder is asked for the local script's hash: a signature only matches the
     * timestamp the `/player` request carried, which is the local script's.
     */
    private suspend fun solveSignatures(
        videoId: String,
        signatures: List<String>,
    ): Map<String, String> {
        val wanted = signatures.distinct()
        val solved = mutableMapOf<String, String>()
        for (signature in wanted) {
            solved[signature] = CipherDeobfuscator.deobfuscateSignature(signature) ?: break
        }
        val missing = wanted.filterNot { it in solved }
        if (missing.isEmpty()) return solved
        val playerHash = CipherDeobfuscator.playerHash()
        if (playerHash == null) {
            Log.w(TAG, "Signature deciphering unavailable for $videoId — no player script")
            return solved
        }
        Log.w(TAG, "Local signature function unavailable for $videoId; solving ${missing.size} remotely for $playerHash")
        PlayerDiagnostics.logWarning(TAG, "local signature FAILED for $videoId — remote decoder for player $playerHash")
        solved += PipePipeNsigDecoder.decodeSignatures(missing, playerId = playerHash)
        return solved
    }

    /**
     * Last resort for a ciphered format: NewPipe's own decoder. Its result already has `n`
     * transformed; [withPlayableUrl] accepts that because it carries no untransformed `n`.
     */
    private suspend fun newPipeResolvedUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
    ): PlayerResponse.StreamingData.Format? {
        val resolved =
            try {
                NewPipeExtractor.getStreamUrl(format, videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "NewPipe cipher fallback failed for $videoId itag=${format.itag}: ${e.message}")
                null
            }
        return resolved?.takeIf { it.isNotEmpty() }?.let { format.copy(url = it) }
    }

    /**
     * GVS's non-SABR stream protection: the streaming (visitor-bound) PoToken rides on the
     * direct URL as the `pot` query param. Appended after the n-transform so the transform
     * never sees or reorders it.
     */
    private fun PlayerResponse.StreamingData.Format.withUrlPoToken(pot: String): PlayerResponse.StreamingData.Format {
        val rawUrl = url ?: return this
        if (POT_PARAM_REGEX.containsMatchIn(rawUrl)) return this
        val separator = if ("?" in rawUrl) "&" else "?"
        return copy(url = "$rawUrl${separator}pot=${Uri.encode(pot)}")
    }

    private suspend fun PlayerResponse.StreamingData.Format.withPlayableUrl(
        videoId: String,
        allowUntransformedN: Boolean,
    ): PlayerResponse.StreamingData.Format? {
        val rawUrl = url ?: return this
        val transformed = transformNParamInUrlOrNull(videoId, rawUrl, "itag=$itag")
        return when {
            transformed != null -> {
                copy(url = transformed)
            }

            allowUntransformedN -> {
                Log.w(TAG, "Using untransformed n URL as last-resort fallback for $videoId itag=$itag; playback may throttle")
                this
            }

            else -> {
                Log.w(TAG, "Rejecting untransformed n URL for $videoId itag=$itag; direct playback would likely throttle")
                null
            }
        }
    }

    private suspend fun transformNParamInUrlOrNull(
        videoId: String,
        rawUrl: String,
        label: String,
    ): String? {
        val rawN = extractNParameter(rawUrl) ?: return rawUrl
        return try {
            val transformed =
                localNTransformOrNull(rawUrl, rawN)
                    ?: PipePipeNsigDecoder
                        .deobfuscateUrl(rawUrl)
                        ?.takeIf { isNParameterTransformed(rawN, it) }
                    ?: newPipeNTransformOrNull(videoId, rawUrl, rawN)
            if (transformed != null) {
                Log.d(TAG, "Applied n-transform for $videoId $label")
            }
            transformed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "n-transform failed for $videoId $label: ${e.message}")
            null
        }
    }

    /** The player script's own n function. Null when it could not transform [rawN]. */
    private suspend fun localNTransformOrNull(
        rawUrl: String,
        rawN: String,
    ): String? =
        CipherDeobfuscator
            .transformNParamInUrl(rawUrl)
            .takeIf { isNParameterTransformed(rawN, it) }

    /** NewPipe's decoder: the last n-transform strategy, tried only after the in-app and remote ones failed. */
    private fun newPipeNTransformOrNull(
        videoId: String,
        rawUrl: String,
        rawN: String,
    ): String? =
        NewPipeExtractor
            .deobfuscateThrottling(videoId, rawUrl)
            ?.takeIf { isNParameterTransformed(rawN, it) }

    /**
     * Batch-primes the remote n decoder, but only once both local decoders have failed on a
     * representative URL.
     *
     * The remote decoder is the third fallback in [transformNParamInUrlOrNull], yet priming it
     * unconditionally put a third-party round trip in front of *every* extraction — the dominant
     * cost of the InnerTube leg on a cold start. Probing locally first keeps that round trip off
     * the path to first frame whenever a local decoder can do the job, while still collapsing the
     * whole ladder into one request when it genuinely is needed. The probe is not wasted work:
     * both local decoders cache their player script, so it warms exactly what the per-format loop
     * below is about to ask for.
     */
    private suspend fun primeRemoteNsigIfNeeded(
        videoId: String,
        adaptiveFormats: List<PlayerResponse.StreamingData.Format>,
    ) {
        val urls = adaptiveFormats.mapNotNull { it.url }
        val sample =
            urls.firstNotNullOfOrNull { url ->
                extractNParameter(url)?.let { url to it }
            } ?: return
        if (localNTransformOrNull(sample.first, sample.second) != null) return
        Log.w(TAG, "Local n-decoder failed for $videoId; priming remote decoder for ${urls.size} formats")
        PlayerDiagnostics.logWarning(
            TAG,
            "local n-decoders FAILED for $videoId" +
                (CipherDeobfuscator.unparseablePlayerHash?.let { " — player JS $it is unreadable to this build" } ?: "") +
                " — falling back to the remote decoder",
        )
        PipePipeNsigDecoder.prefetch(urls)
    }

    private fun isNParameterTransformed(
        rawN: String,
        candidateUrl: String,
    ): Boolean {
        if (candidateUrl.isBlank()) return false
        val candidateN = extractNParameter(candidateUrl) ?: return candidateUrl != rawN
        return candidateN != rawN
    }

    private fun extractNParameter(url: String): String? =
        try {
            Uri.parse(url).getQueryParameter("n")
        } catch (_: Exception) {
            N_PARAM_REGEX.find(url)?.groupValues?.getOrNull(1)
        }
}
