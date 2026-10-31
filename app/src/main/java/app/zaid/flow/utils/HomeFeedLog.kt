package app.zaid.flow.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A step-by-step record of how the Home feed was built: which client was asked, what YouTube answered
 * (signed in or not), how many videos, Shorts and posts came back, what was filtered, which source won.
 * Shown in Settings > Diagnostics > Home feed. Never records cookie values, only which cookies exist.
 */
object HomeFeedLog {
    private const val MAX_LINES = 800
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val versionFlow = MutableStateFlow(0L)

    /** Changes whenever a line is added or the log is cleared. */
    val version: StateFlow<Long> = versionFlow.asStateFlow()

    fun d(
        tag: String,
        message: String,
    ) = add("$tag  $message")

    fun fail(
        tag: String,
        message: String,
    ) = add("$tag  FAIL $message")

    fun warn(
        tag: String,
        message: String,
    ) = add("$tag  WARN $message")

    private fun add(body: String) {
        val line: String
        synchronized(lines) {
            line = "${format.format(Date())}  $body"
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        versionFlow.value = versionFlow.value + 1
        Log.w("HomeFeedLog", line)
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        versionFlow.value = versionFlow.value + 1
    }
}
