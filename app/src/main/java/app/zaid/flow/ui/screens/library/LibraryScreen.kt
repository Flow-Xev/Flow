package app.zaid.flow.ui.screens.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.music.DownloadedTrack
import app.zaid.flow.data.music.model.MusicTrack
import app.zaid.flow.data.shorts.queue.ShortsQueueSource
import app.zaid.flow.data.stats.RecapPeriod
import app.zaid.flow.data.video.DownloadedVideo
import app.zaid.flow.ui.OnTabReselected
import app.zaid.flow.ui.components.layout.flowBottomContentPadding
import app.zaid.flow.ui.components.layout.navigation.FlowTab
import app.zaid.flow.ui.components.layout.topbar.FlowTopBar
import app.zaid.flow.ui.components.library.LibraryNavigationRow
import app.zaid.flow.ui.components.shared.FlowEmptyState
import app.zaid.flow.ui.components.shared.FlowMaxContentWidth
import app.zaid.flow.ui.components.shared.MediaKind
import app.zaid.flow.ui.components.stats.RecapEntryCard
import kotlinx.coroutines.delay
import java.time.format.TextStyle

private val ListVerticalPadding = 12.dp
/** One fixed gap between every shelf, whether or not the shelf above it is empty. */
private val ShelfSpacing = 16.dp
private val RecapCardPadding = 16.dp

@Composable
fun LibraryScreen(
    onNavigateToHistory: () -> Unit,
    onNavigateToPlaylists: (MediaKind?) -> Unit,
    onNavigateToLikedVideos: () -> Unit,
    onNavigateToLikedMusic: () -> Unit,
    onNavigateToWatchLater: () -> Unit,
    onNavigateToSavedShorts: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToLocalMedia: () -> Unit,
    onNavigateToNotes: () -> Unit,
    onManageData: () -> Unit,
    onOpenRecap: (RecapPeriod?) -> Unit,
    onOpenAccount: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onMusicClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onMusicPlaylistClick: (String) -> Unit,
    onDownloadedVideoClick: (List<DownloadedVideo>, Int) -> Unit,
    onDownloadedMusicClick: (List<DownloadedTrack>, Int) -> Unit,
    onSavedShortClick: (Video) -> Unit,
    onHistoryShortsClick: (ShortsQueueSource) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val shortsEnabled by viewModel.shortsEnabled.collectAsStateWithLifecycle()
    val shelfPreviewsEnabled by viewModel.shelfPreviewsEnabled.collectAsStateWithLifecycle()
    val separatePlaylistKinds by viewModel.separatePlaylistKinds.collectAsStateWithLifecycle()
    val isLibraryEmpty by viewModel.isLibraryEmpty.collectAsStateWithLifecycle()
    val recapReady by viewModel.recapReady.collectAsStateWithLifecycle()
    val recapDue by viewModel.recapDue.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val incognito by viewModel.incognito.collectAsStateWithLifecycle()
    var accountExpanded by rememberSaveable { mutableStateOf(false) }
    var signingIn by rememberSaveable { mutableStateOf(false) }
    val notesCount by viewModel.notesCount.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val listState = rememberLazyListState()
    OnTabReselected(FlowTab.Library.route) { listState.animateScrollToItem(0) }

    if (signingIn) {
        // The sign-in page over everything; closing it (or back) returns to Library as it was.
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { signingIn = false },
            properties =
                androidx.compose.ui.window
                    .DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                app.zaid.flow.ui.screens.account.AccountSignInScreen(
                    onSignedIn = { signingIn = false },
                    onCancel = { signingIn = false },
                )
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { FlowTopBar(title = stringResource(R.string.library)) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(top = ListVerticalPadding, bottom = flowBottomContentPadding(ListVerticalPadding)),
            verticalArrangement = Arrangement.spacedBy(ShelfSpacing),
        ) {
            item(key = "account", contentType = "account") {
                LibraryAccountCard(
                    account = activeAccount,
                    accounts = accounts,
                    expanded = accountExpanded,
                    incognito = incognito,
                    onToggle = { if (activeAccount == null) onOpenAccount() else accountExpanded = !accountExpanded },
                    onSwitchAccount = viewModel::switchAccount,
                    onIncognitoChange = viewModel::setIncognito,
                    onAddAccount = { signingIn = true },
                    onMoreSettings = onOpenAccount,
                    modifier = Modifier.padding(horizontal = RecapCardPadding),
                )
            }
            if (recapDue) {
                item(key = "recap", contentType = "recap") {
                    RecapEntryCard(
                        readyLabel =
                            when (val ready = recapReady) {
                                is RecapPeriod.Month -> "${ready.month.month.getDisplayName(
                                    TextStyle.FULL_STANDALONE,
                                    locale,
                                )} ${ready.month.year}"

                                is RecapPeriod.Year -> ready.year.toString()

                                else -> null
                            },
                        onOpen = {
                            val period = recapReady
                            viewModel.onRecapHandled()
                            onOpenRecap(period)
                        },
                        onDismiss = viewModel::onRecapHandled,
                        modifier = Modifier.padding(horizontal = RecapCardPadding),
                    )
                }
            }
            if (shelfPreviewsEnabled && !isLibraryEmpty) {
                item(key = "quick-access", contentType = "quick-access") {
                    val quickCounts by viewModel.counts.collectAsStateWithLifecycle()
                    LibraryQuickAccess(
                        counts = quickCounts,
                        shortsEnabled = shortsEnabled,
                        onNavigateToHistory = onNavigateToHistory,
                        onNavigateToWatchLater = onNavigateToWatchLater,
                        onNavigateToLikedVideos = onNavigateToLikedVideos,
                        onNavigateToDownloads = onNavigateToDownloads,
                        onNavigateToSavedShorts = onNavigateToSavedShorts,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (shelfPreviewsEnabled && isLibraryEmpty) {
                item(key = "library-empty", contentType = "empty") {
                    FlowEmptyState(
                        title = stringResource(R.string.library_empty_title),
                        subtitle = stringResource(R.string.library_empty_body),
                        icon = Icons.Outlined.VideoLibrary,
                    )
                }
            } else if (shelfPreviewsEnabled) {
                libraryShelves(
                    viewModel = viewModel,
                    shortsEnabled = shortsEnabled,
                    separatePlaylistKinds = separatePlaylistKinds,
                    onNavigateToHistory = onNavigateToHistory,
                    onNavigateToPlaylists = onNavigateToPlaylists,
                    onNavigateToLikedVideos = onNavigateToLikedVideos,
                    onNavigateToLikedMusic = onNavigateToLikedMusic,
                    onNavigateToWatchLater = onNavigateToWatchLater,
                    onNavigateToSavedShorts = onNavigateToSavedShorts,
                    onNavigateToDownloads = onNavigateToDownloads,
                    onVideoClick = onVideoClick,
                    onMusicClick = onMusicClick,
                    onPlaylistClick = onPlaylistClick,
                    onMusicPlaylistClick = onMusicPlaylistClick,
                    onDownloadedVideoClick = onDownloadedVideoClick,
                    onDownloadedMusicClick = onDownloadedMusicClick,
                    onSavedShortClick = onSavedShortClick,
                    onHistoryShortClick = { shelf, tapped ->
                        onHistoryShortsClick(viewModel.historyShortsSource(shelf, tapped))
                    },
                )
            } else {
                item(key = "sections", contentType = "navigation-section") {
                    val counts by viewModel.counts.collectAsStateWithLifecycle()
                    LibrarySectionList(
                        counts = counts,
                        shortsEnabled = shortsEnabled,
                        separatePlaylistKinds = separatePlaylistKinds,
                        onNavigateToHistory = onNavigateToHistory,
                        onNavigateToPlaylists = onNavigateToPlaylists,
                        onNavigateToLikedVideos = onNavigateToLikedVideos,
                        onNavigateToLikedMusic = onNavigateToLikedMusic,
                        onNavigateToWatchLater = onNavigateToWatchLater,
                        onNavigateToSavedShorts = onNavigateToSavedShorts,
                        onNavigateToDownloads = onNavigateToDownloads,
                    )
                }
            }

            item(key = "settings-data", contentType = "navigation-section") {
                Column(modifier = Modifier.widthIn(max = FlowMaxContentWidth).padding(horizontal = 16.dp)) {
                    LibrarySectionHeader(stringResource(R.string.library_settings_data_header))
                    // An empty library has no shelves, but downloads from an earlier install can still be found.
                    if (shelfPreviewsEnabled && isLibraryEmpty) {
                        LibraryNavigationRow(
                            icon = LibrarySection.DOWNLOADS.icon,
                            title = LibrarySection.DOWNLOADS.title,
                            subtitle = stringResource(R.string.empty_downloads),
                            onClick = onNavigateToDownloads,
                        )
                    }
                    LibrarySectionRow(
                        section = LibrarySection.LOCAL_MEDIA,
                        counts = null,
                        onClick = onNavigateToLocalMedia,
                    )
                    notesCount?.let { count ->
                        LibraryNavigationRow(
                            icon = LibrarySection.NOTES.icon,
                            title = LibrarySection.NOTES.title,
                            subtitle =
                                if (count > 0) {
                                    pluralStringResource(R.plurals.notes_count, count, count)
                                } else {
                                    LibrarySection.NOTES.subtitle(null)
                                },
                            onClick = onNavigateToNotes,
                        )
                    }
                    LibrarySectionRow(
                        section = LibrarySection.SETTINGS,
                        counts = null,
                        onClick = onManageData,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun LazyListScope.libraryShelves(
    viewModel: LibraryViewModel,
    shortsEnabled: Boolean,
    separatePlaylistKinds: Boolean,
    onNavigateToHistory: () -> Unit,
    onNavigateToPlaylists: (MediaKind?) -> Unit,
    onNavigateToLikedVideos: () -> Unit,
    onNavigateToLikedMusic: () -> Unit,
    onNavigateToWatchLater: () -> Unit,
    onNavigateToSavedShorts: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onMusicClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onMusicPlaylistClick: (String) -> Unit,
    onDownloadedVideoClick: (List<DownloadedVideo>, Int) -> Unit,
    onDownloadedMusicClick: (List<DownloadedTrack>, Int) -> Unit,
    onSavedShortClick: (Video) -> Unit,
    onHistoryShortClick: (shelf: List<Video>, tapped: Video) -> Unit,
) {
    item(key = "history", contentType = "media-shelf") {
        LibraryEntrance(index = 0) {
            LibraryMediaShelfRoute(
                section = LibrarySection.HISTORY,
                itemsFlow = viewModel.history,
                onTitleClick = onNavigateToHistory,
                onVideoClick = onVideoClick,
                onMusicClick = onMusicClick,
                onDownloadedVideoClick = onDownloadedVideoClick,
                onDownloadedMusicClick = onDownloadedMusicClick,
                onShortClick = onHistoryShortClick,
            )
        }
    }

    if (separatePlaylistKinds) {
        item(key = "video-playlists", contentType = "playlist-shelf") {
            LibraryEntrance(index = 1) {
                LibraryPlaylistsShelf(
                    section = LibrarySection.VIDEO_PLAYLISTS,
                    videoPlaylistsFlow = viewModel.playlists,
                    musicPlaylistsFlow = null,
                    onTitleClick = { onNavigateToPlaylists(MediaKind.Videos) },
                    onVideoPlaylistClick = onPlaylistClick,
                    onMusicPlaylistClick = onMusicPlaylistClick,
                )
            }
        }
        item(key = "music-playlists", contentType = "playlist-shelf") {
            LibraryEntrance(index = 2) {
                LibraryPlaylistsShelf(
                    section = LibrarySection.MUSIC_PLAYLISTS,
                    videoPlaylistsFlow = null,
                    musicPlaylistsFlow = viewModel.musicPlaylists,
                    onTitleClick = { onNavigateToPlaylists(MediaKind.Music) },
                    onVideoPlaylistClick = onPlaylistClick,
                    onMusicPlaylistClick = onMusicPlaylistClick,
                )
            }
        }
    } else {
        item(key = "playlists", contentType = "playlist-shelf") {
            LibraryEntrance(index = 3) {
                LibraryPlaylistsShelf(
                    section = LibrarySection.PLAYLISTS,
                    videoPlaylistsFlow = viewModel.playlists,
                    musicPlaylistsFlow = viewModel.musicPlaylists,
                    onTitleClick = { onNavigateToPlaylists(null) },
                    onVideoPlaylistClick = onPlaylistClick,
                    onMusicPlaylistClick = onMusicPlaylistClick,
                )
            }
        }
    }

    item(key = "watch-later", contentType = "video-shelf") {
        LibraryEntrance(index = 4) {
            LibraryVideoShelf(
                section = LibrarySection.WATCH_LATER,
                videosFlow = viewModel.watchLater,
                onTitleClick = onNavigateToWatchLater,
                onVideoClick = onVideoClick,
            )
        }
    }

    item(key = "liked-videos", contentType = "video-shelf") {
        LibraryEntrance(index = 5) {
            LibraryVideoShelf(
                section = LibrarySection.LIKED_VIDEOS,
                videosFlow = viewModel.likedVideos,
                onTitleClick = onNavigateToLikedVideos,
                onVideoClick = onVideoClick,
            )
        }
    }

    item(key = "liked-music", contentType = "media-shelf") {
        LibraryEntrance(index = 6) {
            LibraryMediaShelfRoute(
                section = LibrarySection.LIKED_MUSIC,
                itemsFlow = viewModel.likedMusic,
                onTitleClick = onNavigateToLikedMusic,
                onVideoClick = onVideoClick,
                onMusicClick = onMusicClick,
                onDownloadedVideoClick = onDownloadedVideoClick,
                onDownloadedMusicClick = onDownloadedMusicClick,
            )
        }
    }

    item(key = "downloads", contentType = "media-shelf") {
        LibraryEntrance(index = 7) {
            val activeDownloads by viewModel.activeDownloadCount.collectAsStateWithLifecycle()
            LibraryMediaShelfRoute(
                section = LibrarySection.DOWNLOADS,
                itemsFlow = viewModel.downloads,
                onTitleClick = onNavigateToDownloads,
                onVideoClick = onVideoClick,
                onMusicClick = onMusicClick,
                onDownloadedVideoClick = onDownloadedVideoClick,
                onDownloadedMusicClick = onDownloadedMusicClick,
                emptyMessage =
                    stringResource(if ((activeDownloads ?: 0) > 0) R.string.downloads_section_in_progress else R.string.empty_downloads),
            )
        }
    }

    item(key = "downloaded-games", contentType = "games-shelf") {
        app.zaid.flow.ui.screens.home.DownloadedGamesShelf()
    }

    if (shortsEnabled) {
        item(key = "saved-shorts", contentType = "shorts-shelf") {
            LibraryEntrance(index = 8) {
                LibraryShortsShelfRoute(
                    section = LibrarySection.SAVED_SHORTS,
                    shortsFlow = viewModel.savedShorts,
                    onTitleClick = onNavigateToSavedShorts,
                    onShortClick = onSavedShortClick,
                )
            }
        }
    }
}

/**
 * Fades and lifts a shelf in the first time it appears, a little after the one above it, so the page
 * settles top to bottom instead of popping in. Scrolling a shelf back into view does not replay it.
 */
@Composable
private fun LibraryEntrance(
    index: Int,
    content: @Composable () -> Unit,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!shown) {
            delay(index.coerceAtMost(6) * 70L)
            shown = true
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow),
        label = "libraryEntrance",
    )
    Box(
        modifier =
            Modifier.graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 24.dp.toPx()
            },
    ) {
        content()
    }
}
