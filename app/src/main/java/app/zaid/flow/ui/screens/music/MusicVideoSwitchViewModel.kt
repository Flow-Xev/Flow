package app.zaid.flow.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.player.musicvideo.MusicVideoSwitch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The music player's Song/Video switch, shown only when it is turned on in Settings. */
@HiltViewModel
class MusicVideoSwitchViewModel
    @Inject
    constructor(
        private val videoSwitch: MusicVideoSwitch,
        preferences: PlayerPreferences,
    ) : ViewModel() {
        val isEnabled: StateFlow<Boolean> =
            preferences.musicVideoSwitch.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        val videoMode: StateFlow<Boolean> = videoSwitch.videoMode

        val isLoading: StateFlow<Boolean> = videoSwitch.isLoading

        fun select(video: Boolean) = videoSwitch.select(video)

        fun listenId(videoId: String): String = videoSwitch.listenId(videoId)

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
