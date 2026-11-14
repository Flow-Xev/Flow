package app.zaid.flow.utils

import android.util.Log

/**
 * Detailed tracing for the parts of the app that talk to YouTube: InnerTube, the NewPipe
 * extractor, and the metadata built from their answers. Everything goes through [Log] so
 * [FlowLogger] records it, and every call is a no-op while full logging is switched off in
 * Settings > Diagnostics, so call sites can trace freely without checking anything themselves.
 *
 * Message lambdas are only evaluated when logging is on, so building a long string costs nothing
 * when it is off.
 */
object FlowTrace {
    const val INNERTUBE = "FlowInnerTube"
    const val NEWPIPE = "FlowNewPipe"
    const val YOUTUBE = "FlowYouTube"
    const val METADATA = "FlowMetadata"

    private const val MAX_VALUE_CHARS = 600

    val enabled: Boolean get() = FlowLogger.isEnabled

    fun d(
        tag: String,
        message: () -> String,
    ) {
        if (enabled) Log.d(tag, message())
    }

    fun i(
        tag: String,
        message: () -> String,
    ) {
        if (enabled) Log.i(tag, message())
    }

    fun w(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        if (enabled) Log.w(tag, message(), throwable)
    }

    fun e(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        if (enabled) Log.e(tag, message(), throwable)
    }

    /** [value] on one line and cut to a sane length, for titles, descriptions and similar. */
    fun clip(value: String?): String {
        if (value == null) return "null"
        val oneLine = value.replace('\n', ' ').replace('\r', ' ')
        return if (oneLine.length <= MAX_VALUE_CHARS) oneLine else oneLine.take(MAX_VALUE_CHARS) + "… (${oneLine.length} chars)"
    }
}
