package app.zaid.flow.data.paging

import app.zaid.flow.data.local.ContentType
import app.zaid.flow.data.local.SearchFilter
import app.zaid.flow.data.model.Channel
import app.zaid.flow.data.model.distinctByNonBlankKey
import app.zaid.flow.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val MAX_RESULTS = 15
private const val CHANNEL_ID_PREFIX = "UC"

/**
 * The first page of channels matching a query, from the same InnerTube search the Search tab runs.
 * Only `UC…` ids come back: a handle is not a browse id, and a subscription stored under one never
 * gets a feed.
 */
class ChannelSearch
    @Inject
    constructor() {
        suspend fun search(query: String): List<Channel> =
            withContext(Dispatchers.IO) {
                YouTube
                    .videoSearch(query, SearchFilter(contentType = ContentType.CHANNELS).toSearchParams())
                    .map { page ->
                        page
                            .toResultItems(shortsEnabled = false)
                            .filterIsInstance<SearchResultItem.ChannelResult>()
                            .map { it.channel }
                    }.getOrDefault(emptyList())
                    .filter { it.id.startsWith(CHANNEL_ID_PREFIX) }
                    .distinctByNonBlankKey(Channel::id)
                    .take(MAX_RESULTS)
            }
    }
