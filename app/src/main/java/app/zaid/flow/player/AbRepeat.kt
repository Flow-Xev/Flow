package app.zaid.flow.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A section of one video, [startMs] to [endMs], that plays on repeat. Either end is null until it has been marked. */
data class AbRepeatState(
    val videoId: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
) {
    val isActive: Boolean get() = startMs != null && endMs != null
}

/** A section shorter than this is ignored, so a double tap cannot create a loop that stutters. */
private const val MIN_SECTION_MS = 500L
private const val POLL_MS = 100L

/**
 * The next state after the "A-B repeat" row is tapped at [positionMs] in [videoId]:
 * first tap marks A, second marks B and starts the loop, third stops it.
 */
internal fun advanceAbRepeat(
    state: AbRepeatState,
    videoId: String?,
    positionMs: Long,
): AbRepeatState =
    when {
        state.videoId != videoId || state.startMs == null -> {
            AbRepeatState(videoId, positionMs, null)
        }

        state.endMs == null -> {
            if (positionMs - state.startMs >= MIN_SECTION_MS) {
                state.copy(endMs = positionMs)
            } else {
                // B at or before A makes no section: start again from here.
                AbRepeatState(videoId, positionMs, null)
            }
        }

        else -> {
            AbRepeatState()
        }
    }

/** Where to jump back to when the loop has reached B, or null while playback is still inside the section. */
internal fun abRepeatSeekTarget(
    state: AbRepeatState,
    positionMs: Long,
): Long? {
    val start = state.startMs ?: return null
    val end = state.endMs ?: return null
    return start.takeIf { positionMs >= end }
}

/**
 * Plays a section of the current video on repeat. The loop belongs to one video: it ends by itself when
 * another video starts. While it runs the player position is checked every [POLL_MS] ms and sent back to A
 * when it reaches B.
 */
object AbRepeat {
    private val _state = MutableStateFlow(AbRepeatState())
    val state: StateFlow<AbRepeatState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    /** Advances A, then B, then off, using the player's current position. Call on the main thread. */
    fun mark() {
        val player = EnhancedPlayerManager.getInstance()
        _state.value = advanceAbRepeat(_state.value, player.playerState.value.currentVideoId, player.getCurrentPosition())
        syncWatcher()
    }

    fun clear() {
        _state.value = AbRepeatState()
        syncWatcher()
    }

    private fun syncWatcher() {
        watcher?.cancel()
        watcher = null
        if (!_state.value.isActive) return
        watcher =
            scope.launch {
                while (true) {
                    delay(POLL_MS)
                    val player = EnhancedPlayerManager.getInstance()
                    val current = _state.value
                    if (!current.isActive) return@launch
                    if (player.playerState.value.currentVideoId != current.videoId) {
                        _state.value = AbRepeatState()
                        return@launch
                    }
                    abRepeatSeekTarget(current, player.getCurrentPosition())?.let { player.seekTo(it) }
                }
            }
    }
}
