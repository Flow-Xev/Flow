package app.zaid.flow.innertube.pages.channel

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelStoreParserTest {
    private fun parse(json: String) = Json.parseToJsonElement(json).toChannelStorePage()

    @Test
    fun parsesVerticalCardAndUnwrapsRedirect() {
        val page =
            parse(
                """{"a":{"verticalProductCardRenderer":{
                  "title":{"simpleText":"Cool Hoodie"},
                  "price":"${'$'}49.99",
                  "merchantName":"Teespring",
                  "thumbnail":{"thumbnails":[{"url":"//img.example/s.jpg","width":10,"height":10},{"url":"https://img.example/l.jpg","width":100,"height":100}]},
                  "onTap":{"urlEndpoint":{"url":"https://www.youtube.com/redirect?q=https%3A%2F%2Fshop.example%2Fhoodie&v=x"}}
                }}}""",
            )
        val p = page.products.single()
        assertEquals("Cool Hoodie", p.title)
        assertEquals("${'$'}49.99", p.price)
        assertEquals("Teespring", p.merchant)
        assertEquals("https://img.example/l.jpg", p.imageUrl)
        assertEquals("https://shop.example/hoodie", p.url)
    }

    @Test
    fun dropsUnsafeOrMissingLinksAndDuplicates() {
        val page =
            parse(
                """[
                 {"productListItemRenderer":{"title":{"runs":[{"text":"A"}]},"onClickCommand":{"urlEndpoint":{"url":"javascript:alert(1)"}}}},
                 {"productListItemRenderer":{"title":{"simpleText":"B"}}},
                 {"productListItemRenderer":{"title":{"simpleText":"C"},"onClickCommand":{"urlEndpoint":{"url":"https://s.example/c"}}}},
                 {"productListItemRenderer":{"title":{"simpleText":"C again"},"onClickCommand":{"urlEndpoint":{"url":"https://s.example/c"}}}}
                ]""",
            )
        assertEquals(listOf("C"), page.products.map { it.title })
    }

    @Test
    fun readsContinuationAndSurvivesGarbage() {
        val page =
            parse(
                """{"x":{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"TOK"}}}},"y":[1,null,"s",{"verticalProductCardRenderer":5}]}""",
            )
        assertEquals("TOK", page.continuation)
        assertEquals(0, page.products.size)
    }

    @Test
    fun safeLinkRules() {
        assertNull(safeLink("intent://x#Intent;end"))
        assertNull(safeLink("javascript:alert(1)"))
        assertNull(safeLink("file:///etc/passwd"))
        assertNull(safeLink("content://x/y"))
        assertNull(safeLink("data:text/html,hi"))
        assertNull(safeLink("not a url"))
        assertNull(safeLink("mailto:"))
        assertEquals("https://a.example/p", safeLink("//a.example/p"))
    }

    @Test
    fun allowsLinksWithoutHttps() {
        assertEquals("http://a.example/p", safeLink("http://a.example/p"))
        assertEquals("ftp://x.example/a", safeLink("ftp://x.example/a"))
        assertEquals("mailto:shop@a.example", safeLink("mailto:shop@a.example"))
        assertEquals("tel:+15551234", safeLink("tel:+15551234"))
        assertEquals("market://details?id=a.b", safeLink("market://details?id=a.b"))
        assertEquals("https://shop.example/p", safeLink("shop.example/p"))
        assertEquals("http://shop.example/h", safeLink("https://www.youtube.com/redirect?q=http%3A%2F%2Fshop.example%2Fh"))
    }

    @Test
    fun linkLabelShowsHostOrScheme() {
        assertEquals("shop.example", linkLabel("https://www.shop.example/p"))
        assertEquals("shop.example · http", linkLabel("http://shop.example/p"))
        assertEquals("mailto", linkLabel("mailto:a@b.c"))
    }
}
