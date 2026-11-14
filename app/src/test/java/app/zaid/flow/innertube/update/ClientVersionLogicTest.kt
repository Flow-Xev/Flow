package app.zaid.flow.innertube.update

import app.zaid.flow.innertube.models.YouTubeClient
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class ClientVersionLogicTest {
    @After
    fun reset() = ClientVersions.setOverrides(emptyMap())

    @Test
    fun `versions compare numerically, not as text`() {
        assertThat(isNewerVersion("2.20261101.00.00", "2.20261022.01.00")).isTrue()
        assertThat(isNewerVersion("2.9", "2.10")).isFalse()
        assertThat(isNewerVersion("1.0", "1.0.0")).isFalse()
    }

    @Test
    fun `manifest drops malformed entries and unsafe strings`() {
        val parsed =
            parseManifest(
                """{"clients":{"WEB":{"version":"2.1.0","features":["live","BAD FEATURE"]},
                    "X":{"version":"<script>"},"Y":{"features":["a"]}}}""",
            )
        assertThat(parsed.keys).containsExactly("WEB")
        assertThat(parsed.getValue("WEB").features).containsExactly("live")
        assertThat(parseManifest("not json")).isEmpty()
    }

    @Test
    fun `merge keeps the newest version and every announced feature`() {
        val merged =
            mergeNewest(
                mapOf("WEB" to RemoteClient("2.1", listOf("a"))),
                mapOf("WEB" to RemoteClient("2.3"), "MWEB" to RemoteClient("2.0")),
            )
        assertThat(merged.getValue("WEB")).isEqualTo(RemoteClient("2.3", listOf("a")))
        assertThat(merged).hasSize(2)
    }

    @Test
    fun `plan skips pinned clients and known features`() {
        val found = mapOf("ANDROID" to RemoteClient("99.0"), "WEB" to RemoteClient("3.0", listOf("live", "old")))
        val plan = planChanges(found, current = { if (it == "WEB") "2.0" else null }, knownFeatures = { setOf("old") })
        assertThat(plan).containsExactly(Change("WEB", "2.0", "3.0", listOf("live")))
    }

    @Test
    fun `learned version applies only while newer than the compiled one`() {
        val base = YouTubeClient.WEB.clientVersion
        assertThat(YouTubeClient.WEB.effectiveVersion).isEqualTo(base)
        ClientVersions.setOverrides(mapOf("WEB" to "9.20990101.00.00"))
        assertThat(YouTubeClient.WEB.effectiveVersion).isEqualTo("9.20990101.00.00")
        ClientVersions.setOverrides(mapOf("WEB" to "1.0"))
        assertThat(YouTubeClient.WEB.effectiveVersion).isEqualTo(base)
        // pinned native client is never moved
        ClientVersions.setOverrides(mapOf("ANDROID" to "99.0"))
        assertThat(YouTubeClient.ANDROID.effectiveVersion).isEqualTo(YouTubeClient.ANDROID.clientVersion)
    }

    private val now = 1_795_000_000_000L // 2026-11-18

    private fun cand(
        version: String,
        confirmed: Boolean = true,
        current: String = "2.20261022.01.00",
        blocked: Set<String> = emptySet(),
        quarantined: Boolean = false,
    ) = Candidate("WEB", version, "2.20261022.01.00", current, confirmed, blocked, quarantined)

    @Test
    fun `reviewer rejects malformed, future, vetoed, quarantined and wildly jumping versions`() {
        fun verdict(c: Candidate) = ClientReviewer.decide(c, now, Probe.PASS).verdict
        assertThat(verdict(cand("2.20261101.00.00"))).isEqualTo(Verdict.ACCEPT)
        assertThat(verdict(cand("2.2026110.00.00"))).isEqualTo(Verdict.REJECT) // shape
        assertThat(verdict(cand("2.20261340.00.00"))).isEqualTo(Verdict.REJECT) // impossible date
        assertThat(verdict(cand("3.20261101.00.00"))).isEqualTo(Verdict.REJECT) // major changed
        assertThat(verdict(cand("2.20300101.00.00"))).isEqualTo(Verdict.REJECT) // future
        assertThat(verdict(cand("2.20261101.00.00", blocked = setOf("2.20261101.00.00")))).isEqualTo(Verdict.REJECT)
        assertThat(verdict(cand("2.20261101.00.00", quarantined = true))).isEqualTo(Verdict.REJECT)
        assertThat(verdict(cand("2.20261101.00.00", confirmed = false, current = "2.20250101.00.00"))).isEqualTo(Verdict.REJECT) // jump
    }

    @Test
    fun `probe decides only when it can, and fails closed for unconfirmed versions`() {
        val live = cand("2.20261101.00.00", confirmed = true)
        val claimed = cand("2.20261101.00.00", confirmed = false)
        assertThat(ClientReviewer.decide(live, now, Probe.FAIL).verdict).isEqualTo(Verdict.REJECT)
        assertThat(ClientReviewer.decide(live, now, Probe.INCONCLUSIVE).verdict).isEqualTo(Verdict.ACCEPT)
        assertThat(ClientReviewer.decide(claimed, now, Probe.INCONCLUSIVE).verdict).isEqualTo(Verdict.DEFER)
        assertThat(ClientReviewer.decide(claimed, now, Probe.PASS).verdict).isEqualTo(Verdict.ACCEPT)
    }

    @Test
    fun `probation rolls back on rejections, promotes on a clean record, settles by age`() {
        val day = 86_400_000L
        assertThat(evaluateProbation(ok = 4, bad = 3, ageMs = 0)).isEqualTo(Outcome.ROLLBACK)
        assertThat(evaluateProbation(ok = 40, bad = 3, ageMs = 0)).isEqualTo(Outcome.GRADUATE)
        assertThat(evaluateProbation(ok = 2, bad = 0, ageMs = day)).isEqualTo(Outcome.CONTINUE)
        assertThat(evaluateProbation(ok = 0, bad = 0, ageMs = 8 * day)).isEqualTo(Outcome.GRADUATE)
        assertThat(evaluateProbation(ok = 3, bad = 1, ageMs = 8 * day)).isEqualTo(Outcome.ROLLBACK)
    }

    @Test
    fun `manifest blocked versions are parsed and merged`() {
        val m = parseManifest("""{"clients":{"WEB":{"version":"2.1.0","blocked":["2.0.9","bad one"]}}}""")
        assertThat(m.getValue("WEB").blocked).containsExactly("2.0.9")
        val merged = mergeNewest(m, mapOf("WEB" to RemoteClient("2.2.0", blocked = listOf("2.0.8"))))
        assertThat(merged.getValue("WEB").blocked).containsExactly("2.0.9", "2.0.8")
    }

    @Test
    fun `history is capped`() {
        var c = ClientUpdateConfig()
        repeat(50) { c = c.withLog("e$it", now) }
        assertThat(c.history).hasSize(ClientUpdateConfig.MAX_HISTORY)
        assertThat(c.history.last()).endsWith("e49")
    }

    @Test
    fun `explicit versions are respected and user-agent copies still follow the learned version`() {
        ClientVersions.setOverrides(mapOf("WEB" to "9.20990101.00.00", "TVHTML5" to "9.20990101.00.00"))
        // the reviewer's probe builds a client with the candidate version: it must be sent as is
        assertThat(YouTubeClient.WEB.copy(clientVersion = "2.20261101.00.00").effectiveVersion).isEqualTo("2.20261101.00.00")
        // tvBrowse uses a copy with another User-Agent: it should still get the learned version
        assertThat(YouTubeClient.TVHTML5.copy(userAgent = "x").effectiveVersion).isEqualTo("9.20990101.00.00")
        // TV_TIZEN shares name and id with TVHTML5 but is a different build: never redirected
        assertThat(YouTubeClient.TV_TIZEN.effectiveVersion).isEqualTo(YouTubeClient.TV_TIZEN.clientVersion)
    }

    @Test
    fun `stored versions at or below the factory version are dropped`() {
        val factory = { key: String -> if (key == "WEB") "2.20261022.01.00" else null }
        val kept =
            dropNotNewerThanFactory(
                mapOf("WEB" to "2.20261101.00.00", "MWEB" to "2.20990101.00.00"),
                factory,
            )
        assertThat(kept).containsExactly("WEB", "2.20261101.00.00") // MWEB unknown here, so dropped
        assertThat(dropNotNewerThanFactory(mapOf("WEB" to "2.20261022.01.00"), factory)).isEmpty() // equal
        assertThat(dropNotNewerThanFactory(mapOf("WEB" to "2.20260101.00.00"), factory)).isEmpty() // older
    }

    @Test
    fun `adoption is refused for a step back, a repeat or a quarantined version`() {
        val live = "2.20261101.00.00"
        assertThat(canAdopt("2.20261201.00.00", live, null, now)).isTrue()
        assertThat(canAdopt("2.20261022.01.00", live, null, now)).isFalse() // older than live
        assertThat(canAdopt(live, live, null, now)).isFalse() // same
        assertThat(canAdopt("2.20261201.00.00", live, now + 1, now)).isFalse() // quarantined
        assertThat(canAdopt("2.20261201.00.00", live, now - 1, now)).isTrue() // quarantine over
        assertThat(canAdopt("2.20261201.00.00", null, null, now)).isFalse() // client does not move
    }

    @Test
    fun `reviewer rejects a version that is not newer than the one in use or the factory`() {
        assertThat(ClientReviewer.decide(cand("2.20261022.01.00"), now, Probe.PASS).verdict).isEqualTo(Verdict.REJECT)
        assertThat(ClientReviewer.decide(cand("2.20261030.00.00", current = "2.20261101.00.00"), now, Probe.PASS).verdict)
            .isEqualTo(Verdict.REJECT)
        assertThat(ClientReviewer.decide(cand("2.20261110.00.00", current = "2.20261101.00.00"), now, Probe.PASS).verdict)
            .isEqualTo(Verdict.ACCEPT)
    }

    @Test
    fun `stable only ever moves forward`() {
        val stable = mapOf("WEB" to "2.20261101.00.00")
        assertThat(promoteStable(stable, mapOf("WEB" to "2.20261201.00.00"), setOf("WEB"))).containsExactly("WEB", "2.20261201.00.00")
        assertThat(promoteStable(stable, mapOf("WEB" to "2.20261030.00.00"), setOf("WEB"))).containsExactly("WEB", "2.20261101.00.00")
        assertThat(promoteStable(emptyMap(), mapOf("WEB" to "2.20261101.00.00"), setOf("WEB"))).containsExactly("WEB", "2.20261101.00.00")
    }

    @Test
    fun `clearing the overrides puts every movable client back on its factory version`() {
        ClientVersions.setOverrides(mapOf("WEB" to "2.20991231.00.00", "MWEB" to "2.20991231.00.00"))
        assertThat(YouTubeClient.WEB.effectiveVersion).isNotEqualTo(YouTubeClient.WEB.clientVersion)
        ClientVersions.setOverrides(emptyMap())
        ClientVersions.updatable.forEach { (key, client) ->
            assertThat(ClientVersions.current(key)).isEqualTo(client.clientVersion)
            assertThat(client.effectiveVersion).isEqualTo(client.clientVersion)
        }
    }
}
