package app.zaid.flow.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zaid.flow.data.local.LikedVideosRepository
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.PlaylistRepository
import app.zaid.flow.data.local.ViewHistory
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.model.toVideo
import app.zaid.flow.data.notes.NotesRepository
import app.zaid.flow.data.playlist.sortedFor
import app.zaid.flow.data.shorts.ShortsContentFilter
import app.zaid.flow.data.shorts.queue.ShortsQueueHandoff
import app.zaid.flow.data.shorts.queue.ShortsQueueSource
import app.zaid.flow.data.stats.RecapPeriod
import app.zaid.flow.data.stats.RecapReadiness
import app.zaid.flow.data.video.VideoDownloadManager
import app.zaid.flow.ui.components.library.LIBRARY_SHELF_ITEM_LIMIT
import app.zaid.flow.ui.components.library.LibraryMediaItem
import app.zaid.flow.ui.components.library.toHistoryShelfItems
import app.zaid.flow.ui.components.library.toLibraryMediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.zaid.flow.data.music.DownloadManager as MusicDownloadManager

internal data class LibraryCounts(
    val history: Int,
    val videoPlaylists: Int,
    val musicPlaylists: Int,
    val watchLater: Int,
    val likedVideos: Int,
    val likedMusic: Int,
    val downloadedVideos: Int,
    val downloadedTracks: Int,
    val savedShorts: Int,
) {
    val isEmpty: Boolean
        get() =
            history == 0 &&
                videoPlaylists == 0 &&
                musicPlaylists == 0 &&
                watchLater == 0 &&
                likedVideos == 0 &&
                likedMusic == 0 &&
                downloadedVideos == 0 &&
                downloadedTracks == 0 &&
                savedShorts == 0
}

@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        playlistRepository: PlaylistRepository,
        likedVideosRepository: LikedVideosRepository,
        viewHistory: ViewHistory,
        videoDownloadManager: VideoDownloadManager,
        musicDownloadManager: MusicDownloadManager,
        shortsContentFilter: ShortsContentFilter,
        playerPreferences: PlayerPreferences,
        private val recapReadiness: RecapReadiness,
        notesRepository: NotesRepository,
        private val shortsQueueHandoff: ShortsQueueHandoff,
        private val accountManager: app.zaid.flow.account.AccountManager,
    ) : ViewModel() {
        /** The signed-in account shown at the top of Library; null when nobody is signed in. */
        val activeAccount: StateFlow<app.zaid.flow.account.AccountProfile?> = accountManager.activeAccount
        val accounts: StateFlow<List<app.zaid.flow.account.AccountProfile>> = accountManager.accounts
        val incognito: StateFlow<Boolean> = accountManager.incognito

        /** The Shorts player queue for a Shorts box tapped in the history shelf, opening at [tapped]. */
        fun historyShortsSource(
            shelf: List<Video>,
            tapped: Video,
        ): ShortsQueueSource = shortsQueueHandoff.sourceForShelf(shelf, tapped)

        fun switchAccount(id: String) = accountManager.switchTo(id)

        fun setIncognito(enabled: Boolean) = accountManager.setIncognito(enabled)

        private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L)

        /** Null while notes are turned off in Content settings, which hides the Notes row. */
        internal val notesCount: StateFlow<Int?> =
            combine(playerPreferences.notesEnabled, notesRepository.observeCount()) { enabled, count -> count.takeIf { enabled } }
                .distinctUntilChanged()
                .stateIn(viewModelScope, sharing, null)

        private val _recapReady = MutableStateFlow<RecapPeriod?>(null)

        /** A month or year that just closed with a recap waiting; checked once each time Library is created. */
        val recapReady: StateFlow<RecapPeriod?> = _recapReady.asStateFlow()

        private val _recapDue = MutableStateFlow(false)

        /** Whether the Recap card is showing: it comes back a day after it was last opened or dismissed. */
        val recapDue: StateFlow<Boolean> = _recapDue.asStateFlow()

        init {
            viewModelScope.launch { _recapReady.value = runCatching { recapReadiness.readyPeriod() }.getOrNull() }
            viewModelScope.launch { _recapDue.value = runCatching { recapReadiness.isDue() }.getOrDefault(true) }
        }

        /** The recap was opened or dismissed: the card hides for a day, and a waiting month is not offered again. */
        fun onRecapHandled() {
            _recapDue.value = false
            viewModelScope.launch { recapReadiness.markSeen() }
            val period = _recapReady.value ?: return
            _recapReady.value = null
            viewModelScope.launch { recapReadiness.markShown(period) }
        }

        private fun <T> Flow<T>.shared(): StateFlow<T?> {
            val upstream: Flow<T?> = this
            return upstream
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, sharing, null)
        }

        private val allLikes = likedVideosRepository.getAllLikedVideos().shared()
        private val playlistOrder = playerPreferences.playlistListOrder
        private val allVideoPlaylists =
            combine(playlistRepository.getAllPlaylistsFlow(), playlistOrder) { playlists, order -> playlists.sortedFor(order) }
                .shared()
        private val allMusicPlaylists =
            combine(playlistRepository.getMusicPlaylistsFlow(), playlistOrder) { playlists, order -> playlists.sortedFor(order) }
                .shared()
        private val allWatchLater = playlistRepository.getVideoOnlyWatchLaterFlow().shared()
        private val allSavedShorts = playlistRepository.getVideoOnlySavedShortsFlow().shared()

        private val allDownloads =
            combine(
                videoDownloadManager.downloadedVideos,
                musicDownloadManager.downloadedTracks,
            ) { videos, tracks -> videos to tracks }.shared()

        internal val history =
            combine(
                viewHistory.getRecentLibraryHistory(LIBRARY_SHELF_ITEM_LIMIT),
                shortsContentFilter.enabled,
            ) { entries, shortsOn ->
                // Shorts sit in the shelf as grouped boxes, and drop out when Shorts are switched off.
                entries.filter { shortsOn || !it.isShort }.toHistoryShelfItems()
            }.shared()

        internal val likedVideos =
            allLikes
                .map { liked ->
                    liked
                        ?.asSequence()
                        ?.filterNot { it.isMusic }
                        ?.take(LIBRARY_SHELF_ITEM_LIMIT)
                        ?.map { it.toVideo() }
                        ?.toList()
                }.shared()

        internal val likedMusic =
            allLikes
                .map { liked ->
                    liked
                        ?.asSequence()
                        ?.filter { it.isMusic }
                        ?.take(LIBRARY_SHELF_ITEM_LIMIT)
                        ?.map { it.toLibraryMediaItem() }
                        ?.toList()
                }.shared()

        internal val playlists = allVideoPlaylists.map { it?.take(LIBRARY_SHELF_ITEM_LIMIT) }.shared()

        internal val musicPlaylists = allMusicPlaylists.map { it?.take(LIBRARY_SHELF_ITEM_LIMIT) }.shared()

        internal val watchLater = allWatchLater.map { it?.take(LIBRARY_SHELF_ITEM_LIMIT) }.shared()

        internal val savedShorts = allSavedShorts.map { it?.take(LIBRARY_SHELF_ITEM_LIMIT) }.shared()

        internal val downloads =
            allDownloads
                .map { downloaded ->
                    downloaded?.let { (videos, tracks) ->
                        buildList<LibraryMediaItem> {
                            videos.forEach { add(LibraryMediaItem.DownloadedVideoItem(it)) }
                            tracks.forEach { add(LibraryMediaItem.DownloadedMusicItem(it)) }
                        }.sortedByDescending { item -> item.downloadedAt }
                            .take(LIBRARY_SHELF_ITEM_LIMIT)
                    }
                }.shared()

        internal val shortsEnabled =
            shortsContentFilter.enabled
                .stateIn(viewModelScope, sharing, true)

        internal val separatePlaylistKinds =
            playerPreferences.separatePlaylistKinds
                .stateIn(viewModelScope, sharing, false)

        internal val shelfPreviewsEnabled =
            playerPreferences.libraryShelfPreviewsEnabled
                .distinctUntilChanged()
                .stateIn(viewModelScope, sharing, true)

        /**
         * Counts come off the same shared sources the shelves read, so the compact layout costs one
         * extra `COUNT(*)` on watch history and nothing else.
         */
        internal val counts: StateFlow<LibraryCounts?> =
            combine(
                viewHistory.getLibraryHistoryCount(),
                combine(allVideoPlaylists, allMusicPlaylists) { video, music ->
                    if (video == null || music == null) null else video.size to music.size
                },
                combine(allWatchLater, allSavedShorts) { later, shorts ->
                    if (later == null || shorts == null) null else later.size to shorts.size
                },
                allLikes,
                allDownloads,
            ) { historyCount, playlistCount, saved, liked, downloaded ->
                if (playlistCount == null || saved == null || liked == null || downloaded == null) {
                    null
                } else {
                    LibraryCounts(
                        history = historyCount,
                        videoPlaylists = playlistCount.first,
                        musicPlaylists = playlistCount.second,
                        watchLater = saved.first,
                        likedVideos = liked.count { !it.isMusic },
                        likedMusic = liked.count { it.isMusic },
                        downloadedVideos = downloaded.first.size,
                        downloadedTracks = downloaded.second.size,
                        savedShorts = saved.second,
                    )
                }
            }.distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, sharing, null)

        /** Queued, running and paused downloads; null until Room first answers. */
        internal val activeDownloadCount: StateFlow<Int?> =
            videoDownloadManager.activeDownloads
                .map { it.size }
                .distinctUntilChanged()
                .flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, sharing, null)

        internal val isLibraryEmpty =
            combine(counts, activeDownloadCount, ::libraryIsEmpty)
                .distinctUntilChanged()
                .stateIn(viewModelScope, sharing, false)
    }

/**
 * Empty only once both counts are known: a download still in progress is library content too, and
 * deciding before Room answers would flash the empty state.
 */
internal fun libraryIsEmpty(
    counts: LibraryCounts?,
    activeDownloads: Int?,
): Boolean = counts != null && activeDownloads == 0 && counts.isEmpty

private val LibraryMediaItem.downloadedAt: Long
    get() =
        when (this) {
            is LibraryMediaItem.DownloadedVideoItem -> download.downloadedAt
            is LibraryMediaItem.DownloadedMusicItem -> download.downloadedAt
            else -> 0L
        }
