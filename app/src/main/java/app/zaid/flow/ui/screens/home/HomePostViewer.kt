package app.zaid.flow.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.zaid.flow.R
import coil3.compose.AsyncImage

internal data class PostViewerTarget(
    val row: HomePostRow,
    val postIndex: Int,
    val imageIndex: Int,
)

/** Fullscreen gallery over every image of one channel's row, with a current/total pill at the top right. */
@Composable
internal fun HomePostViewer(
    target: PostViewerTarget,
    onDismiss: () -> Unit,
    onComment: (app.zaid.flow.innertube.pages.renderer.CommunityPost) -> Unit = {},
) {
    val pages = remember(target) { target.row.posts.flatMapIndexed { pi, p -> p.imageUrls().map { pi to it } } }
    if (pages.isEmpty()) {
        onDismiss()
        return
    }
    val start =
        (pages.indexOfFirst { it.first == target.postIndex }.coerceAtLeast(0) + target.imageIndex)
            .coerceIn(0, pages.lastIndex)
    val pager = rememberPagerState(initialPage = start) { pages.size }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
            val cur = target.row.posts[pages[pager.currentPage].first]
            Column(Modifier.fillMaxSize()) {
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    AsyncImage(
                        model = pages[page].second,
                        contentDescription = stringResource(R.string.community_post_image_content_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                // Solid surface under the text and buttons: always readable, never over the picture.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (cur.text.isNotBlank()) {
                        Text(
                            text = cur.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    HomePostActionBar(post = cur, onComment = { onComment(cur) })
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = "${pager.currentPage + 1}/${pages.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
