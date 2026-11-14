package app.zaid.flow.ui.screens.categories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.zaid.flow.R
import app.zaid.flow.data.local.HomeFeedColumns
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.model.Video
import app.zaid.flow.innertube.pages.explore.ExploreSectionKind
import app.zaid.flow.ui.OnTabReselected
import app.zaid.flow.ui.components.categories.CategoryChartGrid
import app.zaid.flow.ui.components.categories.CategoryPagedGrid
import app.zaid.flow.ui.components.categories.CategoryShelfPage
import app.zaid.flow.ui.components.categories.CategorySubTabMenu
import app.zaid.flow.ui.components.categories.CategoryTabBar
import app.zaid.flow.ui.components.layout.navigation.FlowTab
import app.zaid.flow.ui.components.layout.topbar.FlowTopBar
import app.zaid.flow.ui.components.rememberFeedGridLayout
import app.zaid.flow.ui.components.shared.FeedGridSkeleton
import app.zaid.flow.ui.components.shared.FlowChoice
import app.zaid.flow.ui.components.shared.FlowChoiceDialog
import app.zaid.flow.ui.components.shared.FlowErrorState
import app.zaid.flow.utils.RegionCatalog

/**
 * Explore: one tab per YouTube destination, each rendering whichever of the three shapes its source
 * actually serves — a page of shelves, a paged grid, or a ranked chart.
 */
@Composable
fun CategoriesScreen(
    onVideoClick: (Video) -> Unit,
    onShortClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val trendingRegion by viewModel.trendingRegion.collectAsStateWithLifecycle()
    val pagingItems = viewModel.gridItems.collectAsLazyPagingItems()
    var showRegionDialog by rememberSaveable { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val columnPreference by preferences.homeFeedColumns.collectAsStateWithLifecycle(HomeFeedColumns.AUTO)

    val shelfState = rememberLazyGridState()
    val gridState = rememberLazyGridState()
    OnTabReselected(FlowTab.Explore.route) {
        val isGridSection =
            uiState.sectionKind == ExploreSectionKind.CHART || uiState.sectionKind == ExploreSectionKind.GRID
        if (uiState.openShelfTitle == null && isGridSection) gridState.animateScrollToItem(0) else shelfState.animateScrollToItem(0)
    }

    BackHandler(enabled = uiState.openShelfTitle != null) { viewModel.closeShelf() }

    Scaffold(
        topBar = {
            FlowTopBar(
                title = uiState.openShelfTitle ?: stringResource(R.string.categories_title),
                onBack = uiState.openShelfTitle?.let { { viewModel.closeShelf() } },
                actions = {
                    IconButton(onClick = { showRegionDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription =
                                stringResource(R.string.categories_region_picker_desc, trendingRegion),
                        )
                    }
                    IconButton(onClick = viewModel::toggleViewMode) {
                        Icon(
                            imageVector = if (uiState.isListView) Icons.Outlined.GridView else Icons.Outlined.List,
                            contentDescription =
                                if (uiState.isListView) {
                                    stringResource(R.string.categories_switch_to_grid)
                                } else {
                                    stringResource(R.string.categories_switch_to_list)
                                },
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.openShelfTitle == null) {
                CategoryTabBar(
                    selected = uiState.selected,
                    onSelect = viewModel::select,
                    modifier = Modifier.padding(vertical = ChipRowVerticalPadding),
                )
                CategorySubTabMenu(
                    subTabs = uiState.subTabs,
                    selected = uiState.selectedSubTab,
                    onSelect = viewModel::selectSubTab,
                    modifier = Modifier.padding(bottom = ChipRowVerticalPadding),
                )
            }

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val feedLayout = rememberFeedGridLayout(maxWidth, columnPreference)
                when {
                    uiState.isLoading -> {
                        FeedGridSkeleton(layout = feedLayout, listMode = uiState.isListView)
                    }

                    uiState.error != null -> {
                        FlowErrorState(error = uiState.error!!, onRetry = viewModel::refresh)
                    }

                    uiState.sectionKind == ExploreSectionKind.CHART -> {
                        CategoryChartGrid(
                            entries = uiState.chartEntries,
                            title = uiState.chartTitle,
                            gridState = gridState,
                            feedLayout = feedLayout,
                            isListView = uiState.isListView,
                            onVideoClick = onVideoClick,
                        )
                    }

                    uiState.sectionKind == ExploreSectionKind.GRID -> {
                        CategoryPagedGrid(
                            pagingItems = pagingItems,
                            gridState = gridState,
                            feedLayout = feedLayout,
                            isListView = uiState.isListView,
                            onVideoClick = onVideoClick,
                            onPlaylistClick = onPlaylistClick,
                        )
                    }

                    else -> {
                        CategoryShelfPage(
                            shelves = uiState.shelves,
                            isLoading = uiState.isLoading,
                            listState = shelfState,
                            columnPreference = columnPreference,
                            onVideoClick = onVideoClick,
                            onShortClick = onShortClick,
                            onPlaylistClick = onPlaylistClick,
                            onShelfOpen = viewModel::openShelf,
                        )
                    }
                }
            }
        }
    }

    if (showRegionDialog) {
        val regionOptions = remember { RegionCatalog.sorted().map { (code, name) -> FlowChoice(code, name) } }
        FlowChoiceDialog(
            title = stringResource(R.string.settings_region_dialog_title),
            options = regionOptions,
            selected = trendingRegion,
            onSelect = viewModel::setRegion,
            onDismiss = { showRegionDialog = false },
            listMaxHeight = RegionDialogMaxHeight,
        )
    }
}

private val ChipRowVerticalPadding = 4.dp
private val RegionDialogMaxHeight = 260.dp
