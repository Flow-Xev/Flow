package app.zaid.flow.ui.components.shared.card

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.zaid.flow.R
import app.zaid.flow.data.local.InlinePreviewConditions
import app.zaid.flow.data.local.InlinePreviewMode
import app.zaid.flow.data.local.inlinePreviewAllowed
import app.zaid.flow.data.model.Video
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.player.datasource.YouTubeHttpDataSource
import app.zaid.flow.player.stream.InnerTubeVideoStreamExtractor
import app.zaid.flow.utils.NetworkState
import app.zaid.flow.utils.formatDurationMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal val LocalInlineVideoPreviewController =
    staticCompositionLocalOf<InlineVideoPreviewController?> { null }

/**
 * Carries the playhead between a feed preview and the main player so each carries on where the
 * other left off. Offers are keyed by video id and expire, so a stale position never leaks into an
 * unrelated open.
 */
internal object PreviewHandoff {
    private class Entry(
        val videoId: String,
        val positionMs: Long,
        val atMs: Long,
    )

    private var toPlayer: Entry? = null
    private var toPreview: Entry? = null

    /** A preview was tapped open: the player should start at [positionMs]. */
    @Synchronized
    fun offerToPlayer(
        videoId: String,
        positionMs: Long,
    ) {
        toPlayer = Entry(videoId, positionMs, SystemClock.elapsedRealtime())
    }

    /** The position the player should start [videoId] at, consumed on read; null when there is none. */
    @Synchronized
    fun takeForPlayer(videoId: String): Long? = take(toPlayer, videoId, PLAYER_HANDOFF_MAX_AGE_MS).also { toPlayer = null }

    /** The main player is showing [videoId] at [positionMs]; a preview of it later resumes there. */
    @Synchronized
    fun offerToPreview(
        videoId: String,
        positionMs: Long,
    ) {
        toPreview = Entry(videoId, positionMs, SystemClock.elapsedRealtime())
    }

    /** Where a preview of [videoId] should start, consumed on read; null when there is none. */
    @Synchronized
    fun takeForPreview(videoId: String): Long? = take(toPreview, videoId, PREVIEW_HANDOFF_MAX_AGE_MS).also { toPreview = null }

    private fun take(
        entry: Entry?,
        videoId: String,
        maxAgeMs: Long,
    ): Long? {
        if (entry == null || entry.videoId != videoId) return null
        if (SystemClock.elapsedRealtime() - entry.atMs > maxAgeMs) return null
        // Under a second in is no progress worth carrying over; the normal start is better.
        return entry.positionMs.takeIf { it >= HANDOFF_MIN_POSITION_MS }
    }

    private const val PLAYER_HANDOFF_MAX_AGE_MS = 10_000L
    private const val PREVIEW_HANDOFF_MAX_AGE_MS = 120_000L
    private const val HANDOFF_MIN_POSITION_MS = 1_000L
}

/**
 * One capped-resolution preview shared by Home and Search, never the main player.
 *
 * A preview only starts when [inlinePreviewAllowed] says the device and the user's setting agree,
 * never replays a video that failed or was left to run out, and plays through to the end of the
 * video (no time cut-off, no looping) unless the viewer scrolls away or opens it.
 */
@OptIn(UnstableApi::class)
internal class InlineVideoPreviewController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null

    var activeVideoId by mutableStateOf<String?>(null)
        private set
    var renderedVideoId by mutableStateOf<String?>(null)
        private set

    var positionMs by mutableLongStateOf(0L)
        internal set
    var durationMs by mutableLongStateOf(0L)
        internal set

    /** True while a finger is on the seekbar; the position then follows the finger, not the player. */
    var isScrubbing by mutableStateOf(false)
        private set

    /** Set while the main player owns a video (full screen or mini player); previews never run then. */
    private var blockedState by mutableStateOf(false)
    var blocked: Boolean
        get() = blockedState
        set(value) {
            blockedState = value
            if (value) stop()
        }

    /** The user's "Video previews" setting. */
    var mode by mutableStateOf(InlinePreviewMode.Default)
        private set

    /** Whether the active network is Wi-Fi, pushed in by the provider so Wi-Fi-only mode reacts to it. */
    var onWifi by mutableStateOf(false)
        internal set

    /** What the settings alone allow; the device conditions are checked again each time a preview starts. */
    val settingsAllow: Boolean
        get() =
            when (mode) {
                InlinePreviewMode.OFF -> false
                InlinePreviewMode.WIFI_ONLY -> onWifi
                InlinePreviewMode.ALWAYS -> true
            }

    /** Previews start muted unless the user asked for sound; the speaker icon then overrides it for this session. */
    var muted by mutableStateOf(true)
        private set
    var captionsOn by mutableStateOf(false)
        private set
    var hasCaptions by mutableStateOf(false)
        private set

    private var soundOnStart = false
    private var dataSourceFactory: DataSource.Factory? = null

    /** Videos that would not load this session; asking again only repeats the failure. */
    private val unplayableIds = mutableSetOf<String>()

    /** Failures so far per video; one flaky-network miss must not disable a preview for the whole session. */
    private val failureCounts = mutableMapOf<String, Int>()

    private fun noteFailure(videoId: String) {
        val failures = (failureCounts[videoId] ?: 0) + 1
        failureCounts[videoId] = failures
        if (failures >= MAX_PREVIEW_FAILURES) unplayableIds += videoId
    }

    /** The video that just ran out its time, so the feed does not restart it while it stays on screen. */
    private var finishedVideoId: String? = null
    private var startedAtMs = 0L
    private var lastInteractionMs = 0L
    private var resumeAfterScrub = false
    private var scrubTargetMs = 0L

    fun applySettings(
        newMode: InlinePreviewMode,
        newSoundOnStart: Boolean,
    ) {
        mode = newMode
        if (newSoundOnStart != soundOnStart) {
            soundOnStart = newSoundOnStart
            muted = !newSoundOnStart
            applyAudio()
        }
        if (newMode == InlinePreviewMode.OFF) stop()
    }

    fun toggleMute() {
        muted = !muted
        noteInteraction()
        applyAudio()
    }

    fun toggleCaptions() {
        captionsOn = !captionsOn
        noteInteraction()
        applyCaptions()
    }

    private fun noteInteraction() {
        lastInteractionMs = SystemClock.elapsedRealtime()
    }

    private fun applyAudio() {
        player?.apply {
            // Only take audio focus while audible, so a muted preview never pauses someone's music.
            setAudioAttributes(AudioAttributes.DEFAULT, !muted)
            volume = if (muted) 0f else 1f
        }
    }

    private fun applyCaptions() {
        player?.let {
            it.trackSelectionParameters =
                it.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsOn)
                    .build()
        }
    }

    val activePlayer: ExoPlayer? get() = player.takeIf { activeVideoId != null }

    private val playerListener =
        object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedVideoId = activeVideoId
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // The video ran all the way out: back to the thumbnail, and not restarted while it stays on screen.
                if (playbackState == Player.STATE_ENDED && activeVideoId != null) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                activeVideoId?.let { noteFailure(it) }
                stop()
            }
        }

    private fun currentConditions(): InlinePreviewConditions =
        try {
            val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            InlinePreviewConditions(
                online = NetworkState.isOnline(appContext),
                onWifi = NetworkState.isOnWifi(appContext),
                metered = connectivity?.isActiveNetworkMetered ?: true,
                dataSaver = connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                powerSave = power?.isPowerSaveMode == true,
            )
        } catch (_: SecurityException) {
            InlinePreviewConditions(online = false, onWifi = false, metered = true, dataSaver = true, powerSave = false)
        }

    /** Whether something else (music, a podcast) is already making sound that a preview must not talk over. */
    private fun otherAudioPlaying(): Boolean {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audio?.isMusicActive == true
    }

    /** True when the last [play] did not start for a reason that may pass (device conditions, a transient load failure). */
    var skippedForConditions: Boolean = false
        private set

    suspend fun play(video: Video) {
        stop()
        skippedForConditions = false
        if (blocked || !video.isPreviewable()) {
            android.util.Log.i(LOG_TAG, "skip ${video.id}: blocked=$blocked previewable=${video.isPreviewable()}")
            return
        }
        if (video.id in unplayableIds || video.id == finishedVideoId) {
            android.util.Log.i(LOG_TAG, "skip ${video.id}: already failed or finished this session")
            return
        }
        val conditions = currentConditions()
        if (!inlinePreviewAllowed(mode, conditions)) {
            android.util.Log.i(LOG_TAG, "skip ${video.id}: mode=$mode $conditions")
            skippedForConditions = true
            return
        }
        android.util.Log.i(LOG_TAG, "start ${video.id}: mode=$mode")

        val duckedByOtherAudio = otherAudioPlaying()
        val result =
            try {
                withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        InnerTubeVideoStreamExtractor.extract(video.id)?.takeUnless { it.isLive }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        val format = result?.let { selectInlinePreviewFormat(it.videoFormats) }
        val url = format?.url
        if (result == null || url == null) {
            android.util.Log.w(LOG_TAG, "no playable stream for ${video.id}: extracted=${result != null} format=${format != null}")
            if (result == null) {
                // Timed out, offline or live: often transient, so the feed may try this card again.
                noteFailure(video.id)
                skippedForConditions = video.id !in unplayableIds
            } else {
                // Extracted fine but nothing small enough to preview; asking again changes nothing.
                unplayableIds += video.id
            }
            return
        }
        val audioUrl = selectInlinePreviewAudio(result.audioFormats)?.url
        val captionUrl = selectInlinePreviewCaptionUrl(result.playerResponse)

        if (blocked) return
        val previewPlayer = player ?: createPlayer().also { player = it }
        if (duckedByOtherAudio) muted = true
        renderedVideoId = null
        positionMs = 0L
        durationMs = video.duration.takeIf { it > 0 }?.times(1000L) ?: 0L
        finishedVideoId = null
        startedAtMs = SystemClock.elapsedRealtime()
        noteInteraction()
        activeVideoId = video.id
        hasCaptions = captionUrl != null

        val factory = dataSourceFactory!!
        val sources =
            buildList {
                add(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url)))
                if (audioUrl != null) {
                    add(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(audioUrl)))
                }
                if (captionUrl != null) {
                    val subtitle =
                        MediaItem.SubtitleConfiguration
                            .Builder(android.net.Uri.parse(captionUrl))
                            .setMimeType(MimeTypes.TEXT_VTT)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    // A caption file that fails to load must end quietly, never kill the preview.
                    add(
                        SingleSampleMediaSource
                            .Factory(factory)
                            .setTreatLoadErrorsAsEndOfStream(true)
                            .createMediaSource(subtitle, C.TIME_UNSET),
                    )
                }
            }
        applyAudio()
        applyCaptions()
        // Picking up from the main player: start where the viewer stopped watching it.
        val resumeAtMs =
            PreviewHandoff.takeForPreview(video.id)?.takeIf { at ->
                durationMs <= 0L || at < durationMs - PREVIEW_RESUME_END_GUARD_MS
            }
        val mergedSource = if (sources.size == 1) sources[0] else MergingMediaSource(*sources.toTypedArray())
        if (resumeAtMs != null) {
            positionMs = resumeAtMs
            previewPlayer.setMediaSource(mergedSource, resumeAtMs)
        } else {
            previewPlayer.setMediaSource(mergedSource)
        }
        previewPlayer.prepare()
        previewPlayer.playWhenReady = true
    }

    /** Called when scrolling starts, a new card takes focus, or a feed leaves the resumed screen. */
    fun stop() {
        isScrubbing = false
        resumeAfterScrub = false
        activeVideoId = null
        renderedVideoId = null
        player?.let { previewPlayer ->
            previewPlayer.playWhenReady = false
            previewPlayer.stop()
            previewPlayer.clearMediaItems()
            previewPlayer.setSeekParameters(SeekParameters.EXACT)
        }
    }

    /** Ends the preview and remembers it, so the card goes back to its thumbnail and stays there. */
    fun finish() {
        finishedVideoId = activeVideoId
        stop()
    }

    /**
     * Drops a preview that never produced a frame. A preview that is playing is never cut short by
     * time: it runs to the end of the video. Polled by the surface while it is on screen; never ends
     * a preview mid-scrub.
     */
    fun endIfStale() {
        val id = activeVideoId ?: return
        if (isScrubbing) return
        if (renderedVideoId != id && SystemClock.elapsedRealtime() - startedAtMs > FIRST_FRAME_TIMEOUT_MS) {
            noteFailure(id)
            stop()
        }
    }

    /** One-shot seek to [fraction] of the video, for accessibility actions and taps. */
    fun seekTo(fraction: Float) {
        val length = durationMs
        if (length <= 0L) return
        val target = previewSeekTargetMs(length, fraction)
        positionMs = target
        noteInteraction()
        player?.seekTo(target)
    }

    /** A finger went down on the seekbar: pause, and let seeks snap to keyframes so dragging stays smooth. */
    fun beginScrub() {
        if (activeVideoId == null) return
        isScrubbing = true
        noteInteraction()
        player?.let {
            resumeAfterScrub = it.playWhenReady
            it.playWhenReady = false
            it.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    fun scrubTo(fraction: Float) {
        if (!isScrubbing) return
        val length = durationMs
        if (length <= 0L) return
        val target = previewSeekTargetMs(length, fraction)
        scrubTargetMs = target
        positionMs = target
        noteInteraction()
        player?.seekTo(target)
    }

    /** The finger lifted: settle on the exact frame under it and carry on playing. */
    fun endScrub() {
        if (!isScrubbing) return
        isScrubbing = false
        noteInteraction()
        player?.let {
            it.setSeekParameters(SeekParameters.EXACT)
            it.seekTo(scrubTargetMs)
            it.playWhenReady = resumeAfterScrub
        }
        resumeAfterScrub = false
    }

    /**
     * Called as a preview card is tapped open: remembers how far the preview got so the main player
     * starts there. A no-op unless [videoId] is the preview that is playing.
     */
    fun handOffToPlayer(videoId: String) {
        if (activeVideoId != videoId) return
        val position = if (isScrubbing) scrubTargetMs else (player?.currentPosition ?: positionMs)
        PreviewHandoff.offerToPlayer(videoId, position.coerceAtLeast(0L))
    }

    fun release() {
        stop()
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    private fun createPlayer(): ExoPlayer {
        val dataSourceFactory = DefaultDataSource.Factory(appContext, YouTubeHttpDataSource.Factory())
        this.dataSourceFactory = dataSourceFactory
        val trackSelector =
            DefaultTrackSelector(appContext).apply {
                setParameters(
                    buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setMaxVideoSize(PREVIEW_MAX_WIDTH, PREVIEW_MAX_HEIGHT)
                        .build(),
                )
            }

        return ExoPlayer
            .Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                volume = if (muted) 0f else 1f
                addListener(playerListener)
            }
    }
}

/** Only ordinary, already-published videos preview: not live, upcoming, Shorts or members-only. */
internal fun Video.isPreviewable(): Boolean =
    id.isNotBlank() && !isLive && !isUpcoming && !isShort && membersOnlyText == null

/**
 * Where [fraction] of a [lengthMs]-long video lands. Held back from the very end so dragging all the
 * way right does not wrap the looping preview straight back to the start.
 */
internal fun previewSeekTargetMs(
    lengthMs: Long,
    fraction: Float,
): Long {
    if (lengthMs <= 0L) return 0L
    val clamped = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val latest = (lengthMs - PREVIEW_SEEK_END_GUARD_MS).coerceAtLeast(0L)
    return (lengthMs * clamped).toLong().coerceIn(0L, latest)
}

/**
 * Maps a touch at [x] on a bar [widthPx] wide to a position 0..1. The track is inset by [insetPx] on
 * both sides so the thumb at either end stays inside the touch area.
 */
internal fun previewSeekFractionForX(
    x: Float,
    widthPx: Float,
    insetPx: Float,
): Float {
    val track = widthPx - 2f * insetPx
    if (track <= 0f) return 0f
    return ((x - insetPx) / track).coerceIn(0f, 1f)
}

/** Prefer a direct MP4 video stream at or below 480p; never select audio or live playback. */
internal fun selectInlinePreviewFormat(formats: List<PlayerResponse.StreamingData.Format>): PlayerResponse.StreamingData.Format? {
    val directVideo =
        formats.filter { format ->
            format.width != null &&
                format.height != null &&
                format.mimeType.startsWith("video/", ignoreCase = true) &&
                format.url?.startsWith("https://", ignoreCase = true) == true
        }
    val candidates =
        directVideo
            .filter { it.mimeType.startsWith("video/mp4", ignoreCase = true) }
            .ifEmpty { directVideo }

    return candidates
        .filter {
            (it.height ?: Int.MAX_VALUE) <= PREVIEW_MAX_HEIGHT &&
                (it.width ?: Int.MAX_VALUE) <= PREVIEW_MAX_WIDTH
        }.maxByOrNull { it.height ?: 0 }
}

/** Smallest default-language AAC track: previews only need it to be audible, not high fidelity. */
internal fun selectInlinePreviewAudio(formats: List<PlayerResponse.StreamingData.Format>): PlayerResponse.StreamingData.Format? =
    formats
        .filter {
            it.mimeType.startsWith("audio/mp4", ignoreCase = true) &&
                it.url?.startsWith("https://", ignoreCase = true) == true &&
                !it.isDynamicRangeCompressed &&
                it.audioTrack?.audioIsDefault != false
        }.minByOrNull { it.bitrate }

/** The caption track in the device language (else the first one), as WebVTT. */
internal fun selectInlinePreviewCaptionUrl(response: PlayerResponse): String? {
    val tracks =
        response.captions
            ?.playerCaptionsTracklistRenderer
            ?.captionTracks
            .orEmpty()
            .filter { !it.baseUrl.isNullOrBlank() }
    val device =
        java.util.Locale
            .getDefault()
            .language
    val track = tracks.firstOrNull { it.languageCode?.startsWith(device) == true } ?: tracks.firstOrNull()
    val base = track?.baseUrl ?: return null
    return if (base.contains("fmt=")) base.replace(Regex("fmt=[^&]*"), "fmt=vtt") else "$base&fmt=vtt"
}

@OptIn(UnstableApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BoxScope.InlineVideoPreviewSurface(
    controller: InlineVideoPreviewController,
    videoId: String,
) {
    val player = controller.activePlayer ?: return
    val context = LocalContext.current
    val hasFrame = controller.renderedVideoId == videoId
    val previewAlpha by
        animateFloatAsState(
            targetValue = if (hasFrame) 1f else 0f,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "inlinePreviewFrame",
        )
    LaunchedEffect(player, videoId) {
        while (controller.activeVideoId == videoId) {
            // While a finger is on the seekbar the thumb follows it; the player is still catching up.
            if (!controller.isScrubbing) {
                controller.positionMs = player.currentPosition.coerceAtLeast(0L)
            }
            val reported = player.duration
            if (reported > 0L) controller.durationMs = reported
            controller.endIfStale()
            delay(PREVIEW_PROGRESS_UPDATE_MS)
        }
    }
    val playerView =
        remember(context) {
            (LayoutInflater.from(context).inflate(R.layout.video_player_view, null) as PlayerView).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_OFF
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        }

    AndroidView(
        factory = { playerView },
        update = {
            it.player = player
            it.subtitleView?.visibility =
                if (controller.captionsOn) android.view.View.VISIBLE else android.view.View.GONE
        },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = previewAlpha },
    )

    Column(
        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        PreviewIconButton(
            onClick = controller::toggleMute,
            imageVector = if (controller.muted) Icons.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription =
                stringResource(if (controller.muted) R.string.video_preview_muted else R.string.video_preview_sound_on),
        )
        if (controller.hasCaptions) {
            PreviewIconButton(
                onClick = controller::toggleCaptions,
                imageVector = if (controller.captionsOn) Icons.Rounded.ClosedCaption else Icons.Outlined.ClosedCaption,
                contentDescription = stringResource(R.string.captions),
            )
        }
    }
}

/** A round control over the video: a dark scrim keeps the white icon legible on any frame. */
@Composable
private fun PreviewIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
) {
    Box(
        modifier =
            Modifier
                .size(PreviewButtonSize)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * YouTube-style feed preview seekbar: red progress with a dot, elapsed and total time either side.
 *
 * The whole bar is a [SeekbarTouchHeight] tall touch target, though only a thin line is drawn. A tap
 * jumps there and a drag scrubs, pausing the preview until the finger lifts. The gesture consumes the
 * touch from first press to release so the card behind it neither opens the video nor scrolls the feed.
 */
@Composable
internal fun InlineVideoPreviewSeekbar(
    controller: InlineVideoPreviewController,
    modifier: Modifier = Modifier,
) {
    val position = controller.positionMs
    val duration = controller.durationMs
    val scrubbing = controller.isScrubbing
    val seekable = duration > 0L
    val progress = if (seekable) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val grow by animateFloatAsState(
        targetValue = if (scrubbing) 1f else 0f,
        label = "inlinePreviewSeekbarGrow",
    )
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val seekbarDescription = stringResource(R.string.video_preview_seekbar)

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = formatDurationMillis(position.coerceAtLeast(0L)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 40.dp),
            textAlign = TextAlign.Start,
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(SeekbarTouchHeight)
                    .pointerInput(controller) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val widthPx = size.width.toFloat()
                            val insetPx = SeekbarInset.toPx()
                            // Nothing to seek in yet: leave the touch alone so the card still opens.
                            if (widthPx <= 2f * insetPx || controller.durationMs <= 0L) return@awaitEachGesture
                            down.consume()
                            controller.beginScrub()
                            try {
                                controller.scrubTo(previewSeekFractionForX(down.position.x, widthPx, insetPx))
                                drag(down.id) { change ->
                                    change.consume()
                                    controller.scrubTo(previewSeekFractionForX(change.position.x, widthPx, insetPx))
                                }
                                // The release is part of the gesture too; unconsumed, it would tap the card.
                                currentEvent.changes.forEach { it.consume() }
                            } finally {
                                controller.endScrub()
                            }
                        }
                    }.semantics {
                        contentDescription = seekbarDescription
                        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                        if (seekable) {
                            setProgress { target ->
                                controller.seekTo(target)
                                true
                            }
                        }
                    },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val insetPx = SeekbarInset.toPx()
                val trackHeight = lerp(3.dp.toPx(), 6.dp.toPx(), grow)
                val thumbRadius = lerp(5.dp.toPx(), 9.dp.toPx(), grow)
                val centerY = size.height / 2f
                val trackWidth = (size.width - 2f * insetPx).coerceAtLeast(0f)
                val thumbX = insetPx + trackWidth * progress
                val corner = CornerRadius(trackHeight / 2f)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(insetPx, centerY - trackHeight / 2f),
                    size = Size(trackWidth, trackHeight),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = PreviewRed,
                    topLeft = Offset(insetPx, centerY - trackHeight / 2f),
                    size = Size(thumbX - insetPx, trackHeight),
                    cornerRadius = corner,
                )
                if (seekable) {
                    drawCircle(color = PreviewRed, radius = thumbRadius, center = Offset(thumbX, centerY))
                }
            }
        }
        Text(
            text = if (seekable) formatDurationMillis(duration) else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 40.dp),
            textAlign = TextAlign.End,
        )
    }
}

private val PreviewRed = Color(0xFFFF0033)

/** Tall enough to be an easy thumb target, as Material asks of any touch control. */
private val SeekbarTouchHeight = 48.dp

/** Space either side of the track so the thumb, at either end, is still fully inside the touch area. */
private val SeekbarInset: Dp = 10.dp
private val PreviewButtonSize = 40.dp

/** How long a feed card must be the most visible one before its preview starts. */
internal const val INLINE_PREVIEW_DWELL_MS = 1_200L

/** A preview nobody touches runs this long, then returns the card to its thumbnail. */

/** A preview that shows no frame in this long is not going to; give up on it. */
private const val FIRST_FRAME_TIMEOUT_MS = 12_000L
private const val LOG_TAG = "InlinePreview"
private const val MAX_PREVIEW_FAILURES = 3
private const val EXTRACT_TIMEOUT_MS = 10_000L
private const val PREVIEW_SEEK_END_GUARD_MS = 500L

/** A video watched to within this of its end previews from the start rather than its last seconds. */
private const val PREVIEW_RESUME_END_GUARD_MS = 3_000L

private const val PREVIEW_MAX_HEIGHT = 480
private const val PREVIEW_MAX_WIDTH = 854
private const val PREVIEW_PROGRESS_UPDATE_MS = 250L
