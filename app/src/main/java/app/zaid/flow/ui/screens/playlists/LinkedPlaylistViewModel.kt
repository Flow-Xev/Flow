package app.zaid.flow.ui.screens.playlists

import androidx.lifecycle.ViewModel
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.music.model.MusicTrack
import app.zaid.flow.data.newmusic.InnertubeMusicService
import app.zaid.flow.data.repository.RemotePlaylistPage
import app.zaid.flow.data.repository.YouTubePlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** A playlist a link plays from, positioned at the video the link names. */
internal data class LinkedQueue<T>(
    val items: List<T>,
    val startIndex: Int,
    val title: String?,
)

/**
 * Reads the playlist a `watch?v=…&list=…` link plays its video from. It loads nothing until asked,
 * so the route that plays the link can own one freely.
 */
@HiltViewModel
internal class LinkedPlaylistViewModel
    @Inject
    constructor(
        private val playlists: YouTubePlaylistRepository,
    ) : ViewModel() {
        /** Null when the playlist cannot be read or the video is not in it, so the video plays alone. */
        suspend fun videoQueue(
            playlistId: String,
            startVideoId: String,
        ): LinkedQueue<Video>? {
            playlists.cachedComplete(playlistId)?.let { cached ->
                return linkedQueue(cached.videos, startVideoId, cached.title, Video::id)
            }
            val first = playlists.firstPage(playlistId) ?: return null
            return findLinkedVideoQueue(first, startVideoId) { token -> playlists.nextPage(playlistId, token) }
        }

        suspend fun musicQueue(
            videoId: String,
            playlistId: String,
        ): LinkedQueue<MusicTrack>? =
            linkedQueue(InnertubeMusicService.fetchQueue(playlistId = playlistId), videoId, title = null, MusicTrack::videoId)
    }

internal fun <T> linkedQueue(
    items: List<T>,
    startId: String,
    title: String?,
    idOf: (T) -> String,
): LinkedQueue<T>? = items.indexOfFirst { idOf(it) == startId }.takeIf { it >= 0 }?.let { LinkedQueue(items, it, title) }

/**
 * Walks the playlist a page at a time until the linked video turns up, so a link deep into a long
 * playlist still finds it, and stops after [maxPages] rather than reading thousands of videos
 * before anything plays.
 */
internal suspend fun findLinkedVideoQueue(
    first: RemotePlaylistPage,
    startVideoId: String,
    maxPages: Int = LINKED_PLAYLIST_MAX_PAGES,
    nextPage: suspend (continuation: String) -> Pair<List<Video>, String?>?,
): LinkedQueue<Video>? {
    var videos = first.videos
    var continuation = first.continuation
    var pages = 1
    while (true) {
        linkedQueue(videos, startVideoId, first.title, Video::id)?.let { return it }
        val token = continuation?.takeIf { pages < maxPages } ?: return null
        val (more, next) = nextPage(token) ?: return null
        if (more.isEmpty()) return null
        videos = videos + more
        continuation = next
        pages++
    }
}

private const val LINKED_PLAYLIST_MAX_PAGES = 10
