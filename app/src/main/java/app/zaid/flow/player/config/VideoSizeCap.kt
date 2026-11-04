package app.zaid.flow.player.config

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.zaid.flow.data.local.DataSaver

/** The largest frame the track selector may pick: never above 4K, lower on a small app heap. */
data class VideoSizeCap(
    val maxWidth: Int,
    val maxHeight: Int,
) {
    /**
     * This cap lowered so that nothing taller than [ceilingHeight]p fits. Both sides stay within a
     * 16:9 frame's long side, so the limit holds upright or sideways, as Data Saver's does. A cap
     * already below that is left as it is, and [Int.MAX_VALUE] means no ceiling.
     */
    fun withCeiling(ceilingHeight: Int): VideoSizeCap {
        if (ceilingHeight >= maxHeight) return this
        val longSide = (ceilingHeight.toLong() * 16 / 9).toInt()
        return VideoSizeCap(maxWidth = minOf(maxWidth, longSide), maxHeight = minOf(maxHeight, longSide))
    }

    companion object {
        val UHD = VideoSizeCap(PlayerConfig.MAX_VIDEO_WIDTH, PlayerConfig.MAX_VIDEO_HEIGHT)

        fun forHeap(
            memoryClassMb: Int,
            isLowRamDevice: Boolean,
        ): VideoSizeCap =
            when {
                isLowRamDevice || memoryClassMb <= 256 -> VideoSizeCap(1920, 1080)
                memoryClassMb <= 384 -> VideoSizeCap(2560, 1440)
                else -> UHD
            }

        fun forDevice(context: Context): VideoSizeCap {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val cap =
                forHeap(
                    memoryClassMb = activityManager?.memoryClass ?: 256,
                    isLowRamDevice = activityManager?.isLowRamDevice == true,
                )
            return if (DataSaver.enabled) cap.forDataSaver() else cap
        }

        /** Both sides stay within 1280, so 720p fits upright or sideways while 1080p and up do not. */
        private fun VideoSizeCap.forDataSaver() =
            VideoSizeCap(
                maxWidth = minOf(maxWidth, DATA_SAVER_LONG_SIDE),
                maxHeight = minOf(maxHeight, DATA_SAVER_LONG_SIDE),
            )

        private const val DATA_SAVER_LONG_SIDE = 1280
    }
}

/**
 * Drops every per-quality size constraint and puts the device limits back. Clearing alone would also
 * lift the heap cap, and a local file clears the screen viewport, so an automatic pick could climb to
 * a size the device can neither decode comfortably nor show.
 */
@UnstableApi
fun DefaultTrackSelector.Parameters.Builder.resetVideoSizeTo(cap: VideoSizeCap): DefaultTrackSelector.Parameters.Builder =
    apply {
        clearVideoSizeConstraints()
        setMinVideoSize(0, 0)
        setMaxVideoSize(cap.maxWidth, cap.maxHeight)
        setViewportSizeToPhysicalDisplaySize(true)
    }

/** Automatic quality: any size up to the device cap, no forced bitrate, the default codec order. */
@UnstableApi
internal fun adaptiveTrackSelectorDefaults(
    builder: DefaultTrackSelector.Parameters.Builder,
    cap: VideoSizeCap,
): DefaultTrackSelector.Parameters.Builder =
    builder
        .setPreferredVideoMimeTypes(*PlayerConfig.PREFERRED_VIDEO_MIME_TYPES)
        .setAllowVideoMixedMimeTypeAdaptiveness(false)
        .setAllowMultipleAdaptiveSelections(true)
        .resetVideoSizeTo(cap)
        .setForceHighestSupportedBitrate(false)
