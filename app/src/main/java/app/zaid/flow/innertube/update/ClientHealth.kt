package app.zaid.flow.innertube.update

import java.util.concurrent.atomic.AtomicInteger

internal enum class Outcome { CONTINUE, ROLLBACK, GRADUATE }

private const val DAY_MS = 86_400_000L
internal const val QUARANTINE_MS = 7 * DAY_MS
private const val PROBATION_MAX_AGE_MS = 7 * DAY_MS

/** Pure decision for a version on probation: ~25% rejected answers roll it back, a clean record promotes it. */
internal fun evaluateProbation(
    ok: Int,
    bad: Int,
    ageMs: Long,
): Outcome =
    when {
        bad >= 3 && bad * 4 >= ok -> Outcome.ROLLBACK
        ok >= 10 && bad * 5 <= ok -> Outcome.GRADUATE
        ageMs >= PROBATION_MAX_AGE_MS -> if (bad > 0 && bad * 4 >= ok) Outcome.ROLLBACK else Outcome.GRADUATE
        else -> Outcome.CONTINUE
    }

/** Promotes the live versions of [clients] to "stable", never replacing a stable version with an older one. */
internal fun promoteStable(
    stable: Map<String, String>,
    versions: Map<String, String>,
    clients: Set<String>,
): Map<String, String> =
    stable +
        clients.mapNotNull { k ->
            val v = versions[k] ?: return@mapNotNull null
            val old = stable[k]
            if (old == null || isNewerVersion(v, old)) k to v else null
        }

/**
 * Watches real traffic after a new version goes live. A bad version is rolled back to the last proven one,
 * quarantined for a week, and written to the log; a good one is promoted to "stable".
 */
object ClientHealth {
    private val okCount = AtomicInteger()
    private val badCount = AtomicInteger()

    @Volatile
    private var epoch = 0L

    /** Called per real request outcome (success, or a 4xx rejection). Cheap when nothing is on probation. */
    fun record(success: Boolean) {
        runCatching {
            val p = ClientUpdateSettings.state.value.probation ?: return
            if (p.since != epoch) {
                epoch = p.since
                okCount.set(0)
                badCount.set(0)
            }
            if (success) okCount.incrementAndGet() else badCount.incrementAndGet()
            act(p)
        }
    }

    /** Lets age alone settle a probation (e.g. a quiet week). Called at the start of every check. */
    internal fun settle() {
        runCatching { ClientUpdateSettings.state.value.probation?.let { act(it) } }
    }

    private fun act(p: Probation) {
        val ok = okCount.get()
        val bad = badCount.get()
        when (evaluateProbation(ok, bad, System.currentTimeMillis() - p.since)) {
            Outcome.ROLLBACK -> rollbackClients(p.clients, "unhealthy: $bad rejected / $ok ok")
            Outcome.GRADUATE -> graduate(ok, bad)
            Outcome.CONTINUE -> Unit
        }
    }

    private fun graduate(
        ok: Int,
        bad: Int,
    ) = ClientUpdateSettings.update { c ->
        val p = c.probation ?: return@update c
        c
            .copy(
                stable = promoteStable(c.stable, c.versions, p.clients),
                probation = null,
            ).withLog("verified ${p.clients.sorted().joinToString()} ($ok ok / $bad rejected)")
    }

    /** Puts [keys] back to their last proven version (or the built-in one) and quarantines what was live. */
    internal fun rollbackClients(
        keys: Set<String>,
        reason: String,
    ) = ClientUpdateSettings.update { c ->
        val now = System.currentTimeMillis()
        val quarantined = c.quarantine + keys.mapNotNull { k -> c.versions[k]?.let { "$k@$it" to now + QUARANTINE_MS } }
        val restored = keys.mapNotNull { k -> c.stable[k]?.let { k to it } }.toMap()
        c
            .copy(
                versions = c.versions - keys + restored,
                quarantine = quarantined.filterValues { it > now },
                probation =
                    c.probation
                        ?.let { p -> p.copy(clients = p.clients - keys) }
                        ?.takeIf { it.clients.isNotEmpty() },
            ).withLog("rolled back ${keys.sorted().joinToString()}: $reason")
    }
}
