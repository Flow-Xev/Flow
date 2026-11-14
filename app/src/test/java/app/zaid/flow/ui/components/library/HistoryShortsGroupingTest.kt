package app.zaid.flow.ui.components.library

import app.zaid.flow.data.local.VideoHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryShortsGroupingTest {
    private fun entry(
        id: String,
        short: Boolean,
    ) = VideoHistoryEntry(videoId = id, position = 0, duration = 0, timestamp = 0, title = id, thumbnailUrl = "", isShort = short)

    private fun sizes(items: List<LibraryMediaItem>) = items.map { (it as? LibraryMediaItem.ShortsGroupItem)?.shorts?.size ?: 0 }

    @Test
    fun groupsOfThreeWithRemainder() {
        val items = (1..8).map { entry("s$it", true) }.toHistoryShelfItems()
        assertEquals(listOf(3, 3, 2), sizes(items))
    }

    @Test
    fun videosSplitRuns() {
        val items = listOf(entry("a", true), entry("b", true), entry("v", false), entry("c", true)).toHistoryShelfItems()
        assertEquals(listOf(2, 0, 1), sizes(items))
    }

    @Test
    fun neverMoreThanFour() {
        val items = (1..9).map { entry("s$it", true) }.toHistoryShelfItems(groupSize = 10)
        assertEquals(listOf(4, 4, 1), sizes(items))
    }
}
