package app.zaid.flow.ui.screens.crash

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val APP_PACKAGE = "app.zaid.flow"
private const val TOP_FRAMES = 6
private val ReportTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** The facts a person needs from a crash report, read back from the text the crash handler saved. */
data class CrashSummary(
    val exception: String,
    val message: String?,
    val location: String?,
    val appVersion: String?,
    val android: String?,
    val device: String?,
    val time: LocalDateTime?,
    val topFrames: List<String>,
)

fun parseCrashSummary(report: String): CrashSummary {
    val lines = report.lines().map { it.trim() }

    fun value(label: String) =
        lines
            .firstOrNull { it.startsWith("$label:") }
            ?.substringAfter(':')
            ?.trim()
            ?.ifBlank { null }
    val frames = lines.filter { it.startsWith("at ") }
    val ownFrame = frames.firstOrNull { APP_PACKAGE in it } ?: frames.firstOrNull()
    val manufacturer = value("Manufacturer")
    val model = value("Model")
    return CrashSummary(
        exception = value("Exception")?.substringAfterLast('.') ?: "Crash",
        message = value("Message")?.takeUnless { it == "none" },
        location = ownFrame?.substringAfterLast('(')?.substringBefore(')'),
        appVersion = value("App Version"),
        android = value("Android"),
        device = listOfNotNull(manufacturer, model).joinToString(" ").ifBlank { null },
        time =
            lines.firstOrNull { it.startsWith("CRASH REPORT - ") }?.substringAfter(" - ")?.let {
                runCatching { LocalDateTime.parse(it, ReportTime) }.getOrNull()
            },
        topFrames = frames.take(TOP_FRAMES),
    )
}
