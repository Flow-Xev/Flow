package app.zaid.flow.ui.screens.settings.diagnostics

import android.content.Context
import androidx.lifecycle.viewModelScope
import app.zaid.flow.player.stream.ClientGateTracker
import app.zaid.flow.ui.screens.settings.SettingsViewModel
import app.zaid.flow.utils.DeviceInfo
import app.zaid.flow.utils.FlowDiagnostics
import app.zaid.flow.utils.FlowLogger
import app.zaid.flow.utils.HomeFeedLog
import app.zaid.flow.utils.cipher.CipherDeobfuscator
import app.zaid.flow.utils.cipher.PlayerJsFetcher
import app.zaid.flow.utils.potoken.WebPoTokenSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SettingsViewModel() {
        val device: DeviceInfo by lazy { FlowDiagnostics.deviceInfo(context) }

        private val _session = MutableStateFlow<LogState>(LogState.Loading)
        private val _crashes = MutableStateFlow<LogState>(LogState.Loading)
        val session: StateFlow<LogState> = _session.asStateFlow()
        val crashes: StateFlow<LogState> = _crashes.asStateFlow()

        private var sessionText = ""

        private val _filter = MutableStateFlow(LogFilter.ALL)
        private val _paused = MutableStateFlow(false)
        val filter: StateFlow<LogFilter> = _filter.asStateFlow()
        val paused: StateFlow<Boolean> = _paused.asStateFlow()

        // Touched only by the full-log flow below, which runs one collector at a time.
        private val window = ArrayDeque<LogLine>()
        private var nextLine = 0L
        private var epoch = 0

        /**
         * Every line the app logs, as it is written. Only collected while the Full Log tab is on
         * screen (and for a few seconds after), and refreshed at most [LIVE_REFRESH_MS] apart, so
         * a burst of logging costs one rebuild rather than one per line. While paused the view
         * holds still; the file keeps recording.
         */
        @OptIn(FlowPreview::class)
        val fullLog: StateFlow<LogState> =
            combine(FlowLogger.ticks.sample(LIVE_REFRESH_MS), _filter, _paused) { _, filter, paused ->
                if (!paused) pullNewLines()
                liveLogState(window, filter)
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogState.Loading)

        /** The Home feed's step-by-step record; see [HomeFeedLog]. */
        @OptIn(FlowPreview::class)
        val homeFeedLog: StateFlow<LogState> =
            HomeFeedLog.version
                .sample(LIVE_REFRESH_MS)
                .map { parseLog(HomeFeedLog.text(), ::homeFeedLevel) }
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogState.Loading)

        fun homeFeedLogText(): String = HomeFeedLog.text()

        fun clearHomeFeedLog() = HomeFeedLog.clear()

        init {
            viewModelScope.launch(Dispatchers.IO) {
                sessionText = FlowDiagnostics.readSessionLogs()
                _session.value = parseLog(sessionText, ::logcatLevel)
                _crashes.value = parseLog(FlowDiagnostics.crashLogsOrNull(context), ::crashLevel)
            }
        }

        private fun pullNewLines() {
            val batch = FlowLogger.linesSince(nextLine)
            if (batch.epoch != epoch) {
                window.clear()
                epoch = batch.epoch
            }
            batch.lines.forEach { window.addLast(LogLine(it, logcatLevel(it))) }
            while (window.size > WINDOW_LINES) window.removeFirst()
            nextLine = batch.next
        }

        /** Whether full logging is on; see [FlowLogger.setEnabled]. */
        val fullLoggingEnabled: StateFlow<Boolean> = FlowLogger.enabledState

        fun setFullLogging(on: Boolean) = FlowLogger.setEnabled(on)

        fun setFilter(value: LogFilter) {
            _filter.value = value
        }

        fun togglePaused() {
            _paused.value = !_paused.value
        }

        suspend fun report(): String =
            withContext(Dispatchers.IO) {
                FlowDiagnostics.buildFullReport(context, sessionText, FlowDiagnostics.readFullLog())
            }

        /** The whole log as a file to hand to another app, or null when nothing has been recorded. */
        suspend fun exportFullLog(): File? = withContext(Dispatchers.IO) { FlowDiagnostics.exportFullLog(context) }

        fun clearFullLog() =
            write {
                withContext(Dispatchers.IO) { FlowDiagnostics.clearFullLog() }
            }

        fun clearCrashes() =
            write {
                withContext(Dispatchers.IO) { FlowDiagnostics.clearCrashLogs(context) }
                _crashes.value = LogState.Empty
            }

        /**
         * Re-attesting alone cannot lift a verdict the stream servers already reached about this
         * visitor, so the identity itself is discarded: the supported form of the "clear app data"
         * workaround for videos that stall a minute in.
         */
        suspend fun resetSession() {
            WebPoTokenSession.resetIdentity()
            ClientGateTracker.clear()
            CipherDeobfuscator.invalidateSignatureTimestamp()
            withContext(Dispatchers.IO) { PlayerJsFetcher.invalidateCache() }
        }

        private companion object {
            const val LIVE_REFRESH_MS = 250L
            const val STOP_TIMEOUT_MS = 5_000L
            const val WINDOW_LINES = 5_000
        }
    }
