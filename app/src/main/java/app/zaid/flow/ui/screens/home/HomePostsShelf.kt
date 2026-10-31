package app.zaid.flow.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.zaid.flow.account.AccountActions
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.innertube.pages.renderer.CommunityPost
import app.zaid.flow.innertube.pages.renderer.PostAttachment
import app.zaid.flow.ui.theme.amoledCardOutline
import app.zaid.flow.utils.ThumbnailUrlResolver
import coil3.compose.AsyncImage

// Every card is exactly this size, whatever the post holds: header, text, a 1:1 media square, actions.
private val PostCardWidth = 300.dp
private val PostCardHeight = 452.dp
private val PostCardPadding = 12.dp
private val PostTextSlotHeight = 56.dp

/** First image of a post, resolved to full size, or null when the post has none. */
internal fun CommunityPost.imageUrls(): List<String> =
    (attachment as? PostAttachment.Images)?.urls.orEmpty().map { ThumbnailUrlResolver.resolveCommunityPostImage(it) }

/** One row of posts from the account's Home. [onPostClick] gets (row, postIndex, imageIndex). */
@Composable
internal fun HomePostsShelf(
    row: HomePostRow,
    onPostClick: (HomePostRow, Int, Int) -> Unit,
    onComment: (CommunityPost) -> Unit,
    modifier: Modifier = Modifier,
    onVideoClick: (app.zaid.flow.data.model.Video) -> Unit = {},
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(row.posts, key = { _, p -> p.id }) { index, post ->
            HomePostCard(post = post, onImageClick = { img -> onPostClick(row, index, img) }, onComment = { onComment(post) }, onVideoClick = onVideoClick)
        }
    }
}

@Composable
private fun HomePostCard(
    post: CommunityPost,
    onImageClick: (Int) -> Unit,
    onComment: () -> Unit,
    onVideoClick: (app.zaid.flow.data.model.Video) -> Unit,
) {
    val images = remember(post.id) { post.imageUrls() }
    val hasMedia = images.isNotEmpty() || post.attachment is PostAttachment.SharedVideo || post.attachment is PostAttachment.Poll
    Column(
        modifier =
            Modifier
                .width(PostCardWidth)
                .height(PostCardHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .amoledCardOutline(RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier.padding(start = PostCardPadding, end = PostCardPadding, top = PostCardPadding, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = post.authorAvatarUrl,
                contentDescription = null,
                modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = post.authorName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = post.publishedTimeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (hasMedia) {
            // Fixed text slot: three lines at most, the same height with or without text.
            Text(
                text = post.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(PostTextSlotHeight).padding(horizontal = PostCardPadding, vertical = 2.dp),
            )
        }
        // The media slot is always a square.
        Box(
            Modifier
                .padding(horizontal = PostCardPadding)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            when {
                images.isNotEmpty() -> HomePostImages(images, onImageClick)
                post.attachment is PostAttachment.SharedVideo -> HomePostSharedVideo((post.attachment as PostAttachment.SharedVideo).video, onVideoClick)
                post.attachment is PostAttachment.Poll -> HomePostPoll(post.id, post.attachment as PostAttachment.Poll)
                else ->
                    Text(
                        text = post.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(14.dp),
                    )
            }
        }
        Spacer(Modifier.weight(1f))
        HomePostActionBar(post = post, onComment = onComment, modifier = Modifier.padding(horizontal = PostCardPadding, vertical = 10.dp))
    }
}

/** The post's images as a swipeable 1:1 gallery with a current/total pill and page dots. */
@Composable
private fun HomePostImages(
    images: List<String>,
    onImageClick: (Int) -> Unit,
) {
    val pager = rememberPagerState { images.size }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { images[it] }) { page ->
            AsyncImage(
                model = images[page],
                contentDescription = stringResource(R.string.community_post_image_content_description),
                modifier = Modifier.fillMaxSize().clickable { onImageClick(page) },
                contentScale = ContentScale.Crop,
            )
        }
        if (images.size > 1) {
            Text(
                text = "${pager.currentPage + 1}/${images.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(minOf(images.size, 8)) { i ->
                    Box(
                        Modifier
                            .size(if (i == pager.currentPage) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                    )
                }
            }
        }
    }
}

/** Poll choices as tappable rows; once voted (or when YouTube sends results) each shows its share as a bar. */
@Composable
private fun HomePostPoll(
    postId: String,
    poll: PostAttachment.Poll,
) {
    var picked by remember(postId) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val showResults = picked != null || poll.choices.any { it.voteRatio != null }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        poll.choices.take(4).forEachIndexed { i, c ->
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(enabled = picked == null && c.voteAction != null) {
                        picked = i
                        scope.launch { c.voteAction?.let { AccountActions.sendAction(it) } }
                    },
            ) {
                if (showResults && c.voteRatio != null) {
                    Box(
                        Modifier
                            .fillMaxWidth(c.voteRatio.coerceIn(0f, 1f))
                            .height(36.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    )
                }
                Row(Modifier.height(36.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (picked == i) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (showResults && c.voteCountText != null) {
                        Text(c.voteCountText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        poll.totalVotesText?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A video shared in a post: thumbnail, title and channel; opens the video in the player. */
@Composable
private fun HomePostSharedVideo(
    video: app.zaid.flow.data.model.Video,
    onClick: (app.zaid.flow.data.model.Video) -> Unit,
) {
    Column(Modifier.fillMaxSize().clickable { onClick(video) }) {
        AsyncImage(
            model = video.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.padding(12.dp)) {
            Text(
                video.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                video.channelName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
