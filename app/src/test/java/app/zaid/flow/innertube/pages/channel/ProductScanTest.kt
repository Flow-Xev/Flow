package app.zaid.flow.innertube.pages.channel

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SYNTHETIC fixtures: hand-written to exercise the diagnostics, NOT captured from YouTube. They say
 * nothing about which formats YouTube really sends; see RealProductFixturesTest for that.
 */
class ProductScanTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun scanListsProductKeyPathsWithArraysCollapsed() {
        val hits =
            json(
                """{"contents":{"items":[
                    {"merchandiseShelfRenderer":{"items":[{"merchandiseItemRenderer":{}},{"merchandiseItemRenderer":{}}]}},
                    {"shoppingOverlayRenderer":{}}
                ]},"unrelated":{"title":"x"}}""",
            ).scanProductKeys().associate { it.shape to it.count }
        assertEquals(1, hits["contents.items[].merchandiseShelfRenderer"])
        assertEquals(2, hits["contents.items[].merchandiseShelfRenderer.items[].merchandiseItemRenderer"])
        assertEquals(1, hits["contents.items[].shoppingOverlayRenderer"])
        assertEquals(3, hits.size)
    }

    @Test
    fun tallyExplainsWhyCardsWereDropped() {
        val tally =
            json(
                """[
                  {"merchandiseItemRenderer":{"title":"Good","buttonCommand":{"urlEndpoint":{"url":"https://s.example/a"}}}},
                  {"merchandiseItemRenderer":{"title":"No link"}},
                  {"merchandiseItemRenderer":{"price":"1"}},
                  {"merchandiseItemRenderer":{"title":"Bad link","buttonCommand":{"urlEndpoint":{"url":"javascript:alert(1)"}}}}
                ]""",
            ).tallyProductCards().getValue("merchandiseItemRenderer")
        assertEquals(4, tally.found)
        assertEquals(1, tally.parsed)
        assertEquals(1, tally.noTitle)
        assertEquals(2, tally.noLink)
    }

    @Test
    fun scanAndTallySurviveGarbage() {
        val element = json("""[1,null,"s",{"merchandiseItemRenderer":5},{"x":[[[]]]}]""")
        assertTrue(element.scanProductKeys().isNotEmpty())
        assertEquals(1, element.tallyProductCards().getValue("merchandiseItemRenderer").noTitle)
    }

    @Test
    fun reportMentionsRedirectsAndMissingKeys() {
        val report = json("""{"a":"https://www.youtube.com/redirect?q=https%3A%2F%2Fx.example"}""").describeProductScan("t")
        assertTrue(report.contains("Known product renderers found: none"))
        assertTrue(report.contains("redirect links in response: 1"))
    }
}
