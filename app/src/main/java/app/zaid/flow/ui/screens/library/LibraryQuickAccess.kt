package app.zaid.flow.ui.screens.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class QuickAccessEntry(
    val section: LibrarySection,
    val count: Int?,
    val onClick: () -> Unit,
)

/**
 * A row of pills straight under the profile header: one tap to History, Watch later, Liked videos,
 * Downloads or Saved Shorts, each with a live count that rolls when it changes. The pills press in
 * with a spring and slide into place as the counts arrive.
 */
@Composable
internal fun LibraryQuickAccess(
    counts: LibraryCounts?,
    shortsEnabled: Boolean,
    onNavigateToHistory: () -> Unit,
    onNavigateToWatchLater: () -> Unit,
    onNavigateToLikedVideos: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToSavedShorts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries =
        remember(
            counts,
            shortsEnabled,
            onNavigateToHistory,
            onNavigateToWatchLater,
            onNavigateToLikedVideos,
            onNavigateToDownloads,
            onNavigateToSavedShorts,
        ) {
            buildList {
                add(QuickAccessEntry(LibrarySection.HISTORY, counts?.history, onNavigateToHistory))
                add(QuickAccessEntry(LibrarySection.WATCH_LATER, counts?.watchLater, onNavigateToWatchLater))
                add(QuickAccessEntry(LibrarySection.LIKED_VIDEOS, counts?.likedVideos, onNavigateToLikedVideos))
                add(
                    QuickAccessEntry(
                        LibrarySection.DOWNLOADS,
                        counts?.let { it.downloadedVideos + it.downloadedTracks },
                        onNavigateToDownloads,
                    ),
                )
                if (shortsEnabled) add(QuickAccessEntry(LibrarySection.SAVED_SHORTS, counts?.savedShorts, onNavigateToSavedShorts))
            }
        }
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.section.name }) { entry ->
            QuickAccessPill(entry)
        }
    }
}

@Composable
private fun QuickAccessPill(entry: QuickAccessEntry) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "quickAccessPress",
    )
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(interactionSource = interaction, indication = null, onClick = entry.onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = entry.section.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = entry.section.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            AnimatedContent(
                targetState = entry.count,
                transitionSpec = {
                    (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220)))
                        .togetherWith(slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(120)))
                },
                label = "quickAccessCount",
            ) { count ->
                if (count != null && count > 0) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
