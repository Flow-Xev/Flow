package app.zaid.flow.data.subscriptions

import app.zaid.flow.data.local.AppDatabase
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.ViewHistory
import app.zaid.flow.data.shorts.finishedShortIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionWatchedVideos
    @Inject
    constructor(
        private val viewHistory: ViewHistory,
        private val playerPreferences: PlayerPreferences,
        private val database: AppDatabase,
    ) {
        val ids: Flow<Set<String>> =
            combine(
                viewHistory.getVideoWatchProgress(),
                playerPreferences.hideWatchedVideosFromSubscriptions,
                playerPreferences.watchedThreshold,
                database.downloadDao().getVideoDownloads(),
            ) { history, hideWatched, threshold, downloads ->
                if (!hideWatched) return@combine emptySet<String>()
                val downloadedIds = downloads.mapTo(HashSet()) { it.download.videoId }
                history
                    .asSequence()
                    .filter { threshold.isWatched(it.position, it.duration) || it.videoId in downloadedIds }
                    .map { it.videoId }
                    .toHashSet()
            }.distinctUntilChanged()

        /** Watched reels, for the Shorts shelf and the Shorts queues; governed by the Shorts setting alone. */
        val shortIds: Flow<Set<String>> =
            combine(
                viewHistory.getVideoWatchProgress(),
                playerPreferences.hideWatchedShorts,
            ) { history, hideWatched ->
                if (hideWatched) history.finishedShortIds() else emptySet()
            }.distinctUntilChanged()
    }
