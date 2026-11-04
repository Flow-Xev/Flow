package app.zaid.flow.ui.screens.crash

import app.zaid.flow.utils.FlowCrashReportFormatter
import app.zaid.flow.utils.FlowCrashReportSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

class CrashSummaryTest {
    private val report =
        FlowCrashReportFormatter.build(
            FlowCrashReportSnapshot(
                timestampMs = 0,
                threadName = "main",
                threadId = 2,
                exceptionClass = "java.lang.IllegalArgumentException",
                exceptionMessage = "Padding must be non-negative",
                stackTrace =
                    """
                    java.lang.IllegalArgumentException: Padding must be non-negative
                    	at androidx.compose.foundation.layout.PaddingKt.padding(Padding.kt:88)
                    	at app.zaid.flow.ui.screens.player.PlayerBottomBarKt.PlayerBottomBar(PlayerBottomBar.kt:212)
                    """.trimIndent(),
                deviceInfo = "  Model: 23049PCD8G\n  Manufacturer: Xiaomi\n  Android: 16 (SDK 36)\n  App Version: 2.2.5 (225)",
                memoryInfo = "  Used heap: 10 MB",
                breadcrumbs = emptyList(),
            ),
        )

    @Test
    fun `the summary points at the first frame in Flow's own code`() {
        val summary = parseCrashSummary(report)

        assertThat(summary.exception).isEqualTo("IllegalArgumentException")
        assertThat(summary.message).isEqualTo("Padding must be non-negative")
        assertThat(summary.location).isEqualTo("PlayerBottomBar.kt:212")
        assertThat(summary.appVersion).isEqualTo("2.2.5 (225)")
        assertThat(summary.device).isEqualTo("Xiaomi 23049PCD8G")
        assertThat(summary.time).isInstanceOf(LocalDateTime::class.java)
    }

    @Test
    fun `an unreadable report still gives a usable summary`() {
        val summary = parseCrashSummary("something went wrong")

        assertThat(summary.exception).isEqualTo("Crash")
        assertThat(summary.location).isNull()
    }
}
