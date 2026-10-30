package app.zaid.flow.ui.screens.settings.appearance

import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.ui.screens.settings.SettingsViewModel
import app.zaid.flow.utils.DateContextMode
import app.zaid.flow.utils.DateDisplayMode
import app.zaid.flow.utils.DateFormatStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DateTimeViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val mode = preferences.dateDisplayMode.asState(DateDisplayMode.RELATIVE)
        val formatStyle = preferences.dateFormatStyle.asState(DateFormatStyle.SYSTEM)
        val listsMode = preferences.dateModeLists.asState(DateContextMode.DEFAULT)
        val watchMode = preferences.dateModeWatch.asState(DateContextMode.DEFAULT)
        val descriptionMode = preferences.dateModeDescription.asState(DateContextMode.DEFAULT)

        fun setMode(value: DateDisplayMode) = write { preferences.setDateDisplayMode(value) }

        fun setFormatStyle(value: DateFormatStyle) = write { preferences.setDateFormatStyle(value) }

        fun setListsMode(value: DateContextMode) = write { preferences.setDateModeLists(value) }

        fun setWatchMode(value: DateContextMode) = write { preferences.setDateModeWatch(value) }

        fun setDescriptionMode(value: DateContextMode) = write { preferences.setDateModeDescription(value) }
    }
