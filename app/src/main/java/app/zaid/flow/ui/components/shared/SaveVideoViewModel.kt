package app.zaid.flow.ui.components.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zaid.flow.data.local.PlaylistRepository
import app.zaid.flow.data.model.PlaylistInfo
import app.zaid.flow.data.model.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SHARING_TIMEOUT_MS = 5_000L

/** Marks a sheet entry id as a YouTube-account playlist rather than a local one. */
const val YT_PREFIX = "yt:"

@HiltViewModel
class SaveVideoViewModel
    @Inject
    constructor(
        private val repository: PlaylistRepository,
    ) : ViewModel() {
        private val _savedIds = MutableStateFlow<Set<String>>(emptySet())
        val savedIds: StateFlow<Set<String>> = _savedIds.asStateFlow()

        private val _membershipLoaded = MutableStateFlow(false)
        val membershipLoaded: StateFlow<Boolean> = _membershipLoaded.asStateFlow()

        private var loadedVideoId: String? = null

        val playlists: StateFlow<List<PlaylistInfo>> =
            repository
                .getAllPlaylistsFlow()
                .map { all -> all.filter { it.id != PlaylistRepository.WATCH_LATER_ID && it.id != PlaylistRepository.SAVED_SHORTS_ID } }
                .distinctUntilChanged()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARING_TIMEOUT_MS),
                    emptyList(),
                )

        val watchLaterVideos: StateFlow<List<Video>> =
            repository
                .getWatchLaterVideosFlow()
                .distinctUntilChanged()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARING_TIMEOUT_MS),
                    emptyList(),
                )

        private val _youtubeOptions = MutableStateFlow<List<app.zaid.flow.account.AccountPlaylists.Option>>(emptyList())

        /** The signed-in account's own YouTube playlists for the current video; empty when signed out. */
        val youtubeOptions: StateFlow<List<app.zaid.flow.account.AccountPlaylists.Option>> = _youtubeOptions.asStateFlow()

        private val _message = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = _message.asStateFlow()

        fun messageShown() {
            _message.value = null
        }

        /** One request: the account's YouTube playlists and which already hold the video. */
        private fun loadYoutube(videoId: String) {
            if (!app.zaid.flow.innertube.YouTube.innerTube.isSignedIn) return
            viewModelScope.launch {
                app.zaid.flow.account.AccountPlaylists
                    .options(videoId)
                    .onSuccess { list ->
                        _youtubeOptions.value = list
                        _savedIds.update { ids -> ids + list.filter { it.containsVideo }.map { YT_PREFIX + it.id } }
                    }
            }
        }

        /** Adds or removes [videoId] on YouTube for an entry id carrying [YT_PREFIX]. */
        private fun toggleYoutube(
            videoId: String,
            entryId: String,
        ) {
            val playlistId = entryId.removePrefix(YT_PREFIX)
            val wasSaved = entryId in _savedIds.value
            _savedIds.update { if (wasSaved) it - entryId else it + entryId }
            viewModelScope.launch {
                val result =
                    if (wasSaved) {
                        app.zaid.flow.account.AccountPlaylists
                            .remove(playlistId, videoId)
                    } else {
                        app.zaid.flow.account.AccountPlaylists
                            .add(playlistId, videoId)
                    }
                if (result.isFailure) {
                    _savedIds.update { ids -> if (wasSaved) ids + entryId else ids - entryId }
                    _message.value = result.exceptionOrNull()?.message ?: "Couldn't update YouTube playlist"
                }
            }
        }

        fun loadMembership(videoId: String) {
            if (loadedVideoId == videoId) return
            loadedVideoId = videoId
            loadYoutube(videoId)
            _membershipLoaded.value = false
            viewModelScope.launch {
                val local = repository.getPlaylistIdsForVideo(videoId).toSet()
                _savedIds.update { ids -> local + ids.filter { it.startsWith(YT_PREFIX) } }
                _membershipLoaded.value = true
            }
        }

        fun toggle(
            video: Video,
            collectionId: String,
        ) {
            if (collectionId.startsWith(YT_PREFIX)) {
                toggleYoutube(video.id, collectionId)
                return
            }
            if (!_membershipLoaded.value) return
            val wasSaved = collectionId in _savedIds.value
            _savedIds.update { if (wasSaved) it - collectionId else it + collectionId }
            viewModelScope.launch {
                runCatching {
                    when {
                        collectionId == PlaylistRepository.WATCH_LATER_ID && wasSaved -> {
                            repository.removeFromWatchLater(video.id)
                        }

                        collectionId == PlaylistRepository.WATCH_LATER_ID -> {
                            repository.addToWatchLater(video)
                        }

                        wasSaved -> {
                            repository.removeVideoFromPlaylist(collectionId, video.id)
                        }

                        else -> {
                            repository.addVideoToPlaylist(collectionId, video)
                        }
                    }
                }.onFailure {
                    _savedIds.update { ids -> if (wasSaved) ids + collectionId else ids - collectionId }
                }
            }
        }

        fun createAndAdd(
            video: Video,
            name: String,
            description: String,
        ) {
            viewModelScope.launch {
                val playlistId = System.currentTimeMillis().toString()
                repository.createPlaylist(playlistId, name, description, true)
                repository.addVideoToPlaylist(playlistId, video)
                _savedIds.update { it + playlistId }
            }
        }
    }
