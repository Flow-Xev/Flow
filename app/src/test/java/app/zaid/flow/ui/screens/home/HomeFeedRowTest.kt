package app.zaid.flow.ui.screens.home

import app.zaid.flow.data.model.Video
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeFeedRowTest {
    @Test
    fun `phone follows V S V P V S V P V S VV S VV S pattern`() {
        val rows =
            homeFeedRows(videos(11), columns = 1, showContinueWatching = false, shorts = shorts(60), postRows = posts(5))

        assertThat(rows.map { it.kind() })
            .containsExactly("V", "S", "V", "P", "V", "S", "V", "P", "V", "S", "V", "V", "S", "V", "V", "S", "V", "V", "S")
            .inOrder()
    }

    @Test
    fun `only two posts rows ever appear`() {
        val rows = homeFeedRows(videos(40), 1, false, shorts(120), posts(6))
        assertThat(rows.filterIsInstance<HomeFeedRow.Posts>()).hasSize(2)
    }

    @Test
    fun `a missing shelf is skipped not swapped`() {
        val rows = homeFeedRows(videos(3), 1, false, shorts = emptyList(), postRows = posts(2))
        assertThat(rows.map { it.kind() }).containsExactly("V", "V", "P").inOrder()
    }

    @Test
    fun `continue watching follows the first run`() {
        val rows = homeFeedRows(videos(4), columns = 3, showContinueWatching = true, shorts = shorts(6))

        assertThat(rows.map { it.key })
            .containsExactly("0", "1", "2", "continue_watching_shelf", "shorts_shelf_0", "3")
            .inOrder()
    }

    @Test
    fun `a shorts row with fewer than four reels is dropped`() {
        fun shelves(n: Int) = homeFeedRows(videos(3), 1, false, shorts(n)).filterIsInstance<HomeFeedRow.Shorts>()
        assertThat(shelves(3)).isEmpty()
        assertThat(shelves(9)).hasSize(1)
        assertThat(shelves(10)).hasSize(2)
        assertThat(homeFeedRows(videos(3), 1, false, shorts(3))).hasSize(3)
    }

    @Test
    fun `every shelf is a single row of six`() {
        val shelves = homeFeedRows(videos(6), 1, false, shorts(18)).filterIsInstance<HomeFeedRow.Shorts>()
        assertThat(shelves.map { it.shorts.size }).containsExactly(6, 6).inOrder()
    }

    @Test
    fun `shorts slots match the shelves the feed builds`() {
        for (cols in 1..3) {
            for (n in listOf(1, 3, 6, 11, 25)) {
                val built = homeFeedRows(videos(n), cols, false, shorts(500)).count { it is HomeFeedRow.Shorts }
                assertThat(shortsSlotsUpTo(n, cols)).isEqualTo(built)
            }
        }
    }

    @Test
    fun `no videos means no shelves`() {
        assertThat(homeFeedRows(emptyList(), columns = 3, showContinueWatching = true, shorts = shorts(6))).isEmpty()
    }

    private fun HomeFeedRow.kind() =
        when (this) {
            is HomeFeedRow.VideoCard -> "V"
            is HomeFeedRow.Shorts -> "S"
            is HomeFeedRow.Posts -> "P"
            HomeFeedRow.ContinueWatching -> "C"
        }

    private fun posts(count: Int) = List(count) { HomePostRow("c$it", "c$it", "", emptyList()) }

    private fun shorts(count: Int) = videos(count).map { it.copy(id = "s${it.id}") }

    private fun videos(count: Int) =
        List(count) { index ->
            Video(
                id = "$index",
                title = "$index",
                channelName = "Channel",
                channelId = "channel",
                thumbnailUrl = "thumbnail",
                duration = 60,
                viewCount = 1,
                uploadDate = "today",
            )
        }
}
