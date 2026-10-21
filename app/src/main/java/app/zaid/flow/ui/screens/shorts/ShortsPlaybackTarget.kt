package app.zaid.flow.ui.screens.shorts

import android.content.Context
import app.zaid.flow.data.local.VideoQuality
import app.zaid.flow.data.shorts.ShortsStreamResolver
import app.zaid.flow.player.quality.AutoQualityLadder
import app.zaid.flow.player.quality.MeasuredSpeed
import app.zaid.flow.utils.NetworkState

/**
 * The height a Short is resolved at.
 *
 * This is not merely a display preference — it forms part of the key of the repository's
 * playback-stream cache, so the pager and the ViewModel's prefetch have to agree on it exactly or
 * the prefetch resolves one entry and the pager then misses it and resolves the stream a second
 * time. Both therefore derive it here rather than each computing it their own way.
 */
internal fun shortsTargetHeight(
    isWifi: Boolean,
    wifiQuality: VideoQuality,
    cellularQuality: VideoQuality,
): Int {
    val quality = if (isWifi) wifiQuality else cellularQuality
    // Auto follows the connection speed (see AutoQualityLadder); a fixed choice is used as it is.
    return if (quality == VideoQuality.AUTO) ShortsStreamResolver.AUTO_ADAPTIVE else quality.height
}

/**
 * Reads the speed the app has measured for this kind of network and updates what Auto plays at.
 * Called on entry and periodically during a visit so a later speed drop (or recovery) can change
 * the height. Before anything has been measured, Auto starts at 480p.
 */
internal fun refreshShortsAutoHeight(context: Context) {
    AutoQualityLadder.shortsEstimateBps = MeasuredSpeed.read(context, isOnWifi(context))
}

/**
 * Reads the active transport synchronously. Callers must not substitute a placeholder while a
 * network callback settles: a wrong answer here silently changes the cache key above.
 */
internal fun isOnWifi(context: Context): Boolean = NetworkState.isOnWifi(context)
