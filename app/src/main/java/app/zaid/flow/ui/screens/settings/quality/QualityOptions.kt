package app.zaid.flow.ui.screens.settings.quality

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.zaid.flow.R
import app.zaid.flow.data.local.MusicAudioQuality
import app.zaid.flow.data.local.ThumbnailQuality
import app.zaid.flow.data.local.VideoCodec
import app.zaid.flow.data.local.VideoQuality
import app.zaid.flow.player.quality.AutoQualityBounds

/** Resolutions offered for regular video and Shorts, best first. */
internal val VideoQualities =
    listOf(
        VideoQuality.AUTO,
        VideoQuality.Q_2160P,
        VideoQuality.Q_1440P,
        VideoQuality.Q_1080P,
        VideoQuality.Q_720P,
        VideoQuality.Q_480P,
        VideoQuality.Q_360P,
        VideoQuality.Q_240P,
        VideoQuality.Q_144P,
    )

/** Heights a quality ceiling can be set to, best first. */
private val AutoCeilingHeights = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

/**
 * Heights a quality floor can be set to, best first. Without a floor Auto stops at 480p; a lower
 * floor lets it drop that far on a slow connection.
 */
private val AutoFloorHeights = listOf(2160, 1440, 1080, 720, 360, 240, 144)

/**
 * The floors on offer given the ceiling in force ([AutoQualityBounds.NONE] when there is none): the
 * off choice, then every height up to the ceiling. The current choice stays listed even if a
 * lowered ceiling has put it out of range, so the dialog never opens with nothing selected.
 */
internal fun autoFloorChoices(
    ceiling: Int,
    current: Int,
): List<Int> =
    listOf(AutoQualityBounds.NONE) +
        AutoFloorHeights.filter { (ceiling == AutoQualityBounds.NONE || it <= ceiling) || it == current }

/** The ceilings on offer given the floor in force: no limit, then every height from the floor up. */
internal fun autoCeilingChoices(
    floor: Int,
    current: Int,
): List<Int> =
    listOf(AutoQualityBounds.NONE) +
        AutoCeilingHeights.filter { (floor == AutoQualityBounds.NONE || it >= floor) || it == current }

@StringRes
internal fun autoFloorLabelRes(height: Int): Int =
    if (height == AutoQualityBounds.NONE) R.string.auto_quality_floor_off else autoQualityHeightLabel(height)

@StringRes
internal fun autoCeilingLabelRes(height: Int): Int =
    if (height == AutoQualityBounds.NONE) R.string.auto_quality_ceiling_off else autoQualityHeightLabel(height)

@StringRes
internal fun autoQualityHeightLabel(height: Int): Int =
    when (height) {
        2160 -> R.string.quality_2160p_4k
        1440 -> R.string.quality_1440p_qhd
        1080 -> R.string.quality_1080p_full_hd
        720 -> R.string.quality_720p_hd
        480 -> R.string.quality_480p
        360 -> R.string.quality_360p
        240 -> R.string.quality_240p
        144 -> R.string.quality_144p
        else -> R.string.quality_auto
    }

internal val MusicQualities = MusicAudioQuality.entries.toList()

internal val ThumbnailQualities = ThumbnailQuality.entries.toList()

@StringRes
internal fun thumbnailQualityLabel(quality: ThumbnailQuality): Int =
    when (quality) {
        ThumbnailQuality.HIGH -> R.string.thumbnail_quality_high
        ThumbnailQuality.MEDIUM -> R.string.thumbnail_quality_medium
        ThumbnailQuality.LOW -> R.string.thumbnail_quality_low
        ThumbnailQuality.OFF -> R.string.thumbnail_quality_off
    }

@StringRes
internal fun thumbnailQualityDescription(quality: ThumbnailQuality): Int =
    when (quality) {
        ThumbnailQuality.HIGH -> R.string.thumbnail_quality_high_desc
        ThumbnailQuality.MEDIUM -> R.string.thumbnail_quality_medium_desc
        ThumbnailQuality.LOW -> R.string.thumbnail_quality_low_desc
        ThumbnailQuality.OFF -> R.string.thumbnail_quality_off_desc
    }

@StringRes
internal fun videoQualityLabel(quality: VideoQuality): Int =
    when (quality) {
        VideoQuality.AUTO -> R.string.quality_auto
        VideoQuality.Q_144P -> R.string.quality_144p
        VideoQuality.Q_240P -> R.string.quality_240p
        VideoQuality.Q_360P -> R.string.quality_360p
        VideoQuality.Q_480P -> R.string.quality_480p
        VideoQuality.Q_720P -> R.string.quality_720p_hd
        VideoQuality.Q_1080P -> R.string.quality_1080p_full_hd
        VideoQuality.Q_1440P -> R.string.quality_1440p_qhd
        VideoQuality.Q_2160P -> R.string.quality_2160p_4k
    }

@StringRes
internal fun musicQualityLabel(quality: MusicAudioQuality): Int =
    when (quality) {
        MusicAudioQuality.AUTO -> R.string.music_quality_auto
        MusicAudioQuality.HIGH -> R.string.music_quality_high
        MusicAudioQuality.MEDIUM -> R.string.music_quality_medium
        MusicAudioQuality.LOW -> R.string.music_quality_low
    }

/** The fallback choices for [preferred]: every codec but the preferred one. */
internal fun fallbackCodecs(preferred: VideoCodec): List<VideoCodec> = VideoCodec.entries.filter { it != preferred }

/** A fallback equal to the new preferred codec is meaningless, so it drops back to automatic. */
internal fun fallbackAfterPreferredChange(
    preferred: VideoCodec,
    fallback: VideoCodec,
): VideoCodec = if (fallback == preferred) VideoCodec.AUTO else fallback

/** Codec names are product names and stay as they are; only "automatic" is translated. */
@Composable
internal fun codecLabel(codec: VideoCodec): String =
    if (codec == VideoCodec.AUTO) stringResource(R.string.player_settings_video_codec_fallback_auto) else codec.label
