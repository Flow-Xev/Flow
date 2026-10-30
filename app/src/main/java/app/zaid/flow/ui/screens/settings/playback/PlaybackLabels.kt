package app.zaid.flow.ui.screens.settings.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.zaid.flow.R
import app.zaid.flow.data.local.ShortsPreloadConstrainedMode
import app.zaid.flow.player.stream.CaptionTrackResolver
import app.zaid.flow.ui.components.shared.playbackSpeedLabel
import app.zaid.flow.utils.LanguageCatalog

@Composable
internal fun countdownLabel(seconds: Int): String =
    if (seconds <= 0) {
        stringResource(R.string.player_settings_autoplay_countdown_none)
    } else {
        pluralStringResource(R.plurals.player_settings_autoplay_countdown_seconds_template, seconds, seconds)
    }

@Composable
internal fun longPressSpeedLabel(speed: Float): String =
    if (speed <= 0f) stringResource(R.string.player_appearance_long_press_speed_disabled) else playbackSpeedLabel(speed)

@Composable
internal fun doubleTapSeekLabel(seconds: Int): String =
    if (seconds <= 0) {
        stringResource(R.string.player_settings_double_tap_seek_off)
    } else {
        pluralStringResource(R.plurals.player_settings_autoplay_countdown_seconds_template, seconds, seconds)
    }

@Composable
internal fun audioLanguageLabel(code: String): String =
    if (code == PlaybackSettingsViewModel.ORIGINAL_AUDIO) {
        stringResource(R.string.player_settings_audio_original)
    } else {
        LanguageCatalog.displayName(code)
    }

@Composable
internal fun subtitleLanguageLabel(code: String): String =
    if (code == CaptionTrackResolver.NO_PREFERRED_LANGUAGE) {
        stringResource(R.string.player_settings_subtitle_language_none)
    } else {
        LanguageCatalog.displayName(code)
    }

@Composable
internal fun shortsModeLabel(
    mode: String,
    seconds: Int,
): String =
    when (mode) {
        ShortsPlaybackModes.AUTO_NEXT -> {
            stringResource(R.string.player_settings_shorts_playback_mode_auto_next)
        }

        ShortsPlaybackModes.AUTO_INTERVAL -> {
            pluralStringResource(R.plurals.player_settings_shorts_playback_mode_auto_interval_summary, seconds, seconds)
        }

        else -> {
            stringResource(R.string.player_settings_shorts_playback_mode_loop)
        }
    }

@Composable
internal fun shortsPreloadAheadLabel(ahead: Int): String =
    if (ahead <= 0) {
        stringResource(R.string.shorts_preload_ahead_none)
    } else {
        pluralStringResource(R.plurals.shorts_preload_ahead_summary, ahead, ahead)
    }

@Composable
internal fun shortsPreloadModeLabel(mode: ShortsPreloadConstrainedMode): String =
    stringResource(
        when (mode) {
            ShortsPreloadConstrainedMode.SAME -> R.string.shorts_preload_mode_same
            ShortsPreloadConstrainedMode.LIGHT -> R.string.shorts_preload_mode_light
            ShortsPreloadConstrainedMode.OFF -> R.string.shorts_preload_mode_off
        },
    )
