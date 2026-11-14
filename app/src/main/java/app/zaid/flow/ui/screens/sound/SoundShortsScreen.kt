package app.zaid.flow.ui.screens.sound

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zaid.flow.R
import app.zaid.flow.data.model.ShortVideo
import app.zaid.flow.data.model.toShortVideo
import app.zaid.flow.data.shorts.queue.ShortsQueueHandoff
import app.zaid.flow.data.shorts.queue.ShortsQueuePage
import app.zaid.flow.data.shorts.queue.ShortsQueueSource
import app.zaid.flow.data.shorts.queue.SnapshotContinuation
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.pages.reel.SoundContinuation
import app.zaid.flow.innertube.pages.reel.SoundTrace
import app.zaid.flow.utils.formatViewCount
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import javax.inject.Inject

data class SoundShortsState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val shorts: List<ShortVideo> = emptyList(),
    /** The total YouTube states for the whole sound; null when the page states none. */
    val total: Long? = null,
    /** The sound's own name and cover when YouTube gave them; the screen falls back to the link's title and the first short. */
    val title: String? = null,
    val coverUrl: String? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val debug: String = "",
) {
    /** YouTube's total, never below what is already on screen. */
    val count: Long get() = maxOf(total ?: 0L, shorts.size.toLong())

    /** Only a lower bound is known: no stated total and more pages still to come. */
    val countIsLowerBound: Boolean get() = total == null && hasMore
}

@HiltViewModel
class SoundShortsViewModel
    @Inject
    constructor(
        private val handoff: ShortsQueueHandoff,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SoundShortsState())
        val state: StateFlow<SoundShortsState> = _state
        private var loadedKey: String? = null
        private var lastArgs: Triple<String, String, String>? = null

        // Paging state. Everything below is only touched while holding [pageLock].
        private val pageLock = Mutex()
        private var next: SoundContinuation? = null
        private val seen = HashSet<String>()
        private val usedTokens = HashSet<String>()

        fun load(
            browseId: String,
            params: String,
            videoId: String,
        ) {
            val key = "$browseId|$params|$videoId"
            if (key == loadedKey) return
            loadedKey = key
            lastArgs = Triple(browseId, params, videoId)
            _state.value = SoundShortsState()
            viewModelScope.launch {
                // One call resolves the sound: every route YouTube offers is tried together and the first usable
                // page wins. Only that first page is awaited; the grid fills as the person scrolls ([loadMore]).
                val trace = SoundTrace()
                val first =
                    withContext(Dispatchers.IO) { YouTube.soundFeed(videoId, browseId, params, trace) }.getOrNull()
                val log = trace.toString()
                android.util.Log.w("SoundShorts", "video=$videoId link=${browseId.ifBlank { "none" }}\n$log")
                if (first == null) {
                    _state.value = SoundShortsState(loading = false, failed = true, debug = log)
                    return@launch
                }
                pageLock.withLock {
                    seen.clear()
                    usedTokens.clear()
                    seen.addAll(first.reels.map { it.id })
                    first.next?.let { usedTokens.add(it.token) }
                    next = first.next
                }
                _state.value =
                    SoundShortsState(
                        loading = false,
                        shorts = first.reels.map { it.toShortVideo() },
                        total = first.total,
                        title = first.title,
                        coverUrl = first.coverUrl,
                        hasMore = first.next != null,
                        debug = log,
                    )
            }
        }

        /** Tries the whole load again after a failure; nothing failed is ever cached, so this goes back to YouTube. */
        fun retry() {
            val args = lastArgs ?: return
            loadedKey = null
            load(args.first, args.second, args.third)
        }

        /** Called by the grid when the person nears the end of what is loaded. */
        fun loadMore() {
            val s = _state.value
            if (s.loading || !s.hasMore || s.loadingMore || s.moreFailed) return
            _state.update { it.copy(loadingMore = true) }
            viewModelScope.launch { fetchMore() }
        }

        fun retryMore() {
            _state.update { it.copy(moreFailed = false) }
            loadMore()
        }

        /**
         * Appends the next page that has anything new. Serialised so the grid and the Shorts player (which
         * keeps pulling from this same list) never fetch the same page twice or skip one. Returns false when
         * the request failed, leaving the page to be retried.
         */
        private suspend fun fetchMore(): Boolean =
            pageLock.withLock {
                var cont: SoundContinuation =
                    next?.takeIf { _state.value.hasMore } ?: run {
                        _state.update { it.copy(loadingMore = false, hasMore = false) }
                        return@withLock true
                    }
                _state.update { it.copy(loadingMore = true, moreFailed = false) }
                var emptyPages = 0
                var succeeded: Boolean? = null
                while (succeeded == null) {
                    val request = cont
                    val page = withContext(Dispatchers.IO) { YouTube.soundMore(request) }.getOrNull()
                    if (page == null) {
                        android.util.Log.w("SoundShorts", "next page failed")
                        _state.update { it.copy(loadingMore = false, moreFailed = true) }
                        succeeded = false
                        continue
                    }
                    val fresh = page.reels.filter { seen.add(it.id) }.map { it.toShortVideo() }
                    // A repeated token would loop forever on the same page.
                    val following = page.next?.takeIf { usedTokens.add(it.token) }
                    next = following
                    if (fresh.isNotEmpty() || following == null || ++emptyPages >= MAX_EMPTY_PAGES) {
                        if (fresh.isEmpty()) next = null
                        val hasMore = next != null
                        _state.update { it.copy(shorts = it.shorts + fresh, hasMore = hasMore, loadingMore = false) }
                        succeeded = true
                    } else {
                        cont = following
                    }
                }
                succeeded == true
            }

        fun sourceFor(startId: String): ShortsQueueSource {
            val snapshot = _state.value
            var delivered = snapshot.shorts.size
            // The player keeps pulling from this list, so a sound with more Shorts than are loaded does not
            // end the queue at whatever the grid happened to have.
            val more =
                if (snapshot.hasMore) {
                    SnapshotContinuation {
                        var fresh = _state.value.shorts.drop(delivered)
                        if (fresh.isEmpty() && _state.value.hasMore) {
                            fetchMore()
                            fresh = _state.value.shorts.drop(delivered)
                        }
                        delivered += fresh.size
                        val hasMore = _state.value.hasMore
                        ShortsQueuePage(fresh, cursor = if (hasMore) "more" else null, exhausted = !hasMore)
                    }
                } else {
                    null
                }
            return ShortsQueueSource.Snapshot(handoff.offer(snapshot.shorts, more), startId)
        }

        private companion object {
            const val MAX_EMPTY_PAGES = 3
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundShortsScreen(
    browseId: String,
    params: String,
    title: String,
    videoId: String = "",
    onBack: () -> Unit,
    onOpen: (ShortsQueueSource) -> Unit,
    viewModel: SoundShortsViewModel = hiltViewModel(),
) {
    LaunchedEffect(browseId, params, videoId) { viewModel.load(browseId, params, videoId) }
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    val soundTitle = title.ifBlank { state.title.orEmpty() }.ifBlank { stringResource(R.string.sound_original) }
    // Ask for the next page a few rows before the end. Keyed on the item count too, so a short page that
    // leaves the grid still near its end keeps loading until the screen is full.
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.distinctUntilChanged()
            .filter { (last, total) -> total > 0 && last >= total - PREFETCH_ITEMS }
            .collect { viewModel.loadMore() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(soundTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.failed -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.sound_load_failed), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                    }
                }

                state.shorts.isEmpty() -> {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sound_empty))
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SoundHeader(
                                title = soundTitle,
                                coverUrl = state.coverUrl ?: state.shorts.first().thumbnailUrl,
                                count = state.count,
                                countIsLowerBound = state.countIsLowerBound,
                                onPlayAll = { onOpen(viewModel.sourceFor(state.shorts.first().id)) },
                            )
                        }
                        items(state.shorts, key = { it.id }) { short ->
                            Box(
                                Modifier
                                    .aspectRatio(9f / 16f)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable { onOpen(viewModel.sourceFor(short.id)) },
                            ) {
                                AsyncImage(
                                    model = short.thumbnailUrl,
                                    contentDescription = short.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (short.viewCount > 0) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        shape = MaterialTheme.shapes.extraSmall,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(14.dp))
                                            Text(formatViewCount(short.viewCount), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                        if (state.hasMore || state.loadingMore) {
                            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (state.moreFailed) {
                                        TextButton(onClick = viewModel::retryMore) { Text(stringResource(R.string.retry)) }
                                    } else {
                                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val PREFETCH_ITEMS = 9

private fun formatSoundCount(count: Long): String =
    if (count < 10_000L) NumberFormat.getInstance().format(count) else formatViewCount(count)

@Composable
private fun SoundHeader(
    title: String,
    coverUrl: String,
    count: Long,
    countIsLowerBound: Boolean,
    onPlayAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(88.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    countIsLowerBound -> stringResource(R.string.sound_shorts_count_more, formatSoundCount(count))
                    count == 1L -> stringResource(R.string.sound_only_one)
                    count < 1_000L -> pluralStringResource(R.plurals.sound_shorts_count, count.toInt(), count.toInt())
                    else -> stringResource(R.string.sound_shorts_total, formatSoundCount(count))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPlayAll, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.sound_play_all), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
