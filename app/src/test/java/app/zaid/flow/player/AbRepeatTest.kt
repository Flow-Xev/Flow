package app.zaid.flow.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbRepeatTest {
    @Test
    fun tapsMarkAThenBThenStop() {
        val a = advanceAbRepeat(AbRepeatState(), "vid", 10_000)
        assertEquals(AbRepeatState("vid", 10_000, null), a)
        assertFalse(a.isActive)

        val b = advanceAbRepeat(a, "vid", 25_000)
        assertEquals(AbRepeatState("vid", 10_000, 25_000), b)
        assertTrue(b.isActive)

        assertEquals(AbRepeatState(), advanceAbRepeat(b, "vid", 30_000))
    }

    @Test
    fun endBeforeStartRestartsFromHere() {
        val a = advanceAbRepeat(AbRepeatState(), "vid", 20_000)
        assertEquals(AbRepeatState("vid", 5_000, null), advanceAbRepeat(a, "vid", 5_000))
        assertEquals(AbRepeatState("vid", 20_100, null), advanceAbRepeat(a, "vid", 20_100))
    }

    @Test
    fun aDifferentVideoStartsOver() {
        val b = AbRepeatState("old", 1_000, 5_000)
        assertEquals(AbRepeatState("new", 7_000, null), advanceAbRepeat(b, "new", 7_000))
    }

    @Test
    fun jumpsBackOnlyOnceBIsReached() {
        val s = AbRepeatState("vid", 10_000, 25_000)
        assertNull(abRepeatSeekTarget(s, 24_999))
        assertEquals(10_000L, abRepeatSeekTarget(s, 25_000))
        assertEquals(10_000L, abRepeatSeekTarget(s, 26_000))
        assertNull(abRepeatSeekTarget(AbRepeatState("vid", 10_000, null), 99_000))
    }
}
