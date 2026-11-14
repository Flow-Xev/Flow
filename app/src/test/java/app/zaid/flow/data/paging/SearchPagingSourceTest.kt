package app.zaid.flow.data.paging

import androidx.paging.PagingSource
import app.zaid.flow.data.local.ContentType
import app.zaid.flow.data.local.Duration
import app.zaid.flow.data.local.SearchFilter
import app.zaid.flow.data.local.SortType
import app.zaid.flow.data.local.UploadDate
import app.zaid.flow.data.recommendation.FeedExclusions
import app.zaid.flow.innertube.pages.SearchFixture
import app.zaid.flow.innertube.pages.search.SearchHeader
import app.zaid.flow.innertube.pages.search.toSearchResultsPage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SearchPagingSourceTest {
    private class RecordingLoader(
        private val pages: List<String>,
    ) : SearchPageLoader {
        val calls = mutableListOf<Triple<String, String?, String?>>()
        var failNext = false

        override suspend fun invoke(
            query: String,
            params: String?,
            continuation: String?,
        ) = run {
            calls += Triple(query, params, continuation)
            if (failNext) {
                failNext = false
                throw IllegalStateException("boom")
            }
            SearchFixture(pages[(calls.size - 1).coerceAtMost(pages.lastIndex)]).toSearchResultsPage()
        }
    }

    private fun source(
        loader: SearchPageLoader,
        filter: SearchFilter = SearchFilter.DEFAULT,
        shortsEnabled: Boolean = true,
        onHeader: (SearchHeader) -> Unit = {},
        blocked: Set<String> = emptySet(),
    ) = SearchPagingSource("sam sulek", filter, shortsEnabled, onHeader, loader) { FeedExclusions(blockedChannelIds = blocked) }

    private suspend fun SearchPagingSource.loadPage(key: String? = null) =
        load(PagingSource.LoadParams.Refresh(key, 20, false)) as PagingSource.LoadResult.Page

    @Test
    fun `issues exactly one request per page`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))
            source(loader).loadPage()

            assertThat(loader.calls).hasSize(1)
        }

    @Test
    fun `the first page carries the query and the params, a later page carries only the token`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK, SearchFixture.ALL_CONTINUATION))
            val paging = source(loader, SearchFilter(contentType = ContentType.VIDEOS))

            val first = paging.loadPage()
            paging.loadPage(first.nextKey)

            assertThat(loader.calls[0].second).isEqualTo("EgIQAQ%3D%3D")
            assertThat(loader.calls[0].third).isNull()
            assertThat(loader.calls[1].third).isEqualTo(first.nextKey)
        }

    @Test
    fun `hands the next page token back as the paging key`() =
        runTest {
            val page = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).loadPage()

            assertThat(page.prevKey).isNull()
            assertThat(page.nextKey).isNotNull()
        }

    @Test
    fun `reports the header from the first page only`() =
        runTest {
            val headers = mutableListOf<SearchHeader>()
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK, SearchFixture.ALL_CONTINUATION))
            val paging = source(loader, onHeader = { headers += it })

            val first = paging.loadPage()
            paging.loadPage(first.nextKey)

            assertThat(headers).hasSize(1)
            assertThat(headers.single().filterGroups).hasSize(5)
        }

    @Test
    fun `keeps every strip distinct across the page`() =
        runTest {
            val page = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).loadPage()
            val shelves = page.data.filterIsInstance<SearchResultItem.ShelfResult>()

            assertThat(shelves.map { it.id }).containsNoDuplicates()
            assertThat(shelves.count { it.kind == SearchShelfKind.SHORTS }).isEqualTo(2)
        }

    @Test
    fun `folds the latest-from strip into the creator card it belongs to`() =
        runTest {
            val page = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).loadPage()
            val creator = page.data.filterIsInstance<SearchResultItem.ChannelResult>().single()

            assertThat(creator.latestVideos).isNotEmpty()
            assertThat(creator.latestTitle).isNotNull()
            assertThat(
                page.data.filterIsInstance<SearchResultItem.ShelfResult>().none {
                    it.kind == SearchShelfKind.VIDEOS
                },
            ).isTrue()
        }

    @Test
    fun `de-duplicates a result that comes back on a later page`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK, SearchFixture.ALL_SAM_SULEK))
            val paging = source(loader)

            val first = paging.loadPage()
            val second = paging.loadPage(first.nextKey)

            assertThat(first.data).isNotEmpty()
            assertThat(second.data).isEmpty()
        }

    @Test
    fun `hides shorts and shorts strips when the user turned shorts off`() =
        runTest {
            val page = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK)), shortsEnabled = false).loadPage()

            assertThat(
                page.data.filterIsInstance<SearchResultItem.ShelfResult>().none {
                    it.kind == SearchShelfKind.SHORTS
                },
            ).isTrue()
        }

    @Test
    fun `passes the shorts type as a params token instead of a second request`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.TYPE_SHORTS))
            source(loader, SearchFilter(contentType = ContentType.SHORTS)).loadPage()

            assertThat(loader.calls).hasSize(1)
            assertThat(loader.calls.single().second).isEqualTo("EgIQCQ%3D%3D")
        }

    @Test
    fun `builds one token for a combination of filters`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.COMBINED_FILTERS))
            val filter =
                SearchFilter(
                    contentType = ContentType.VIDEOS,
                    duration = Duration.OVER_20_MINUTES,
                    uploadDate = UploadDate.THIS_YEAR,
                    sortType = SortType.VIEW_COUNT,
                )
            source(loader, filter).loadPage()

            assertThat(loader.calls.single().second).isEqualTo("CAMSBggFEAEYAg%3D%3D")
        }

    @Test
    fun `returns every item the response carried, filtering nothing on the client`() =
        runTest {
            val response = SearchFixture(SearchFixture.DURATION_UNDER_3).toSearchResultsPage()
            val page =
                source(
                    RecordingLoader(listOf(SearchFixture.DURATION_UNDER_3)),
                    SearchFilter(duration = Duration.UNDER_3_MINUTES),
                ).loadPage()

            assertThat(page.data).hasSize(response.toResultItems(shortsEnabled = true).size)
        }

    @Test
    fun `hides a blocked creator, their card and their videos`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))
            val visible = source(loader).loadPage()
            val creator = visible.data.filterIsInstance<SearchResultItem.ChannelResult>().single()

            val page =
                source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK)), blocked = setOf(creator.channel.id))
                    .loadPage()

            assertThat(page.data.filterIsInstance<SearchResultItem.ChannelResult>()).isEmpty()
            assertThat(
                page.data.filterIsInstance<SearchResultItem.VideoResult>().none {
                    it.video.channelId == creator.channel.id
                },
            ).isTrue()
            assertThat(
                page.data.filterIsInstance<SearchResultItem.ShelfResult>().none { shelf ->
                    shelf.videos.any { it.channelId == creator.channel.id }
                },
            ).isTrue()
        }

    @Test
    fun `blocking nobody leaves the page untouched`() =
        runTest {
            val open = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).loadPage()
            val empty = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK)), blocked = emptySet()).loadPage()

            assertThat(empty.data).hasSize(open.data.size)
        }

    @Test
    fun `a strip emptied by blocking is dropped rather than left as a heading`() {
        val shelf =
            SearchResultItem.ShelfResult(
                id = "s1",
                title = "Latest",
                kind = SearchShelfKind.VIDEOS,
                videos = listOf(video(channelId = "UCblocked")),
            )

        assertThat(listOf(shelf).withoutHidden(FeedExclusions(blockedChannelIds = setOf("UCblocked")))).isEmpty()
    }

    @Test
    fun `a video with no channel id survives blocking`() {
        val item = SearchResultItem.VideoResult(video(channelId = ""))

        assertThat(listOf(item).withoutHidden(FeedExclusions(blockedChannelIds = setOf("UCblocked")))).containsExactly(item)
    }

    @Test
    fun `a video marked not interested leaves the results but its channel stays`() {
        val hidden = SearchResultItem.VideoResult(video(channelId = "UCkept"))
        val channel =
            SearchResultItem.ChannelResult(
                app.zaid.flow.data.model
                    .Channel(id = "UCkept", name = "Kept", thumbnailUrl = "", subscriberCount = 0L),
            )

        val visible =
            listOf(hidden, channel).withoutHidden(
                FeedExclusions(suppressedVideoIds = setOf(hidden.video.id), suppressedChannelIds = setOf("UCkept")),
            )

        assertThat(visible).containsExactly(channel)
    }

    private fun video(channelId: String) =
        app.zaid.flow.data.model.Video(
            id = "v1",
            title = "t",
            channelName = "c",
            channelId = channelId,
            thumbnailUrl = "",
            duration = 60,
            viewCount = 1,
            uploadDate = "",
        )

    @Test
    fun `surfaces a loader failure as an error result`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK)).apply { failNext = true }

            val result = source(loader).load(PagingSource.LoadParams.Refresh(null, 20, false))

            assertThat(result).isInstanceOf(PagingSource.LoadResult.Error::class.java)
        }

    @Test
    fun `never resumes from a stale key`() =
        runTest {
            assertThat(source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).getRefreshKey(mockState())).isNull()
        }

    private fun mockState() = androidx.paging.PagingState<String, SearchResultItem>(emptyList(), null, PAGING_CONFIG, 0)

    private companion object {
        val PAGING_CONFIG = androidx.paging.PagingConfig(pageSize = 20)
    }
}

class SearchPagingSourceChainingTest {
    private class Loader(
        private val pages: List<app.zaid.flow.innertube.pages.search.SearchResultsPage>,
    ) : SearchPageLoader {
        var calls = 0

        override suspend fun invoke(
            query: String,
            params: String?,
            continuation: String?,
        ) = pages[calls++.coerceAtMost(pages.lastIndex)]
    }

    private fun video(id: String) =
        app.zaid.flow.innertube.pages.search.SearchSection.Result(
            app.zaid.flow.innertube.pages.renderer.FeedItem.VideoItem(
                app.zaid.flow.data.model.Video(
                    id = id,
                    title = id,
                    channelName = "c",
                    channelId = "c",
                    thumbnailUrl = "",
                    duration = 60,
                    viewCount = 1,
                    uploadDate = "",
                ),
            ),
        )

    private fun page(
        vararg ids: String,
        next: String?,
    ) = app.zaid.flow.innertube.pages.search.SearchResultsPage(sections = ids.map(::video), continuation = next)

    @Test
    fun `a thin page chains the next one in the same load`() =
        runTest {
            val loader = Loader(listOf(page("a", next = "t1"), page("b", "c", next = null)))
            val source = SearchPagingSource("q", loadPage = loader, minResultsPerLoad = 3)
            val result = source.load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
            assertThat(loader.calls).isEqualTo(2)
            assertThat(result.data).hasSize(3)
            assertThat(result.nextKey).isNull()
        }

    @Test
    fun `an empty page with a next key never reaches paging`() =
        runTest {
            val loader = Loader(listOf(page(next = "t1"), page("a", next = "t2")))
            val source = SearchPagingSource("q", loadPage = loader)
            val result = source.load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
            assertThat(result.data).hasSize(1)
        }
}

class SearchPagingSourceEndlessTest {
    private class Loader : SearchPageLoader {
        val queries = mutableListOf<String>()

        override suspend fun invoke(
            query: String,
            params: String?,
            continuation: String?,
        ): app.zaid.flow.innertube.pages.search.SearchResultsPage {
            queries += query
            val video =
                app.zaid.flow.innertube.pages.search.SearchSection.Result(
                    app.zaid.flow.innertube.pages.renderer.FeedItem.VideoItem(
                        app.zaid.flow.data.model.Video(
                            id = "$query-${queries.size}",
                            title = query,
                            channelName = "c",
                            channelId = "c",
                            thumbnailUrl = "",
                            duration = 60,
                            viewCount = 1,
                            uploadDate = "",
                        ),
                    ),
                )
            return app.zaid.flow.innertube.pages.search.SearchResultsPage(sections = listOf(video), continuation = null)
        }
    }

    @Test
    fun `when youtube runs out the list continues into related searches`() =
        runTest {
            val loader = Loader()
            val source =
                SearchPagingSource("cats", loadPage = loader, endless = true, relatedQueries = { listOf("$it kittens") }, recommendedFeed = { _, _ -> null })
            val first = source.load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
            assertThat(first.nextKey).isNotNull()
            val second = source.load(PagingSource.LoadParams.Append(first.nextKey!!, 20, false)) as PagingSource.LoadResult.Page
            assertThat(loader.queries).containsExactly("cats", "cats kittens").inOrder()
            assertThat(second.data).hasSize(1)
        }

    @Test
    fun `endless stops only when suggestions are spent`() =
        runTest {
            val loader = Loader()
            val source =
                SearchPagingSource("cats", loadPage = loader, endless = true, relatedQueries = { emptyList() }, recommendedFeed = { _, _ -> null })
            var page = source.load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
            var guard = 0
            while (page.nextKey != null && guard++ < 5) {
                page = source.load(PagingSource.LoadParams.Append(page.nextKey!!, 20, false)) as PagingSource.LoadResult.Page
            }
            assertThat(page.nextKey).isNull()
        }
}

class SearchPagingSourceShortsTest {
    @Test
    fun `shorts tab opens a shorts shelf into single shorts`() {
        val v = { id: String ->
            app.zaid.flow.data.model.Video(
                id = id, title = id, channelName = "c", channelId = "c", thumbnailUrl = "",
                duration = 30, viewCount = 1, uploadDate = "", isShort = true,
            )
        }
        val shelf = SearchResultItem.ShelfResult("s", "Shorts", SearchShelfKind.SHORTS, videos = listOf(v("a"), v("b")))
        val flat = listOf(shelf, SearchResultItem.VideoResult(v("c"))).flattenShorts()
        assertThat(flat).hasSize(3)
    }
}

class SearchPagingSourcePeopleAlsoWatchTest {
    private fun v(id: String) =
        app.zaid.flow.data.model.Video(
            id = id, title = id, channelName = "c", channelId = "c", thumbnailUrl = "",
            duration = 60, viewCount = 1, uploadDate = "",
        )

    @Test
    fun `when youtube runs out people also watch comes before related searches`() =
        runTest {
            val queries = mutableListOf<String>()
            val loader =
                SearchPageLoader { q, _, _ ->
                    queries += q
                    app.zaid.flow.innertube.pages.search.SearchResultsPage(
                        sections =
                            listOf(
                                app.zaid.flow.innertube.pages.search.SearchSection.Result(
                                    app.zaid.flow.innertube.pages.renderer.FeedItem.VideoItem(v("top")),
                                ),
                            ),
                    )
                }
            val source =
                SearchPagingSource(
                    "cats", loadPage = loader, endless = true,
                    relatedQueries = { listOf("cats kittens") },
                    recommendedFeed = { seed, token -> if (token == null) listOf(v("rec1"), v("rec2")) to null else null },
                )
            val first = source.load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
            val second = source.load(PagingSource.LoadParams.Append(first.nextKey!!, 20, false)) as PagingSource.LoadResult.Page
            val ids = second.data.filterIsInstance<SearchResultItem.VideoResult>().map { it.video.id }
            assertThat(ids).containsExactly("rec1", "rec2").inOrder()
            assertThat(queries).containsExactly("cats")
        }
}
