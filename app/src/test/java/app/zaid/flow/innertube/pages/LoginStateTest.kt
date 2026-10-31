package app.zaid.flow.innertube.pages

import app.zaid.flow.ui.screens.account.holdsFullSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginStateTest {
    private fun response(vararg params: Pair<String, String>) =
        Json
            .parseToJsonElement(
                """{"responseContext":{"serviceTrackingParams":[
                    {"service":"GFEEDBACK","params":[${params.joinToString(",") { """{"key":"${it.first}","value":"${it.second}"}""" }}]},
                    {"service":"CSI","params":[{"key":"c","value":"WEB"}]}]}}""",
            ).jsonObject

    @Test
    fun `logged_in 1 is a signed-in response`() {
        assertEquals(true, response("browse_id" to "FEwhat_to_watch", "logged_in" to "1").isLoggedInResponse())
    }

    @Test
    fun `logged_in 0 is the anonymous feed`() {
        assertEquals(false, response("logged_in" to "0").isLoggedInResponse())
    }

    @Test
    fun `yt_li is read when logged_in is absent`() {
        assertEquals(false, response("yt_li" to "0").isLoggedInResponse())
    }

    @Test
    fun `either flag saying signed in wins`() {
        assertEquals(true, response("logged_in" to "0", "yt_li" to "1").isLoggedInResponse())
    }

    @Test
    fun `a page without the flags is not mistaken for a rejected session`() {
        assertNull(response("browse_id" to "x").isLoggedInResponse())
        assertNull(Json.parseToJsonElement("{}").jsonObject.isLoggedInResponse())
    }

    @Test
    fun `a session needs both the signature key and the session id`() {
        assertTrue("SID=a; SAPISID=b; HSID=c".holdsFullSession())
        assertTrue("__Secure-3PSID=a; __Secure-3PAPISID=b".holdsFullSession())
        assertFalse("SAPISID=b; HSID=c; APISID=d".holdsFullSession())
        assertFalse("SID=a; HSID=c".holdsFullSession())
        assertFalse("".holdsFullSession())
    }
}
