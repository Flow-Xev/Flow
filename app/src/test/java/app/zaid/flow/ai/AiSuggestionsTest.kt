package app.zaid.flow.ai

import app.zaid.flow.data.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSuggestionsTest {
    private fun video(
        title: String,
        duration: Int = 600,
        isMusic: Boolean = false,
    ) = Video(
        id = "id",
        title = title,
        channelName = "c",
        channelId = "cid",
        thumbnailUrl = "",
        duration = duration,
        viewCount = 1,
        uploadDate = "",
        isMusic = isMusic,
    )

    @Test
    fun `a lecture is educational`() {
        assertEquals(VideoKind.EDUCATION, videoKindOf(video("Calculus explained: a lecture for beginners")))
    }

    @Test
    fun `music videos are music`() {
        assertEquals(VideoKind.MUSIC, videoKindOf(video("Some Song", isMusic = true)))
    }

    @Test
    fun `an unremarkable video is general`() {
        assertEquals(VideoKind.GENERAL, videoKindOf(video("My weekend")))
    }

    @Test
    fun `pills change with the video and always start with a summary`() {
        val lecture = pillsFor(video("Physics lecture explained for beginners"))
        val song = pillsFor(video("Song", isMusic = true))
        assertNotEquals(lecture.map { it.id }, song.map { it.id })
        assertEquals("summarise", lecture.first().id)
        assertTrue(lecture.any { it.id == "study_notes" })
        assertTrue(song.any { it.id == "song_about" })
    }

    @Test
    fun `no video gives the general pills`() {
        assertEquals(listOf("recent", "searches", "find"), pillsFor(null).map { it.id })
    }
}
