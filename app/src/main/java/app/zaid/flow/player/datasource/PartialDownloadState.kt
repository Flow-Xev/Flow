package app.zaid.flow.player.datasource

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Shared state between the downloader and Media3's progressive reader. */
object PartialDownloadState {
    enum class Status { RUNNING, PAUSED, COMPLETE, FAILED }
    private class Entry(val file: File, @Volatile var status: Status) { val monitor = java.lang.Object() }
    private val entries = ConcurrentHashMap<String, Entry>()
    private fun key(file: File) = file.canonicalPath
    fun begin(file: File) { entries[key(file)] = Entry(file, Status.RUNNING); signal(file) }
    fun status(file: File): Status = entries[key(file)]?.status ?: Status.PAUSED
    fun update(file: File, status: Status) { entries[key(file)]?.let { it.status = status; synchronized(it.monitor) { it.monitor.notifyAll() } } }
    fun signal(file: File) { entries[key(file)]?.let { synchronized(it.monitor) { it.monitor.notifyAll() } } }
    fun waitForChange(file: File, timeoutMs: Long) {
        entries[key(file)]?.let { synchronized(it.monitor) { it.monitor.wait(timeoutMs) } }
    }
}
