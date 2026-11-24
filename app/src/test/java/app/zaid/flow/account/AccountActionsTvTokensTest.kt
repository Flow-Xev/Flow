package app.zaid.flow.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic TV tile menus; replace with a captured history page when one is available. */
class AccountActionsTvTokensTest {
    private fun tile(
        id: String,
        vararg items: Pair<String, String>,
    ): String {
        val menu =
            items.joinToString(",") { (icon, token) ->
                """{"menuServiceItemRenderer":{"icon":{"iconType":"$icon"},"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"$token"}}}}"""
            }
        return """{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"$id",
            "onLongPressCommand":{"showMenuCommand":{"menu":{"menuRenderer":{"items":[$menu]}}}}}}"""
    }

    @Test
    fun aHistoryTileKeepsItsDeleteTokenAndNotTheChannelOne() {
        val page = Json.parseToJsonElement("""{"items":[${tile("AAAAAAAAAAA", "REMOVE" to "channel-token", "DELETE" to "history-token")}]}""")
        AccountActions.rememberTvFeedbackTokens(page, fromHistory = true)
        assertEquals("history-token", AccountActions.historyRemoveToken("AAAAAAAAAAA"))
    }

    @Test
    fun aPlainRemoveIconIsNeverTakenForHistoryRemoval() {
        val page = Json.parseToJsonElement("""{"items":[${tile("BBBBBBBBBBB", "REMOVE" to "channel-token")}]}""")
        AccountActions.rememberTvFeedbackTokens(page, fromHistory = true)
        assertNull(AccountActions.historyRemoveToken("BBBBBBBBBBB"))
    }

    @Test
    fun feedsOtherThanHistoryNeverRecordRemovalTokens() {
        val page = Json.parseToJsonElement("""{"items":[${tile("CCCCCCCCCCC", "DELETE" to "would-delete")}]}""")
        AccountActions.rememberTvFeedbackTokens(page)
        assertNull(AccountActions.historyRemoveToken("CCCCCCCCCCC"))
    }
}
