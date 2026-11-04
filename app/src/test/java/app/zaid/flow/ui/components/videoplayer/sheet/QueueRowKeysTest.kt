package app.zaid.flow.ui.components.videoplayer.sheet

import app.zaid.flow.data.model.Video
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueueRowKeysTest {
    private fun video(id: String) =
        Video(
            id = id,
            title = id,
            channelName = "Channel",
            channelId = "UC1",
            thumbnailUrl = "",
            duration = 60,
            viewCount = 1L,
            uploadDate = "",
        )

    @Test
    fun `a removal leaves the keys of the rows below unchanged`() {
        val queue = listOf(video("a"), video("b"), video("c"), video("d"))

        val before = queueRowKeys(queue)
        val after = queueRowKeys(queue - queue[1])

        assertThat(after).containsExactly(before[0], before[2], before[3]).inOrder()
    }

    @Test
    fun `the same video twice gets two keys`() {
        val keys = queueRowKeys(listOf(video("a"), video("b"), video("a")))

        assertThat(keys).containsNoDuplicates()
    }
}
