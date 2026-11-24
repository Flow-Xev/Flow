package app.zaid.flow.ai

import app.zaid.flow.data.transcript.TranscriptCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChapterBoundariesTest {
    /** A talk of [topics] subjects, each [linesPerTopic] twelve-second lines, every topic with its own vocabulary. */
    private fun talk(
        topics: List<String>,
        linesPerTopic: Int = 15,
    ): List<TranscriptCue> =
        topics.flatMapIndexed { t, word ->
            (0 until linesPerTopic).map { i ->
                TranscriptCue((t * linesPerTopic + i) * 12_000L, "$word $word engine$word detail$word number $i", 12_000L)
            }
        }

    @Test
    fun linesReachTheEndOfALongVideoWithinTheLimit() {
        val cues = (0 until 3000).map { TranscriptCue(it * 5_000L, "sentence number $it about something", 5_000L) }
        val lines = buildChapterLines(cues, 20_000)
        assertTrue(promptTranscript(lines).length <= 20_000 + lines.size * 4)
        // The last minutes are still there; nothing is cut off the end.
        assertTrue(lines.last().startSeconds > 14_000)
        assertEquals(lines.indices.toList(), lines.map { it.index })
    }

    @Test
    fun aChapterStartsExactlyWhereItsLineStarts() {
        val lines = buildChapterLines(talk(listOf("alpha", "bravo", "charlie")), 100_000)
        val reply = """[{"line":0,"title":"Alpha"},{"line":15,"title":"Bravo"},{"line":30,"title":"Charlie"}]"""
        val chapters = parseLineChapters(reply, lines, 540)
        assertEquals(listOf(0, 180, 360), chapters.map { it.startSeconds })
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), chapters.map { it.title })
    }

    @Test
    fun aBoundaryIsNudgedOntoTheRealTopicChange() {
        val lines = buildChapterLines(talk(listOf("alpha", "bravo", "charlie")), 100_000)
        // The model is one line late on the second boundary; the wording changes at line 15.
        val reply = """[{"line":0,"title":"Alpha"},{"line":16,"title":"Bravo"},{"line":30,"title":"Charlie"}]"""
        val chapters = parseLineChapters(reply, lines, 540)
        assertEquals(180, chapters[1].startSeconds)
    }

    @Test
    fun aGoodPickIsNotMoved() {
        val lines = buildChapterLines(talk(listOf("alpha", "bravo", "charlie")), 100_000)
        assertEquals(listOf(0, 15, 30), refineBoundaries(lines, listOf(0, 15, 30)))
    }

    @Test
    fun linesThatDoNotExistAreIgnoredAndTooFewGivesNone() {
        val lines = buildChapterLines(talk(listOf("alpha", "bravo", "charlie")), 100_000)
        assertTrue(parseLineChapters("""[{"line":0,"title":"A"},{"line":999,"title":"B"},{"line":30,"title":"C"}]""", lines, 540).isEmpty())
        assertTrue(parseLineChapters("not json", lines, 540).isEmpty())
    }

    @Test
    fun crowdedAndRepeatedChaptersAreDropped() {
        val lines = buildChapterLines(talk(listOf("alpha", "bravo", "charlie", "delta")), 100_000)
        val reply =
            """[{"line":0,"title":"Alpha"},{"line":1,"title":"Alpha Again"},{"line":15,"title":"Bravo"},
               {"line":30,"title":"bravo"},{"line":45,"title":"Delta"}]"""
        val chapters = parseLineChapters(reply, lines, 720)
        assertEquals(listOf("Alpha", "Bravo", "Delta"), chapters.map { it.title })
    }

    @Test
    fun titlesAreCleaned() {
        assertEquals("Setting Up the Project", cleanChapterTitle("\"1. Setting Up the Project.\""))
        assertEquals("Setting Up", cleanChapterTitle("03:25 - Setting Up"))
        assertEquals("10-Minute Setup", cleanChapterTitle("10-Minute Setup"))
        assertEquals(8, cleanChapterTitle("one two three four five six seven eight nine ten").split(" ").size)
    }

    @Test
    fun chapterCountGrowsWithLength() {
        assertEquals(3..4, chapterCountRange(600))
        assertTrue(chapterCountRange(3600).first >= 8)
        assertEquals(20, chapterCountRange(20_000).last)
    }
}
