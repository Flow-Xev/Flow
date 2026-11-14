package app.zaid.flow.ui.components.shorts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.zaid.flow.data.local.DownloadDialogStyle
import app.zaid.flow.data.model.ShortVideo
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.model.toVideo
import app.zaid.flow.data.shorts.ShortAudioTrack
import app.zaid.flow.data.shorts.ShortVideoQuality
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.ui.components.shared.MediaDownloadDialog
import app.zaid.flow.ui.components.shared.MediaDownloadDialogCompact
import app.zaid.flow.ui.screens.shorts.ShortsViewModel

internal enum class ShortsSettingsPage {
    Main,
    Quality,
    Speed,
    Audio,
}

/**
 * The settings sheet's state, owned by the screen rather than a page: the sheet is drawn beside
 * the pager, not inside it, so a drag on the sheet never reaches the pager as a nested scroll.
 */
@Stable
internal class ShortsSettingsSheetState {
    var targetIndex by mutableIntStateOf(-1)
        private set
    var targetId by mutableStateOf<String?>(null)
        private set
    var page by mutableStateOf(ShortsSettingsPage.Main)
    var isLoadingStreams by mutableStateOf(false)
    var availableQualities by mutableStateOf<List<ShortVideoQuality>>(emptyList())
    var availableAudioTracks by mutableStateOf<List<ShortAudioTrack>>(emptyList())
    var selectedAudioIndex by mutableIntStateOf(0)
    var selectedQualityUrl by mutableStateOf<String?>(null)
    var selectedQualityHeight by mutableIntStateOf(-1)

    /** The reel is playing Auto's multi-quality manifest rather than one pinned quality. */
    var isAutoActive by mutableStateOf(false)

    /** The resolution Auto is playing at this moment, e.g. "1080p". */
    var autoDetail by mutableStateOf<String?>(null)
    var downloadVideo by mutableStateOf<Video?>(null)
    var streamSizes by mutableStateOf<Map<String, Long>>(emptyMap())
    var videoFormats by mutableStateOf<List<PlayerResponse.StreamingData.Format>>(emptyList())
    var audioFormats by mutableStateOf<List<PlayerResponse.StreamingData.Format>>(emptyList())

    val isOpen: Boolean
        get() = targetId != null

    fun open(
        index: Int,
        videoId: String,
    ) {
        if (targetId != videoId) {
            availableQualities = emptyList()
            availableAudioTracks = emptyList()
            selectedAudioIndex = 0
            selectedQualityUrl = null
            selectedQualityHeight = -1
            isAutoActive = false
            autoDetail = null
        }
        targetIndex = index
        targetId = videoId
        page = ShortsSettingsPage.Main
    }

    fun close() {
        targetId = null
        targetIndex = -1
        page = ShortsSettingsPage.Main
    }

    /** Runs in the screen's scope: the sheet that asked for it has already animated out. */
    suspend fun prepareDownload(
        short: ShortVideo,
        viewModel: ShortsViewModel,
    ) {
        val (video, audio) = viewModel.downloadFormats(short.id)
        videoFormats = video
        audioFormats = audio
        if (video.isEmpty()) return
        streamSizes = viewModel.streamSizesFor(short.id, video, audio)
        downloadVideo = short.toVideo()
    }
}

@Composable
internal fun ShortsDownloadDialog(
    state: ShortsSettingsSheetState,
    style: DownloadDialogStyle,
) {
    val video = state.downloadVideo ?: return
    if (state.videoFormats.isEmpty()) return
    val onDismiss = { state.downloadVideo = null }
    if (style == DownloadDialogStyle.COMPACT) {
        MediaDownloadDialogCompact(
            streamSizes = state.streamSizes,
            innerTubeVideoFormats = state.videoFormats,
            innerTubeAudioFormats = state.audioFormats,
            video = video,
            onDismiss = onDismiss,
        )
    } else {
        MediaDownloadDialog(
            streamSizes = state.streamSizes,
            innerTubeVideoFormats = state.videoFormats,
            innerTubeAudioFormats = state.audioFormats,
            video = video,
            onDismiss = onDismiss,
        )
    }
}
