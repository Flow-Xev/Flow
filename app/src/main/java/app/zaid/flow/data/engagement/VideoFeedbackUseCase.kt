package app.zaid.flow.data.engagement

import android.content.Context
import app.zaid.flow.data.local.PlaylistRepository
import app.zaid.flow.data.local.ViewHistory
import app.zaid.flow.data.local.WatchLaterCleanup
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.recommendation.FlowNeuroEngine
import app.zaid.flow.data.recommendation.InteractionType
import app.zaid.flow.data.repository.YouTubeRepository
import app.zaid.flow.data.stats.LedgerAction
import app.zaid.flow.data.stats.VideoStatsRecorder
import app.zaid.flow.utils.ThumbnailUrlResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** A channel a block applied to, named as the viewer knows it. */
data class BlockedChannel(
    val channelId: String,
    val channelName: String,
    /** Reverses the "Don't recommend channel" sent to the YouTube account, when one was sent and can be undone. */
    val youtubeUndoToken: String? = null,
)

/**
 * What a video's menu tells the recommendation engine and the library: Watch later, not interested,
 * more like this, watched, and hiding a channel. Each call does the writes and the learning signal
 * that belong together, and says nothing to the viewer; the caller decides how to confirm it.
 */
class VideoFeedbackUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: YouTubeRepository,
        private val playlistRepository: PlaylistRepository,
        private val viewHistory: ViewHistory,
        private val videoStats: VideoStatsRecorder,
        private val watchLaterCleanup: WatchLaterCleanup,
    ) {
        /** Adds [video] to Watch later, or takes it out; returns whether it is saved afterwards. */
        suspend fun toggleWatchLater(video: Video): Boolean {
            if (playlistRepository.isInWatchLater(video.id)) {
                playlistRepository.removeFromWatchLater(video.id)
                return false
            }
            playlistRepository.addToWatchLater(video)
            videoStats.onAction(LedgerAction.SAVE)
            runCatching { FlowNeuroEngine.onVideoInteraction(context, video, InteractionType.SAVED) }
            return true
        }

        suspend fun setWatchLater(
            video: Video,
            saved: Boolean,
        ) {
            if (saved) playlistRepository.addToWatchLater(video) else playlistRepository.removeFromWatchLater(video.id)
        }

        /**
         * Hides [video]'s channel from every feed. A live or premiere entry can carry a handle instead
         * of a channel id, so that case asks the watch page for the real id first.
         */
        suspend fun blockChannel(video: Video): BlockedChannel {
            val metadata = if (video.channelId.startsWith("UC")) null else repository.getLiveWatchMetadata(video.id)
            val channelId = metadata?.channelId.orEmpty().ifBlank { video.channelId }
            check(channelId.isNotBlank()) { "No channel id for ${video.id}" }
            FlowNeuroEngine.blockChannel(context, channelId)
            videoStats.onAction(LedgerAction.BLOCK_CHANNEL)
            FeedInvalidationBus.emit(FeedInvalidationBus.Event.ChannelBlocked(channelId, video.id))
            // Also tell YouTube, so the signed-in account stops recommending the channel everywhere.
            val youtubeUndo =
                if (app.zaid.flow.innertube.YouTube.innerTube.hasAccount) {
                    app.zaid.flow.account.AccountActions
                        .dontRecommendChannel(video.id)
                        .getOrNull()
                        ?.undoToken
                } else {
                    null
                }
            return BlockedChannel(channelId, metadata?.channelName.orEmpty().ifBlank { video.channelName }, youtubeUndo)
        }

        suspend fun unblockChannel(
            channelId: String,
            youtubeUndoToken: String? = null,
        ) {
            FlowNeuroEngine.unblockChannel(context, channelId)
            youtubeUndoToken?.let {
                app.zaid.flow.account.AccountActions
                    .undoFeedback(it)
            }
        }

        suspend fun markNotInterested(video: Video) {
            FlowNeuroEngine.markNotInterested(context, video)
            app.zaid.flow.account.YouTubeAccountReporter
                .reportNotInterested(video.id)
            videoStats.onAction(LedgerAction.NOT_INTERESTED)
            FeedInvalidationBus.emit(FeedInvalidationBus.Event.NotInterested(video.id, video.channelId))
        }

        suspend fun markInterested(video: Video) {
            FlowNeuroEngine.onVideoInteraction(context, video, InteractionType.LIKED, percentWatched = 0f)
        }

        /** Records [video] as watched to the end, so every card shows it full and the engine learns from it. */
        suspend fun markWatched(video: Video) {
            FlowNeuroEngine.onVideoInteraction(context, video, InteractionType.WATCHED, percentWatched = 1.0f)
            val durationMs = if (video.duration > 0) video.duration * 1000L else 1000L
            viewHistory.savePlaybackPosition(
                videoId = video.id,
                position = durationMs,
                duration = durationMs,
                title = video.title,
                thumbnailUrl = video.thumbnailUrl.ifEmpty { ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(video.id) },
                channelName = video.channelName,
                channelId = video.channelId,
                isMusic = false,
                isShort = video.isShort,
            )
            FeedInvalidationBus.emit(FeedInvalidationBus.Event.MarkedWatched(video.id))
            watchLaterCleanup.onFinished(video.id)
        }

        /**
         * Clears the progress that makes [video] read as watched. The history row stays, and the
         * recommendation signal from the earlier watched interaction is left as it was.
         */
        suspend fun markUnwatched(video: Video) {
            viewHistory.clearProgress(video.id)
        }
    }
