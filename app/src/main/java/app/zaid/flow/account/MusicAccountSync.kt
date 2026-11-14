package app.zaid.flow.account

import android.content.Context
import android.util.Log
import app.zaid.flow.data.local.ChannelSubscription
import app.zaid.flow.data.local.LikedVideoInfo
import app.zaid.flow.data.local.LikedVideosRepository
import app.zaid.flow.data.local.SubscriptionRepository
import app.zaid.flow.data.music.model.MusicArtist
import app.zaid.flow.data.music.model.MusicTrack
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.models.AlbumItem
import app.zaid.flow.innertube.models.ArtistItem
import app.zaid.flow.innertube.models.PlaylistItem
import app.zaid.flow.innertube.models.SongItem
import app.zaid.flow.innertube.models.YTItem
import app.zaid.flow.innertube.pages.LibraryPage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import app.zaid.flow.data.local.PlaylistRepository as LocalPlaylistRepository
import app.zaid.flow.data.music.PlaylistRepository as MusicPlaylistRepository

/**
 * Connects the Music tab to the signed-in Google account's YouTube Music library: liked songs, the
 * playlists and albums it saved, the artists it follows, and its listening history. Everything is
 * read from music.youtube.com as that account, so what the Music tab holds is what YouTube Music
 * holds.
 *
 * It only ever adds. Playlists, albums and artists the account holds are brought in once and then
 * belong to the device, so removing one in the app is not undone by the next refresh. Going the
 * other way, likes and plays made in the app already reach the account through
 * [YouTubeAccountReporter].
 *
 * Every entry point is a no-op when nobody is signed in, and a failing section is logged and skipped
 * without stopping the others.
 */
@Singleton
class MusicAccountSync
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val musicPlaylists: MusicPlaylistRepository,
        private val playlists: LocalPlaylistRepository,
        private val subscriptions: SubscriptionRepository,
        private val likedVideos: LikedVideosRepository,
    ) {
        private val lock = Mutex()
        private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

        @Volatile private var lastRunAt = 0L

        private val signedIn: Boolean
            get() = runCatching { YouTube.innerTube.isSignedIn }.getOrDefault(false)

        /** Runs [sync] unless it ran within [maxAgeMs]; the Music tab calls this each time it opens. */
        suspend fun refreshIfStale(maxAgeMs: Long = REFRESH_INTERVAL_MS) {
            if (System.currentTimeMillis() - lastRunAt < maxAgeMs) return
            sync()
        }

        suspend fun sync() =
            withContext(Dispatchers.IO) {
                if (!signedIn) return@withContext
                lock.withLock {
                    attempt("liked songs") { importLikedSongs() }
                    attempt("playlists") { importSaved(LIKED_PLAYLISTS, kind = "pl") }
                    attempt("albums") { importSaved(LIKED_ALBUMS, kind = "al") }
                    attempt("artists") { importArtists() }
                    attempt("history") { importHistory() }
                    lastRunAt = System.currentTimeMillis()
                }
            }

        private suspend fun attempt(
            what: String,
            block: suspend () -> Unit,
        ) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "YouTube Music $what import failed: ${error.message}")
            }
        }

        private suspend fun importLikedSongs() {
            val tracks = collect(LIKED_SONGS, MAX_SONG_PAGES).filterIsInstance<SongItem>().map { it.toTrack() }
            if (tracks.isEmpty()) return
            val added = musicPlaylists.importFavorites(tracks)
            // The like state is what lights the heart in the player, so it is set alongside the list.
            val now = System.currentTimeMillis()
            likedVideos.importLikes(
                tracks.mapIndexed { index, track ->
                    LikedVideoInfo(
                        videoId = track.videoId,
                        title = track.title,
                        thumbnail = track.thumbnailUrl,
                        channelName = track.artist,
                        likedAt = now - index * ORDER_STEP_MS,
                        isMusic = true,
                        channelId = track.channelId.takeIf(String::isNotBlank),
                        durationSeconds = track.duration,
                    )
                },
            )
            Log.i(TAG, "Imported $added liked songs from YouTube Music")
        }

        private suspend fun importSaved(
            browseId: String,
            kind: String,
        ) {
            var added = 0
            collect(browseId, MAX_LIST_PAGES).forEach { item ->
                val (id, title, thumbnail) =
                    when (item) {
                        is PlaylistItem -> Triple(item.id, item.title, item.thumbnail)
                        is AlbumItem -> Triple(item.browseId, item.title, item.thumbnail)
                        else -> return@forEach
                    }
                // "Liked Music" is the favorites list already, and episodes are not music.
                if (id in SKIPPED_PLAYLISTS || !firstTime("$kind:$id")) return@forEach
                playlists.saveExternalMusicPlaylist(id = id, name = title, description = "", thumbnailUrl = thumbnail.orEmpty())
                added++
            }
            Log.i(TAG, "Imported $added saved $kind from YouTube Music")
        }

        private suspend fun importArtists() {
            val artists =
                collect(FOLLOWED_ARTISTS, MAX_LIST_PAGES)
                    .filterIsInstance<ArtistItem>()
                    .filter { firstTime("ar:${it.id}") }
            subscriptions.subscribeAll(
                artists.map {
                    ChannelSubscription(
                        channelId = it.channelId?.takeIf(String::isNotBlank) ?: it.id,
                        channelName = it.title,
                        channelThumbnail = it.thumbnail.orEmpty(),
                        isMusic = true,
                    )
                },
            )
            Log.i(TAG, "Imported ${artists.size} followed artists from YouTube Music")
        }

        private suspend fun importHistory() {
            val sections =
                YouTube
                    .musicHistory()
                    .getOrThrow()
                    .sections
                    .orEmpty()
            val tracks = sections.flatMap { it.songs }.map { it.toTrack() }.distinctBy { it.videoId }
            if (tracks.isEmpty()) return
            musicPlaylists.importHistory(tracks)
            Log.i(TAG, "Imported ${tracks.size} plays from YouTube Music history")
        }

        private suspend fun collect(
            browseId: String,
            maxPages: Int,
        ): List<YTItem> {
            val items = mutableListOf<YTItem>()
            var page: LibraryPage = YouTube.library(browseId).getOrThrow()
            items += page.items
            var token = page.continuation
            var pages = 1
            while (token != null && pages < maxPages) {
                val next = YouTube.libraryContinuation(token).getOrNull() ?: break
                items += next.items
                token = next.continuation
                pages++
            }
            return items
        }

        /** True the first time [key] is seen, and remembered; what the user later removes stays removed. */
        private fun firstTime(key: String): Boolean {
            val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
            if (key in seen) return false
            prefs.edit().putStringSet(KEY_SEEN, seen + key).apply()
            return true
        }

        private fun SongItem.toTrack() =
            MusicTrack(
                videoId = id,
                title = title,
                artist = artists.firstOrNull()?.name ?: "Unknown Artist",
                thumbnailUrl = thumbnail,
                duration = duration ?: 0,
                album = album?.name.orEmpty(),
                channelId = artists.firstOrNull()?.id.orEmpty(),
                isExplicit = explicit,
                isVideoSong = isVideoSong,
                albumId = album?.id,
                artists = artists.map { MusicArtist(name = it.name, id = it.id) },
            )

        private companion object {
            const val TAG = "MusicAccountSync"
            const val PREFS = "music_account_sync"
            const val KEY_SEEN = "seen"
            const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000
            const val ORDER_STEP_MS = 60_000L
            const val MAX_SONG_PAGES = 12
            const val MAX_LIST_PAGES = 4
            const val LIKED_SONGS = "FEmusic_liked_videos"
            const val LIKED_PLAYLISTS = "FEmusic_liked_playlists"
            const val LIKED_ALBUMS = "FEmusic_liked_albums"
            const val FOLLOWED_ARTISTS = "FEmusic_library_corpus_artists"
            val SKIPPED_PLAYLISTS = setOf("LM", "SE", "RDPN", "LL")
        }
    }
