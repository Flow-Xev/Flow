package app.zaid.flow.ui.screens.settings.appearance

import app.zaid.flow.data.local.AppFontPreferences
import app.zaid.flow.data.local.AppFontSelection
import app.zaid.flow.data.local.AppUiModePreferences
import app.zaid.flow.data.local.DEFAULT_NAV_TAB_ORDER
import app.zaid.flow.data.local.HomeFeedColumns
import app.zaid.flow.data.local.HomeViewMode
import app.zaid.flow.data.local.InlinePreviewMode
import app.zaid.flow.data.local.LocalDataManager
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.platform.AppIconController
import app.zaid.flow.platform.AppUiMode
import app.zaid.flow.ui.components.layout.navigation.NavigationVisibility
import app.zaid.flow.ui.screens.settings.SettingsViewModel
import app.zaid.flow.ui.theme.GridItemSize
import app.zaid.flow.ui.theme.ThemeMode
import app.zaid.flow.ui.theme.ThemeVariant
import app.zaid.flow.util.AppIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The Appearance page and its Navigation bar sub-page. */
@HiltViewModel
class AppearanceViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
        private val uiModePreferences: AppUiModePreferences,
        private val appIconController: AppIconController,
        localDataManager: LocalDataManager,
        fontPreferences: AppFontPreferences,
    ) : SettingsViewModel() {
        val themeMode = localDataManager.themeMode.asState(ThemeMode.SYSTEM)
        val themeVariant = localDataManager.themeVariant.asState(ThemeVariant.DARK)
        val customThemeName = localDataManager.activeCustomTheme.map { it?.name }.asState(null)
        val interfaceMode = uiModePreferences.mode.asState(AppUiMode.AUTOMATIC)
        val font = fontPreferences.selection.asState(AppFontSelection())

        val homeViewMode = preferences.homeViewMode.asState(HomeViewMode.GRID)
        val homeColumns = preferences.homeFeedColumns.asState(HomeFeedColumns.AUTO)
        val gridItemSize =
            preferences.gridItemSize
                .map { raw -> GridItemSize.entries.firstOrNull { it.name == raw } ?: GridItemSize.BIG }
                .asState(GridItemSize.BIG)
        val libraryPreviews = preferences.libraryShelfPreviewsEnabled.asState(true)
        val separatePlaylists = preferences.separatePlaylistKinds.asState(false)
        val appLogo = preferences.showAppLogoIcon.asState(true)
        val groupBadges = preferences.showChannelGroupBadges.asState(false)
        val cardLikeButtons = preferences.videoCardActionsEnabled.asState(false)
        val cardMarkWatched = preferences.videoCardMarkWatchedEnabled.asState(false)
        val cardWatchProgress = preferences.showWatchProgress.asState(true)
        val cardPreviewMode = preferences.inlinePreviewMode.asState(InlinePreviewMode.Default)
        val cardPreviewSound = preferences.inlinePreviewSound.asState(false)

        val shortsContent = preferences.shortsContentEnabled.asState(true)
        val navigationVisibility: StateFlow<NavigationVisibility> =
            combine(
                preferences.homeNavigationEnabled,
                preferences.shortsNavigationEnabled,
                preferences.musicNavigationEnabled,
                preferences.searchNavigationEnabled,
                preferences.categoriesNavigationEnabled,
            ) { home, shorts, music, search, categories ->
                NavigationVisibility(home = home, shorts = shorts, music = music, search = search, categories = categories)
            }.asState(NavigationVisibility())
        val hideNavOnScroll = preferences.bottomNavHideOnScroll.asState(true)
        val navTabOrder = preferences.navTabOrder.asState(DEFAULT_NAV_TAB_ORDER)
        val defaultNavTabIndex = preferences.defaultNavTabIndex.asState(0)

        private val _appIcon = MutableStateFlow(AppIcons.DEFAULT_SUFFIX)
        val appIcon: StateFlow<String> = _appIcon.asStateFlow()

        init {
            write { _appIcon.value = appIconController.activeSuffix() }
        }

        fun setInterfaceMode(mode: AppUiMode) = write { uiModePreferences.setMode(mode) }

        fun setAppIcon(suffix: String) =
            write {
                appIconController.apply(suffix)
                _appIcon.value = suffix
            }

        fun setHomeViewMode(mode: HomeViewMode) = write { preferences.setHomeViewMode(mode) }

        fun setHomeColumns(columns: HomeFeedColumns) = write { preferences.setHomeFeedColumns(columns) }

        fun setGridItemSize(size: GridItemSize) = write { preferences.setGridItemSize(size.name) }

        fun setLibraryPreviews(enabled: Boolean) = write { preferences.setLibraryShelfPreviewsEnabled(enabled) }

        fun setSeparatePlaylists(enabled: Boolean) = write { preferences.setSeparatePlaylistKinds(enabled) }

        fun setAppLogo(enabled: Boolean) = write { preferences.setShowAppLogoIcon(enabled) }

        fun setGroupBadges(enabled: Boolean) = write { preferences.setShowChannelGroupBadges(enabled) }

        fun setCardLikeButtons(enabled: Boolean) = write { preferences.setVideoCardActionsEnabled(enabled) }

        fun setCardMarkWatched(enabled: Boolean) = write { preferences.setVideoCardMarkWatchedEnabled(enabled) }

        fun setCardWatchProgress(enabled: Boolean) = write { preferences.setShowWatchProgress(enabled) }

        fun setCardPreviewMode(mode: InlinePreviewMode) = write { preferences.setInlinePreviewMode(mode) }

        fun setCardPreviewSound(enabled: Boolean) = write { preferences.setInlinePreviewSound(enabled) }

        fun setHomeTab(enabled: Boolean) = write { preferences.setHomeNavigationEnabled(enabled) }

        fun setShortsTab(enabled: Boolean) = write { preferences.setShortsNavigationEnabled(enabled) }

        fun setMusicTab(enabled: Boolean) = write { preferences.setMusicNavigationEnabled(enabled) }

        fun setSearchTab(enabled: Boolean) = write { preferences.setSearchNavigationEnabled(enabled) }

        fun setExploreTab(enabled: Boolean) = write { preferences.setCategoriesNavigationEnabled(enabled) }

        fun setHideNavOnScroll(enabled: Boolean) = write { preferences.setBottomNavHideOnScroll(enabled) }

        fun setNavTabOrder(order: List<Int>) = write { preferences.setNavTabOrder(order) }

        fun setDefaultNavTab(index: Int) = write { preferences.setDefaultNavTabIndex(index) }
    }
