package app.zaid.flow.ui.screens.player.related

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.data.model.Video
import app.zaid.flow.ui.components.shared.card.MediaVideoCard
import app.zaid.flow.ui.components.shared.card.VideoCardLayout

/**
 * The landscape fullscreen drawer behind the "More videos" button. Same [RelatedFeed] as the
 * portrait list, so it pages endlessly; the chips slide in whenever the list is at the top or is
 * being scrolled back up, and slide away while scrolling down so they never cover a row.
 */
@Composable
internal fun RelatedVideosDrawer(
    videoId: String,
    related: List<Video>,
    onVideoClick: (Video) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val feed = rememberRelatedFeed(videoId)
    val videos = feed.display(related)
    val listState = rememberLazyListState()
    LoadMoreRelatedOnScroll(listState, feed, related)

    var chipsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var prevIndex = 0
        var prevOffset = 0
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val atTop = index == 0 && offset == 0
                val up = index < prevIndex || (index == prevIndex && offset < prevOffset - SCROLL_SLOP)
                val down = index > prevIndex || (index == prevIndex && offset > prevOffset + SCROLL_SLOP)
                if (atTop || up) {
                    chipsVisible = true
                } else if (down) {
                    chipsVisible = false
                }
                if (up || down) {
                    prevIndex = index
                    prevOffset = offset
                }
            }
    }
    // A new chip is a new list; start it from the top.
    LaunchedEffect(feed.selectedChip) { listState.scrollToItem(0) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.more_videos),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
            }
        }
        AnimatedVisibility(
            visible = chipsVisible && feed.chips.size >= 2,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            RelatedFeedChips(feed)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(videos, key = { it.id }) { video ->
                MediaVideoCard(
                    video = video,
                    layout = VideoCardLayout.Row,
                    onClick = { onVideoClick(video) },
                )
            }
            relatedFeedFooter(feed)
        }
    }
}

private const val SCROLL_SLOP = 12
