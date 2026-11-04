package app.zaid.flow.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Records everything this process logs, at every level (verbose through fatal, from Flow and
 * from every library it uses), to a rotating file the Diagnostics screen reads.
 *
 * The system's logcat buffer is small and only keeps the newest few hundred KB, so on its own a
 * busy session loses its beginning. This follows the process's own logcat stream (no permission
 * is needed to read one's own PID) and writes each line out as it arrives. The read blocks until
 * the next line, so it is event-driven rather than polled. The newest lines are also held in memory
 * so the Diagnostics screen can follow them live.
 *
 * The whole thing can be switched off from Settings > Diagnostics ([setEnabled]). Off means off:
 * the logcat reader is killed, the file is closed, nothing is held in memory, and the extra
 * request/metadata tracing in [FlowTrace] and the network interceptor stops doing any work. The
 * choice is kept in SharedPreferences (not DataStore) because it has to be readable synchronously
 * in Application.onCreate(), before anything else has a chance to log.
 */
object FlowLogger {
    private const val TAG = "FlowLogger"
    private const val LOG_DIR = "logs"
    private const val EXPORT_DIR = "logs"
    private const val EXPORT_FILE = "flow-full-log.txt"
    private const val PREFS = "flow_logging"
    private const val KEY_ENABLED = "full_logging_enabled"

    /** How much of the log the screen and the copied report load; the shared file has all of it. */
    const val DISPLAY_BYTES = 1_000_000

    private val ring = FlowLogRing()
    private val lock = Any()
    private val enabledFlow = MutableStateFlow(true)

    /** Whether full logging is on. Observed by the Diagnostics switch. */
    val enabledState: StateFlow<Boolean> = enabledFlow.asStateFlow()

    /** Cheap check for hot paths (every HTTP call) so a disabled logger costs a volatile read. */
    @Volatile
    var isEnabled: Boolean = true
        private set

    /** Changes whenever a line is added or the log is cleared; collect it (sampled) to follow the log live. */
    val ticks: StateFlow<Long> get() = ring.ticks

    /** Lines from number [from] on that are still held in memory; see [FlowLogRing.since]. */
    fun linesSince(from: Long): FlowLogRing.Batch = ring.since(from)

    @Volatile
    private var files: FlowLogFiles? = null

    @Volatile
    private var capture: Process? = null

    @Volatile
    private var generation = 0
    private lateinit var appContext: Context

    /** Begins recording (unless the user turned it off). Call once from Application.onCreate(). */
    fun start(context: Context) {
        synchronized(lock) {
            if (::appContext.isInitialized) return
            appContext = context.applicationContext
            val on = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
            isEnabled = on
            enabledFlow.value = on
            if (on) beginLocked()
        }
    }

    /** Turns full logging on or off and remembers the choice across launches. */
    fun setEnabled(on: Boolean) {
        synchronized(lock) {
            if (!::appContext.isInitialized || isEnabled == on) return
            appContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, on)
                .apply()
            isEnabled = on
            enabledFlow.value = on
            if (on) {
                Log.i(TAG, "Full logging turned on")
                beginLocked()
            } else {
                Log.i(TAG, "Full logging turned off")
                endLocked()
            }
        }
    }

    private fun beginLocked() {
        val dir = File(appContext.filesDir, LOG_DIR)
        val mine = ++generation
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { record(dir, mine) }
    }

    private fun endLocked() {
        generation++
        capture?.destroy()
        capture = null
        files?.close()
        files = null
        ring.clear()
    }

    private fun record(
        dir: File,
        mine: Int,
    ) {
        val sink = FlowLogFiles(dir)
        var process: Process? = null
        try {
            sink.open()
            synchronized(lock) {
                if (mine != generation) {
                    sink.close()
                    return
                }
                files = sink
                process =
                    ProcessBuilder("logcat", "--pid=${android.os.Process.myPid()}", "-v", "threadtime")
                        .redirectErrorStream(true)
                        .start()
                capture = process
            }
            process!!.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (mine != generation) break
                    sink.append(line)
                    ring.add(line)
                }
            }
        } catch (e: Exception) {
            if (mine == generation) Log.e(TAG, "Full logging stopped", e)
        } finally {
            process?.destroy()
            sink.flush()
        }
    }

    /** Writes anything still buffered, e.g. just before a crash takes the process down. */
    fun flush() {
        files?.flush()
    }

    /** The newest part of this session's log, or empty when recording has not started or cannot run. */
    fun readTail(limit: Int = DISPLAY_BYTES): String = files?.readTail(limit).orEmpty()

    fun clear() {
        files?.clear()
        ring.clear()
    }

    /** The whole log, last run first, as a file other apps may be handed; null when nothing is recorded. */
    fun export(context: Context): File? {
        val source = files ?: return null
        val target = File(File(context.cacheDir, EXPORT_DIR), EXPORT_FILE)
        return runCatching { source.exportTo(target) }.map { target }.getOrNull()
    }
}
