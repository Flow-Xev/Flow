package app.zaid.flow.innertube.update

internal enum class Verdict { ACCEPT, REJECT, DEFER }

/** Result of the canary request: FAIL only when a control request with the current version passed. */
internal enum class Probe { PASS, FAIL, INCONCLUSIVE }

internal class Review(
    val verdict: Verdict,
    val reason: String,
)

internal data class Candidate(
    val client: String,
    val version: String,
    /** The version compiled into this app. */
    val baseline: String,
    /** The version in use right now. */
    val current: String,
    /** True when YouTube's own page serves exactly this version. */
    val confirmedLive: Boolean,
    val blocked: Set<String> = emptySet(),
    val quarantined: Boolean = false,
)

/**
 * The reviewer: nothing becomes a live client version without passing here.
 * Layer 1 (offline rules) rejects anything malformed, vetoed, previously harmful, from the future or implausibly far ahead.
 * Layer 2 (canary probe, run by the updater) must agree. A version only YouTube-confirmed may pass when the probe
 * cannot run; a version only a manifest claims may not (fail closed).
 */
internal object ClientReviewer {
    private val SHAPES =
        mapOf(
            "WEB" to Regex("^2\\.(\\d{8})\\.\\d{2}\\.\\d{2}$"),
            "MWEB" to Regex("^2\\.(\\d{8})\\.\\d{2}\\.\\d{2}$"),
            "WEB_REMIX" to Regex("^1\\.(\\d{8})\\.\\d{2}\\.\\d{2}$"),
            "TVHTML5" to Regex("^\\d\\.(\\d{8})\\.\\d{2}\\.\\d{2}$"),
        )
    private const val DAY_MS = 86_400_000L
    private const val FUTURE_SLACK_DAYS = 3L
    private const val MAX_UNCONFIRMED_JUMP_DAYS = 120L

    /** Epoch day encoded in the version, or null when the shape or the calendar date is invalid. */
    fun versionDay(
        client: String,
        version: String,
    ): Long? {
        val digits = SHAPES[client]?.matchEntire(version)?.groupValues?.get(1) ?: return null
        val y = digits.substring(0, 4).toInt()
        val m = digits.substring(4, 6).toInt()
        val d = digits.substring(6, 8).toInt()
        if (y !in 2015..2100 || m !in 1..12 || d !in 1..daysIn(y, m)) return null
        return epochDay(y, m, d)
    }

    fun wellFormed(
        client: String,
        version: String,
    ) = versionDay(client, version) != null

    private fun daysIn(
        y: Int,
        m: Int,
    ) = when (m) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
    }

    private fun epochDay(
        year: Int,
        month: Int,
        day: Int,
    ): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468
    }

    /** Offline rules. Null means "no objection"; otherwise a REJECT with the reason. */
    fun staticReview(
        c: Candidate,
        nowMs: Long,
    ): Review? {
        val day = versionDay(c.client, c.version) ?: return Review(Verdict.REJECT, "malformed version ${c.version}")
        // Never a step back (or sideways): the candidate must beat both what runs now and the factory version.
        if (!isNewerVersion(c.version, c.current) || !isNewerVersion(c.version, c.baseline)) {
            return Review(Verdict.REJECT, "not newer than the version in use")
        }
        if (c.version.substringBefore('.') != c.baseline.substringBefore('.')) return Review(Verdict.REJECT, "major version changed")
        if (c.version in c.blocked) return Review(Verdict.REJECT, "vetoed by manifest")
        if (c.quarantined) return Review(Verdict.REJECT, "quarantined after an earlier failure")
        if (day > nowMs / DAY_MS + FUTURE_SLACK_DAYS) return Review(Verdict.REJECT, "dated in the future")
        val currentDay = versionDay(c.client, c.current)
        if (!c.confirmedLive && currentDay != null && day - currentDay > MAX_UNCONFIRMED_JUMP_DAYS) {
            return Review(Verdict.REJECT, "unconfirmed jump of ${day - currentDay} days")
        }
        return null
    }

    fun finalVerdict(
        probe: Probe,
        confirmedLive: Boolean,
    ): Review =
        when (probe) {
            Probe.PASS -> Review(Verdict.ACCEPT, "probe passed")
            Probe.FAIL -> Review(Verdict.REJECT, "probe failed while the control passed")
            Probe.INCONCLUSIVE ->
                if (confirmedLive) {
                    Review(Verdict.ACCEPT, "served by youtube.com, probe inconclusive")
                } else {
                    Review(Verdict.DEFER, "unconfirmed and probe inconclusive")
                }
        }

    fun decide(
        c: Candidate,
        nowMs: Long,
        probe: Probe,
    ): Review = staticReview(c, nowMs) ?: finalVerdict(probe, c.confirmedLive)
}
