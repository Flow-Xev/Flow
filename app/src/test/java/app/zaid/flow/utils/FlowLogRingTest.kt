package app.zaid.flow.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowLogRingTest {
    @Test
    fun `a reader gets each line once, in order`() {
        val ring = FlowLogRing()
        listOf("a", "b").forEach(ring::add)
        val first = ring.since(0)
        assertEquals(listOf("a", "b"), first.lines)
        ring.add("c")
        val second = ring.since(first.next)
        assertEquals(listOf("c"), second.lines)
        assertTrue(ring.since(second.next).lines.isEmpty())
    }

    @Test
    fun `only the newest lines are held and numbering carries on`() {
        val ring = FlowLogRing(capacity = 3)
        (1..5).forEach { ring.add("line $it") }
        val batch = ring.since(0)
        assertEquals(listOf("line 3", "line 4", "line 5"), batch.lines)
        assertEquals(5L, batch.next)
    }

    @Test
    fun `clearing changes the epoch and drops what was held`() {
        val ring = FlowLogRing()
        ring.add("old")
        val before = ring.since(0)
        ring.clear()
        ring.add("new")
        val after = ring.since(before.next)
        assertNotEquals(before.epoch, after.epoch)
        assertEquals(listOf("new"), after.lines)
    }

    @Test
    fun `ticks change on every add and on clear`() {
        val ring = FlowLogRing()
        val start = ring.ticks.value
        ring.add("x")
        val afterAdd = ring.ticks.value
        ring.clear()
        assertNotEquals(start, afterAdd)
        assertNotEquals(afterAdd, ring.ticks.value)
    }
}
