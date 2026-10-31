package app.zaid.flow.ui.screens.player.related

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.repository.YouTubeRepository
import app.zaid.flow.innertube.pages.RelatedFeedChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The player's "up next" column with YouTube's paging: the videos the player already loaded, the
 * pages that follow them, and the chips that swap the list. One instance per watched video, shared
 * by the portrait list and the landscape drawer so both show — and keep paging — the same list.
 */
@Stable
class RelatedFeed internal constructor(
    val videoId: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = YouTubeRepository.getInstance()

    private var extra by mutableStateOf<List<Video>>(emptyList())
    private var chipVideos by mutableStateOf<List<Video>?>(null)
    var chips by mutableStateOf<List<RelatedFeedChip>>(emptyList())
        private set
    var selectedChip by mutableIntStateOf(0)
        private set
    var isLoading by mutableStateOf(false)
        private set

    private var baseContinuation: String? = null
    private var continuation: String? = null
    private var started = false
    private var job: Job? = null

    /** The list to draw: what the player loaded (or the selected chip's list) plus the pages after it. */
    fun display(base: List<Video>): List<Video> = chipVideos ?: (base + extra).distinctBy { it.id }

    /** Reads chips and the first continuation off the cached watch response; idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val first = repository.relatedFeedFirstPage(videoId) ?: return@launch
            chips = first.chips
            selectedChip = first.chips.indexOfFirst { it.selected }.coerceAtLeast(0)
            baseContinuation = first.continuation
            if (chipVideos == null && job == null) continuation = first.continuation
        }
    }

    fun loadMore(known: () -> Set<String> = { emptySet() }) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                isLoading = true
                try {
                    var attempts = 0
                    while (attempts++ < MAX_EMPTY_PAGES) {
                        val token = continuation ?: break
                        val page = repository.relatedFeedPage(token)
                        if (page == null) {
                            continuation = null
                            break
                        }
                        continuation = page.continuation
                        val seen = known() + (chipVideos ?: extra).map { it.id }
                        val fresh = page.videos.filter { it.id !in seen && it.id != videoId }.distinctBy { it.id }
                        if (chipVideos != null) chipVideos = chipVideos.orEmpty() + fresh else extra = extra + fresh
                        if (fresh.isNotEmpty()) break
                    }
                } finally {
                    isLoading = false
                }
            }
    }

    fun selectChip(index: Int) {
        val chip = chips.getOrNull(index) ?: return
        if (index == selectedChip && chipVideos == null) return
        job?.cancel()
        selectedChip = index
        val token = chip.token
        if (index == 0 || token == null) {
            chipVideos = null
            continuation = baseContinuation
            return
        }
        chipVideos = emptyList()
        continuation = token
        job =
            scope.launch {
                isLoading = true
                try {
                    val page = repository.relatedFeedPage(token) ?: return@launch
                    continuation = page.continuation
                    chipVideos = page.videos.filter { it.id != videoId }.distinctBy { it.id }
                } finally {
                    isLoading = false
                }
            }
    }

    val hasMore: Boolean get() = continuation != null

    private companion object {
        const val MAX_EMPTY_PAGES = 3
    }
}

/** Holds the feed of the video being watched; opening another video starts a fresh one. */
object RelatedFeeds {
    private var current: RelatedFeed? = null

    fun forVideo(videoId: String): RelatedFeed = current?.takeIf { it.videoId == videoId } ?: RelatedFeed(videoId).also { current = it }
}

@Composable
fun rememberRelatedFeed(videoId: String): RelatedFeed = remember(videoId) { RelatedFeeds.forVideo(videoId).also { it.start() } }

/** Pages the list in when the viewport gets within a few rows of its end. */
@Composable
fun LoadMoreRelatedOnScroll(
    listState: LazyListState,
    feed: RelatedFeed,
    base: List<Video>,
) {
    LaunchedEffect(listState, feed) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (info.totalItemsCount > 0 && last >= info.totalItemsCount - PREFETCH_ROWS) info.totalItemsCount else -1
        }.distinctUntilChanged()
            .filter { it >= 0 }
            .collect { feed.loadMore { base.mapTo(HashSet()) { v -> v.id } } }
    }
}

@Composable
fun RelatedFeedChips(
    feed: RelatedFeed,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(feed.chips, key = { index, chip -> "$index-${chip.title}" }) { index, chip ->
            val selected = index == feed.selectedChip
            // YouTube-style pill: solid fill, inverted when selected. Drawn by hand so the chip
            // keeps a fixed 32dp height and is never clipped by the row or the sticky header.
            Surface(
                onClick = { feed.selectChip(index) },
                shape = RoundedCornerShape(8.dp),
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                modifier = Modifier.height(32.dp),
            ) {
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = chip.title,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
            }
        }
    }
}

/** Chips pinned above the related videos, only when YouTube sent any. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.relatedFeedChipsHeader(feed: RelatedFeed) {
    if (feed.chips.size < 2) return
    stickyHeader(key = "related_feed_chips", contentType = "related_chips") {
        RelatedFeedChips(feed, Modifier.background(MaterialTheme.colorScheme.background))
    }
}

/** A small spinner under the list while the next page loads. */
fun LazyListScope.relatedFeedFooter(feed: RelatedFeed) {
    if (!feed.isLoading) return
    item(key = "related_feed_loading", contentType = "related_loading") {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.padding(0.dp), strokeWidth = 3.dp)
        }
    }
}

private const val PREFETCH_ROWS = 5
