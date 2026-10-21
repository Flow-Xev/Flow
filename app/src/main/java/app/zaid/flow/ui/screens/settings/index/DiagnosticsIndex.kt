package app.zaid.flow.ui.screens.settings.index

import app.zaid.flow.R
import app.zaid.flow.ui.components.settings.SettingEntry
import app.zaid.flow.ui.components.settings.SettingsDestination

internal object DiagnosticsIndex {
    private val page = SettingsDestination.DIAGNOSTICS

    private fun entry(
        key: String,
        title: Int,
        section: Int,
        summary: Int? = null,
    ) = SettingEntry(
        key = "diagnostics.$key",
        title = title,
        summary = summary,
        section = section,
        keywords = R.string.settings_keywords_diagnostics,
        destination = page,
    )

    val model = entry("model", R.string.settings_diagnostics_model, R.string.settings_section_this_device)
    val android = entry("android", R.string.settings_diagnostics_android, R.string.settings_section_this_device)
    val appVersion = entry("app_version", R.string.settings_diagnostics_app_version, R.string.settings_section_this_device)
    val copy = entry("copy", R.string.diagnostics_copy, R.string.settings_section_report, R.string.settings_diagnostics_copy_summary)
    val share = entry("share", R.string.diagnostics_share, R.string.settings_section_report, R.string.settings_diagnostics_share_summary)
    val fullLogging =
        entry(
            "full_logging",
            R.string.diagnostics_full_logging,
            R.string.settings_section_logging,
            R.string.settings_diagnostics_full_logging_summary,
        )
    val shareLog =
        entry(
            "share_log",
            R.string.diagnostics_share_log,
            R.string.diagnostics_tab_full_log,
            R.string.settings_diagnostics_share_log_summary,
        )
    val clearLog = entry("clear_log", R.string.diagnostics_clear_log, R.string.diagnostics_tab_full_log)
    val resetSession =
        entry(
            "reset_session",
            R.string.diagnostics_reset_session,
            R.string.settings_section_report,
            R.string.settings_diagnostics_reset_summary,
        )
    val clearCrashes = entry("clear_crashes", R.string.diagnostics_clear_crashes, R.string.diagnostics_tab_crashes)

    val all =
        listOf(
            fullLogging,
            model,
            android,
            appVersion,
            copy,
            share,
            resetSession,
            shareLog,
        )
}
