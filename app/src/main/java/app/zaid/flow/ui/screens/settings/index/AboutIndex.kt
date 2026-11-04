package app.zaid.flow.ui.screens.settings.index

import app.zaid.flow.R
import app.zaid.flow.ui.components.settings.SettingEntry
import app.zaid.flow.ui.components.settings.SettingsDestination

internal object AboutIndex {
    private val page = SettingsDestination.ABOUT

    private fun entry(
        key: String,
        title: Int,
        section: Int,
        summary: Int? = null,
    ) = SettingEntry(
        key = "about.$key",
        title = title,
        summary = summary,
        section = section,
        keywords = R.string.settings_keywords_about,
        destination = page,
    )

    val changelog = entry("changelog", R.string.about_changelog, R.string.section_app, R.string.whats_new_in_flow)
    val website = entry("website", R.string.about_website, R.string.section_contact, R.string.about_website_address)
    val reddit = entry("reddit", R.string.about_reddit, R.string.section_contact, R.string.about_reddit_subtitle)
    val deviceInfo = entry("device_info", R.string.about_device_info, R.string.section_device)

    val all = listOf(changelog, website, reddit, deviceInfo)
}
