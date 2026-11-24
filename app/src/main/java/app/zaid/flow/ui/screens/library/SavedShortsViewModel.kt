package app.zaid.flow.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zaid.flow.data.local.PlaylistRepository
import app.zaid.flow.data.model.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val SHARING_TIMEOUT_MS = 5_000L

@HiltViewModel
class SavedShortsViewModel
    @Inject
    constructor(
        playlistRepository: PlaylistRepository,
    ) : ViewModel() {
        val savedShorts: StateFlow<List<Video>> =
            playlistRepository
                .getVideoOnlySavedShortsFlow()
                .distinctUntilChanged()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARING_TIMEOUT_MS),
                    emptyList(),
                )
    }
