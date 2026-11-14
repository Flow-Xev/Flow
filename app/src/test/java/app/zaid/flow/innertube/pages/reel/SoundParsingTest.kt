package app.zaid.flow.innertube.pages.reel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SoundParsingTest {
    private fun anchor(
        raw: String,
        selfId: String = "",
    ) = Json.parseToJsonElement(raw).soundAnchor(selfId)

    @Test
    fun `audio pivot params match the value yt-dlp documents for its test video`() {
        assertEquals(
            "8gUrCikSJwoLMkd0VmtzQk1ZRk0SCzJHdFZrc0JNWUZNGgsyR3RWa3NCTVlGTQ==",
            audioPivotParams("2GtVksBMYFM"),
        )
    }

    @Test
    fun `audio pivot params stay valid when a length needs two varint bytes`() {
        val bytes = Base64.getDecoder().decode(audioPivotParams("x".repeat(200)))
        assertEquals(0xF2.toByte(), bytes[0])
        assertEquals(0x05.toByte(), bytes[1])
        assertTrue("outer length continues into a second byte", (bytes[2].toInt() and 0x80) != 0)
    }

    @Test
    fun `reads the sound button's own browse link, title and source videos`() {
        val a =
            anchor(
                """
                { "buttons": [ { "pivotButtonViewModel": {
                    "soundAttributionTitle": { "content": "Original sound - Someone" },
                    "onTap": { "innertubeCommand": { "browseEndpoint": { "browseId": "FEsfv_audio_pivot", "params": "abc" } } } } } ],
                  "link": "https://www.youtube.com/source/AAAAAAAAAAA/shorts",
                  "self": "/source/SELFSELFSELF" }
                """.trimIndent(),
                selfId = "SELFSELFSEL",
            )
        assertEquals("FEsfv_audio_pivot", a.browseId)
        assertEquals("abc", a.params)
        assertEquals("Original sound - Someone", a.title)
        assertEquals(listOf("AAAAAAAAAAA"), a.sourceIds)
        assertTrue(a.hasEndpoint)
    }

    @Test
    fun `a response with no sound button gives an empty anchor`() {
        val a = anchor("""{ "overlay": { "other": 1 } }""")
        assertNull(a.browseId)
        assertTrue(a.sourceIds.isEmpty())
        assertTrue(a.panelTokens.isEmpty())
    }

    @Test
    fun `collects continuation tokens only from the audio pivot panel`() {
        val a =
            anchor(
                """
                { "panels": [
                    { "engagementPanelSectionListRenderer": { "panelIdentifier": "engagement-panel-shorts-audio-pivot",
                      "content": { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "AUDIO_TOKEN" } } } } } },
                    { "engagementPanelSectionListRenderer": { "panelIdentifier": "engagement-panel-comments-section",
                      "content": { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "COMMENT_TOKEN" } } } } } } ] }
                """.trimIndent(),
            )
        assertEquals(listOf("AUDIO_TOKEN"), a.panelTokens)
    }

    @Test
    fun `extracts page data from a plain object literal, ignoring braces inside strings`() {
        val html = """<script>var ytInitialData = {"a":"}{","b":{"c":1}};</script><script>other()</script>"""
        val data = extractYtInitialData(html)
        assertNotNull(data)
        assertEquals("}{", data!!["a"]!!.jsonPrimitive.content)
        assertEquals(1, data["b"]!!.jsonObject["c"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `extracts page data from the window-assigned form`() {
        val data = extractYtInitialData("""<script>window["ytInitialData"] = {"x":2};</script>""")
        assertEquals("2", data!!["x"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extracts page data from a quoted hex-escaped string`() {
        val data = extractYtInitialData("""<script>var ytInitialData = '\x7b\x22k\x22:\x22v\x22\x7d';</script>""")
        assertEquals("v", data!!["k"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a page without data, or with unreadable data, gives null instead of throwing`() {
        assertNull(extractYtInitialData("<html>nothing here</html>"))
        assertNull(extractYtInitialData("""<script>var ytInitialData = {"broken": </script>"""))
    }

    @Test
    fun `usability needs more than a lone seed unless YouTube says there is more`() {
        val reel = ReelLockup(id = "a", title = "", viewCount = 0)
        assertEquals(false, SoundPage(listOf(reel)).isUsable)
        assertEquals(true, SoundPage(listOf(reel), next = SoundContinuation("t")).isUsable)
        assertEquals(true, SoundPage(listOf(reel), total = 40L).isUsable)
        assertEquals(true, SoundPage(listOf(reel, reel.copy(id = "b"))).isUsable)
        assertEquals(false, SoundPage(emptyList(), total = 40L).isUsable)
    }
}
