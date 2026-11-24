package app.zaid.flow.innertube.pages.reel

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundPageTest {
    private fun total(raw: String) = Json.parseToJsonElement(raw).soundShortsTotal()?.count

    @Test
    fun `reads an abbreviated total from a plain string`() {
        assertEquals(1_200_000L, total("""{ "header": { "subtitle": { "content": "1.2M Shorts" } } }"""))
    }

    @Test
    fun `reads an exact total split across runs`() {
        assertEquals(
            4_821L,
            total("""{ "header": { "subtitle": { "runs": [ { "text": "4,821" }, { "text": " videos" } ] } } }"""),
        )
    }

    @Test
    fun `takes the first match in document order`() {
        assertEquals(
            9_000L,
            total("""{ "a": { "simpleText": "9K Shorts" }, "b": { "simpleText": "12K videos" } }"""),
        )
    }

    @Test
    fun `ignores view counts and unrelated text`() {
        assertNull(total("""{ "a": { "simpleText": "1.2M views" }, "b": "Original audio", "c": "3 days ago" }"""))
    }
}
