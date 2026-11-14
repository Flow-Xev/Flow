package app.zaid.flow.ai

import app.zaid.flow.data.transcript.TranscriptCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChaptersTest {
    @Test
    fun readsAPlainJsonReply() {
        val reply = """[{"start":0,"title":"Intro"},{"start":95,"title":"Setup"},{"start":310.0,"title":"Demo"}]"""
        assertEquals(
            listOf(AiChapter("Intro", 0), AiChapter("Setup", 95), AiChapter("Demo", 310)),
            parseAiChapters(reply, 600),
        )
    }

    @Test
    fun toleratesMarkdownFencesAndChatter() {
        val reply =
            "Here you go:\n```json\n" +
                "[{\"start\":0,\"title\":\"A\"},{\"start\":60,\"title\":\"B\"},{\"start\":120,\"title\":\"C\"}]\n" +
                "```"
        assertEquals(3, parseAiChapters(reply, 300).size)
    }

    @Test
    fun firstChapterIsMovedToZero() {
        val reply = """[{"start":7,"title":"A"},{"start":60,"title":"B"},{"start":120,"title":"C"}]"""
        assertEquals(0, parseAiChapters(reply, 300).first().startSeconds)
    }

    @Test
    fun dropsOutOfRangeCrowdedAndBlankEntries() {
        val reply =
            """[{"start":0,"title":"A"},{"start":5,"title":"Too close"},{"start":60,"title":"B"},""" +
                """{"start":120,"title":"  "},{"start":150,"title":"C"},{"start":9999,"title":"Past the end"}]"""
        assertEquals(listOf("A", "B", "C"), parseAiChapters(reply, 300).map { it.title })
    }

    @Test
    fun sortsOutOfOrderStarts() {
        val reply = """[{"start":120,"title":"C"},{"start":0,"title":"A"},{"start":60,"title":"B"}]"""
        assertEquals(listOf("A", "B", "C"), parseAiChapters(reply, 300).map { it.title })
    }

    @Test
    fun tooFewChaptersOrGarbageGivesNone() {
        assertTrue(parseAiChapters("""[{"start":0,"title":"A"},{"start":60,"title":"B"}]""", 300).isEmpty())
        assertTrue(parseAiChapters("I cannot do that", 300).isEmpty())
        assertTrue(parseAiChapters("[not json]", 300).isEmpty())
        assertTrue(parseAiChapters("""[1,2,3]""", 300).isEmpty())
    }

    @Test
    fun condensedTranscriptKeepsTimestampsAndFitsTheLimit() {
        val cues = (0 until 600).map { i -> TranscriptCue(i * 5_000L, "line number $i of the talk", 5_000L) }
        val text = condenseTranscript(cues, 4_000)
        assertTrue(text.length <= 4_000)
        assertTrue(text.startsWith("[00:00] "))
        // The widened lines still reach the end of the video rather than stopping early.
        assertTrue(text.contains("line number 5") && text.lines().size > 3)
    }
}
