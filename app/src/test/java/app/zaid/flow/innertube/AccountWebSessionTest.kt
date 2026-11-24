package app.zaid.flow.innertube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountWebSessionTest {
    private fun page(vararg entries: String) = "<html><script>ytcfg.set({${entries.joinToString(",")}});</script></html>"

    @Test
    fun primaryAccountReadsVisitorDataAndNoDelegation() {
        val session =
            parseAccountWebSession(
                page(
                    "\"LOGGED_IN\":true",
                    "\"VISITOR_DATA\":\"CgtBYmNEZWYxMjM0KOHD\"",
                    "\"DATASYNC_ID\":\"1234567890||\"",
                    "\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"2.20261022.01.00\"",
                ),
            )!!
        assertEquals("CgtBYmNEZWYxMjM0KOHD", session.visitorData)
        assertEquals(true, session.loggedIn)
        assertEquals("2.20261022.01.00", session.clientVersion)
        assertNull("a primary identity needs no delegation", session.delegatedId)
    }

    @Test
    fun brandAccountDelegatesToItsChannelIdentity() {
        val session =
            parseAccountWebSession(
                page("\"LOGGED_IN\":true", "\"VISITOR_DATA\":\"CgtZ\"", "\"DATASYNC_ID\":\"1122334455||1234567890\""),
            )!!
        assertEquals("1122334455", session.delegatedId)
    }

    @Test
    fun delegatedSessionIdWinsOverDataSyncId() {
        val session =
            parseAccountWebSession(
                page("\"DELEGATED_SESSION_ID\":\"998877\"", "\"DATASYNC_ID\":\"1122334455||1234567890\""),
            )!!
        assertEquals("998877", session.delegatedId)
    }

    @Test
    fun signedOutPageReportsNotLoggedIn() {
        val session = parseAccountWebSession(page("\"LOGGED_IN\":false", "\"VISITOR_DATA\":\"CgtX\""))!!
        assertEquals(false, session.loggedIn)
        assertFalse(session.visitorData.isNullOrBlank())
    }

    @Test
    fun visitorDataFallsBackToTheContextBlock() {
        val session = parseAccountWebSession(page("\"INNERTUBE_CONTEXT\":{\"client\":{\"visitorData\":\"CgtCtx\"}}"))!!
        assertEquals("CgtCtx", session.visitorData)
    }

    @Test
    fun pageWithoutAnySessionValuesGivesNull() {
        assertNull(parseAccountWebSession("<html>consent required</html>"))
        assertTrue(parseAccountWebSession(page("\"SOMETHING\":\"else\"")) == null)
    }
}

class NewestClientVersionTest {
    @Test
    fun theHighestVersionWinsPartByPart() {
        assertEquals(
            "2.20261022.01.00",
            newestClientVersion("2.20250122.04.00", "2.20261022.01.00", "2.20260710.06.00"),
        )
        assertEquals("2.20261022.02.00", newestClientVersion("2.20261022.01.00", "2.20261022.02.00"))
        assertEquals("2.20261022.01.10", newestClientVersion("2.20261022.01.9", "2.20261022.01.10"))
    }

    @Test
    fun blankAndMissingVersionsAreIgnored() {
        assertEquals("2.20260710.06.00", newestClientVersion(null, "", "  ", "2.20260710.06.00"))
        assertNull(newestClientVersion(null, ""))
    }
}
