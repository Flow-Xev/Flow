package app.zaid.flow.ui.components.videoplayer.sheet

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.zaid.flow.data.repository.LiveChatSession
import kotlinx.coroutines.launch

private val QUICK_EMOJIS =
    listOf(
        "😂",
        "❤️",
        "🔥",
        "👏",
        "😍",
        "🙌",
        "😮",
        "😢",
        "🎉",
        "👍",
        "💯",
        "🤣",
        "😭",
        "🥰",
        "😎",
        "🤔",
        "🙏",
        "💀",
        "✨",
        "😡",
        "🤯",
        "🫡",
        "💪",
        "🎶",
        "😅",
        "🥳",
        "👀",
        "💖",
        "🤩",
        "😊",
    )

/**
 * The live chat input: a text box, an emoji row and Send. Hidden unless this account is signed in and
 * YouTube offered it a chat box for the stream (it is absent for members-only chats, slow-mode locks, etc.).
 * Nothing extra is fetched: the send token comes with the polls the chat already makes.
 */
@Composable
fun LiveChatComposer(modifier: Modifier = Modifier) {
    val canSend by LiveChatSession.canSend.collectAsState(initial = false)
    if (!canSend) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var emojiOpen by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val trimmed = text.trim()

    fun send() {
        if (sending || trimmed.isEmpty()) return
        sending = true
        scope.launch {
            val result = LiveChatSession.send(trimmed)
            sending = false
            if (result.isSuccess) {
                text = ""
            } else {
                Toast.makeText(context, result.exceptionOrNull()?.message ?: "Couldn't send", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier.fillMaxWidth().imePadding()) {
        if (emojiOpen) {
            LazyRow(
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(QUICK_EMOJIS) { emoji ->
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.titleLarge,
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .clickable { if (text.length < MAX_CHAT_LENGTH) text += emoji }
                                .padding(6.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { emojiOpen = !emojiOpen }) {
                Icon(
                    Icons.Filled.EmojiEmotions,
                    contentDescription = "Emoji",
                    tint = if (emojiOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextField(
                value = text,
                onValueChange = { if (it.length <= MAX_CHAT_LENGTH) text = it },
                placeholder = { Text("Chat…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                shape = CircleShape,
            )
            IconButton(onClick = ::send, enabled = !sending && trimmed.isNotEmpty()) {
                Icon(Icons.Filled.Send, contentDescription = "Send", modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** YouTube's live chat message limit. */
private const val MAX_CHAT_LENGTH = 200
