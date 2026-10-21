package app.zaid.flow.ui.screens.home

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Lets other screens ask Home to switch to a chip (by chip key) once it is on screen. */
object HomeChipRequests {
    @Volatile
    var pending: String? = null
        private set

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun request(key: String) {
        pending = key
        _events.tryEmit(key)
    }

    /** Takes the request, if any, so it applies once. */
    fun consume(): String? = pending.also { pending = null }
}
