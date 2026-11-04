package app.zaid.flow.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.account.AccountActions
import app.zaid.flow.data.model.Comment
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.pages.renderer.CommunityPost
import app.zaid.flow.ui.components.shared.FlowCommentsBottomSheet
import app.zaid.flow.ui.components.shared.rememberCommentPoster
import kotlinx.coroutines.launch

/** Reactions made on Home posts this session: 1 liked, -1 disliked. Shared so card and fullscreen agree. */
private val reactions = mutableStateMapOf<String, Int>()

private fun CommunityPost.reaction(): Int = reactions[id] ?: if (isLiked) 1 else 0

/**
 * Like, dislike and comment for one post. Like is sent to YouTube when the post carries action tokens;
 * YouTube gives posts no dislike action here, so dislike is kept on this device only.
 */
@Composable
internal fun HomePostActionBar(
    post: CommunityPost,
    onComment: () -> Unit,
    modifier: Modifier = Modifier,
    idleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val state = post.reaction()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canLike = post.likeAction != null && post.unlikeAction != null
    val active = MaterialTheme.colorScheme.primary

    fun send(token: String?, rollback: Int) {
        scope.launch {
            val result = token?.let { AccountActions.sendAction(it) }
            if (result == null || result.isFailure) {
                reactions[post.id] = rollback
                Toast.makeText(context, result?.exceptionOrNull()?.message ?: "Couldn't like", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pill = MaterialTheme.colorScheme.surfaceContainerHighest
    val tone = if (idleColor == MaterialTheme.colorScheme.onSurfaceVariant) pill else Color.White.copy(alpha = 0.14f)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Like | dislike in one pill, as on YouTube.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(32.dp).clip(CircleShape).background(tone),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxHeight().clickable(enabled = canLike) {
                        val before = state
                        if (before == 1) {
                            reactions[post.id] = 0
                            send(post.unlikeAction, 1)
                        } else {
                            reactions[post.id] = 1
                            send(post.likeAction, before)
                        }
                    }.padding(start = 12.dp, end = 10.dp),
            ) {
                Icon(
                    if (state == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = stringResource(R.string.like),
                    tint = if (state == 1) active else idleColor,
                    modifier = Modifier.size(16.dp),
                )
                if (post.likeCountText.isNotBlank()) {
                    Spacer(Modifier.width(5.dp))
                    Text(post.likeCountText, style = MaterialTheme.typography.labelMedium, color = idleColor)
                }
            }
            Box(Modifier.width(1.dp).height(16.dp).background(idleColor.copy(alpha = 0.3f)))
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.fillMaxHeight().clickable {
                        val before = state
                        reactions[post.id] = if (before == -1) 0 else -1
                        if (before == 1) send(post.unlikeAction, 1)
                    }.padding(horizontal = 12.dp),
            ) {
                Icon(
                    if (state == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = null,
                    tint = if (state == -1) active else idleColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(32.dp).clip(CircleShape).background(tone).clickable(onClick = onComment).padding(horizontal = 12.dp),
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = idleColor, modifier = Modifier.size(16.dp))
            if (post.commentCountText.isNotBlank()) {
                Spacer(Modifier.width(5.dp))
                Text(post.commentCountText, style = MaterialTheme.typography.labelMedium, color = idleColor)
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.height(32.dp).clip(CircleShape).background(tone).clickable {
                    val send =
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, "https://www.youtube.com/post/${post.id}")
                        }
                    context.startActivity(android.content.Intent.createChooser(send, null))
                }.padding(horizontal = 12.dp),
        ) {
            Icon(Icons.Outlined.Share, contentDescription = null, tint = idleColor, modifier = Modifier.size(16.dp))
        }
    }
}

/** Comments of the post opened from Home, in the same sheet the channel Posts tab uses. */
@Composable
internal fun HomePostCommentsSheet(
    activePost: CommunityPost?,
    comments: List<Comment>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    viewModel: HomeViewModel,
) {
    if (activePost == null) return
    FlowCommentsBottomSheet(
        comments = comments,
        isLoading = isLoading,
        onDismiss = viewModel::closePostComments,
        isLoadingMore = isLoadingMore,
        onLoadMore = viewModel::loadMorePostComments,
        hasMore = hasMore,
        onLoadReplies = viewModel::loadPostCommentReplies,
        onLoadMoreReplies = viewModel::loadMorePostCommentReplies,
        onPostComment =
            if (YouTube.innerTube.hasAccount) {
                rememberCommentPoster { text, done -> viewModel.postPostComment(text, done) }
            } else {
                null
            },
    )
}
