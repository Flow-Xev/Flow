package app.zaid.flow.ui.components.shared

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zaid.flow.account.YouTubeAccountReporter
import app.zaid.flow.data.model.Comment
import kotlinx.coroutines.launch

/** The comment a reply is currently being written to; shared by the list's Reply buttons and the input bar. */
@Stable
class CommentReplyState {
    var target by mutableStateOf<Comment?>(null)

    /** The own comment being edited in the input bar, if any. */
    var editTarget by mutableStateOf<Comment?>(null)

    /** Comments deleted this session; the list hides them without waiting for a reload. */
    val deletedIds = mutableStateListOf<String>()

    /** New text for comments edited this session, by comment id. */
    val editedText = mutableStateMapOf<String, String>()

    /** This session's vote per comment id (0 none, 1 liked, 2 disliked); overrides what the page loaded with. */
    val votes = mutableStateMapOf<String, Int>()

    /**
     * Applies [target] (0, 1 or 2) right away and sends it to YouTube; on failure the old vote is restored
     * and [onError] gets the reason. Switching like to dislike undoes the first, then sends the second.
     */
    fun vote(
        scope: kotlinx.coroutines.CoroutineScope,
        comment: Comment,
        target: Int,
        onError: (String) -> Unit,
    ) {
        val previous = votes[comment.id] ?: comment.likeState
        if (previous == target) return
        votes[comment.id] = target
        scope.launch {
            val steps =
                buildList {
                    if (previous == 1) add(app.zaid.flow.account.AccountActions.CommentVote.UNLIKE)
                    if (previous == 2) add(app.zaid.flow.account.AccountActions.CommentVote.UNDISLIKE)
                    if (target == 1) add(app.zaid.flow.account.AccountActions.CommentVote.LIKE)
                    if (target == 2) add(app.zaid.flow.account.AccountActions.CommentVote.DISLIKE)
                }
            for (step in steps) {
                val result =
                    app.zaid.flow.account.AccountActions
                        .voteComment(comment.id, step)
                if (result.isFailure) {
                    votes[comment.id] = previous
                    onError(result.exceptionOrNull()?.message ?: "Couldn't rate this comment")
                    return@launch
                }
            }
        }
    }

    fun startReply(comment: Comment) {
        editTarget = null
        target = comment
    }

    fun startEdit(comment: Comment) {
        target = null
        editTarget = comment
    }
}

/** [comment] with this session's edit applied, or null once it was deleted. */
@Composable
fun CommentReplyState?.applied(comment: Comment): Comment? {
    if (this == null) return comment
    if (comment.id in deletedIds) return null
    val edited = editedText[comment.id] ?: return comment
    return comment.copy(text = edited, richText = null)
}

val LocalCommentReplyState = compositionLocalOf<CommentReplyState?> { null }

/** YouTube's limit for one comment. */
const val MAX_COMMENT_LENGTH = 10_000

/** Shown once the text is this close to the limit. */
private const val COUNTER_THRESHOLD = 9_000

@Composable
fun CommentInputBar(
    onPost: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val replyState = LocalCommentReplyState.current
    val target = replyState?.target
    val editTarget = replyState?.editTarget
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(target?.id) { if (target != null) runCatching { focusRequester.requestFocus() } }
    LaunchedEffect(editTarget?.id) {
        if (editTarget != null) {
            text = editTarget.text
            runCatching { focusRequester.requestFocus() }
        }
    }

    val trimmed = text.trim()
    val unchangedEdit = editTarget != null && trimmed == editTarget.text.trim()
    val canSend = enabled && !sending && trimmed.isNotEmpty() && text.length <= MAX_COMMENT_LENGTH && !unchangedEdit

    // The text stays in the box until YouTube accepts it, so a failed send can be retried as it was.
    fun finish(
        result: Result<Unit>,
        success: String,
        failure: String,
        onSuccess: () -> Unit = {},
    ) {
        sending = false
        if (result.isSuccess) {
            text = ""
            onSuccess()
            Toast.makeText(context, success, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, result.exceptionOrNull()?.message ?: failure, Toast.LENGTH_LONG).show()
        }
    }

    fun send() {
        if (!canSend) return
        sending = true
        when {
            editTarget != null -> {
                val edited = editTarget
                scope.launch {
                    val result = YouTubeAccountReporter.editComment(edited.id, trimmed)
                    finish(result, "Comment updated", "Could not edit comment") {
                        replyState?.editedText?.set(edited.id, trimmed)
                        replyState?.editTarget = null
                    }
                }
            }

            target != null -> {
                val parent = target
                scope.launch {
                    val result = YouTubeAccountReporter.postReply(parent.id, trimmed)
                    finish(result, "Reply posted", "Could not post reply") { replyState?.target = null }
                }
            }

            onPost is CommentPoster -> {
                onPost.post(trimmed) { result ->
                    sending = false
                    if (result.isSuccess) text = ""
                }
            }

            else -> {
                onPost(trimmed)
                sending = false
                text = ""
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            val banner =
                when {
                    target != null -> "Replying to ${target.author}"
                    editTarget != null -> "Editing your comment"
                    else -> null
                }
            if (banner != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = !sending,
                        onClick = {
                            if (editTarget != null) text = ""
                            replyState?.target = null
                            replyState?.editTarget = null
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = if (target != null) "Cancel reply" else "Cancel edit")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_COMMENT_LENGTH + 100) },
                    placeholder = {
                        Text(
                            when {
                                target != null -> "Add a reply…"
                                editTarget != null -> "Edit your comment…"
                                else -> "Add a comment…"
                            },
                        )
                    },
                    supportingText =
                        if (text.length >= COUNTER_THRESHOLD) {
                            { Text("${text.length} / $MAX_COMMENT_LENGTH") }
                        } else {
                            null
                        },
                    isError = text.length > MAX_COMMENT_LENGTH,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    enabled = enabled && !sending,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors =
                        TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = ::send, enabled = canSend) {
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Send,
                            contentDescription =
                                when {
                                    target != null -> "Post reply"
                                    editTarget != null -> "Save edit"
                                    else -> "Post comment"
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * What [rememberCommentPoster] returns. It is still the `(String) -> Unit` the screens pass around, but the
 * input bar can also ask for the outcome, to keep the text when posting fails and show progress meanwhile.
 */
class CommentPoster(
    private val send: (text: String, done: (Result<Unit>) -> Unit) -> Unit,
    private val context: android.content.Context,
) : (String) -> Unit {
    override fun invoke(text: String) = post(text) {}

    fun post(
        text: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        send(text) { result ->
            val message =
                if (result.isSuccess) "Comment posted" else result.exceptionOrNull()?.message ?: "Could not post comment"
            Toast.makeText(context, message, if (result.isSuccess) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            onResult(result)
        }
    }
}

/** Turns a view model's "post this comment" call into the `(String) -> Unit` the input bar takes, with a toast for the result. */
@Composable
fun rememberCommentPoster(post: (text: String, done: (Result<Unit>) -> Unit) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(post)
    return remember { CommentPoster(send = { text, done -> latest(text, done) }, context = context) }
}
