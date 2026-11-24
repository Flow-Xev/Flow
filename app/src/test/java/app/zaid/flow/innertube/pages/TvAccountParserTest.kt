package app.zaid.flow.innertube.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic fixtures shaped like the TV app's accounts list and subscriptions page; replace with captured responses when available. */
class TvAccountParserTest {
    private fun obj(text: String) = Json.parseToJsonElement(text.trimIndent()).jsonObject

    @Test
    fun readsTheSelectedAccount() {
        val info =
            TvAccountParser.parseAccountInfo(
                obj(
                    """
                    {"contents":[{"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[
                      {"accountItem":{"accountName":{"simpleText":"Other Channel"},"accountByline":{"simpleText":"Brand"},"isSelected":false}},
                      {"accountItem":{"accountName":{"runs":[{"text":"Zaid"}]},"accountByline":{"simpleText":"zaid@example.com"},
                        "channelHandle":{"simpleText":"@zaid"},"isSelected":true,
                        "accountPhoto":{"thumbnails":[{"url":"https://yt3.ggpht.com/small"},{"url":"https://yt3.ggpht.com/large"}]}}}
                    ]}}]}}]}
                    """,
                ),
            )!!
        assertEquals("Zaid", info.name)
        assertEquals("zaid@example.com", info.email)
        assertEquals("@zaid", info.channelHandle)
        assertEquals("https://yt3.ggpht.com/large", info.thumbnailUrl)
    }

    @Test
    fun anEmptyAccountListIsNoAccount() {
        assertNull(TvAccountParser.parseAccountInfo(obj("{}")))
    }

    @Test
    fun readsFollowedChannelsFromTheTabStrip() {
        val channels =
            TvAccountParser.parseSubscribedChannels(
                obj(
                    """
                    {"contents":{"tvBrowseRenderer":{"content":{"tvSecondaryNavRenderer":{"sections":[{"tvSecondaryNavSectionRenderer":{"tabs":[
                      {"tabRenderer":{"title":"All","endpoint":{"browseEndpoint":{"browseId":"FEsubscriptions"}}}},
                      {"tabRenderer":{"title":"First Channel","endpoint":{"browseEndpoint":{"browseId":"UCaaaaaaaaaaaaaaaaaaaaaa"}},
                        "thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/a"}]}}},
                      {"tabRenderer":{"title":"Second Channel","endpoint":{"browseEndpoint":{"browseId":"UCbbbbbbbbbbbbbbbbbbbbbb"}}}},
                      {"tabRenderer":{"title":"First Channel","endpoint":{"browseEndpoint":{"browseId":"UCaaaaaaaaaaaaaaaaaaaaaa"}}}}
                    ]}}]}}}}}
                    """,
                ),
            )
        assertEquals(listOf("UCaaaaaaaaaaaaaaaaaaaaaa", "UCbbbbbbbbbbbbbbbbbbbbbb"), channels.map { it.id })
        assertEquals("First Channel", channels[0].name)
        assertEquals("https://yt3.ggpht.com/a", channels[0].thumbnailUrl)
        assertTrue(channels.all { it.isSubscribed })
    }

    @Test
    fun readsChannelTilesToo() {
        val channels =
            TvAccountParser.parseSubscribedChannels(
                obj(
                    """
                    {"items":[{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_CHANNEL","contentId":"UCcccccccccccccccccccccc",
                      "onSelectCommand":{"browseEndpoint":{"browseId":"UCcccccccccccccccccccccc"}},
                      "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Tile Channel"}}},
                      "header":{"tileHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/c"}]}}}}},
                      {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"dQw4w9WgXcQ"}}]}
                    """,
                ),
            )
        assertEquals(1, channels.size)
        assertEquals("Tile Channel", channels.single().name)
    }
}
