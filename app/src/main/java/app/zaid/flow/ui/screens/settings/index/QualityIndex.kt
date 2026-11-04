package app.zaid.flow.ui.screens.settings.index

import app.zaid.flow.R
import app.zaid.flow.ui.components.settings.SettingEntry
import app.zaid.flow.ui.components.settings.SettingsDestination
import app.zaid.flow.ui.components.settings.SettingsTabs

internal object QualityIndex {
    private val page = SettingsDestination.QUALITY

    private fun entry(
        key: String,
        title: Int,
        tab: String,
        section: Int,
        keywords: Int = R.string.settings_keywords_quality,
        revealVia: String? = null,
    ) = SettingEntry(
        key = "quality.$key",
        title = title,
        tab = tab,
        section = section,
        keywords = keywords,
        revealVia = revealVia,
        destination = page,
    )

    val videoWifi = entry("video.wifi", R.string.settings_quality_wifi, SettingsTabs.QUALITY_VIDEO, R.string.settings_quality_tab_video)
    val videoMobile =
        entry("video.mobile", R.string.settings_quality_mobile, SettingsTabs.QUALITY_VIDEO, R.string.settings_quality_tab_video)
    val codec =
        entry(
            "video.codec",
            R.string.settings_quality_preferred_codec,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_quality_tab_video,
            keywords = R.string.settings_keywords_codec,
        )
    val fallbackCodec =
        entry(
            "video.fallback_codec",
            R.string.player_settings_video_codec_fallback,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_quality_tab_video,
            keywords = R.string.settings_keywords_codec,
            revealVia = "quality.video.codec",
        )
    val shortsWifi = entry("shorts.wifi", R.string.settings_quality_wifi, SettingsTabs.QUALITY_SHORTS, R.string.settings_quality_tab_shorts)
    val shortsMobile =
        entry("shorts.mobile", R.string.settings_quality_mobile, SettingsTabs.QUALITY_SHORTS, R.string.settings_quality_tab_shorts)
    val thumbnailsWifi =
        entry(
            "thumbnails.wifi",
            R.string.settings_quality_wifi,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_section_thumbnails,
            keywords = R.string.settings_keywords_thumbnails,
        )
    val thumbnailsMobile =
        entry(
            "thumbnails.mobile",
            R.string.settings_quality_mobile,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_section_thumbnails,
            keywords = R.string.settings_keywords_thumbnails,
        )
    val dataSaver =
        entry(
            "data_saver",
            R.string.data_saver_title,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_section_data_saver,
            keywords = R.string.settings_keywords_data_saver,
        ).copy(summary = R.string.data_saver_summary)
    val autoFloor =
        entry(
            "auto.floor",
            R.string.auto_quality_floor_title,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_section_auto_quality,
            keywords = R.string.settings_keywords_auto_quality,
        ).copy(summary = R.string.auto_quality_floor_summary)
    val autoCeiling =
        entry(
            "auto.ceiling",
            R.string.auto_quality_ceiling_title,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_section_auto_quality,
            keywords = R.string.settings_keywords_auto_quality,
        ).copy(summary = R.string.auto_quality_ceiling_summary)
    val shortsAutoFloor =
        entry(
            "auto.shorts.floor",
            R.string.auto_quality_shorts_floor_title,
            SettingsTabs.QUALITY_SHORTS,
            R.string.settings_section_auto_quality,
            keywords = R.string.settings_keywords_auto_quality,
        ).copy(summary = R.string.auto_quality_floor_summary)
    val shortsAutoCeiling =
        entry(
            "auto.shorts.ceiling",
            R.string.auto_quality_shorts_ceiling_title,
            SettingsTabs.QUALITY_SHORTS,
            R.string.settings_section_auto_quality,
            keywords = R.string.settings_keywords_auto_quality,
        ).copy(summary = R.string.auto_quality_ceiling_summary)
    val music = entry("music", R.string.music_quality_header, SettingsTabs.QUALITY_MUSIC, R.string.settings_quality_tab_music)

    val all =
        listOf(
            dataSaver,
            autoFloor,
            autoCeiling,
            shortsAutoFloor,
            shortsAutoCeiling,
            videoWifi,
            videoMobile,
            codec,
            fallbackCodec,
            thumbnailsWifi,
            thumbnailsMobile,
            shortsWifi,
            shortsMobile,
            music,
        )
}
