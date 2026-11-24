package app.zaid.flow.innertube.pages.reel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sound parser's tolerance for the response shapes YouTube has used, per YouTube.js's node definitions. */
class SoundResilienceTest {
    private fun json(raw: String) = Json.parseToJsonElement(raw.trimIndent())

    private fun lockup(
        id: String,
        entityOnly: Boolean = false,
    ) = if (entityOnly) {
        """{ "shortsLockupViewModel": { "entityId": "shorts-shelf-item-$id", "accessibilityText": "t, 1K views - play Short" } }"""
    } else {
        """{ "shortsLockupViewModel": { "onTap": { "innertubeCommand": { "reelWatchEndpoint": { "videoId": "$id" } } } } }"""
    }

    @Test
    fun `sound title is read from simple text and from runs as well as from view-model content`() {
        val simple = json("""{ "pivotButtonRenderer": { "soundAttributionTitle": { "simpleText": "Plain title" } } }""").soundAnchor()
        val runs = json("""{ "pivotButtonRenderer": { "soundAttributionTitle": { "runs": [ { "text": "Run " }, { "text": "title" } ] } } }""").soundAnchor()
        assertEquals("Plain title", simple.title)
        assertEquals("Run title", runs.title)
    }

    @Test
    fun `the pivot that links to the audio page wins over an earlier pivot that does not`() {
        val a =
            json(
                """
                { "a": { "pivotButtonRenderer": { "soundAttributionTitle": { "simpleText": "No link" } } },
                  "b": { "pivotButtonRenderer": { "soundAttributionTitle": { "simpleText": "Linked" },
                    "onClickCommand": { "browseEndpoint": { "browseId": "FEsfv_audio_pivot", "params": "P" } } } } }
                """,
            ).soundAnchor()
        assertEquals("Linked", a.title)
        assertEquals("P", a.params)
    }

    @Test
    fun `an audio pivot link outside any pivot button still anchors the sound`() {
        val a = json("""{ "chip": { "browseEndpoint": { "browseId": "FEsfv_audio_pivot", "params": "LOOSE" } } }""").soundAnchor()
        assertEquals("FEsfv_audio_pivot", a.browseId)
        assertEquals("LOOSE", a.params)
    }

    @Test
    fun `an ordinary browse link is not mistaken for a sound link`() {
        val a = json("""{ "chip": { "browseEndpoint": { "browseId": "UCabc", "params": "x" } } }""").soundAnchor()
        assertNull(a.browseId)
    }

    @Test
    fun `a lockup whose tap carries no command is still read through its entity id`() {
        val reels = json("""{ "items": [ ${lockup("AAAAAAAAAAA", entityOnly = true)} ] }""").jsonObject.toReelLockups()
        assertEquals(listOf("AAAAAAAAAAA"), reels.map { it.id })
    }

    @Test
    fun `a thin page is topped up with reels the strict parser skips, a full page is not`() {
        val extra = """{ "someNewRenderer": { "navigationEndpoint": { "reelWatchEndpoint": { "videoId": "BBBBBBBBBBB" } } } }"""
        val thin = json("""{ "items": [ ${lockup("AAAAAAAAAAA")}, $extra ] }""").jsonObject.soundReels()
        assertEquals(listOf("AAAAAAAAAAA", "BBBBBBBBBBB"), thin.map { it.id })

        val full = (1..8).joinToString(",") { lockup("FULLFULL%03d".format(it)) }
        val page = json("""{ "items": [ $full, $extra ] }""").jsonObject.soundReels()
        assertEquals(8, page.size)
        assertTrue(page.none { it.id == "BBBBBBBBBBB" })
    }

    @Test
    fun `plain shorts links are only used when nothing else was found`() {
        val onlyLinks = json("""{ "u": "https://www.youtube.com/shorts/CCCCCCCCCCC" }""").jsonObject.soundReels()
        assertEquals(listOf("CCCCCCCCCCC"), onlyLinks.map { it.id })
        val withLockup = json("""{ "items": [ ${lockup("AAAAAAAAAAA")} ], "u": "https://www.youtube.com/shorts/CCCCCCCCCCC" }""").jsonObject.soundReels()
        assertEquals(listOf("AAAAAAAAAAA"), withLockup.map { it.id })
    }

    @Test
    fun `next page token is found directly and inside a command executor`() {
        val direct =
            json("""{ "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "DIRECT" } } } }""")
        val wrapped =
            json(
                """
                { "x": [ { "continuationItemRenderer": { "continuationEndpoint": { "commandExecutorCommand": { "commands": [
                    { "other": 1 }, { "continuationCommand": { "token": "WRAPPED" } } ] } } } } ] }
                """,
            )
        assertEquals("DIRECT", direct.nextPageToken())
        assertEquals("WRAPPED", wrapped.nextPageToken())
        assertNull(json("""{ "continuationCommand": { "token": "NOT_AN_ITEM" } }""").nextPageToken())
    }
}
