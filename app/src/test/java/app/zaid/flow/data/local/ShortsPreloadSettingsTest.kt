package app.zaid.flow.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortsPreloadSettingsTest {
    private val defaults = ShortsPreloadSettings()

    @Test
    fun `defaults preload two ahead with everything on`() {
        assertEquals(
            ShortsPreloadPlan(2, streams = true, details = true, description = true),
            defaults.planFor(dataSaver = false, metered = false),
        )
    }

    @Test
    fun `defaults behave the same on data saver and metered`() {
        assertEquals(defaults.planFor(false, false), defaults.planFor(dataSaver = true, metered = true))
    }

    @Test
    fun `master switch off preloads nothing`() {
        assertEquals(ShortsPreloadPlan.NONE, defaults.copy(enabled = false).planFor(false, false))
    }

    @Test
    fun `light mode caps to one ahead and drops the description`() {
        val plan = defaults.copy(dataSaverMode = ShortsPreloadConstrainedMode.LIGHT).planFor(dataSaver = true, metered = false)
        assertEquals(ShortsPreloadPlan(1, streams = true, details = true, description = false), plan)
    }

    @Test
    fun `light mode never raises a lower count`() {
        val plan =
            defaults.copy(ahead = 0, meteredMode = ShortsPreloadConstrainedMode.LIGHT).planFor(dataSaver = false, metered = true)
        assertEquals(0, plan.ahead)
    }

    @Test
    fun `off mode on data saver preloads nothing`() {
        val plan = defaults.copy(dataSaverMode = ShortsPreloadConstrainedMode.OFF).planFor(dataSaver = true, metered = false)
        assertEquals(ShortsPreloadPlan.NONE, plan)
    }

    @Test
    fun `data saver mode is ignored when data saver is off`() {
        val plan = defaults.copy(dataSaverMode = ShortsPreloadConstrainedMode.OFF).planFor(dataSaver = false, metered = false)
        assertEquals(defaults.planFor(false, false), plan)
    }

    @Test
    fun `the stricter of data saver and metered wins`() {
        val settings =
            defaults.copy(
                dataSaverMode = ShortsPreloadConstrainedMode.LIGHT,
                meteredMode = ShortsPreloadConstrainedMode.OFF,
            )
        assertEquals(ShortsPreloadPlan.NONE, settings.planFor(dataSaver = true, metered = true))
    }

    @Test
    fun `ahead is clamped to the supported range`() {
        assertEquals(5, defaults.copy(ahead = 99).planFor(false, false).ahead)
        assertEquals(0, defaults.copy(ahead = -3).planFor(false, false).ahead)
    }

    @Test
    fun `unknown stored mode falls back to same`() {
        assertEquals(ShortsPreloadConstrainedMode.SAME, ShortsPreloadConstrainedMode.fromStored("bogus"))
        assertEquals(ShortsPreloadConstrainedMode.OFF, ShortsPreloadConstrainedMode.fromStored("OFF"))
    }
}
