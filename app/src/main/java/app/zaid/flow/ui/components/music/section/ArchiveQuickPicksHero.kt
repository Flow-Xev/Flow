package app.zaid.flow.ui.components.music.section

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.carousel.CarouselState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.zaid.flow.ui.ModeSide
import app.zaid.flow.ui.modeCard
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zaid.flow.data.music.model.MusicTrack
import coil3.compose.AsyncImage

/**
 * ArchiveTune's "Quick picks": a title row with a play-all button over a centred hero carousel of
 * large artwork cards with the title and artist laid over a bottom gradient.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ArchiveQuickPicksHero(
    title: String,
    tracks: List<MusicTrack>,
    onPlayAll: () -> Unit,
    onTrackClick: (MusicTrack) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picks = tracks.distinctBy { it.videoId }
    if (picks.isEmpty()) return
    val state: CarouselState = rememberCarouselState { picks.size }
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(onClick = onPlayAll) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Same proportions as ArchiveTune: taller cards on wider windows, never wider than 440dp.
        val heroHeight = if (maxWidth >= 840.dp) 380.dp else if (maxWidth >= 600.dp) 356.dp else 332.dp
        HorizontalCenteredHeroCarousel(
            state = state,
            itemSpacing = 10.dp,
            contentPadding = PaddingValues(horizontal = 16.dp),
            maxItemWidth = (maxWidth - 48.dp).coerceIn(232.dp, 440.dp),
            modifier = Modifier.fillMaxWidth().height(heroHeight),
        ) { index ->
            val track = picks[index]
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .modeCard(ModeSide.Music, "hero:${track.videoId}", track.thumbnailUrl, 28f)
                        .maskClip(MaterialTheme.shapes.extraLarge)
                        .maskBorder(
                            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                            MaterialTheme.shapes.extraLarge,
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .combinedClickable(
                            onClick = { onTrackClick(track) },
                            onLongClick = { onTrackMenu(track) },
                        ),
            ) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.5f to Color.Black.copy(alpha = 0.08f),
                                    1f to Color.Black.copy(alpha = 0.84f),
                                ),
                            ),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        }
    }
}
