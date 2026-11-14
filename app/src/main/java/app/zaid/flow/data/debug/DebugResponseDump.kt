package app.zaid.flow.data.debug

import java.io.File

/**
 * Debug-build-only sink for raw InnerTube responses, so a response that misbehaves on a real device
 * can be pulled off it and read. [directory] is only ever set from a debug build (see
 * FlowApplication); in every other build it stays null and [write] does nothing.
 */
object DebugResponseDump {
    @Volatile
    var directory: File? = null

    private val unsafeNameChars = Regex("[^A-Za-z0-9._-]")

    fun write(
        fileName: String,
        content: String,
    ): File? {
        val dir = directory ?: return null
        return runCatching {
            dir.mkdirs()
            File(dir, fileName.replace(unsafeNameChars, "_")).also { it.writeText(content) }
        }.getOrNull()
    }
}
