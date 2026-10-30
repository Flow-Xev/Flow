package app.zaid.flow.ui.components.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.data.model.Video
import app.zaid.flow.ui.components.shared.DurationBadge
import app.zaid.flow.ui.components.shared.MediaIconBadge
import app.zaid.flow.ui.components.shared.VideoThumbnailImage
import app.zaid.flow.ui.components.shared.feedStripCardWidth
import coil3.compose.AsyncImage

internal val LibraryShelfCardWidth = 220.dp
internal val LibraryShelfArtworkHeight = LibraryShelfCardWidth * 9f / 16f

/** A shelf card's width: the phone's pinned width, derived from the shelf's own width once it widens. */
internal fun libraryShelfCardWidth(availableWidth: Dp): Dp = feedStripCardWidth(availableWidth, LibraryShelfCardWidth)

@Composable
internal fun LibraryVideoCard(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = LibraryShelfCardWidth,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "libraryCardPress",
    )
    Column(
        modifier =
            modifier
                .width(width)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            VideoThumbnailImage(
                videoId = video.id,
                model = video.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            if (video.duration > 0) {
                DurationBadge(
                    seconds = video.duration,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = video.channelName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun LibraryAlbumCard(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    isDownloaded: Boolean = false,
    modifier: Modifier = Modifier,
    artworkSize: Dp = LibraryShelfArtworkHeight,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .width(artworkSize)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.size(artworkSize),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
        ) {
            Box {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )

                if (isDownloaded) {
                    MediaIconBadge(
                        icon = Icons.Rounded.OfflinePin,
                        contentDescription = stringResource(R.string.status_downloaded),
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val ShortsGroupGap = 2.dp

/**
 * One shelf box holding 1–4 Shorts side by side. It is the same size as [LibraryVideoCard]
 * (16:9 artwork plus two text lines) and each poster opens its own Short.
 */
@Composable
internal fun LibraryShortsGroupCard(
    shorts: List<Video>,
    onShortClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = LibraryShelfCardWidth,
) {
    val visible = shorts.take(MAX_SHORTS_PER_GROUP)
    Column(
        modifier = modifier.width(width).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            horizontalArrangement = Arrangement.spacedBy(ShortsGroupGap),
        ) {
            visible.forEach { short ->
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onShortClick(short) },
                ) {
                    VideoThumbnailImage(
                        videoId = short.id,
                        model = short.thumbnailUrl,
                        contentDescription = short.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        portrait = true,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.history_tab_shorts),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = visible.first().title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
