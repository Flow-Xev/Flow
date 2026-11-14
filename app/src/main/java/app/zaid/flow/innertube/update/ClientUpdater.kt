package app.zaid.flow.innertube.update

import android.util.Log
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.models.YouTubeClient
import app.zaid.flow.innertube.parseAccountWebSession
import app.zaid.flow.network.ProxyAwareClient
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Finds newer YouTube client versions, has every one reviewed ([ClientReviewer] + a canary probe) and only then
 * applies it, honouring [ClientUpdateMode]. Applied versions go on probation ([ClientHealth]) and are rolled back
 * automatically if real traffic suffers.
 *
 * Sources, newest wins: the live pages YouTube serves (first-party truth) and an optional https manifest
 * ([MANIFEST_URL]) that can also announce features and veto bad versions.
 */
object ClientUpdater {
    private const val TAG = "ClientUpdater"
    private const val DAY_MS = 86_400_000L
    private const val EMERGENCY_COOLDOWN_MS = 24 * 3_600_000L
    private const val MAX_PAGE_BYTES = 1_500_000L

    /** Optional: https URL of a client-versions.json (template in the repo root). Empty = live pages only. */
    const val MANIFEST_URL = ""

    enum class Status { OFF, UP_TO_DATE, UPDATES_FOUND, APPLIED, REJECTED, FAILED }

    internal class Report(
        val status: Status,
        val changes: List<Change> = emptyList(),
        val rejected: List<String> = emptyList(),
    )

    private val http = ProxyAwareClient { callTimeout(15, TimeUnit.SECONDS) }
    private val mutex = Mutex()
    private val liveVersion = Regex("^[0-9]+(\\.[0-9]+){1,4}$")

    private val livePages: List<Triple<String, String, String>> by lazy {
        listOf(
            Triple("WEB", "https://www.youtube.com/", YouTubeClient.USER_AGENT_WEB),
            Triple("MWEB", "https://m.youtube.com/", YouTubeClient.USER_AGENT_MWEB),
            Triple("WEB_REMIX", "https://music.youtube.com/", YouTubeClient.USER_AGENT_WEB),
            Triple("TVHTML5", "https://www.youtube.com/tv", YouTubeClient.TVHTML5.userAgent),
        )
    }

    /**
     * Searches for updates. [apply] false only reports what exists. OFF when the user switched updates off (unless
     * [force]); FAILED when no source could be read (last-check time is left alone, so a retry comes soon);
     * REJECTED when newer versions exist but none passed review.
     */
    internal suspend fun check(
        apply: Boolean,
        force: Boolean = false,
    ): Report =
        mutex.withLock {
            ClientUpdateSettings.ensureLoaded()
            if (ClientUpdateSettings.state.value.mode == ClientUpdateMode.OFF && !force) return@withLock Report(Status.OFF)
            ClientHealth.settle()

            val (manifest, live) =
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val m = async { fetchManifest() }
                        val l = async { fetchLivePages() }
                        m.await() to l.await()
                    }
                }
            val found = mergeNewest(manifest, live)
            if (found.isEmpty()) return@withLock Report(Status.FAILED)

            // Remote kill-switch: a live version the manifest vetoes goes back immediately.
            if (apply) {
                val vetoed = ClientUpdateSettings.state.value.versions.filter { (k, v) -> v in found[k]?.blocked.orEmpty() }.keys
                if (vetoed.isNotEmpty()) ClientHealth.rollbackClients(vetoed, "vetoed by manifest")
            }

            val cfg = ClientUpdateSettings.state.value
            val now = System.currentTimeMillis()
            val plan =
                planChanges(
                    found,
                    current = ClientVersions::current,
                    knownFeatures = { cfg.features[it].orEmpty() + cfg.pending[it].orEmpty() },
                )
            if (!apply) return@withLock Report(if (plan.isEmpty()) Status.UP_TO_DATE else Status.UPDATES_FOUND, plan)

            // Review every version move in parallel; feature-only announcements need no probe.
            val verdicts =
                coroutineScope {
                    plan
                        .filter { it.versionChanged }
                        .map { ch -> async(Dispatchers.IO) { ch to review(ch, found.getValue(ch.client), live[ch.client], cfg, now) } }
                        .awaitAll()
                }.toMap()
            val applied = plan.filter { !it.versionChanged || verdicts[it]?.verdict == Verdict.ACCEPT }
            val rejected = plan.filter { it.versionChanged && verdicts[it]?.verdict != Verdict.ACCEPT }
            val rejectedText = rejected.map { "${it.client} ${it.to}: ${verdicts[it]?.reason}" }
            val newQuarantine =
                rejected
                    .filter { verdicts[it]?.verdict == Verdict.REJECT }
                    .associate { "${it.client}@${it.to}" to now + QUARANTINE_MS }

            // The review above took seconds; what is live may have moved since (a rollback, a veto, another adoption).
            // So every move is checked once more against the state at the moment of writing, and a step back is dropped.
            var adopted: List<Change> = emptyList()
            ClientUpdateSettings.update { c ->
                var versions = c.versions
                var features = c.features
                var pending = c.pending
                val done = mutableListOf<Change>()
                for (ch in applied) {
                    if (ch.versionChanged) {
                        val live = versions[ch.client] ?: ClientVersions.factory(ch.client)
                        if (!canAdopt(ch.to, live, c.quarantine["${ch.client}@${ch.to}"], now)) continue
                        versions = versions + (ch.client to ch.to)
                    }
                    done += ch
                    if (ch.newFeatures.isEmpty()) continue
                    if (c.autoFeatures) {
                        features = features + (ch.client to features[ch.client].orEmpty() + ch.newFeatures)
                    } else {
                        pending = pending + (ch.client to pending[ch.client].orEmpty() + ch.newFeatures)
                    }
                }
                adopted = done
                val moved = done.filter { it.versionChanged }.map { it.client }.toSet()
                var next =
                    c.copy(
                        versions = versions,
                        features = features,
                        pending = pending,
                        quarantine = (c.quarantine + newQuarantine).filterValues { it > now },
                        probation = if (moved.isNotEmpty()) Probation(now, c.probation?.clients.orEmpty() + moved) else c.probation,
                        lastCheck = now,
                        lastSummary = summary(done),
                    )
                done.forEach { next = next.withLog("adopted ${describe(it)} (${verdicts[it]?.reason ?: "features"})", now) }
                rejectedText.forEach { next = next.withLog("refused $it", now) }
                next
            }
            Log.i(TAG, "check: ${adopted.size} applied, ${rejected.size} refused")
            val status =
                when {
                    adopted.isNotEmpty() -> Status.APPLIED
                    rejected.isNotEmpty() -> Status.REJECTED
                    else -> Status.UP_TO_DATE
                }
            Report(status, adopted, rejectedText)
        }

    /**
     * Factory reset. Runs under the same lock as [check], so a check that is mid-flight finishes first and cannot
     * put a learned version back after the reset.
     */
    suspend fun resetToFactory() = mutex.withLock { ClientUpdateSettings.resetToFactory() }

    /** App-start catch-up: only in AUTO mode and only when the interval (default one week) has passed. */
    internal suspend fun runIfDue(): Report? {
        ClientUpdateSettings.ensureLoaded()
        val c = ClientUpdateSettings.state.value
        if (c.mode != ClientUpdateMode.AUTO) return null
        if (System.currentTimeMillis() - c.lastCheck < c.intervalDays * DAY_MS) return null
        return check(apply = true)
    }

    /**
     * Called when YouTube rejects a request (the usual sign of a stale client). AUTO mode only, at most once a day,
     * so unrelated 4xx answers cannot become a request storm. Never throws: a failure here must not break the retry.
     */
    suspend fun emergencyRefresh(): Boolean {
        try {
            ClientUpdateSettings.ensureLoaded()
            val c = ClientUpdateSettings.state.value
            val now = System.currentTimeMillis()
            if (c.mode != ClientUpdateMode.AUTO || now - c.lastEmergency < EMERGENCY_COOLDOWN_MS) return false
            ClientUpdateSettings.update { it.copy(lastEmergency = now) }
            return check(apply = true).status == Status.APPLIED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "emergency refresh failed: ${e.javaClass.simpleName}")
            return false
        }
    }

    // ---------- review ----------
    private suspend fun review(
        ch: Change,
        remote: RemoteClient,
        live: RemoteClient?,
        cfg: ClientUpdateConfig,
        now: Long,
    ): Review {
        val base = ClientVersions.updatable[ch.client]?.clientVersion ?: return Review(Verdict.REJECT, "client not movable")
        val candidate =
            Candidate(
                client = ch.client,
                version = ch.to,
                baseline = base,
                current = ch.from,
                confirmedLive = live?.version == ch.to,
                blocked = remote.blocked.toSet(),
                quarantined = (cfg.quarantine["${ch.client}@${ch.to}"] ?: 0L) > now,
            )
        ClientReviewer.staticReview(candidate, now)?.let { return it }
        return ClientReviewer.finalVerdict(probe(ch.client, ch.from, ch.to), candidate.confirmedLive)
    }

    /**
     * Canary: the same read-only request sent with the control (current) version and the candidate, through Flow's own
     * InnerTube path so it carries the real session (visitor data, and the account's sign-in when signed in). FAIL only
     * when the control is healthy and the candidate is not, so a broken probe can never condemn a good version.
     */
    private suspend fun probe(
        client: String,
        control: String,
        candidate: String,
    ): Probe {
        if (attempt { probeCall(client, control) } != true) return Probe.INCONCLUSIVE
        val healthy = attempt { probeCall(client, candidate) } ?: return Probe.INCONCLUSIVE
        return if (healthy) Probe.PASS else Probe.FAIL
    }

    /** True = healthy answer; false = YouTube rejected or garbled it; throws on network trouble or 429/5xx. */
    private suspend fun probeCall(
        client: String,
        version: String,
    ): Boolean {
        val base = ClientVersions.updatable.getValue(client)
        val text =
            try {
                YouTube.innerTube.probe(base.copy(clientVersion = version))
            } catch (e: ResponseException) {
                val code = e.response.status.value
                if (code == 429 || code in 500..599) throw IOException("HTTP $code")
                return false
            }
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        return "responseContext" in json && ("contents" in json || "estimatedResults" in json)
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    // ---------- sources ----------
    private fun summary(changes: List<Change>): String = changes.joinToString("; ") { describe(it) }

    private fun describe(ch: Change): String =
        buildString {
            append(ch.client)
            if (ch.versionChanged) append(' ').append(ch.from).append(" → ").append(ch.to)
            if (ch.newFeatures.isNotEmpty()) append(" +").append(ch.newFeatures.joinToString(","))
        }

    private fun fetchManifest(): Map<String, RemoteClient> =
        if (!MANIFEST_URL.startsWith("https://")) {
            emptyMap()
        } else {
            runCatching { parseManifest(get(MANIFEST_URL, null)) }.getOrDefault(emptyMap())
        }

    private suspend fun fetchLivePages(): Map<String, RemoteClient> =
        coroutineScope {
            livePages
                .map { (key, url, ua) ->
                    async(Dispatchers.IO) {
                        val version = runCatching { parseAccountWebSession(get(url, ua))?.clientVersion }.getOrNull()
                        // A consent or error page yields nothing; only version-shaped values pass.
                        if (version != null && liveVersion.matches(version)) key to RemoteClient(version) else null
                    }
                }.awaitAll()
                .filterNotNull()
                .toMap()
        }

    private fun readBounded(response: Response): String {
        val source = response.body?.source() ?: error("empty body")
        source.request(MAX_PAGE_BYTES)
        return source.buffer.readUtf8()
    }

    private fun get(
        url: String,
        userAgent: String?,
    ): String {
        val request = Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()
        http.get().newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return readBounded(response)
        }
    }
}
