package app.zaid.flow.ui.screens.settings.index

import app.zaid.flow.R
import app.zaid.flow.ui.components.music.section.MusicHomeShelf
import app.zaid.flow.ui.components.settings.SettingEntry
import app.zaid.flow.ui.components.settings.SettingsDestination

internal object MusicHomeIndex {
    private val entries: Map<MusicHomeShelf, SettingEntry> =
        MusicHomeShelf.entries.associateWith { shelf ->
            SettingEntry(
                key = "music_home.${shelf.name.lowercase()}",
                title = shelf.labelRes,
                section = R.string.settings_music_home_sections,
                destination = SettingsDestination.MUSIC_HOME,
            )
        }

    fun entry(shelf: MusicHomeShelf): SettingEntry = entries.getValue(shelf)

    val all: List<SettingEntry> = MusicHomeShelf.entries.map(::entry)
}
