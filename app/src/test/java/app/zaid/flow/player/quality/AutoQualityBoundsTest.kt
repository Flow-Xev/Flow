package app.zaid.flow.player.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoQualityBoundsTest {
    private fun heights(
        range: AutoQualityRange,
        vararg all: Int,
    ) = range.restrict(all.toList()) { it }

    @Test
    fun `with nothing set Auto keeps its old range, 480p up with no ceiling`() {
        val range = AutoQualityBounds.resolve(userFloor = 0, userCeiling = 0, dataSaver = false)

        assertEquals(AutoQualityRange.DEFAULT, range)
        assertEquals(480, range.floor)
        assertEquals(Int.MAX_VALUE, range.ceiling)
    }

    @Test
    fun `a ceiling and a floor set the range`() {
        val range = AutoQualityBounds.resolve(userFloor = 720, userCeiling = 1440, dataSaver = false)

        assertEquals(720, range.floor)
        assertEquals(1440, range.ceiling)
        assertTrue(1080 in range)
        assertFalse(480 in range)
        assertFalse(2160 in range)
    }

    @Test
    fun `Data Saver's 720p limit wins over a higher ceiling and floor`() {
        val range = AutoQualityBounds.resolve(userFloor = 1080, userCeiling = 2160, dataSaver = true)

        assertEquals(720, range.ceiling)
        assertEquals(720, range.floor)
        assertEquals(720, range.userFloor)
    }

    @Test
    fun `a ceiling under the default floor pulls the floor down with it`() {
        val range = AutoQualityBounds.resolve(userFloor = 0, userCeiling = 360, dataSaver = false)

        assertEquals(360, range.floor)
        assertEquals(360, range.ceiling)
        assertEquals(0, range.userFloor)
    }

    @Test
    fun `a stored floor above the ceiling gives way to it`() {
        val range = AutoQualityBounds.resolve(userFloor = 1440, userCeiling = 720, dataSaver = false)

        assertEquals(720, range.floor)
        assertEquals(720, range.ceiling)
    }

    @Test
    fun `clamp holds a height inside the range`() {
        val range = AutoQualityBounds.resolve(userFloor = 720, userCeiling = 1080, dataSaver = false)

        assertEquals(720, range.clamp(480))
        assertEquals(1080, range.clamp(1440))
        assertEquals(1080, range.clamp(1080))
    }

    @Test
    fun `restrict keeps what is inside the range`() {
        val range = AutoQualityBounds.resolve(userFloor = 720, userCeiling = 1440, dataSaver = false)

        assertEquals(listOf(720, 1080, 1440), heights(range, 2160, 1440, 1080, 720, 480, 360))
    }

    @Test
    fun `restrict with nothing set matches the old Shorts rule`() {
        assertEquals(listOf(2160, 720, 480), heights(AutoQualityRange.DEFAULT, 2160, 720, 480, 360))
        assertEquals(listOf(360, 240), heights(AutoQualityRange.DEFAULT, 360, 240))
    }

    @Test
    fun `restrict falls back to the best under the ceiling, else the lowest there is`() {
        val ceiling720 = AutoQualityBounds.resolve(userFloor = 0, userCeiling = 720, dataSaver = false)
        assertEquals(listOf(1080), heights(ceiling720, 1080))

        val ceiling360 = AutoQualityBounds.resolve(userFloor = 0, userCeiling = 360, dataSaver = false)
        assertEquals(listOf(240), heights(ceiling360, 720, 240))
        assertEquals(listOf(480), heights(ceiling360, 1080, 480))
    }

    @Test
    fun `a floor the video cannot reach leaves only its best quality under it`() {
        val range = AutoQualityBounds.resolve(userFloor = 1080, userCeiling = 0, dataSaver = false)

        assertEquals(listOf(720), heights(range, 720, 480, 360))
    }

    @Test
    fun `a ladder holds the ceiling and only a floor the viewer set`() {
        val none = AutoQualityRange.DEFAULT.forLadder()
        assertEquals(listOf(720, 360, 144), heights(none, 720, 360, 144))

        val set = AutoQualityBounds.resolve(userFloor = 720, userCeiling = 1080, dataSaver = false).forLadder()
        assertEquals(listOf(1080, 720), heights(set, 1080, 720, 360, 2160))
    }

    @Test
    fun `the cache key changes with the range`() {
        val a = AutoQualityBounds.resolve(0, 0, false).cacheKey
        val b = AutoQualityBounds.resolve(0, 720, false).cacheKey
        val c = AutoQualityBounds.resolve(1080, 0, false).cacheKey

        assertEquals(3, setOf(a, b, c).size)
    }

    @Test
    fun `a floor under 480p is used as chosen`() {
        val range = AutoQualityBounds.resolve(userFloor = 360, userCeiling = 0, dataSaver = false)

        assertEquals(360, range.floor)
        assertEquals(360, range.userFloor)
        assertEquals(listOf(360), heights(range, 720, 480, 360, 240).filter { it == 360 })
    }
}
