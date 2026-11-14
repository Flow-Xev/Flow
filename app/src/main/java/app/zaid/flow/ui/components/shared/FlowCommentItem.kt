package app.zaid.flow.ui.components.shared

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zaid.flow.R
import app.zaid.flow.account.AccountActions
import app.zaid.flow.account.YouTubeAccountReporter
import app.zaid.flow.data.model.Comment
import app.zaid.flow.utils.formatLikeCount
import kotlinx.coroutines.launch

private val ThreadHorizontalPadding = 16.dp
private val ThreadLineWidth = 2.dp

/** Width of the elbow column: the turn, plus the gap before the reply's avatar. */
private val ThreadConnectorWidth = 20.dp
private val ThreadElbowRadius = 8.dp

/** Where the line turns: the vertical centre of a reply's 24 dp avatar under its 8 dp top padding. */
private val ThreadElbowY = 20.dp

/** A reply to a reply starts its own branch just inside its parent's text. */
private val NestedThreadStart = 12.dp

/** Centre of the parent avatar: the row's start padding plus half the 40 dp avatar, less the line. */
private val ThreadLineStart = 35.dp
private val ThreadLineGap = 15.dp

@Composable
fun FlowCommentItem(
    comment: Comment,
    onSeekMs: (Long) -> Unit,
    onLoadReplies: (Comment) -> Unit,
    onLoadMoreReplies: (Comment) -> Unit,
    onAuthorClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
    tint: MediaArtworkTint? = null,
) {
    // A comment deleted or edited in this session shows that right away.
    @Suppress("NAME_SHADOWING")
    val comment = LocalCommentReplyState.current.applied(comment) ?: return
    var isExpanded by remember { mutableStateOf(false) }
    var isRepliesVisible by remember { mutableStateOf(false) }
    var isOverflowing by remember { mutableStateOf(false) }
    var isLoadingReplies by remember { mutableStateOf(false) }
    var commentTextLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var showFullSizeImage by remember { mutableStateOf(false) }

    val uriHandler = LocalUriHandler.current

    LaunchedEffect(comment.replies) {
        isLoadingReplies = false
    }

    val accent = tint?.accent ?: MaterialTheme.colorScheme.primary
    val chipColor = tint?.container ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val commentText = rememberCommentText(comment, accent)

    if (showFullSizeImage) {
        FullSizeImageDialog(
            imageUrl = toHighQualityAvatarUrl(comment.authorThumbnail),
            onDismiss = { showFullSizeImage = false },
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ThreadHorizontalPadding)
                    .padding(top = 14.dp, bottom = 6.dp),
        ) {
            ChannelAvatarImage(
                url = comment.authorThumbnail,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            onAvatarClick(comment.authorThumbnail)
                            showFullSizeImage = true
                        },
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (comment.isPinned) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = stringResource(R.string.pinned_comment),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = comment.pinnedByText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.pinned_by_creator),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                CommentAuthorRow(
                    comment = comment,
                    onAuthorClick = onAuthorClick,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Box(modifier = Modifier.animateContentSize()) {
                    SelectionContainer {
                        BasicText(
                            text = commentText.annotated,
                            inlineContent = commentText.inlineContent,
                            style =
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 20.sp,
                                ),
                            maxLines = if (isExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { result ->
                                commentTextLayoutResult = result
                                if (result.hasVisualOverflow) isOverflowing = true
                            },
                            modifier =
                                Modifier
                                    .richTextHighlights(
                                        text = commentText.annotated,
                                        layoutResult = { commentTextLayoutResult },
                                        color = chipColor,
                                    ).pointerInput(commentText.annotated) {
                                        detectTapGestures(
                                            onTap = { tapOffset ->
                                                val result = commentTextLayoutResult ?: return@detectTapGestures
                                                val offset = result.getOffsetForPosition(tapOffset)
                                                val handled =
                                                    commentText.handleTap(
                                                        offset = offset,
                                                        onSeekMs = onSeekMs,
                                                        onOpenUrl = { url ->
                                                            runCatching { uriHandler.openUri(url) }
                                                        },
                                                        onAuthorClick = onAuthorClick,
                                                    )
                                                if (!handled && !isExpanded && isOverflowing) isExpanded = true
                                            },
                                        )
                                    },
                        )
                    }
                }

                if (isOverflowing && !isExpanded) {
                    Text(
                        text = stringResource(R.string.read_more),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .clickable { isExpanded = true },
                    )
                }

                comment.gifUrl?.let { CommentGif(url = it) }

                Spacer(modifier = Modifier.height(8.dp))

                CommentEngagementRow(comment = comment)

                if (comment.replyCount > 0) {
                    RepliesToggle(
                        replyCount = comment.replyCount,
                        expanded = isRepliesVisible,
                        loading = isLoadingReplies,
                        onClick = {
                            if (!isRepliesVisible && comment.replies.isEmpty()) {
                                isLoadingReplies = true
                                onLoadReplies(comment)
                            }
                            isRepliesVisible = !isRepliesVisible
                        },
                    )
                }
            }
        }

        if (isRepliesVisible && comment.replies.isNotEmpty()) {
            val tree = remember(comment.replies) { buildCommentReplyTree(comment.replies) }
            ReplyThread(
                nodes = tree,
                hasMore = comment.repliesPage != null || comment.continuationToken != null,
                startPadding = ThreadLineStart,
                tint = tint,
                onSeekMs = onSeekMs,
                onAuthorClick = onAuthorClick,
                onAvatarClick = onAvatarClick,
                onLoadMore = {
                    isLoadingReplies = true
                    onLoadMoreReplies(comment)
                },
            )
        }
    }
}

/**
 * The replies under one comment, and the replies to those.
 *
 * Each reply hangs off the thread line on an elbow, the way a conversation branches: the line runs
 * down from the parent's avatar, turns into each reply, and carries on past it to the next one. A
 * reply that answered another reply sits one branch deeper, which is the only thing that tells the
 * two apart — YouTube stores a single flat level and marks the nesting with an `@handle` alone.
 */
@Composable
private fun ReplyThread(
    nodes: List<CommentReplyNode>,
    hasMore: Boolean,
    startPadding: Dp,
    tint: MediaArtworkTint?,
    onSeekMs: (Long) -> Unit,
    onAuthorClick: (String) -> Unit,
    onAvatarClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = startPadding, end = ThreadHorizontalPadding, bottom = 4.dp),
    ) {
        nodes.forEachIndexed { index, node ->
            val continues = index != nodes.lastIndex || hasMore
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                ThreadConnector(
                    color = lineColor,
                    continuesBelow = continues,
                    modifier = Modifier.fillMaxHeight(),
                )
                Column(modifier = Modifier.weight(1f)) {
                    FlowReplyItem(
                        reply = node.comment,
                        onSeekMs = onSeekMs,
                        onAuthorClick = onAuthorClick,
                        onAvatarClick = onAvatarClick,
                        tint = tint,
                    )
                    if (node.children.isNotEmpty()) {
                        ReplyThread(
                            nodes = node.children,
                            hasMore = false,
                            startPadding = NestedThreadStart,
                            tint = tint,
                            onSeekMs = onSeekMs,
                            onAuthorClick = onAuthorClick,
                            onAvatarClick = onAvatarClick,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            }
        }

        if (hasMore) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                ThreadConnector(
                    color = lineColor,
                    continuesBelow = false,
                    modifier = Modifier.fillMaxHeight(),
                )
                Text(
                    text = stringResource(R.string.load_more_replies),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .padding(start = ThreadLineGap)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onLoadMore)
                            .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The line into one reply: down from above, a quarter turn towards the text, and on down to the
 * next reply when there is one.
 */
@Composable
private fun ThreadConnector(
    color: Color,
    continuesBelow: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.width(ThreadConnectorWidth)) {
        val stroke = ThreadLineWidth.toPx()
        val elbowY = ThreadElbowY.toPx()
        val radius = ThreadElbowRadius.toPx()
        val x = stroke / 2f

        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, (elbowY - radius).coerceAtLeast(0f)),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = -90f,
            useCenter = false,
            topLeft = Offset(x, elbowY - 2 * radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(x + radius, elbowY),
            end = Offset(size.width, elbowY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (continuesBelow) {
            drawLine(
                color = color,
                start = Offset(x, (elbowY - radius).coerceAtLeast(0f)),
                end = Offset(x, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** "12 replies" with the chevron that says it opens, in place of the rule that used to sit here. */
@Composable
private fun RepliesToggle(
    replyCount: Int,
    expanded: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(top = 8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text =
                if (expanded) {
                    stringResource(R.string.hide_replies)
                } else {
                    pluralStringResource(R.plurals.view_replies_template, replyCount, replyCount)
                },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        if (loading) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun CommentAuthorRow(
    comment: Comment,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CommentAuthorName(
            comment = comment,
            onAuthorClick = onAuthorClick,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (comment.isVerified || comment.isArtist) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.Verified,
                contentDescription = stringResource(R.string.verified),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = localizedCommentPublishedTime(comment.publishedTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CommentAuthorName(
    comment: Comment,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = formatAuthorName(comment.author)
    val clickable =
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(),
            onClick = { onAuthorClick(commentAuthorChannelRef(comment)) },
        )
    if (comment.isCreator) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = CircleShape,
            modifier = clickable,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        return
    }
    Text(
        text = name,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = clickable,
    )
}

/**
 * Like and dislike (sent to YouTube as the signed-in account), the like count, and the heart the
 * creator left on this comment. The thumbs are inert when signed out or when YouTube sent no action
 * tokens for the comment.
 */
@Composable
internal fun CommentEngagementRow(
    comment: Comment,
    modifier: Modifier = Modifier,
) {
    val replyState = LocalCommentReplyState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    // Translation: the target follows the app language (Hindi for a Hindi UI, English otherwise); the chips under a result switch it.
    var translateTarget by remember {
        mutableStateOf(
            if (java.util.Locale
                    .getDefault()
                    .language == "hi"
            ) {
                "hi"
            } else {
                "en"
            },
        )
    }
    var translation by remember(comment.id, comment.text) {
        mutableStateOf<app.zaid.flow.data.translate.CommentTranslation?>(null)
    }
    var translating by remember(comment.id, comment.text) { mutableStateOf(false) }
    val canTranslate =
        remember(comment.text, translateTarget) {
            app.zaid.flow.data.translate
                .looksTranslatable(comment.text, translateTarget)
        }
    val translate: (String) -> Unit = { target ->
        if (!translating) {
            translating = true
            scope.launch {
                val result =
                    app.zaid.flow.data.translate.CommentTranslator
                        .translate(comment.text, target)
                translating = false
                result
                    .onSuccess {
                        translation = it
                        translateTarget = target
                        if (it.sourceLang.substringBefore('-') == target &&
                            app.zaid.flow.data.translate
                                .sameText(comment.text, it.text)
                        ) {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.comment_already_in, languageName(target)),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }.onFailure {
                        Toast.makeText(context, context.getString(R.string.comment_translate_failed), Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Voting needs a signed-in session (replyState is null otherwise) and the tokens YouTube sent with this page.
        val vote = replyState?.votes?.get(comment.id) ?: comment.likeState
        val canVote =
            replyState != null &&
                !comment.id.startsWith("local-") &&
                app.zaid.flow.account.AccountActions
                    .canVoteComment(comment.id)
        val voteTint = MaterialTheme.colorScheme.primary
        val idleTint = MaterialTheme.colorScheme.onSurfaceVariant
        val onVoteError: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        Icon(
            imageVector = if (vote == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            contentDescription = stringResource(R.string.like),
            tint = if (vote == 1) voteTint else idleTint,
            modifier =
                Modifier
                    .likePop(vote == 1, tiltDegrees = -18f)
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canVote) { replyState?.vote(scope, comment, if (vote == 1) 0 else 1, onVoteError) },
        )
        // YouTube shows the count it loaded with; a like made here adds one locally until the list reloads.
        val shownLikes = comment.likeCount + (if (vote == 1) 1 else 0) - (if (comment.likeState == 1) 1 else 0)
        val likeText =
            if (shownLikes != comment.likeCount) {
                formatLikeCount(shownLikes.coerceAtLeast(0))
            } else {
                comment.likeCountText.takeIf { it.isNotBlank() } ?: comment.likeCount.takeIf { it > 0 }?.let(::formatLikeCount)
            }
        if (likeText != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = likeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (replyState != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = if (vote == 2) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                contentDescription = stringResource(R.string.dislike),
                tint = if (vote == 2) voteTint else idleTint,
                modifier =
                    Modifier
                        .likePop(vote == 2, tiltDegrees = 18f)
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable(enabled = canVote) { replyState.vote(scope, comment, if (vote == 2) 0 else 2, onVoteError) },
            )
        }
        if (comment.isHearted) {
            Spacer(modifier = Modifier.width(14.dp))
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = comment.heartedByText ?: stringResource(R.string.comment_hearted_by_creator),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
        if (replyState != null && !comment.id.startsWith("local-")) {
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = stringResource(R.string.reply),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { replyState.startReply(comment) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (comment.text.isNotBlank() && (canTranslate || translation != null)) {
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text =
                    when {
                        translating -> stringResource(R.string.comment_translating)
                        translation != null -> stringResource(R.string.comment_hide_translation)
                        else -> stringResource(R.string.comment_translate_to, languageName(translateTarget))
                    },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(enabled = !translating) {
                            if (translation != null) translation = null else translate(translateTarget)
                        }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        // YouTube only hands out edit and delete tokens for the signed-in account's own comments.
        if (replyState != null && AccountActions.canEditComment(comment.id)) {
            Text(
                text = "Edit",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { replyState.startEdit(comment) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (replyState != null && AccountActions.canDeleteComment(comment.id)) {
            Text(
                text = "Delete",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { confirmDelete = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    translation?.let { result ->
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.comment_translated_from, languageName(result.sourceLang)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = result.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row {
                    listOf("en", "hi").forEach { code ->
                        TextButton(
                            onClick = { translate(code) },
                            enabled = !translating && code != translateTarget,
                            contentPadding =
                                androidx.compose.foundation.layout
                                    .PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(nativeLanguageName(code), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete && replyState != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete comment?") },
            text = { Text("This removes your comment from YouTube. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val result = YouTubeAccountReporter.deleteComment(comment.id)
                        if (result.isSuccess) {
                            replyState.deletedIds.add(comment.id)
                            if (replyState.editTarget?.id == comment.id) replyState.editTarget = null
                        }
                        val message =
                            if (result.isSuccess) {
                                "Comment deleted"
                            } else {
                                result.exceptionOrNull()?.message
                                    ?: "Could not delete comment"
                            }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** A language's name in the app's language, e.g. "Hindi". */
private fun languageName(code: String): String =
    java.util.Locale
        .forLanguageTag(code)
        .getDisplayLanguage(java.util.Locale.getDefault())
        .ifBlank { code }

/** A language's name in itself, e.g. "हिन्दी". */
private fun nativeLanguageName(code: String): String {
    val locale = java.util.Locale.forLanguageTag(code)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase(locale) }.ifBlank { code }
}
