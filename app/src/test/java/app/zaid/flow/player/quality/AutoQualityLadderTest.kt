package app.zaid.flow.player.quality

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoQualityLadderTest {
    private fun kBps(value: Long) = value * 8_000L

    @Test
    fun slowConnectionsNeverDropBelow480p() {
        assertEquals(480, AutoQualityLadder.targetHeight(kBps(0), Int.MAX_VALUE))
        assertEquals(480, AutoQualityLadder.targetHeight(kBps(90), Int.MAX_VALUE))
        assertEquals(480, AutoQualityLadder.targetHeight(kBps(200), Int.MAX_VALUE))
        assertEquals(480, AutoQualityLadder.targetHeight(kBps(259), Int.MAX_VALUE))
    }

    @Test
    fun speedStepsUpTheLadder() {
        assertEquals(720, AutoQualityLadder.targetHeight(kBps(260), Int.MAX_VALUE))
        assertEquals(720, AutoQualityLadder.targetHeight(kBps(399), Int.MAX_VALUE))
        assertEquals(1080, AutoQualityLadder.targetHeight(kBps(400), Int.MAX_VALUE))
        assertEquals(1080, AutoQualityLadder.targetHeight(kBps(659), Int.MAX_VALUE))
        assertEquals(1440, AutoQualityLadder.targetHeight(kBps(660), Int.MAX_VALUE))
        assertEquals(1440, AutoQualityLadder.targetHeight(kBps(1099), Int.MAX_VALUE))
        assertEquals(2160, AutoQualityLadder.targetHeight(kBps(1100), Int.MAX_VALUE))
        assertEquals(2160, AutoQualityLadder.targetHeight(kBps(50_000), Int.MAX_VALUE))
    }

    @Test
    fun shortsStartAt480pUntilASpeedHasBeenMeasured() {
        val saved = AutoQualityLadder.shortsEstimateBps
        try {
            AutoQualityLadder.shortsEstimateBps = 0L
            assertEquals(480, AutoQualityLadder.shortsHeight())
            AutoQualityLadder.shortsEstimateBps = kBps(500)
            assertEquals(1080, AutoQualityLadder.shortsHeight())
            AutoQualityLadder.shortsEstimateBps = kBps(100)
            assertEquals(480, AutoQualityLadder.shortsHeight())
        } finally {
            AutoQualityLadder.shortsEstimateBps = saved
        }
    }

    @Test
    fun theCapHoldsTheResultDown() {
        assertEquals(720, AutoQualityLadder.targetHeight(kBps(50_000), 720))
        assertEquals(480, AutoQualityLadder.targetHeight(kBps(100), 720))
    }

    @Test
    fun `a floor lifts a slow connection and a ceiling holds a fast one`() {
        assertEquals(1080, AutoQualityLadder.targetHeight(kBps(100), maxHeight = Int.MAX_VALUE, minHeight = 1080))
        assertEquals(2160, AutoQualityLadder.targetHeight(kBps(50_000), maxHeight = Int.MAX_VALUE, minHeight = 1080))
        assertEquals(1080, AutoQualityLadder.targetHeight(kBps(50_000), maxHeight = 1080, minHeight = 480))
        assertEquals(720, AutoQualityLadder.targetHeight(kBps(300), maxHeight = 1080, minHeight = 480))
    }

    @Test
    fun `a ceiling under the floor wins`() {
        assertEquals(360, AutoQualityLadder.targetHeight(kBps(50_000), maxHeight = 360, minHeight = 480))
        assertEquals(720, AutoQualityLadder.targetHeight(kBps(100), maxHeight = 720, minHeight = 1080))
    }

    @Test
    fun `the saved floor and ceiling shape Auto and Shorts before a speed is measured`() {
        val saved = AutoQualityLadder.shortsEstimateBps
        try {
            AutoQualityBounds.floor = 1080
            AutoQualityLadder.shortsEstimateBps = 0L
            assertEquals(1080, AutoQualityLadder.shortsHeight())
            assertEquals(1080, AutoQualityLadder.targetHeight(kBps(100)))

            AutoQualityBounds.floor = 0
            AutoQualityBounds.ceiling = 720
            assertEquals(720, AutoQualityLadder.targetHeight(kBps(50_000)))
        } finally {
            AutoQualityBounds.floor = 0
            AutoQualityBounds.ceiling = 0
            AutoQualityLadder.shortsEstimateBps = saved
        }
    }

    @Test
    fun `a floor under 480p lets Auto follow a slow connection down to it`() {
        assertEquals(360, AutoQualityLadder.targetHeight(kBps(100), maxHeight = Int.MAX_VALUE, minHeight = 360))
        assertEquals(240, AutoQualityLadder.targetHeight(kBps(60), maxHeight = Int.MAX_VALUE, minHeight = 240))
        assertEquals(144, AutoQualityLadder.targetHeight(kBps(10), maxHeight = Int.MAX_VALUE, minHeight = 144))
        // Never below the floor chosen, and a faster connection is unaffected by it.
        assertEquals(360, AutoQualityLadder.targetHeight(kBps(10), maxHeight = Int.MAX_VALUE, minHeight = 360))
        assertEquals(1080, AutoQualityLadder.targetHeight(kBps(500), maxHeight = Int.MAX_VALUE, minHeight = 144))
    }
}
