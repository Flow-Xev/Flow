package app.zaid.flow.ui.screens.music

import app.zaid.flow.ui.ModeSide
import app.zaid.flow.ui.modeCard
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.ui.components.layout.flowBottomContentPadding
import app.zaid.flow.ui.components.layout.topbar.FlowTopBar
import coil3.compose.AsyncImage

private enum class LibraryFilter(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    All("Library", Icons.Filled.GraphicEq),
    Playlists("Playlists", Icons.Filled.QueueMusic),
    Artists("Artists", Icons.Filled.Person),
}

private class LibraryEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val round: Boolean,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onClick: () -> Unit,
)

/** The music Library in ArchiveTune's layout: filter chips, a grid/list toggle and one mixed collection. */
@Composable
fun MusicLibraryScreen(
    onPlaylistClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onVideoModeClick: () -> Unit,
    onOpenAccount: () -> Unit,
    libraryViewModel: MusicHomeLibraryViewModel = hiltViewModel(),
    accountViewModel: app.zaid.flow.ui.screens.library.LibraryViewModel = hiltViewModel(),
) {
    val activeAccount by accountViewModel.activeAccount.collectAsStateWithLifecycle()
    val accounts by accountViewModel.accounts.collectAsStateWithLifecycle()
    val incognito by accountViewModel.incognito.collectAsStateWithLifecycle()
    var accountExpanded by rememberSaveable { mutableStateOf(false) }
    var signingIn by rememberSaveable { mutableStateOf(false) }

    if (signingIn) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { signingIn = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                app.zaid.flow.ui.screens.account.AccountSignInScreen(
                    preferred = app.zaid.flow.ui.screens.account.SignInTarget.Music,
                    onSignedIn = { signingIn = false },
                    onCancel = { signingIn = false },
                )
            }
        }
    }

    val playlists by libraryViewModel.allPlaylists.collectAsStateWithLifecycle()
    val artists by libraryViewModel.subscriptions.collectAsStateWithLifecycle()
    var asGrid by rememberSaveable { mutableStateOf(true) }

    fun entriesFor(filter: LibraryFilter): List<LibraryEntry> =
            buildList {
                if (filter == LibraryFilter.All) {
                    add(LibraryEntry("auto_history", "History", "Recently played", null, false, Icons.Filled.History, onHistoryClick))
                    add(LibraryEntry("auto_downloads", "Downloads", "Offline music", null, false, Icons.Filled.Download, onDownloadsClick))
                }
                if (filter != LibraryFilter.Artists) {
                    playlists.forEach {
                        add(LibraryEntry("pl_${it.id}", it.title, "Playlist \u2022 ${it.trackCount} songs", it.thumbnailUrl, false) { onPlaylistClick(it.id) })
                    }
                }
                if (filter != LibraryFilter.Playlists) {
                    artists.forEach {
                        add(LibraryEntry("ar_${it.channelId}", it.name, "Artist", it.thumbnailUrl, true) { onArtistClick(it.channelId) })
                    }
                }
            }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        FlowTopBar(
            title = stringResource(R.string.nav_library),
            actions = {
                FilledTonalButton(
                    onClick = onVideoModeClick,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    Text("Video")
                }
                IconButton(onClick = { asGrid = !asGrid }) {
                    Icon(if (asGrid) Icons.Filled.ViewList else Icons.Filled.ViewModule, contentDescription = null)
                }
            },
        )
        app.zaid.flow.ui.screens.library.LibraryAccountCard(
            account = activeAccount,
            accounts = accounts,
            expanded = accountExpanded,
            incognito = incognito,
            onToggle = { if (activeAccount == null) signingIn = true else accountExpanded = !accountExpanded },
            onSwitchAccount = accountViewModel::switchAccount,
            onIncognitoChange = accountViewModel::setIncognito,
            onAddAccount = { signingIn = true },
            onMoreSettings = onOpenAccount,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        val pagerState = androidx.compose.foundation.pager.rememberPagerState { LibraryFilter.entries.size }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // ArchiveTune's filter row: pill chips that stay in step with the swipeable pages below.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            items(LibraryFilter.entries) { f ->
                LibraryTabChip(f.label, f.icon, pagerState.currentPage == f.ordinal) {
                    scope.launch { pagerState.animateScrollToPage(f.ordinal) }
                }
            }
        }
        val bottom = flowBottomContentPadding(96.dp)
        androidx.compose.foundation.pager.HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val entries = remember(playlists, artists, page, onPlaylistClick, onArtistClick, onHistoryClick, onDownloadsClick) { entriesFor(LibraryFilter.entries[page]) }
            if (asGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries, key = { it.key }) { e ->
                        Column(modifier = Modifier.clickable(onClick = e.onClick)) {
                            Artwork(e, Modifier.fillMaxWidth().aspectRatio(1f).modeCard(ModeSide.Music, "lib:${e.key}", e.thumbnail.orEmpty(), 12f))
                            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text(e.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = bottom), modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.key }) { e ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = e.onClick).padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Artwork(e, Modifier.size(56.dp))
                            Column(modifier = Modifier.padding(start = 14.dp)) {
                                Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(e.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ArchiveTune's ExpressiveTabChip: a pill that fills with the primary colour and bounces when selected. */
@Composable
private fun LibraryTabChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.92f else if (selected) 1.05f else 1f,
        androidx.compose.animation.core.spring(androidx.compose.animation.core.Spring.DampingRatioMediumBouncy, androidx.compose.animation.core.Spring.StiffnessLow),
        label = "libraryChipScale",
    )
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "libraryChipBg",
    )
    val fg by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "libraryChipFg",
    )
    Row(
        modifier =
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(bg)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(20.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun Artwork(e: LibraryEntry, modifier: Modifier) {
    val shape = if (e.round) CircleShape else RoundedCornerShape(12.dp)
    androidx.compose.foundation.layout.Box(
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (e.thumbnail.isNullOrBlank()) {
            e.icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            AsyncImage(model = e.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
