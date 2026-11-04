package app.zaid.flow.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChapterInspectorTest {
    private val lines = (0 until 10).map { TranscriptLine(it, it * 12, "line $it") }

    @Test
    fun `a chapter maps to the last line that starts at or before it`() {
        assertEquals(0, lineIndexAt(lines, 0))
        assertEquals(2, lineIndexAt(lines, 24))
        assertEquals(2, lineIndexAt(lines, 30))
        assertEquals(9, lineIndexAt(lines, 5000))
    }

    @Test
    fun `the draft is sent as line numbers and titles`() {
        val json = draftJson(listOf(AiChapter("Intro", 0), AiChapter("Setup", 36)), lines)
        assertEquals("""[{"line":0,"title":"Intro"},{"line":3,"title":"Setup"}]""", json)
    }

    @Test
    fun `a review with far fewer chapters is rejected`() {
        val draft = (0 until 8).map { AiChapter("Chapter $it", it * 60) }
        assertFalse(isAcceptable(draft, draft.take(3)))
        assertTrue(isAcceptable(draft, draft.take(5)))
        assertFalse(isAcceptable(draft, draft.take(2)))
    }
}
