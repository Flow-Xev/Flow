package app.zaid.flow.account.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvOAuthTest {
    private val id = "123456-abcdef.apps.googleusercontent.com"

    @Test
    fun readsTheClientFromTheMinifiedShape() {
        val script = """var a={clientId:"$id",clientSecret:"s3cr3t-_X"};"""
        assertEquals(id to "s3cr3t-_X", TvOAuth.parseCredentials(script))
    }

    @Test
    fun readsTheClientWhenTheKeysAreSpacedOrMinified() {
        val spaced = """x={ clientId : "$id" , Hz : "abc_DEF-1" }"""
        assertEquals(id to "abc_DEF-1", TvOAuth.parseCredentials(spaced))
        val quoted = """{"clientId":"$id","clientSecret":"abc"}"""
        assertEquals(id to "abc", TvOAuth.parseCredentials(quoted))
    }

    @Test
    fun answersNullWhenNoClientIsThere() {
        assertNull(TvOAuth.parseCredentials("var nothing = 1;"))
    }

    @Test
    fun findsTheScriptWhateverTheAttributeOrder() {
        val pages =
            listOf(
                """<script id="base-js" src="/s/tv/a/base.js"></script>""",
                """<script nonce="n" id="base-js" src="/s/tv/b/base.js"></script>""",
                """<script src="/s/tv/c/base.js" nonce="n" id="base-js"></script>""",
            )
        val found = pages.map { page -> TvOAuth.SCRIPT_REGEXES.firstNotNullOfOrNull { it.find(page)?.groupValues?.get(1) } }
        assertEquals(listOf("/s/tv/a/base.js", "/s/tv/b/base.js", "/s/tv/c/base.js"), found)
    }

    @Test
    fun theCompleteUrlCarriesTheCode() {
        val code = TvDeviceCode("dev", "ABC-DEF-GHI", "https://www.google.com/device", 1800, 5)
        assertEquals("https://www.google.com/device?user_code=ABC-DEF-GHI", code.completeUrl)
        val withQuery = code.copy(verificationUrl = "https://example.com/device?hl=en")
        assertTrue(withQuery.completeUrl.endsWith("?hl=en&user_code=ABC-DEF-GHI"))
    }
}
