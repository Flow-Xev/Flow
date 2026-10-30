package app.zaid.flow.ui.components.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.music.DownloadedTrack
import app.zaid.flow.data.music.model.MusicTrack
import app.zaid.flow.data.video.DownloadedVideo
import app.zaid.flow.ui.components.shared.MediaShortCard
import app.zaid.flow.ui.components.shared.ShimmerBone
import app.zaid.flow.ui.components.shared.ShortCardDefaults
import app.zaid.flow.ui.components.shared.animateMediaListItem

private const val PLACEHOLDER_CARD_COUNT = 2
private const val PLACEHOLDER_STAGGER_MS = 120
private val HeaderIconSize = 20.dp

/**
 * A shelf's title row, as on YouTube's You tab: a bold title with a "View all" pill on the right
 * when the shelf opens a page. The icon is kept in the signature for callers but no longer drawn;
 * the title carries the shelf on its own.
 */
@Composable
private fun LibraryShelfHeader(
    title: String,
    @Suppress("UNUSED_PARAMETER") icon: ImageVector,
    showChevron: Boolean,
    horizontalInset: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalInset, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (showChevron) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = stringResource(R.string.action_view_all),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
internal fun LibraryShelf(
    title: String,
    icon: ImageVector,
    onTitleClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 16.dp,
    content: LazyListScope.(cardWidth: Dp) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cardWidth = libraryShelfCardWidth(maxWidth)
        Column(modifier = Modifier.fillMaxWidth()) {
            LibraryShelfHeader(
                title = title,
                icon = icon,
                showChevron = onTitleClick != null,
                horizontalInset = horizontalInset,
                modifier = if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier,
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = horizontalInset),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content(cardWidth)
            }
        }
    }
}

/** A shelf with nothing in it yet that still opens its page, with [message] where the cards go. */
@Composable
internal fun LibraryEmptyShelf(
    title: String,
    icon: ImageVector,
    message: String,
    onTitleClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onTitleClick)) {
        LibraryShelfHeader(title = title, icon = icon, showChevron = true, horizontalInset = 16.dp)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
internal fun LibraryMediaShelf(
    title: String,
    icon: ImageVector,
    items: List<LibraryMediaItem>,
    sourceName: String,
    onTitleClick: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onMusicClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onDownloadedVideoClick: (List<DownloadedVideo>, Int) -> Unit,
    onDownloadedMusicClick: (List<DownloadedTrack>, Int) -> Unit,
    onShortClick: (shelf: List<Video>, tapped: Video) -> Unit = { _, tapped -> onVideoClick(tapped) },
) {
    val shelfShorts =
        remember(items) {
            items.filterIsInstance<LibraryMediaItem.ShortsGroupItem>().flatMap { it.shorts }.distinctBy { it.id }
        }
    val musicQueue =
        remember(items) {
            items.mapNotNull { (it as? LibraryMediaItem.MusicItem)?.track }
        }
    val downloadedVideoQueue =
        remember(items) {
            items.mapNotNull { (it as? LibraryMediaItem.DownloadedVideoItem)?.download }
        }
    val downloadedMusicQueue =
        remember(items) {
            items.mapNotNull { (it as? LibraryMediaItem.DownloadedMusicItem)?.download }
        }

    LibraryShelf(title = title, icon = icon, onTitleClick = onTitleClick) { cardWidth ->
        val artworkSize = cardWidth * 9f / 16f
        items(
            items = items,
            key = LibraryMediaItem::key,
            contentType = {
                when (it) {
                    is LibraryMediaItem.VideoItem,
                    is LibraryMediaItem.DownloadedVideoItem,
                    -> "video"

                    is LibraryMediaItem.ShortsGroupItem -> "shorts-group"

                    is LibraryMediaItem.MusicItem,
                    is LibraryMediaItem.DownloadedMusicItem,
                    -> "music"
                }
            },
        ) { item ->
            when (item) {
                is LibraryMediaItem.VideoItem -> {
                    LibraryVideoCard(
                        video = item.video,
                        onClick = { onVideoClick(item.video) },
                        width = cardWidth,
                        modifier = animateMediaListItem(),
                    )
                }

                is LibraryMediaItem.ShortsGroupItem -> {
                    LibraryShortsGroupCard(
                        modifier = animateMediaListItem(),
                        shorts = item.shorts,
                        onShortClick = { tapped -> onShortClick(shelfShorts, tapped) },
                        width = cardWidth,
                    )
                }

                is LibraryMediaItem.MusicItem -> {
                    LibraryAlbumCard(
                        modifier = animateMediaListItem(),
                        title = item.track.title,
                        subtitle = item.track.artist,
                        thumbnailUrl = item.track.thumbnailUrl,
                        onClick = { onMusicClick(item.track, musicQueue, sourceName) },
                        artworkSize = artworkSize,
                    )
                }

                is LibraryMediaItem.DownloadedVideoItem -> {
                    LibraryVideoCard(
                        modifier = animateMediaListItem(),
                        video = item.download.video,
                        onClick = {
                            val index =
                                downloadedVideoQueue.indexOfFirst {
                                    it.video.id == item.download.video.id
                                }
                            if (index >= 0) onDownloadedVideoClick(downloadedVideoQueue, index)
                        },
                        width = cardWidth,
                    )
                }

                is LibraryMediaItem.DownloadedMusicItem -> {
                    LibraryAlbumCard(
                        modifier = animateMediaListItem(),
                        title = item.download.track.title,
                        subtitle = item.download.track.artist,
                        thumbnailUrl = item.download.track.thumbnailUrl,
                        isDownloaded = true,
                        onClick = {
                            val index =
                                downloadedMusicQueue.indexOfFirst {
                                    it.track.videoId == item.download.track.videoId
                                }
                            if (index >= 0) onDownloadedMusicClick(downloadedMusicQueue, index)
                        },
                        artworkSize = artworkSize,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LibraryShortsShelf(
    title: String,
    icon: ImageVector,
    shorts: List<Video>,
    onTitleClick: () -> Unit,
    onShortClick: (Video) -> Unit,
) {
    LibraryShelf(title = title, icon = icon, onTitleClick = onTitleClick) {
        items(shorts, key = Video::id, contentType = { "short" }) { short ->
            MediaShortCard(
                video = short,
                onClick = { onShortClick(short) },
                modifier = animateMediaListItem().width(ShortCardDefaults.MinWidth),
                removableFromSavedShorts = true,
            )
        }
    }
}

@Composable
internal fun LibraryShelfPlaceholder(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    portrait: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cardWidth = if (portrait) ShortCardDefaults.MinWidth else libraryShelfCardWidth(maxWidth)
        val aspectRatio = if (portrait) ShortCardDefaults.ASPECT_RATIO else 16f / 9f
        Column(modifier = Modifier.fillMaxWidth()) {
            LibraryShelfHeader(
                title = title,
                icon = icon,
                showChevron = false,
                horizontalInset = 16.dp,
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(PLACEHOLDER_CARD_COUNT) { index ->
                    Column(
                        modifier = Modifier.width(cardWidth),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ShimmerBone(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(aspectRatio),
                            shape = MaterialTheme.shapes.medium,
                            delayMillis = index * PLACEHOLDER_STAGGER_MS,
                        )
                        ShimmerBone(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(14.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            delayMillis = index * PLACEHOLDER_STAGGER_MS,
                        )
                    }
                }
            }
        }
    }
}
