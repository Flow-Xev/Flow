package app.zaid.flow.utils

import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * The on-disk home of the session log.
 *
 * Lines go to [CURRENT]. Once it passes [maxBytes] it is rolled to [ROLLED] (replacing the one
 * before), so a long session keeps its most recent two segments. When the app starts again the
 * whole of the last run becomes [PREVIOUS].
 *
 * Every call is safe from any thread.
 */
class FlowLogFiles(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var written = 0L
    private var pending = 0

    /** Starts a new session: last run's log moves to [PREVIOUS] and a fresh [CURRENT] is opened. */
    fun open() {
        synchronized(lock) {
            closeLocked()
            dir.mkdirs()
            val current = File(dir, CURRENT)
            val rolled = File(dir, ROLLED)
            if (current.length() > 0 || rolled.length() > 0) {
                File(dir, PREVIOUS).outputStream().use { out ->
                    listOf(rolled, current).filter { it.isFile }.forEach { file -> file.inputStream().use { it.copyTo(out) } }
                }
            }
            rolled.delete()
            current.delete()
            openWriterLocked()
        }
    }

    fun append(line: String) {
        synchronized(lock) {
            val sink = writer ?: return
            runCatching {
                sink.write(line)
                sink.newLine()
            }
            written += line.length + 1
            pending++
            if (pending >= FLUSH_EVERY_LINES) flushLocked()
            if (written >= maxBytes) rollLocked()
        }
    }

    fun flush() {
        synchronized(lock) { flushLocked() }
    }

    /** Flushes and closes the writer; later [append] calls are dropped until [open] or [clear]. */
    fun close() {
        synchronized(lock) {
            flushLocked()
            closeLocked()
        }
    }

    /** Deletes this session's log and starts it again; the previous run's log stays. */
    fun clear() {
        synchronized(lock) {
            closeLocked()
            File(dir, CURRENT).delete()
            File(dir, ROLLED).delete()
            openWriterLocked()
        }
    }

    /** The newest [limit] bytes of this session, always starting on a whole line. */
    fun readTail(limit: Int): String =
        synchronized(lock) {
            flushLocked()
            val files = listOf(File(dir, ROLLED), File(dir, CURRENT)).filter { it.isFile }
            val total = files.sumOf { it.length() }
            var skip = (total - limit).coerceAtLeast(0L)
            val out = ByteArrayOutputStream()
            for (file in files) {
                val length = file.length()
                if (skip >= length) {
                    skip -= length
                    continue
                }
                file.inputStream().use {
                    it.skip(skip)
                    it.copyTo(out)
                }
                skip = 0
            }
            val text = out.toString(Charsets.UTF_8.name())
            if (total > limit) text.substringAfter('\n', "") else text
        }

    /** Writes the previous run (if any) and this one, in order, to [target] for sharing. */
    fun exportTo(target: File) {
        synchronized(lock) {
            flushLocked()
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                val previous = File(dir, PREVIOUS)
                if (previous.isFile) {
                    out.write("=== PREVIOUS SESSION ===\n".toByteArray())
                    previous.inputStream().use { it.copyTo(out) }
                    out.write("\n=== THIS SESSION ===\n".toByteArray())
                }
                listOf(File(dir, ROLLED), File(dir, CURRENT)).filter { it.isFile }.forEach { file ->
                    file.inputStream().use { it.copyTo(out) }
                }
            }
        }
    }

    private fun openWriterLocked() {
        val file = File(dir, CURRENT)
        written = file.length()
        pending = 0
        writer = runCatching { BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), BUFFER_CHARS) }.getOrNull()
    }

    private fun rollLocked() {
        closeLocked()
        val current = File(dir, CURRENT)
        val rolled = File(dir, ROLLED)
        rolled.delete()
        current.renameTo(rolled)
        openWriterLocked()
    }

    private fun flushLocked() {
        runCatching { writer?.flush() }
        pending = 0
    }

    private fun closeLocked() {
        runCatching { writer?.close() }
        writer = null
    }

    companion object {
        const val CURRENT = "session.log"
        const val ROLLED = "session.log.old"
        const val PREVIOUS = "previous.log"
        const val DEFAULT_MAX_BYTES = 2_000_000L
        private const val FLUSH_EVERY_LINES = 32
        private const val BUFFER_CHARS = 32 * 1024
    }
}
