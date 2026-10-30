package app.zaid.flow.account

import android.util.Log
import app.zaid.flow.data.account.AccountFeedRepository
import app.zaid.flow.data.account.AccountPage
import app.zaid.flow.data.local.LikedVideoInfo
import app.zaid.flow.data.local.LikedVideosRepository
import app.zaid.flow.data.local.VideoHistoryEntry
import app.zaid.flow.data.local.ViewHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls the account's watch history and liked videos into the on-device stores after sign-in, so
 * the history screen and likes start from what the account has watched. Imported rows are stubs:
 * they never overwrite real playback progress already on the device. The on-device recommendation
 * engine is deliberately not fed from them: while signed in the account does the recommending.
 *
 * Going the other way, likes, dislikes and watches made in the app are sent to the account by
 * [YouTubeAccountReporter].
 */
@Singleton
class AccountSync
    @Inject
    constructor(
        private val accountFeed: AccountFeedRepository,
        private val viewHistory: ViewHistory,
        private val likedVideos: LikedVideosRepository,
        private val musicSync: MusicAccountSync,
    ) {
        suspend fun syncOnLogin() =
            withContext(Dispatchers.IO) {
                if (!accountFeed.isActive) return@withContext
                attempt("history") { importHistory() }
                attempt("liked videos") { importLikes() }
                // The music library is read from music.youtube.com with a web session; the TV client has no such page.
                if (!accountFeed.isTv) attempt("music library") { musicSync.sync() }
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
                Log.w(TAG, "Account $what import failed: ${error.message}")
            }
        }

        private suspend fun importHistory() {
            val now = System.currentTimeMillis()
            val percents = HashMap<String, Int>()
            val entries =
                collect(accountFeed::history, percents).mapIndexed { index, video ->
                    // Where the page showed a progress bar the video resumes there; anything without one
                    // was watched through, so it is saved as finished (a bare "watched" mark when the
                    // page gave no length).
                    val lengthMs = video.duration.toLong().coerceAtLeast(0L) * 1000L
                    val percent = percents[video.id]
                    val positionMs =
                        if (percent == null || percent >= FINISHED_PERCENT) lengthMs else lengthMs * percent / 100L
                    VideoHistoryEntry(
                        videoId = video.id,
                        position = positionMs,
                        duration = lengthMs,
                        // The page lists newest first and carries day headings, not times, so the
                        // order is kept by stepping back a minute per row.
                        timestamp = now - index * ORDER_STEP_MS,
                        title = video.title,
                        thumbnailUrl = video.thumbnailUrl,
                        channelName = video.channelName,
                        channelId = video.channelId,
                        isShort = video.isShort,
                    )
                }
            viewHistory.bulkSaveHistoryEntries(entries)
            // History imported by an earlier version was saved without progress; mark it too.
            viewHistory.markImportedAsWatched()
            Log.i(TAG, "Imported ${entries.size} history entries from the account")
        }

        private suspend fun importLikes() {
            val now = System.currentTimeMillis()
            val likes =
                collect(accountFeed::likedVideos).mapIndexed { index, video ->
                    LikedVideoInfo(
                        videoId = video.id,
                        title = video.title,
                        thumbnail = video.thumbnailUrl,
                        channelName = video.channelName,
                        likedAt = now - index * ORDER_STEP_MS,
                        channelId = video.channelId.ifBlank { null },
                        durationSeconds = video.duration,
                    )
                }
            val added = likedVideos.importLikes(likes)
            Log.i(TAG, "Imported ${added.size} liked videos from the account")
        }

        private suspend fun collect(
            page: suspend (String?) -> AccountPage?,
            percents: MutableMap<String, Int>? = null,
        ) = buildList {
                var token: String? = null
                var pages = 0
                do {
                    val result = page(token) ?: break
                    percents?.putAll(result.watchedPercent)
                    addAll(result.videos)
                    token = result.continuation
                    pages++
                } while (token != null && pages < MAX_PAGES)
            }.distinctBy { it.id }

        private companion object {
            const val TAG = "AccountSync"
            const val MAX_PAGES = 200
            const val FINISHED_PERCENT = 95
            const val ORDER_STEP_MS = 60_000L
        }
    }
