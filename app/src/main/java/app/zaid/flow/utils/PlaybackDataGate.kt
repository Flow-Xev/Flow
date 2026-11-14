package app.zaid.flow.utils

import app.zaid.flow.player.GlobalPlayerState
import kotlinx.coroutines.flow.first

/**
 * Tells background work whether a video is on screen right now, so it can wait instead of competing with the
 * stream for data. Nothing here limits what the viewer asked for: scrolling, pull to refresh and the player
 * itself are never held back. Only work the viewer did not ask for (top-ups, avatar lookups, late extras)
 * waits for playback to pause or end, and then runs.
 *
 * Music and other audio-only playback does not count: with no video in front of the viewer there is nothing
 * to compete with, and the feed they browse meanwhile still needs its avatars.
 */
object PlaybackDataGate {
    /** True while the main player is playing or buffering a video (not music). */
    val videoActive: Boolean
        get() {
            val state = GlobalPlayerState.playerState.value
            if (!state.isPlaying && !state.isBuffering) return false
            return GlobalPlayerState.currentVideo.value?.isMusic != true
        }

    /** Suspends while [videoActive]; returns at once when no video is playing. */
    suspend fun awaitNoVideo() {
        if (!videoActive) return
        GlobalPlayerState.playerState.first { !videoActive }
    }
}
