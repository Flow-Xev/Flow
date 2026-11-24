package app.zaid.flow.ui.screens.player.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.zaid.flow.player.lounge.LoungeCastManager
import app.zaid.flow.player.lounge.LoungeTv
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.data.model.Video
import app.zaid.flow.player.EnhancedPlayerManager
import app.zaid.flow.player.dlna.DlnaCastManager
import app.zaid.flow.player.state.EnhancedPlayerState
import app.zaid.flow.ui.components.shared.MediaDownloadDialog
import app.zaid.flow.ui.components.shared.MediaDownloadDialogCompact
import app.zaid.flow.ui.components.videoplayer.DlnaDevicePickerDialog
import app.zaid.flow.ui.screens.player.VideoPlayerViewModel
import app.zaid.flow.ui.screens.player.state.PlayerScreenState
import app.zaid.flow.ui.screens.player.state.PlayerSheet
import app.zaid.flow.ui.screens.player.state.VideoPlayerPreferencesState
import app.zaid.flow.ui.screens.player.state.VideoPlayerUiState
import kotlinx.coroutines.launch

@Composable
internal fun PlayerDialogsContainer(
    screenState: PlayerScreenState,
    playerState: EnhancedPlayerState,
    uiState: VideoPlayerUiState,
    video: Video,
    viewModel: VideoPlayerViewModel,
    prefs: VideoPlayerPreferencesState,
    hostedInSidePanel: Boolean = false,
    mediaSheetExpandedHeight: Dp? = null,
    mediaSheetCollapsedHeight: Dp = 0.dp,
    onMediaSheetProgressChange: (Float) -> Unit = {},
) {
    val playerPreferences = prefs.preferences
    val coroutineScope = rememberCoroutineScope()

    // Download Quality Dialog
    if (screenState.activeSheet == PlayerSheet.Download) {
        when (prefs.downloadDialogStyle) {
            app.zaid.flow.data.local.DownloadDialogStyle.COMPACT -> {
                MediaDownloadDialogCompact(
                    streamSizes = uiState.streamSizes,
                    innerTubeVideoFormats = uiState.innerTubeVideoFormats,
                    innerTubeAudioFormats = uiState.innerTubeAudioFormats,
                    video = video,
                    currentPlayingHeight = playerState.effectiveQuality,
                    subtitles = playerState.availableSubtitles,
                    onDismiss = { screenState.closeSheet() },
                )
            }

            app.zaid.flow.data.local.DownloadDialogStyle.FULL -> {
                MediaDownloadDialog(
                    streamSizes = uiState.streamSizes,
                    innerTubeVideoFormats = uiState.innerTubeVideoFormats,
                    innerTubeAudioFormats = uiState.innerTubeAudioFormats,
                    video = video,
                    subtitles = playerState.availableSubtitles,
                    onDismiss = { screenState.closeSheet() },
                )
            }

            null -> { }
        }
    }

    if (screenState.isSettingsOpen && !hostedInSidePanel) {
        PlayerSettingsSheetHost(
            screenState = screenState,
            playerState = playerState,
            uiState = uiState,
            viewModel = viewModel,
            playerPreferences = playerPreferences,
            scope = coroutineScope,
            rememberPlaybackSpeed = prefs.rememberPlaybackSpeed,
            ambientModeEnabled = prefs.ambientModeEnabled,
            groupedQualitySelectorEnabled = prefs.groupedQualitySelectorEnabled,
            rememberSubtitleLanguage = { language ->
                coroutineScope.launch { playerPreferences.setPreferredSubtitleLanguage(language) }
            },
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            collapsedHeight = mediaSheetCollapsedHeight,
            pipAspectRatio = null,
            onSheetProgressChange = onMediaSheetProgressChange,
            onDismiss = { screenState.closeSheet() },
        )
    }

    if (screenState.activeSheet == PlayerSheet.Dlna) {
        val dlnaDevices by DlnaCastManager.devices.collectAsStateWithLifecycle()
        val isDlnaDiscovering by DlnaCastManager.isDiscovering.collectAsStateWithLifecycle()
        val loungeContext = LocalContext.current
        val loungeTvs by LoungeCastManager.savedTvs.collectAsStateWithLifecycle()
        val loungeConnected by LoungeCastManager.connected.collectAsStateWithLifecycle()
        var loungeBusy by remember { mutableStateOf(false) }
        var loungeError by remember { mutableStateOf<String?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            LoungeCastManager.load(loungeContext)
            // Like YouTube: reopen the TV used last without asking again.
            if (LoungeCastManager.connected.value == null) {
                loungeBusy = true
                LoungeCastManager.resume(loungeContext)
                loungeBusy = false
            }
        }

        // Casts the open video to [tv] the way YouTube's own Cast button does, then pauses it here.
        fun castToTv(tv: LoungeTv) {
            loungeBusy = true
            loungeError = null
            coroutineScope.launch {
                val player = EnhancedPlayerManager.getInstance().getPlayer()
                val position = player?.currentPosition ?: 0L
                val result = LoungeCastManager.cast(loungeContext, tv, video.id, position)
                loungeBusy = false
                result
                    .onSuccess { player?.pause() }
                    .onFailure { loungeError = it.message ?: "Could not cast" }
            }
        }
        val remoteTv = loungeConnected
        if (remoteTv != null) {
            // Linked to a TV: the cast button opens the remote.
            app.zaid.flow.ui.components.videoplayer.TvRemoteSheet(
                tv = remoteTv,
                videoId = video.id,
                videoTitle = video.title,
                channelName = video.channelName,
                onStop = {
                    coroutineScope.launch { LoungeCastManager.stop() }
                    screenState.closeSheet()
                },
                onDismiss = { screenState.closeSheet() },
            )
        } else DlnaDevicePickerDialog(
            loungeTvs = loungeTvs,
            loungeConnected = loungeConnected,
            loungeBusy = loungeBusy,
            loungeError = loungeError,
            onLoungeTv = { castToTv(it) },
            onLoungeForget = { LoungeCastManager.forget(loungeContext, it) },
            onLoungePair = { code ->
                loungeBusy = true
                loungeError = null
                coroutineScope.launch {
                    val paired = LoungeCastManager.pair(loungeContext, code)
                    loungeBusy = false
                    paired.onSuccess { castToTv(it) }.onFailure { loungeError = it.message }
                }
            },
            onLoungePlay = { coroutineScope.launch { LoungeCastManager.play() } },
            onLoungePause = { coroutineScope.launch { LoungeCastManager.pause() } },
            devices = dlnaDevices,
            isDiscovering = isDlnaDiscovering,
            isCasting = DlnaCastManager.isCasting,
            videoTitle = video.title,
            onDeviceSelected = { device ->
                val currentPlayerUrl =
                    EnhancedPlayerManager
                        .getInstance()
                        .getPlayer()
                        ?.currentMediaItem
                        ?.localConfiguration
                        ?.uri
                        ?.toString()
                DlnaCastManager.castStreamInfo(
                    device = device,
                    title = video.title,
                    streamInfo = null,
                    currentPlayerUrl = currentPlayerUrl,
                )
                screenState.closeSheet()
            },
            onStopCasting = {
                DlnaCastManager.disconnect()
                coroutineScope.launch { LoungeCastManager.stop() }
                screenState.closeSheet()
            },
            onDismiss = {
                DlnaCastManager.stopDiscovery()
                screenState.closeSheet()
            },
        )
    }
}
