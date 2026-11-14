package app.zaid.flow.ui.tv.music

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.zaid.flow.R
import app.zaid.flow.data.music.model.MusicTrack
import app.zaid.flow.ui.components.musicplayer.lyrics.InlineLyricsPanel
import app.zaid.flow.ui.screens.music.MusicPlayerUiState
import app.zaid.flow.ui.tv.components.TvSidePanel
import app.zaid.flow.ui.tv.focus.tvInitialFocus

/**
 * Lyrics side panel hosting the mobile lyrics canvas ([InlineLyricsPanel]):
 * same synced word-level karaoke rendering as the phone player. The canvas
 * drives its own position from EnhancedMusicPlayerManager, so it needs no
 * TV-side position loop.
 */
@Composable
fun BoxScope.TvLyricsPanel(
    visible: Boolean,
    track: MusicTrack?,
    uiState: MusicPlayerUiState,
    positionProvider: () -> Long,
    onEnsureLyrics: (MusicTrack) -> Unit,
    onSeekTo: (Long) -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(visible, track?.videoId) {
        if (!visible) return@LaunchedEffect
        track?.let(onEnsureLyrics)
    }

    TvSidePanel(
        visible = visible,
        title = stringResource(R.string.tv_music_lyrics),
        onClose = onClose,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .tvInitialFocus()
                    .focusable(),
        ) {
            InlineLyricsPanel(
                lyrics = uiState.lyrics,
                syncedLyrics = uiState.syncedLyrics,
                positionProvider = positionProvider,
                isLoading = uiState.isLyricsLoading,
                accentColor = MaterialTheme.colorScheme.primary,
                onSeekTo = onSeekTo,
                providerName = uiState.lyricsProviderName,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
