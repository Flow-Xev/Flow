package app.zaid.flow.ui.screens.player.stage

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Stable
import app.zaid.flow.data.model.Video
import app.zaid.flow.player.state.EnhancedPlayerState
import app.zaid.flow.ui.components.videoplayer.PlayerDraggableState
import app.zaid.flow.ui.screens.player.VideoPlayerViewModel
import app.zaid.flow.ui.screens.player.effects.AudioSystemInfo
import app.zaid.flow.ui.screens.player.effects.PipPreferences
import app.zaid.flow.ui.screens.player.state.PlayerScreenState
import app.zaid.flow.ui.screens.player.state.VideoPlayerPreferencesState
import app.zaid.flow.ui.screens.player.state.VideoPlayerUiState
import kotlinx.coroutines.CoroutineScope

/**
 * Everything the player's stage surfaces read about the session they are drawing. Built fresh by
 * the host on each composition, so the values it carries are exactly the ones the host itself
 * observed that frame.
 */
@Stable
internal class VideoPlayerStageSession(
    val video: Video,
    val context: Context,
    val activity: ComponentActivity,
    val scope: CoroutineScope,
    val screenState: PlayerScreenState,
    val playerState: EnhancedPlayerState,
    val uiState: VideoPlayerUiState,
    val viewModel: VideoPlayerViewModel,
    val sheetState: PlayerDraggableState,
    val prefs: VideoPlayerPreferencesState,
    val audioSystemInfo: AudioSystemInfo,
    val pipPreferences: PipPreferences,
)
