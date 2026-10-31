package app.zaid.flow.data.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The response sample is hand-written in the shape client libraries document for the gtx endpoint, not captured from a live call. */
class CommentTranslateTest {
    @Test
    fun parsesSegmentsAndDetectedLanguage() {
        val body = """[[["Hello, ","नमस्ते, ",null,null,10],["how are you?","आप कैसे हैं?",null,null,10]],null,"hi"]"""
        assertEquals(CommentTranslation("Hello, how are you?", "hi"), parseTranslateResponse(body))
    }

    @Test
    fun badResponsesGiveNull() {
        assertNull(parseTranslateResponse("not json"))
        assertNull(parseTranslateResponse("""[null,null,"hi"]"""))
        assertNull(parseTranslateResponse("""[[],null,"hi"]"""))
    }

    @Test
    fun splitJoinsBackExactlyAndRespectsLimit() {
        val text = ("Sentence number one. " + "x".repeat(30) + "\nAnother line! ").repeat(40)
        val parts = splitForTranslation(text, 100)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 100 })
        assertEquals(listOf("short"), splitForTranslation("short", 100))
    }

    @Test
    fun splitNeverCutsAnEmojiInHalf() {
        val text = "a".repeat(9) + "\uD83D\uDE80" + "b".repeat(20)
        val parts = splitForTranslation(text, 10)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.none { it.endsWith("\uD83D") })
    }

    @Test
    fun hintForEnglishTarget() {
        assertTrue(looksTranslatable("यह वीडियो बहुत अच्छा है", "en"))
        assertTrue(looksTranslatable("yeh video bahut accha hai bhai", "en"))
        assertFalse(looksTranslatable("I really love this video, great work", "en"))
        assertFalse(looksTranslatable("nice video", "en"))
        assertFalse(looksTranslatable("\uD83D\uDD25\uD83D\uDD25\uD83D\uDD25", "en"))
    }

    @Test
    fun hintCoversRomanizedHindiAndInformalEnglish() {
        assertTrue(looksTranslatable("ye video bahut achhi hai", "en"))
        assertTrue(looksTranslatable("bhai ye video bahut mast hai", "en"))
        assertTrue(looksTranslatable("gud vdo bro", "en"))
        assertTrue(looksTranslatable("u r osm, plz make more", "en"))
        assertFalse(looksTranslatable("This video is really good, thank you", "en"))
    }

    @Test
    fun detectsRomanizedHindi() {
        assertTrue(isRomanizedHindi("ye video bahut achhi hai"))
        assertFalse(isRomanizedHindi("I really love this video"))
    }

    @Test
    fun normalizesInformalEnglish() {
        assertEquals("Good video bro", normalizeInformalEnglish("gud vdo bro"))
        assertEquals("You are awesome, please make more", normalizeInformalEnglish("u r osm, plz make more"))
        assertEquals("Nice video", normalizeInformalEnglish("Nice video"))
    }

    @Test
    fun sameTextIgnoresCaseAndPunctuation() {
        assertTrue(sameText("Nice video!", "nice  video"))
        assertFalse(sameText("ye video achhi hai", "this video is good"))
    }

    @Test
    fun hintForHindiTarget() {
        assertTrue(looksTranslatable("I really love this video", "hi"))
        assertFalse(looksTranslatable("यह वीडियो बहुत अच्छा है", "hi"))
    }
}
