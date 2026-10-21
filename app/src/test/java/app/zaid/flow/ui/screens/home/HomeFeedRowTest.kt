package app.zaid.flow.ui.screens.home

import app.zaid.flow.data.model.Video
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeFeedRowTest {
    @Test
    fun `phone alternates one video then two videos with shorts rows between`() {
        val rows = homeFeedRows(videos(6), columns = 1, showContinueWatching = false, shorts = shorts(18))

        assertThat(rows.map { it.key })
            .containsExactly("0", "shorts_shelf_0", "1", "2", "shorts_shelf_1", "3", "4", "5")
            .inOrder()
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
    fun `no videos means no shelves`() {
        assertThat(homeFeedRows(emptyList(), columns = 3, showContinueWatching = true, shorts = shorts(6))).isEmpty()
    }

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
