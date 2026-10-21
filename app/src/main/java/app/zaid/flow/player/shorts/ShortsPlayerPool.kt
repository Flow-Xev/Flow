package app.zaid.flow.player.shorts

import android.content.Context
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.MediaSession
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.model.ShortVideo
import app.zaid.flow.player.analytics.PlaybackAnalyticsLogger
import app.zaid.flow.player.audio.AudioEffectsEntryPoint
import app.zaid.flow.player.audio.eq.EqualizerAudioProcessor
import app.zaid.flow.player.cache.PlayerCacheManager
import app.zaid.flow.player.cache.SharedPlayerCacheProvider
import app.zaid.flow.player.cache.StreamCacheKeys
import app.zaid.flow.player.config.PlayerConfig
import app.zaid.flow.player.config.VideoSizeCap
import app.zaid.flow.player.datasource.YouTubeHttpDataSource
import app.zaid.flow.player.factory.LoadControlFactory
import app.zaid.flow.player.quality.MeasuredSpeed
import app.zaid.flow.player.resolver.MediaSourceBuilder
import app.zaid.flow.utils.NetworkState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ShortsPlayerPool — 3-player pool for instant swipe transitions.
 *
 * Architecture:
 * - 3 ExoPlayer instances with roles: PREVIOUS, CURRENT, NEXT
 * - Aggressive buffer settings (2.5s min / 15s max / 500ms playback / 1.5s rebuffer)
 * - REPEAT_MODE_ONE for looping shorts
 * - RESIZE_MODE_ZOOM for full-screen display
 * - Role rotation on swipe: no player creation/destruction, just role reassignment
 * - Pre-loading: NEXT player starts buffering before user swipes
 *
 * Lifecycle:
 * 1. initialize(context) — creates 3 players
 * 2. prepareCurrent(videoUrl, audioUrl) — loads media into CURRENT player
 * 3. prepareNext(videoUrl, audioUrl) — pre-loads NEXT player
 * 4. swipeForward() — rotates: CURRENT→PREVIOUS, NEXT→CURRENT, PREVIOUS→NEXT
 * 5. swipeBackward() — rotates: CURRENT→NEXT, PREVIOUS→CURRENT, NEXT→PREVIOUS
 * 6. release() — destroys all players
 */
@OptIn(UnstableApi::class)
class ShortsPlayerPool private constructor() {
    companion object {
        private const val SESSION_ID = "flow_shorts_session"
        private const val TAG = "ShortsPlayerPool"
        private const val POOL_SIZE = 3
        private const val MAX_RECOVERIES = 3
        private const val WATCHDOG_INTERVAL_MS = 3_000L
        private const val WATCHDOG_TICKS_TO_TRIGGER = 2
        private const val RECOVERY_RESET_MS = 30_000L
        private const val REPRESENTATION_TAG = "<Representation "

        @Volatile
        private var instance: ShortsPlayerPool? = null

        fun getInstance(): ShortsPlayerPool =
            instance ?: synchronized(this) {
                instance ?: ShortsPlayerPool().also { instance = it }
            }
    }

    private val players = arrayOfNulls<ExoPlayer>(POOL_SIZE)
    private val playerVideoIds = arrayOfNulls<String>(POOL_SIZE)

    // Tracks which content index (absolute position in the list) currently owns this player slot
    private val playerOwnerIndices = arrayOfNulls<Int>(POOL_SIZE)

    // Track the last video and audio URLs per slot so we can hot-swap audio/quality
    private val playerVideoUrls = arrayOfNulls<String>(POOL_SIZE)
    private val playerAudioUrls = arrayOfNulls<String?>(POOL_SIZE)

    private val playerVideoManifests = arrayOfNulls<String?>(POOL_SIZE)
    private val playerAudioManifests = arrayOfNulls<String?>(POOL_SIZE)

    private val equalizers = arrayOfNulls<EqualizerAudioProcessor>(POOL_SIZE)
    private var audioEffects: AudioEffectsEntryPoint? = null
    private var equalizerScope: CoroutineScope? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recoveryCounts = IntArray(POOL_SIZE)
    private val stallWatchdogs = arrayOfNulls<Runnable>(POOL_SIZE)
    private val stuckTicks = IntArray(POOL_SIZE)
    private val lastWatchdogPositions = LongArray(POOL_SIZE) { -1L }
    private val lastRecoveryAt = LongArray(POOL_SIZE)

    /** A short whose stream died, stalled or froze; the UI re-resolves fresh URLs and calls [replaceStreams]. */
    data class StreamFailure(
        val index: Int,
        val videoId: String,
    )

    private val _streamFailures = MutableSharedFlow<StreamFailure>(extraBufferCapacity = 8)
    val streamFailures: SharedFlow<StreamFailure> = _streamFailures.asSharedFlow()

    private var isInitialized = false
    private var dataSourceFactory: DefaultDataSource.Factory? = null

    /**
     * One session for the whole pool, re-pointed at the active player on every settle, so headset
     * and Bluetooth buttons reach the reel on screen. A session per page used to be built and torn
     * down on every swipe.
     */
    private var mediaSession: MediaSession? = null

    private var activeIndex: Int = -1
    private var activeVideoId: String? = null

    private val _ownershipGeneration = MutableStateFlow(0)
    val ownershipGeneration: StateFlow<Int> = _ownershipGeneration.asStateFlow()

    private fun bumpOwnership() {
        _ownershipGeneration.value += 1
    }

    private var hostToken: Long = 0L
    private var nextHostToken: Long = 1L

    fun acquireHost(): Long = nextHostToken++.also { hostToken = it }

    fun releaseIfHost(token: Long) {
        if (token != hostToken) return
        release()
    }

    /**
     * Reads through the shared media cache once it exists.
     *
     * Without it every loop of a REPEAT_MODE_ONE short and every swipe back re-downloaded the
     * whole file — the main video player has read through [SharedPlayerCacheProvider] all along,
     * and this pool was the one playback path that did not.
     *
     * Null until the cache has been opened, which cannot happen here: [initialize] runs on the main
     * thread and opening the cache touches SQLite and the filesystem.
     */
    @Volatile
    private var cachedDataSourceFactory: DataSource.Factory? = null

    private var poolScope: CoroutineScope? = null
    private var preferredAudioLanguage: String = "original"
    private var shortsPlaybackMode: String = "loop"
    private var basePlaybackSpeed: Float = 1f

    private val preferenceObservers = ShortsPreferenceObservers()

    private val _currentVideoId = MutableStateFlow<String?>(null)
    val currentVideoId: StateFlow<String?> = _currentVideoId.asStateFlow()

    private val _currentVideo = MutableStateFlow<ShortVideo?>(null)
    val currentVideo: StateFlow<ShortVideo?> = _currentVideo.asStateFlow()

    fun setCurrentVideo(video: ShortVideo?) {
        _currentVideo.value = video
    }

    fun playbackPosition(): Long = findActivePlayer()?.currentPosition ?: 0L

    fun playbackDuration(): Long = findActivePlayer()?.duration?.coerceAtLeast(0L) ?: 0L

    fun isPlaying(): Boolean = findActivePlayer()?.isPlaying == true

    /**
     * The playing reel's frame aspect, or null before the first frame reports a size.
     *
     * Measured rather than assumed 9:16: reels are only *mostly* portrait, and handing a
     * Picture-in-Picture window the wrong ratio letterboxes it for the whole session.
     */
    fun activeVideoAspectRatio(): Float? =
        findActivePlayer()
            ?.videoSize
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toFloat() / it.height.toFloat() }

    // INITIALIZATION
    fun initialize(context: Context) {
        if (isInitialized) return

        val appContext = context.applicationContext
        Log.d(TAG, "Initializing 3-player pool for Shorts")
        dataSourceFactory = DefaultDataSource.Factory(appContext, YouTubeHttpDataSource.Factory())
        attachSharedCache(appContext)
        val preferences = PlayerPreferences(appContext)
        preferenceObservers.start(
            preferredAudioLanguage = preferences.preferredAudioLanguage,
            playbackMode = preferences.shortsPlaybackMode,
            playbackSpeed = preferences.shortsPlaybackSpeed,
            onPreferredAudioLanguage = { language ->
                preferredAudioLanguage = language
                updateTrackSelectors(language)
            },
            onPlaybackMode = { mode ->
                shortsPlaybackMode = mode
                Log.d(TAG, "Shorts playback mode changed to: $mode")
            },
            onPlaybackSpeed = { speed ->
                setBasePlaybackSpeed(speed)
            },
        )

        val effects = EntryPointAccessors.fromApplication(appContext, AudioEffectsEntryPoint::class.java).also { audioEffects = it }
        val equalizerSpec = effects.equalizerRepository().processingSpec
        equalizerScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope ->
                scope.launch { equalizerSpec.collect { spec -> equalizers.forEach { it?.setSpec(spec) } } }
            }

        try {
            for (i in 0 until POOL_SIZE) {
                val equalizer = EqualizerAudioProcessor().also { it.setSpec(equalizerSpec.value) }
                equalizers[i] = equalizer
                players[i] =
                    createShortsPlayer(appContext, equalizer).also {
                        effects.audioSessionRegistry().open(it.audioSessionId, AudioEffect.CONTENT_TYPE_MOVIE)
                    }
                playerOwnerIndices[i] = null
                playerVideoIds[i] = null
            }
            mediaSession = createMediaSession(appContext, players[0]!!)
            isInitialized = true
            Log.d(TAG, "Player pool initialized with $POOL_SIZE players")
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    /**
     * Points playback at the shared media cache, opening it off the main thread if some other
     * player has not already. Until that completes the pool keeps streaming uncached, so a cold
     * cache delays the benefit rather than the first frame.
     */
    private fun attachSharedCache(appContext: Context) {
        SharedPlayerCacheProvider.existing()?.let {
            cachedDataSourceFactory = buildCachedFactory(appContext, it)
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { poolScope = it }
        scope.launch {
            runCatching {
                PlayerCacheManager.preload(appContext)
                SharedPlayerCacheProvider.existing()
            }.onSuccess { cache ->
                if (cache != null) {
                    cachedDataSourceFactory = buildCachedFactory(appContext, cache)
                    Log.d(TAG, "Shorts playback now reads through the shared media cache")
                }
            }.onFailure { Log.w(TAG, "Shared cache unavailable; Shorts will stream uncached", it) }
        }
    }

    private fun buildCachedFactory(
        appContext: Context,
        cache: Cache,
    ): DataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(cache)
            .setCacheKeyFactory(StreamCacheKeys)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appContext, YouTubeHttpDataSource.Factory()))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun createMediaSession(
        context: Context,
        player: ExoPlayer,
    ): MediaSession? =
        try {
            MediaSession
                .Builder(context, player)
                .setId(SESSION_ID)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Shorts MediaSession unavailable; media buttons will not reach the reel", e)
            null
        }

    private fun updateTrackSelectors(language: String) {
        players.filterNotNull().forEach { player ->
            val trackSelector = player.trackSelector as? DefaultTrackSelector
            trackSelector?.let { selector ->
                val builder = selector.buildUponParameters()
                builder.setPreferredAudioLanguage(language.takeUnless { it == "original" || it.isBlank() })
                selector.setParameters(builder)
            }
        }
    }

    private fun createShortsPlayer(
        context: Context,
        equalizer: EqualizerAudioProcessor,
    ): ExoPlayer {
        // The device cap is landscape; a Short is portrait, so its axes swap.
        val (maxVideoWidth, maxVideoHeight) = VideoSizeCap.forDevice(context).let { it.maxHeight to it.maxWidth }

        val loadControl = LoadControlFactory.forShorts()

        val trackSelector =
            DefaultTrackSelector(
                context,
                // YouTube-style Auto for short clips:
                // - step up as soon as ~1.5 s is buffered (a reel is over in 15-60 s, so waiting longer
                //   means the whole clip plays at the starting rung);
                // - step down only when the buffer is really running low (< 5 s), not while it is full;
                // - after a step up, re-fetch whatever is buffered beyond 2 s at the better rung. The old
                //   25 s retain window was longer than the 8 s Shorts buffer, so a low rung picked at
                //   start could never be replaced;
                // - trust 80% of the measured speed: clips are short and the next ones are prefetched.
                AdaptiveTrackSelection.Factory(
                    // minDurationForQualityIncreaseMs =
                    1_500,
                    // maxDurationForQualityDecreaseMs =
                    5_000,
                    // minDurationToRetainAfterDiscardMs =
                    2_000,
                    // bandwidthFraction =
                    0.8f,
                ),
            ).apply {
                val builder =
                    buildUponParameters()
                        .setPreferredVideoMimeTypes(*PlayerConfig.PREFERRED_VIDEO_MIME_TYPES)
                        .setAllowVideoMixedMimeTypeAdaptiveness(true)
                        .setForceHighestSupportedBitrate(false)
                        .setViewportSizeToPhysicalDisplaySize(context, true)
                        .setMaxVideoSize(maxVideoWidth, maxVideoHeight)

                if (preferredAudioLanguage != "original" && preferredAudioLanguage.isNotEmpty()) {
                    builder.setPreferredAudioLanguage(preferredAudioLanguage)
                }

                setParameters(builder.build())
            }

        // One processor per pooled player: they are all live at once and each keeps its own filter history.
        val renderersFactory =
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink
                        .Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(equalizer))
                        .build()
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)

        return ExoPlayer
            .Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(
                // Auto starts from the speed this connection last delivered, not a per-country guess.
                DefaultBandwidthMeter
                    .Builder(context)
                    .apply {
                        MeasuredSpeed
                            .read(context, NetworkState.isOnWifi(context))
                            .takeIf { it > 0L }
                            ?.let { bps -> setInitialBitrateEstimate(bps) }
                    }.build(),
            ).setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false,
            ).setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory!!))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = false
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                addAnalyticsListener(PlaybackAnalyticsLogger(TAG) { _currentVideoId.value })
                // Feeds the speed Shorts Auto reads next time: what these downloads actually achieved.
                addAnalyticsListener(
                    object : AnalyticsListener {
                        override fun onBandwidthEstimate(
                            eventTime: AnalyticsListener.EventTime,
                            totalLoadTimeMs: Int,
                            totalBytesLoaded: Long,
                            bitrateEstimate: Long,
                        ) {
                            MeasuredSpeed.record(context, app.zaid.flow.player.network.StreamingNetwork.kind, totalBytesLoaded, totalLoadTimeMs.toLong())
                        }
                    },
                )
                val exo = this
                addListener(
                    object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            ShortsStartupTrace.onFirstFrame(_currentVideoId.value)
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            armWatchdog(exo)
                            ShortsStartupTrace.onState(
                                _currentVideoId.value,
                                when (playbackState) {
                                    Player.STATE_IDLE -> "IDLE"
                                    Player.STATE_BUFFERING -> "BUFFERING"
                                    Player.STATE_READY -> "READY"
                                    else -> "ENDED"
                                },
                            )
                        }

                        override fun onPlayWhenReadyChanged(
                            playWhenReady: Boolean,
                            reason: Int,
                        ) {
                            armWatchdog(exo)
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.w(TAG, "Shorts player error ${error.errorCodeName}; recovering", error)
                            requestRefresh(exo)
                            ShortsStartupTrace.onState(_currentVideoId.value, "ERROR ${error.errorCodeName}: ${error.message}")
                        }
                    },
                )
            }
    }

    // PLAYBACK RECOVERY

    /**
     * Runs while a short should be playing and catches the three ways it gets stuck: an error, a
     * buffering/idle hang, and a "ready" player whose position stopped moving (frozen frame).
     * It needs two consecutive bad ticks, so a normal rebuffer never trips it.
     */
    private fun armWatchdog(player: ExoPlayer) {
        val slot = players.indexOf(player)
        if (slot < 0) return
        if (!player.playWhenReady || playerOwnerIndices[slot] == null) {
            stallWatchdogs[slot]?.let { mainHandler.removeCallbacks(it) }
            stallWatchdogs[slot] = null
            return
        }
        if (stallWatchdogs[slot] != null) return
        stuckTicks[slot] = 0
        lastWatchdogPositions[slot] = -1L
        val task =
            object : Runnable {
                override fun run() {
                    if (!player.playWhenReady || playerOwnerIndices[slot] == null) {
                        stallWatchdogs[slot] = null
                        return
                    }
                    val position = player.currentPosition
                    val suspect =
                        when (player.playbackState) {
                            Player.STATE_BUFFERING, Player.STATE_IDLE -> {
                                true
                            }

                            Player.STATE_READY -> {
                                player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                                    position == lastWatchdogPositions[slot]
                            }

                            else -> {
                                false
                            }
                        }
                    lastWatchdogPositions[slot] = position
                    stuckTicks[slot] = if (suspect) stuckTicks[slot] + 1 else 0
                    if (stuckTicks[slot] >= WATCHDOG_TICKS_TO_TRIGGER) {
                        stuckTicks[slot] = 0
                        Log.w(TAG, "Shorts player stuck in slot $slot; recovering")
                        requestRefresh(player)
                    }
                    mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                }
            }
        stallWatchdogs[slot] = task
        mainHandler.postDelayed(task, WATCHDOG_INTERVAL_MS)
    }

    /**
     * First line of defence: ask the UI for freshly resolved URLs (the old ones may have expired).
     * Falls back to re-preparing the same URLs if nobody can take the request.
     */
    private fun requestRefresh(player: ExoPlayer) {
        val slot = players.indexOf(player)
        if (slot < 0) return
        val index = playerOwnerIndices[slot] ?: return
        val videoId = playerVideoIds[slot] ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoveryAt[slot] > RECOVERY_RESET_MS) recoveryCounts[slot] = 0
        if (recoveryCounts[slot] >= MAX_RECOVERIES) {
            Log.w(TAG, "Giving up recovering slot $slot")
            return
        }
        recoveryCounts[slot]++
        lastRecoveryAt[slot] = now
        if (!_streamFailures.tryEmit(StreamFailure(index, videoId))) recoverInPlace(index, videoId)
    }

    /** Swaps in freshly resolved streams for an owned slot, resuming from where it stopped. */
    fun replaceStreams(
        index: Int,
        videoId: String,
        videoUrl: String,
        audioUrl: String?,
        videoDashManifest: String?,
        audioDashManifest: String?,
    ): Boolean {
        val player = ownedPlayer(index, videoId) ?: return false
        val slot = ShortsSlotRules.slotFor(index, POOL_SIZE)
        val position = player.currentPosition
        val shouldPlay = player.playWhenReady
        playerVideoUrls[slot] = videoUrl
        playerAudioUrls[slot] = audioUrl
        playerVideoManifests[slot] = videoDashManifest
        playerAudioManifests[slot] = audioDashManifest
        player.stop()
        player.clearMediaItems()
        preparePlayerInternal(player, videoUrl, audioUrl, videoDashManifest, audioDashManifest)
        if (position > 0) player.seekTo(position)
        player.playWhenReady = shouldPlay
        return true
    }

    /** Re-prepares the slot with the URLs it already has. Used when fresh ones cannot be resolved. */
    fun recoverInPlace(
        index: Int,
        videoId: String,
    ) {
        val slot = ShortsSlotRules.slotFor(index, POOL_SIZE)
        val videoUrl = playerVideoUrls[slot] ?: return
        replaceStreams(index, videoId, videoUrl, playerAudioUrls[slot], playerVideoManifests[slot], playerAudioManifests[slot])
    }

    // PLAYER ACCESS

    /**
     * The player a page should bind its `PlayerView` to.
     *
     * Deliberately returns the slot before any media is loaded: the surface has to be attached to
     * the ExoPlayer instance ahead of [prepare] or the first frame arrives with nowhere to go. It
     * refuses only when the slot currently belongs to a *different* index, which is what used to
     * hand a mid-fling page the still-playing previous short.
     *
     * Callers must re-read this when [ownershipGeneration] changes.
     */
    fun playerForAttach(
        index: Int,
        videoId: String,
    ): ExoPlayer? {
        if (!isInitialized || index < 0) return null
        val slot = ShortsSlotRules.slotFor(index, POOL_SIZE)
        if (!ShortsSlotRules.canAttach(playerOwnerIndices[slot], playerVideoIds[slot], index, videoId)) return null
        return players[slot]
    }

    /**
     * The player that actually holds this index's media, or null while the slot is unprepared or
     * owned by someone else. Everything that reads playback state or issues a command uses this, so
     * a control can never land on a short the user is not looking at.
     */
    fun ownedPlayer(
        index: Int,
        videoId: String?,
    ): ExoPlayer? {
        if (!isInitialized || index < 0 || videoId == null) return null
        val slot = ShortsSlotRules.slotFor(index, POOL_SIZE)
        if (!ShortsSlotRules.isOwnedBy(playerOwnerIndices[slot], playerVideoIds[slot], index, videoId)) return null
        return players[slot]
    }

    /** True while the reel plays Auto's multi-quality manifest, as opposed to one pinned quality. */
    fun isVideoAdaptive(
        index: Int,
        videoId: String,
    ): Boolean {
        if (ownedPlayer(index, videoId) == null) return false
        val manifest = playerVideoManifests[ShortsSlotRules.slotFor(index, POOL_SIZE)] ?: return false
        return manifest.indexOf(REPRESENTATION_TAG) != manifest.lastIndexOf(REPRESENTATION_TAG)
    }

    fun getVideoUrlForIndex(
        index: Int,
        videoId: String,
    ): String? {
        if (ownedPlayer(index, videoId) == null) return null
        return playerVideoUrls[ShortsSlotRules.slotFor(index, POOL_SIZE)]
    }

    fun getCurrentVideoId(): String? = _currentVideoId.value

    // MEDIA LOADING

    /**
     * Prepare the player for a specific index with video + audio streams.
     * @param index The absolute list position of the video
     * @param shouldPlay If true, starts playback immediately (for current item). If false, just buffers (for next/prev).
     */
    fun prepare(
        index: Int,
        videoId: String,
        videoUrl: String,
        audioUrl: String?,
        shouldPlay: Boolean,
        videoDashManifest: String? = null,
        audioDashManifest: String? = null,
    ) {
        if (!isInitialized || index < 0) return
        val slot = index % POOL_SIZE
        val player = players[slot] ?: return

        val isSameVideo = playerVideoIds[slot] == videoId && playerOwnerIndices[slot] == index

        if (isSameVideo) {
            if (shouldPlay && !player.isPlaying) {
                Log.d(TAG, "Player at index $index (slot $slot) already prepared. Resuming.")
                activatePlayer(index, videoId)
            }
            return
        }

        Log.d(TAG, "Preparing player at index $index (slot $slot) for video $videoId. AutoPlay: $shouldPlay")

        // Stop any previous playback in this slot
        player.stop()
        player.clearMediaItems()

        recoveryCounts[slot] = 0
        // Update ownership
        playerOwnerIndices[slot] = index
        playerVideoIds[slot] = videoId
        playerVideoUrls[slot] = videoUrl
        playerAudioUrls[slot] = audioUrl
        playerVideoManifests[slot] = videoDashManifest
        playerAudioManifests[slot] = audioDashManifest
        bumpOwnership()

        // Load media
        if (shouldPlay) ShortsStartupTrace.onPrepared(videoId)
        preparePlayerInternal(player, videoUrl, audioUrl, videoDashManifest, audioDashManifest)

        // Set playback state
        player.setPlaybackSpeed(basePlaybackSpeed)
        player.playWhenReady = shouldPlay
        // Set repeat mode based on playback preference: "loop" → REPEAT_MODE_ONE, "auto_next" → REPEAT_MODE_OFF
        player.repeatMode = if (shortsPlaybackMode == "loop") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

        if (shouldPlay) {
            _currentVideoId.value = videoId

            player.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
        } else {
            player.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false,
            )
        }
    }

    /**
     * Activates the player holding [videoId] at [index] (play) and pauses all others.
     * Call this when a page settles.
     */
    fun activatePlayer(
        index: Int,
        videoId: String,
    ) {
        if (!isInitialized) return
        val activeSlot = index % POOL_SIZE

        activeIndex = index
        activeVideoId = videoId

        for (i in 0 until POOL_SIZE) {
            val player = players[i] ?: continue
            val isTarget = (i == activeSlot)

            if (isTarget) {
                if (ShortsSlotRules.isOwnedBy(playerOwnerIndices[i], playerVideoIds[i], index, videoId)) {
                    player.playWhenReady = true
                    player.setPlaybackSpeed(basePlaybackSpeed)
                    player.setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .setUsage(C.USAGE_MEDIA)
                            .build(),
                        true,
                    )
                    _currentVideoId.value = playerVideoIds[i]
                } else {
                    // The slot still holds another short until prepare() swaps it; it must not play meanwhile.
                    player.playWhenReady = false
                    _currentVideoId.value = null
                }
            } else {
                player.playWhenReady = false
            }
        }
        players[activeSlot]?.let { active ->
            mediaSession?.takeIf { it.player !== active }?.setPlayer(active)
        }
    }

    fun releaseUnusedPlayers(currentIndex: Int) {
        if (!isInitialized) return

        var released = false
        for (i in 0 until POOL_SIZE) {
            val ownerIndex = playerOwnerIndices[i] ?: continue
            if (ShortsSlotRules.shouldRelease(ownerIndex, currentIndex)) {
                Log.d(TAG, "Releasing stale player slot $i (owned by index $ownerIndex, current is $currentIndex)")
                players[i]?.stop()
                players[i]?.clearMediaItems()
                playerVideoIds[i]?.let { ShortsStartupTrace.forget(it) }
                playerOwnerIndices[i] = null
                playerVideoIds[i] = null
                playerVideoUrls[i] = null
                playerAudioUrls[i] = null
                playerVideoManifests[i] = null
                playerAudioManifests[i] = null
                released = true
            }
        }
        if (released) bumpOwnership()
    }

    /**
     * Hot-swap the audio track for an already-prepared player slot, keeping the same video URL.
     * Used for the Shorts audio track selector.
     */
    fun reloadWithAudioUrl(
        index: Int,
        videoId: String,
        newAudioUrl: String?,
        newAudioDashManifest: String? = null,
    ) {
        if (!isInitialized || index < 0) return
        val slot = index % POOL_SIZE
        val player = players[slot] ?: return
        if (playerOwnerIndices[slot] != index || playerVideoIds[slot] != videoId) return

        val videoUrl = playerVideoUrls[slot] ?: return
        val wasPlaying = player.isPlaying || player.playWhenReady

        player.stop()
        player.clearMediaItems()
        playerAudioUrls[slot] = newAudioUrl
        playerAudioManifests[slot] = newAudioDashManifest

        preparePlayerInternal(player, videoUrl, newAudioUrl, playerVideoManifests[slot], newAudioDashManifest)
        player.setPlaybackSpeed(basePlaybackSpeed)
        player.playWhenReady = wasPlaying
        player.repeatMode = if (shortsPlaybackMode == "loop") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /**
     * Hot-swap the video quality (URL) for an already-prepared player slot.
     * Used for the Shorts quality selector.
     */
    fun reloadWithVideoUrl(
        index: Int,
        videoId: String,
        newVideoUrl: String,
        newVideoDashManifest: String? = null,
    ) {
        if (!isInitialized || index < 0) return
        val slot = index % POOL_SIZE
        val player = players[slot] ?: return
        if (playerOwnerIndices[slot] != index || playerVideoIds[slot] != videoId) return

        val wasPlaying = player.isPlaying || player.playWhenReady
        val position = player.currentPosition

        player.stop()
        player.clearMediaItems()
        playerVideoUrls[slot] = newVideoUrl
        playerVideoManifests[slot] = newVideoDashManifest

        val audioUrl = playerAudioUrls[slot]
        preparePlayerInternal(player, newVideoUrl, audioUrl, newVideoDashManifest, playerAudioManifests[slot])
        player.setPlaybackSpeed(basePlaybackSpeed)
        player.playWhenReady = wasPlaying
        player.seekTo(position)
        player.repeatMode = if (shortsPlaybackMode == "loop") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private fun preparePlayerInternal(
        player: ExoPlayer,
        videoUrl: String,
        audioUrl: String?,
        videoDashManifest: String?,
        audioDashManifest: String?,
    ) {
        val factory = cachedDataSourceFactory ?: dataSourceFactory ?: return

        if (audioUrl != null && audioUrl != videoUrl) {
            val videoSource = mediaSourceFor(factory, videoUrl, videoDashManifest)
            val audioSource = mediaSourceFor(factory, audioUrl, audioDashManifest)
            val mergingSource = MergingMediaSource(true, true, videoSource, audioSource)
            player.setMediaSource(mergingSource)
        } else {
            player.setMediaSource(mediaSourceFor(factory, videoUrl, videoDashManifest))
        }

        player.prepare()
        player.setPlaybackSpeed(basePlaybackSpeed)
    }

    private fun mediaSourceFor(
        factory: DataSource.Factory,
        url: String,
        dashManifest: String?,
    ): MediaSource {
        if (dashManifest != null) {
            runCatching {
                return MediaSourceBuilder.buildDashSource(factory, dashManifest, Uri.parse(url))
            }.onFailure { Log.w(TAG, "Generated DASH manifest rejected, falling back to progressive: ${it.message}") }
        }
        return ProgressiveMediaSource
            .Factory(factory)
            .createMediaSource(MediaItem.fromUri(url))
    }

    // PLAYBACK CONTROL

    /**
     * The player for [activeIndex], or null when the active page's media has not loaded yet.
     *
     * Returning null is the point: a command issued during that window is dropped rather than
     * applied to whichever slot happened to still hold the previous short's id.
     */
    private fun findActivePlayer(): ExoPlayer? = ownedPlayer(activeIndex, activeVideoId)

    fun play() {
        findActivePlayer()?.let { player ->
            player.setPlaybackSpeed(basePlaybackSpeed)
            player.playWhenReady = true
        }
    }

    fun pause() {
        findActivePlayer()?.playWhenReady = false
    }

    fun togglePlayPause() {
        val player = findActivePlayer() ?: return
        player.playWhenReady = !player.playWhenReady
    }

    fun seekTo(positionMs: Long) {
        findActivePlayer()?.seekTo(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        findActivePlayer()?.setPlaybackSpeed(speed)
    }

    fun resetPlaybackSpeed() {
        findActivePlayer()?.setPlaybackSpeed(basePlaybackSpeed)
    }

    fun setBasePlaybackSpeed(speed: Float) {
        basePlaybackSpeed = speed
        findActivePlayer()?.setPlaybackSpeed(speed)
    }

    fun getBasePlaybackSpeed(): Float = basePlaybackSpeed

    /** Pause ALL players */
    fun pauseAll() {
        for (i in 0 until POOL_SIZE) {
            players[i]?.playWhenReady = false
        }
    }

    /**
     * Release all players and reset state.
     * Call when leaving the Shorts screen.
     */
    fun release() {
        Log.d(TAG, "Releasing player pool")
        preferenceObservers.stop()
        poolScope?.cancel()
        poolScope = null
        cachedDataSourceFactory = null
        mediaSession?.release()
        mediaSession = null
        equalizerScope?.cancel()
        equalizerScope = null
        for (i in 0 until POOL_SIZE) {
            stallWatchdogs[i]?.let { mainHandler.removeCallbacks(it) }
            stallWatchdogs[i] = null
            stuckTicks[i] = 0
            players[i]?.let { audioEffects?.audioSessionRegistry()?.close(it.audioSessionId) }
            equalizers[i] = null
            players[i]?.stop()
            players[i]?.release()
            players[i] = null
            playerVideoIds[i] = null
            playerOwnerIndices[i] = null
            playerVideoUrls[i] = null
            playerAudioUrls[i] = null
            playerVideoManifests[i] = null
            playerAudioManifests[i] = null
        }
        dataSourceFactory = null
        isInitialized = false
        activeIndex = -1
        activeVideoId = null
        _currentVideoId.value = null
        _currentVideo.value = null
        bumpOwnership()
    }

    fun isReady(): Boolean = isInitialized && players[0] != null
}
