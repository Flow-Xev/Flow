package app.zaid.flow.ui.screens.settings

import app.zaid.flow.ui.screens.settings.diagnostics.LOG_CHUNK_LINES
import app.zaid.flow.ui.screens.settings.diagnostics.LogFilter
import app.zaid.flow.ui.screens.settings.diagnostics.LogLevel
import app.zaid.flow.ui.screens.settings.diagnostics.LogLine
import app.zaid.flow.ui.screens.settings.diagnostics.LogState
import app.zaid.flow.ui.screens.settings.diagnostics.liveLogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLogTest {
    private val lines =
        listOf(
            LogLine("debug", LogLevel.QUIET),
            LogLine("info", LogLevel.INFO),
            LogLine("warn", LogLevel.WARN),
            LogLine("error", LogLevel.ERROR),
        )

    private fun shown(filter: LogFilter) = (liveLogState(lines, filter) as LogState.Lines).chunks.flatten().map { it.text }

    @Test
    fun `each filter shows its levels and everything above`() {
        assertEquals(listOf("debug", "info", "warn", "error"), shown(LogFilter.ALL))
        assertEquals(listOf("warn", "error"), shown(LogFilter.WARNINGS))
        assertEquals(listOf("error"), shown(LogFilter.ERRORS))
    }

    @Test
    fun `a filter that matches nothing is empty and long logs are chunked`() {
        assertEquals(LogState.Empty, liveLogState(lines.take(2), LogFilter.ERRORS))
        val many = List(LOG_CHUNK_LINES + 1) { LogLine("line $it", LogLevel.INFO) }
        val state = liveLogState(many, LogFilter.ALL) as LogState.Lines
        assertTrue(state.chunks.size == 2)
    }
}
