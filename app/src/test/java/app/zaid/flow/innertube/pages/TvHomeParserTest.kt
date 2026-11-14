package app.zaid.flow.innertube.pages

import app.zaid.flow.innertube.pages.renderer.FeedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic fixture shaped like the TV app's home shelves; replace with a captured response when one is available. */
class TvHomeParserTest {
    private val sample =
        """
        {"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{"sectionListRenderer":{
          "contents":[{"shelfRenderer":{"content":{"horizontalListRenderer":{"items":[
            {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"dQw4w9WgXcQ",
              "onSelectCommand":{"watchEndpoint":{"videoId":"dQw4w9WgXcQ"}},
              "header":{"tileHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/a.jpg"},{"url":"https://i.ytimg.com/b.jpg"}]},
                "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"1:02:03"}}}]}},
              "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"A title"},
                "lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"runs":[{"text":"Some Channel"}]}}}]}},
                         {"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"1.2M views"}}},{"lineItemRenderer":{"text":{"simpleText":"3 days ago"}}}]}}]}},
              "onLongPressCommand":{"showMenuCommand":{"menu":{"menuRenderer":{"items":[{"menuNavigationItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCabc"}}}}]}}}}}},
            {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_PLAYLIST","contentId":"PLxyz"}}
          ]}}}}],
          "continuations":[{"nextContinuationData":{"continuation":"TOKEN123"}}]}}}}}}}
        """.trimIndent()

    @Test
    fun readsVideoTilesAndSkipsOtherTiles() {
        val page = TvHomeParser.parse(Json.parseToJsonElement(sample).jsonObject)
        assertEquals(1, page.items.size)
        val video = (page.items.single() as FeedItem.VideoItem).video
        assertEquals("dQw4w9WgXcQ", video.id)
        assertEquals("A title", video.title)
        assertEquals("Some Channel", video.channelName)
        assertEquals("UCabc", video.channelId)
        assertEquals("https://i.ytimg.com/b.jpg", video.thumbnailUrl)
        assertEquals(3723, video.duration)
        assertEquals(1_200_000L, video.viewCount)
        assertEquals("3 days ago", video.uploadDate)
        assertEquals("TOKEN123", page.continuation)
    }

    @Test
    fun parsesCountsAndDurations() {
        assertEquals(845_000L, TvHomeParser.parseCount("845K watching"))
        assertEquals(1234L, TvHomeParser.parseCount("1,234 views"))
        assertEquals(0L, TvHomeParser.parseCount(null))
        assertEquals(754, TvHomeParser.parseDuration("12:34"))
        assertEquals(0, TvHomeParser.parseDuration("LIVE"))
        assertNotNull(TvHomeParser.parse(Json.parseToJsonElement("{}").jsonObject))
        assertTrue(TvHomeParser.parse(Json.parseToJsonElement("{}").jsonObject).items.isEmpty())
    }

    @Test
    fun readsTheResumeBarAsWatchedPercent() {
        val page =
            TvHomeParser.parse(
                Json
                    .parseToJsonElement(
                        """
                        {"items":[
                          {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"dQw4w9WgXcQ",
                            "onSelectCommand":{"watchEndpoint":{"videoId":"dQw4w9WgXcQ"}},
                            "header":{"tileHeaderRenderer":{"thumbnailOverlays":[
                              {"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"10:00"}}},
                              {"thumbnailOverlayResumePlaybackRenderer":{"percentDurationWatched":42}}]}},
                            "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Half watched"}}}}},
                          {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"9bZkp7q19f0",
                            "onSelectCommand":{"watchEndpoint":{"videoId":"9bZkp7q19f0"}},
                            "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Unseen"}}}}}
                        ]}
                        """.trimIndent(),
                    ).jsonObject,
            )
        assertEquals(2, page.items.size)
        assertEquals(mapOf("dQw4w9WgXcQ" to 42), page.watchedPercent)
    }
}
