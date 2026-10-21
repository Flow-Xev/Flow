package app.zaid.flow.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMarkdownTest {
    @Test
    fun inlineBoldItalicCodeAndEmojiSurvive() {
        val spans = parseInline("**Key** point \uD83D\uDE80 with *care* and `code`")
        assertEquals(MdSpan("Key", bold = true), spans[0])
        assertTrue(spans.any { it.text == "care" && it.italic })
        assertTrue(spans.any { it.text == "code" && it.code })
        assertTrue(spans.joinToString("") { it.text }.contains("\uD83D\uDE80"))
    }

    @Test
    fun unmatchedMarkersStayAsText() {
        assertEquals("2 * 3 and **open", parseInline("2 * 3 and **open").joinToString("") { it.text })
    }

    @Test
    fun timestampsBecomeMoments() {
        val moment = parseInline("See 1:05 and 1:02:03").filter { it.timestampMs != null }
        assertEquals(listOf(65_000L, 3_723_000L), moment.map { it.timestampMs })
        assertNull(timestampToMs("1:75"))
    }

    @Test
    fun onlyHttpLinksAreKept() {
        val spans = parseInline("[ok](https://a.example/x) [bad](javascript:alert(1)) https://b.example/y.")
        assertEquals(listOf("https://a.example/x", "https://b.example/y"), spans.mapNotNull { it.link })
    }

    @Test
    fun blocksCoverHeadingsListsAndCode() {
        val blocks = parseAiMarkdown("## Title\n- one\n  - nested\n1. first\n```\nval x = 1\n```\nplain")
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(0, (blocks[1] as MdBlock.Bullet).indent)
        assertEquals(1, (blocks[2] as MdBlock.Bullet).indent)
        assertEquals(1, (blocks[3] as MdBlock.Numbered).number)
        assertEquals("val x = 1", (blocks[4] as MdBlock.Code).text)
        assertTrue(blocks[5] is MdBlock.Paragraph)
    }

    @Test
    fun boldLineIsNotMistakenForABullet() {
        assertTrue(parseAiMarkdown("**Summary**: short")[0] is MdBlock.Paragraph)
    }
}
