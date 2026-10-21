package app.zaid.flow.ui.screens.shorts

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zaid.flow.R
import app.zaid.flow.data.comments.CommentsPager
import app.zaid.flow.data.engagement.FeedInvalidationBus
import app.zaid.flow.data.engagement.VideoEngagementUseCase
import app.zaid.flow.data.feed.FeedPrefetchQueue
import app.zaid.flow.data.local.DataSaver
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.PlaylistRepository
import app.zaid.flow.data.model.Comment
import app.zaid.flow.data.model.ShortLinkedVideo
import app.zaid.flow.data.model.ShortVideo
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.model.toVideo
import app.zaid.flow.data.recommendation.FlowNeuroEngine
import app.zaid.flow.data.recommendation.InteractionType
import app.zaid.flow.data.repository.YouTubeRepository
import app.zaid.flow.data.shorts.ShortAudioTrack
import app.zaid.flow.data.shorts.ShortDetails
import app.zaid.flow.data.shorts.ShortDetailsResolver
import app.zaid.flow.data.shorts.ShortPlaybackStreams
import app.zaid.flow.data.shorts.ShortVideoQuality
import app.zaid.flow.data.shorts.ShortWatchClassifier
import app.zaid.flow.data.shorts.ShortsFeedRepository
import app.zaid.flow.data.shorts.ShortsMetadataRepository
import app.zaid.flow.data.shorts.ShortsStreamResolver
import app.zaid.flow.data.shorts.ShortsWatchHistory
import app.zaid.flow.data.shorts.queue.ShortsQueueChange
import app.zaid.flow.data.shorts.queue.ShortsQueueController
import app.zaid.flow.data.shorts.queue.ShortsQueueLoaderFactory
import app.zaid.flow.data.shorts.queue.ShortsQueueSource
import app.zaid.flow.data.shorts.queue.openAtVideoId
import app.zaid.flow.data.stats.LedgerAction
import app.zaid.flow.data.stats.VideoStatsRecorder
import app.zaid.flow.data.stats.ViewEvent
import app.zaid.flow.data.stats.ViewFormat
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.innertube.pages.VideoCommentSort
import app.zaid.flow.innertube.pages.reel.ReelOverlay
import app.zaid.flow.player.stream.StreamSizeEstimator
import app.zaid.flow.utils.PerformanceDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShortsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: YouTubeRepository,
        private val feed: ShortsFeedRepository,
        private val streams: ShortsStreamResolver,
        private val metadata: ShortsMetadataRepository,
        private val engagement: VideoEngagementUseCase,
        private val playlistRepository: PlaylistRepository,
        private val watchHistory: ShortsWatchHistory,
        private val queueFactory: ShortsQueueLoaderFactory,
        private val playerPreferences: PlayerPreferences,
        private val videoStats: VideoStatsRecorder,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ShortsUiState())
        val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()

        private var queue: ShortsQueueController? = null
        private var loadedSource: ShortsQueueSource? = null
        private var loadJob: Job? = null

        private val prefetch =
            FeedPrefetchQueue(
                prefetchAheadItemCount = PREFETCH_AHEAD_REELS,
                triggerRemainingItems = PREFETCH_TRIGGER_REMAINING,
            )

        private val comments =
            CommentsPager(
                repository = repository,
                scope = viewModelScope,
                fetchTimeoutMs = COMMENTS_FETCH_TIMEOUT_MS,
            )

        val commentsState: StateFlow<List<Comment>> = comments.comments
        val isLoadingComments: StateFlow<Boolean> = comments.isLoading
        val commentSortOptions: StateFlow<List<VideoCommentSort>> = comments.sortOptions
        val commentTotalText: StateFlow<String?> = comments.totalText

        private val savedShortIds = MutableStateFlow<Set<String>>(emptySet())

        private val _snackbarMessage = MutableStateFlow<String?>(null)
        val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

        fun clearSnackbar() {
            _snackbarMessage.value = null
        }

        init {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playlistRepository.getSavedShortsFlow().collect { savedVideos ->
                    savedShortIds.value = savedVideos.map { it.id }.toSet()
                }
            }
            viewModelScope.launch {
                FeedInvalidationBus.events.collect { event ->
                    if (event is FeedInvalidationBus.Event.ChannelBlocked) dropChannel(event.channelId)
                }
            }
        }

        /** Mirrors the controller's state into [uiState], the single thing the screen observes. */
        private fun publishQueue() {
            val controller = queue ?: return
            _uiState.value =
                _uiState.value.copy(
                    shorts = controller.items.value,
                    currentIndex = controller.currentIndex.value,
                    isLoadingMore = controller.isLoadingMore.value,
                )
        }

        /** Products tagged in a Short; empty when it has none or they cannot be loaded. */
        suspend fun videoProducts(videoId: String) = repository.videoProducts(videoId)

        /**
         * `WhileSubscribed` matters here: the page calls this from `remember(video.id)`, so a
         * hand-rolled `launch { collect { } }` left one permanent Room observer per short scrolled
         * past — a few hundred of them after a long session, every one waking on every write.
         */
        fun isVideoLikedState(videoId: String): StateFlow<Boolean> =
            engagement
                .likeState(videoId)
                .map { it == "LIKED" }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = false,
                )

        fun isChannelSubscribedState(channelId: String): StateFlow<Boolean> =
            engagement
                .subscriptionState(channelId)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = false,
                )

        fun isShortSavedState(videoId: String): StateFlow<Boolean> =
            savedShortIds
                .map { it.contains(videoId) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = savedShortIds.value.contains(videoId),
                )

        /**
         * Opens the queue for [source]. Every surface funnels through here; which loader that needs,
         * and whether the algorithmic feed follows it, is [ShortsQueueLoaderFactory]'s decision.
         *
         * Idempotent for the same source: re-entering the screen must not refetch or reset the
         * position. A different source rebuilds, because a reused back stack entry hands this
         * ViewModel a new source rather than a new ViewModel.
         */
        fun load(source: ShortsQueueSource) {
            if (source == loadedSource && (queue != null || _uiState.value.isLoading)) return
            loadJob?.cancel()
            loadedSource = source

            val resolved = queueFactory.resolve(source)
            val controller = queueFactory.create(resolved)
            queue = controller
            _uiState.value = ShortsUiState(isLoading = true)

            // Resolving the tapped short's streams starts now rather than after the queue loads, so
            // playback is not gated on whichever network call the source happens to need.
            resolved.openAtVideoId?.let { prefetchPlaybackStreams(listOf(it)) }

            loadJob =
                viewModelScope.launch(PerformanceDispatcher.networkIO) {
                    try {
                        controller.loadInitial(resolved.openAtVideoId)
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        publishQueue()

                        val items = controller.items.value
                        val at = controller.currentIndex.value
                        prefetchPlaybackStreams(listOfNotNull(items.getOrNull(at)?.id, items.getOrNull(at + 1)?.id))
                        onScreenVisible()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading shorts queue", e)
                        queue = null
                        _uiState.value =
                            _uiState.value.copy(
                                isLoading = false,
                                error = e.message ?: context.getString(R.string.error_failed_to_load_shorts),
                            )
                    }
                }
        }

        fun retry(source: ShortsQueueSource) {
            queue = null
            loadedSource = null
            _uiState.value = _uiState.value.copy(error = null)
            load(source)
        }

        fun loadMoreShorts() {
            val controller = queue ?: return
            if (!controller.hasMore || controller.isLoadingMore.value) return

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                _uiState.value = _uiState.value.copy(isLoadingMore = true)
                val before = controller.items.value.size
                try {
                    controller.loadMore()
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading more shorts", e)
                } finally {
                    _uiState.value = _uiState.value.copy(isLoadingMore = false)
                    publishQueue()
                }
                val after = controller.items.value.size
                if (after > before && prefetch.currentRequest(after) != null) loadMoreShorts()
            }
        }

        private fun prefetchPlaybackStreams(videoIds: List<String>) {
            if (videoIds.isEmpty()) return
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val targetHeight =
                    shortsTargetHeight(
                        isWifi = isOnWifi(context),
                        wifiQuality = playerPreferences.effectiveShortsQualityWifi.first(),
                        cellularQuality = playerPreferences.effectiveShortsQualityCellular.first(),
                    )
                val preferredLang = playerPreferences.preferredAudioLanguage.first()
                videoIds.forEach { id ->
                    launch { runCatching { getPlaybackStreams(id, targetHeight, preferredLang) } }
                }
            }
        }

        /**
         * The pager's single report of where the user is. Also the only place that decides to page
         * ahead.
         */
        fun updateCurrentIndex(index: Int) {
            val controller = queue ?: return
            controller.setCurrentIndex(index)
            _uiState.value = _uiState.value.copy(currentIndex = controller.currentIndex.value)

            prefetch
                .onViewportChanged(currentItemCount = controller.items.value.size, lastVisibleItemIndex = index)
                ?.let { loadMoreShorts() }
        }

        /** A reel counts as seen once it has actually been on screen for a moment, never when fetched. */
        fun onReelShown(videoId: String) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) { feed.recordShown(videoId) }
        }

        /**
         * Streams for [videoId]; the `/player` response that carries them also names the reel, so
         * the queue learns its title, channel and view count from the same request.
         */
        suspend fun refreshPlaybackStreams(
            videoId: String,
            targetHeight: Int,
            preferredAudioLanguage: String,
        ): ShortPlaybackStreams? {
            streams.invalidate(videoId)
            return getPlaybackStreams(videoId, targetHeight, preferredAudioLanguage)
        }

        suspend fun getPlaybackStreams(
            videoId: String,
            targetHeight: Int,
            preferredAudioLanguage: String,
        ): ShortPlaybackStreams? {
            val resolved = streams.resolve(videoId, targetHeight, preferredAudioLanguage) ?: return null
            resolved.details?.let {
                ShortDetailsResolver.remember(videoId, it.title, it.channelName, it.channelId, it.viewCount)
                applyDetails(videoId, it)
            }
            return resolved
        }

        suspend fun availableQualities(videoId: String): List<ShortVideoQuality> {
            val fixed =
                streams.availableQualities(videoId).let { all ->
                    // Never hand back an empty menu: if nothing is at or under the cap, show what exists.
                    all.filter { !DataSaver.enabled || it.heightClass <= DataSaver.MAX_VIDEO_HEIGHT }.ifEmpty { all }
                }
            if (fixed.isEmpty()) return fixed
            // Auto leads the menu, like the main player's, so a pinned reel can go back to adaptive.
            return listOfNotNull(streams.autoQuality(videoId)) + fixed
        }

        suspend fun availableAudioTracks(videoId: String): List<ShortAudioTrack> = streams.availableAudioTracks(videoId)

        suspend fun downloadFormats(
            videoId: String,
        ): Pair<List<PlayerResponse.StreamingData.Format>, List<PlayerResponse.StreamingData.Format>> = streams.downloadFormats(videoId)

        /** Total download size per `(resolution, codec)` pair. Pure: nothing here goes to the network. */
        suspend fun streamSizesFor(
            videoId: String,
            videoFormats: List<PlayerResponse.StreamingData.Format>,
            audioFormats: List<PlayerResponse.StreamingData.Format>,
        ): Map<String, Long> = StreamSizeEstimator.fromInnerTubeFormats(videoFormats, audioFormats, streams.durationMs(videoId) ?: 0L)

        /** Counts, avatar, timestamp and sound for the reel on screen — one overlay request, cached. */
        fun loadShortDetails(videoId: String) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val overlay = runCatching { metadata.overlayFor(videoId) }.getOrNull() ?: return@launch
                enrich(videoId) { it.withOverlay(overlay) }
            }
        }

        /**
         * What a reel shows or opens that is not its video, fetched before the user gets there: likes,
         * channel, avatar and comment count ([details]) and the description ([description]). Comments
         * themselves are never fetched here; the list loads when the sheet opens. Each piece is cached
         * or de-duplicated underneath, so asking again for a reel costs nothing.
         */
        fun preloadShortExtras(
            videoId: String,
            details: Boolean,
            description: Boolean,
        ) {
            if (details) loadShortDetails(videoId)
            if (description) loadShortDescription(videoId)
        }

        /** The description sheet needs the watch page: description, absolute date, exact counts. */
        fun loadShortDescription(videoId: String) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val existing = _uiState.value.shorts.firstOrNull { it.id == videoId } ?: return@launch
                if (existing.description.isNotBlank()) return@launch
                val enriched = runCatching { repository.enrichFromWatchMetadata(existing.toVideo()) }.getOrNull() ?: return@launch
                enrich(videoId) { short ->
                    short.copy(
                        description = enriched.description.ifBlank { short.description },
                        uploadDate = enriched.uploadDate.ifBlank { short.uploadDate },
                        likeCount = if (enriched.likeCount > 0) enriched.likeCount else short.likeCount,
                        viewCount = if (enriched.viewCount > 0) enriched.viewCount else short.viewCount,
                        channelThumbnailUrl = enriched.channelThumbnailUrl.ifBlank { short.channelThumbnailUrl },
                    )
                }
            }
        }

        /**
         * A sequence reel is an id until `/player` names it, so blocked channels and topics are
         * judged here as well as at page assembly; a reel that fails leaves the queue instead.
         */
        private suspend fun applyDetails(
            videoId: String,
            details: ShortDetails,
        ) {
            val dropped =
                feed.isBlocked(details.channelId, details.title, details.channelName) &&
                    (queue?.dropFiltered(videoId) ?: ShortsQueueChange.None) != ShortsQueueChange.None
            if (dropped) {
                publishQueue()
                return
            }
            enrich(videoId) { short ->
                short.copy(
                    title = details.title.ifBlank { short.title },
                    channelName = details.channelName.ifBlank { short.channelName },
                    channelId = details.channelId.ifBlank { short.channelId },
                    viewCount = details.viewCount ?: short.viewCount,
                    durationMs = details.durationMs ?: short.durationMs,
                )
            }
        }

        private fun ShortVideo.withOverlay(overlay: ReelOverlay): ShortVideo =
            copy(
                title = title.ifBlank { overlay.title.orEmpty() },
                channelName = channelName.ifBlank { overlay.channelName.orEmpty() },
                channelId = channelId.ifBlank { overlay.channelId.orEmpty() },
                channelThumbnailUrl = overlay.channelAvatarUrl ?: channelThumbnailUrl,
                likeCount = overlay.likeCount ?: likeCount,
                commentCount = overlay.commentCount ?: commentCount,
                uploadDate = uploadDate.ifBlank { overlay.relativeTimestamp.orEmpty() },
                soundTitle = overlay.soundTitle ?: soundTitle,
                soundThumbnailUrl = overlay.soundThumbnailUrl ?: soundThumbnailUrl,
                soundBrowseId = overlay.soundBrowseId ?: soundBrowseId,
                soundParams = overlay.soundParams ?: soundParams,
                linkedVideo =
                    overlay.companionVideoId?.let { ShortLinkedVideo(it, overlay.companionVideoTitle) } ?: linkedVideo,
            )

        private fun enrich(
            videoId: String,
            transform: (ShortVideo) -> ShortVideo,
        ) {
            val existing = _uiState.value.shorts.firstOrNull { it.id == videoId } ?: return
            val enriched = transform(existing)
            if (enriched == existing) return
            if (queue?.applyEnrichment(listOf(enriched)) != ShortsQueueChange.None) publishQueue()
        }

        /**
         * Liking a short carries no learning signal: the deliberate "more like this" action is
         * what feeds the engine, and firing on the like too would double-count it.
         */
        suspend fun toggleLike(short: ShortVideo) {
            val video = short.toVideo()
            if (engagement.likeState(video.id).first() == "LIKED") {
                engagement.removeLike(video.id)
            } else {
                engagement.like(video)
            }
        }

        suspend fun toggleSubscription(
            channelId: String,
            channelName: String,
            channelThumbnail: String,
        ) = engagement.toggleSubscription(channelId, channelName, channelThumbnail)

        fun toggleSaveShort(short: ShortVideo) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val video = short.toVideo()
                if (playlistRepository.isInSavedShorts(video.id)) {
                    playlistRepository.removeFromSavedShorts(video.id)
                } else {
                    playlistRepository.addToSavedShorts(video)
                    runCatching {
                        FlowNeuroEngine.onVideoInteraction(video.copy(isShort = true), InteractionType.SAVED)
                    }
                }
            }
        }

        fun recordShortProgress(
            short: ShortVideo,
            positionMs: Long,
            durationMs: Long,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val safeDuration =
                    when {
                        durationMs > 0L -> durationMs
                        short.durationMs >= 1_000L -> short.durationMs
                        else -> DEFAULT_REEL_DURATION_MS
                    }
                watchHistory.save(short, positionMs.coerceAtLeast(1_000L).coerceAtMost(safeDuration), safeDuration)
            }
        }

        /**
         * Terminal signal for a short the user swiped away from before the watch threshold fired.
         * Early abandonment emits SKIPPED — the engine's main source of negative watch evidence.
         */
        fun recordShortAbandoned(
            short: ShortVideo,
            positionMs: Long,
            durationMs: Long,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val video = short.toVideo()
                val signal = ShortWatchClassifier.classifyAbandon(positionMs, durationMs, video.duration)
                recordShortView(
                    video,
                    positionMs,
                    counted =
                        signal?.interaction != InteractionType.SKIPPED && positionMs >= ShortWatchClassifier.MIN_SHORT_WATCH_MS,
                )
                signal ?: return@launch
                runCatching {
                    FlowNeuroEngine.onVideoInteraction(video.copy(isShort = true), signal.interaction, percentWatched = signal.percent)
                    FlowNeuroEngine.recordSeenShorts(listOf(video.id))
                }.onFailure { e -> Log.w(TAG, "Failed to record abandoned short in FlowNeuro", e) }
            }
        }

        fun recordShortWatched(
            short: ShortVideo,
            positionMs: Long,
            durationMs: Long,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val video = short.toVideo()
                val signal = ShortWatchClassifier.classify(positionMs, durationMs, video.duration)
                recordShortView(video, signal.position, counted = signal.interaction == InteractionType.WATCHED)
                watchHistory.save(short, signal.position, signal.safeDuration)

                runCatching {
                    FlowNeuroEngine.onVideoInteraction(video.copy(isShort = true), signal.interaction, percentWatched = signal.percent)
                    FlowNeuroEngine.recordSeenShorts(listOf(video.id))
                }.onFailure { e -> Log.w(TAG, "Failed to record watched short in FlowNeuro", e) }
            }
        }

        private fun recordShortView(
            video: Video,
            watchedMs: Long,
            counted: Boolean,
        ) = videoStats.onView(
            ViewEvent(
                videoId = video.id,
                title = video.title,
                channelId = video.channelId,
                channelName = video.channelName,
                format = ViewFormat.SHORT,
                watchedMs = watchedMs,
                counted = counted,
                skipped = !counted,
                channelAvatarUrl = video.channelThumbnailUrl,
            ),
            video.copy(isShort = true),
        )

        fun loadComments(videoId: String) = comments.load(videoId)

        fun reloadComments(videoId: String) = comments.reload(videoId)

        fun postComment(
            videoId: String,
            text: String,
            onDone: (Result<Unit>) -> Unit,
        ) {
            viewModelScope.launch { onDone(comments.post(videoId, text)) }
        }

        fun selectCommentSort(
            videoId: String,
            sort: VideoCommentSort,
        ) = comments.selectSort(videoId, sort)

        fun loadCommentReplies(comment: Comment) {
            val currentShort = _uiState.value.shorts.getOrNull(_uiState.value.currentIndex) ?: return
            comments.loadReplies(currentShort.id, comment)
        }

        /** Opens a related chain from [short] and interleaves its first page right after the current reel. */
        fun wantMoreLikeThis(short: ShortVideo) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    FlowNeuroEngine.onVideoInteraction(short.toVideo(), InteractionType.LIKED)
                    val page = feed.chainFrom(short)
                    if (queue?.mergeDiscovery(page) != ShortsQueueChange.None) publishQueue()
                    _snackbarMessage.value = context.getString(R.string.shorts_showing_more_like_this)
                } catch (e: Exception) {
                    Log.e(TAG, "Error signaling want more", e)
                }
            }
        }

        /** A reel the user stayed on becomes a weak seed for the chains that follow this session. */
        fun onReelDwelled(short: ShortVideo) = feed.noteDwell(short)

        fun onScreenVisible() {
            prefetch
                .onVisible(currentItemCount = queue?.items?.value?.size ?: 0, feedReady = queue != null)
                ?.let { loadMoreShorts() }
        }

        fun onScreenHidden() = prefetch.onHidden()

        /** "Don't show this channel": the same permanent block the Home feed's sheet applies. */
        fun blockChannel(short: ShortVideo) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    val channelId = short.channelId
                    check(channelId.isNotBlank()) { context.getString(R.string.channel_metadata_unavailable) }
                    FlowNeuroEngine.blockChannel(context, channelId)
                    videoStats.onAction(LedgerAction.BLOCK_CHANNEL)
                    dropChannel(channelId)
                    FeedInvalidationBus.emit(FeedInvalidationBus.Event.ChannelBlocked(channelId, short.id))
                    _snackbarMessage.value = context.getString(R.string.channel_blocked_toast, short.channelName)
                } catch (e: Exception) {
                    Log.e(TAG, "Error blocking channel", e)
                    _snackbarMessage.value = context.getString(R.string.quick_actions_error_template, e.message)
                }
            }
        }

        private fun dropChannel(channelId: String) {
            feed.evictChannel(channelId)
            if (queue?.removeChannel(channelId) != ShortsQueueChange.None) publishQueue()
        }

        fun notInterested(short: ShortVideo) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    val video = short.toVideo()
                    FlowNeuroEngine.markNotInterested(video)
                    videoStats.onAction(LedgerAction.NOT_INTERESTED)
                    FeedInvalidationBus.emit(FeedInvalidationBus.Event.NotInterested(video.id, video.channelId))

                    queue?.remove(short.id)
                    publishQueue()

                    _snackbarMessage.value = context.getString(R.string.shorts_showing_less_like_this)
                } catch (e: Exception) {
                    Log.e(TAG, "Error marking not interested", e)
                }
            }
        }

        companion object {
            private const val TAG = "ShortsViewModel"

            /** Reels kept loaded past the one on screen, and how few remaining arm the next page. */
            private const val PREFETCH_AHEAD_REELS = 12
            private const val PREFETCH_TRIGGER_REMAINING = 6

            private const val COMMENTS_FETCH_TIMEOUT_MS = 10_000L

            private const val DEFAULT_REEL_DURATION_MS = 60_000L
        }
    }

data class ShortsUiState(
    val shorts: List<ShortVideo> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)
