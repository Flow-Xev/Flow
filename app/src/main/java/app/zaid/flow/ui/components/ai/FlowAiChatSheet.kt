package app.zaid.flow.ui.components.ai

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.ai.AiChatState
import app.zaid.flow.ai.AiMessage
import app.zaid.flow.ai.AiPill
import app.zaid.flow.ai.AiRole
import app.zaid.flow.ai.FlowAiController
import app.zaid.flow.ai.QUIZ_FINISH_PILL_ID
import app.zaid.flow.ai.QUIZ_PILL_ID
import app.zaid.flow.ai.pillsFor
import app.zaid.flow.ai.quizPills
import app.zaid.flow.ai.timestamp
import app.zaid.flow.data.model.Video
import app.zaid.flow.player.EnhancedPlayerManager
import app.zaid.flow.player.GlobalPlayerState
import app.zaid.flow.ui.components.shared.FlowBottomSheet
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** How much of the screen the chat sheet covers; the rest stays visible behind it. */
private const val SHEET_HEIGHT_FRACTION = 0.8f

/**
 * Opens the assistant sheet. Provided by the app shell around everything it draws, so the player and
 * Home can offer the button without knowing how the sheet is hosted; null in previews and tests.
 */
val LocalFlowAiLauncher = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The sparkle mark as an action: the entry point to the assistant from the player and from Home. */
@Composable
fun FlowAiButton(
    modifier: Modifier = Modifier,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 26.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val launch = LocalFlowAiLauncher.current ?: return
    val label = stringResource(R.string.flow_ai_button)
    IconButton(
        onClick = launch,
        modifier = modifier.size(buttonSize).semantics { contentDescription = label },
    ) {
        FlowAiIcon(
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}

/**
 * The assistant as a bottom sheet. State and actions come from [controller]; the shell decides what
 * opening a video or the settings page means, so the sheet works the same over any screen.
 *
 * The shortcut pills follow the video that is open: they are recomputed whenever the playing video
 * changes, so a lecture offers study notes while a song offers its meaning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowAiChatSheet(
    controller: FlowAiController,
    onOpenVideo: (Video) -> Unit,
    onOpenSettings: () -> Unit,
    sheetHeightBelowVideo: Dp? = null,
) {
    if (sheetHeightBelowVideo != null) {
        // Over the expanded video player: a non-modal sheet exactly as tall as the space under the
        // video, like the comments sheet, so the video stays visible and playable above it.
        FlowBottomSheet(
            onDismiss = controller::closeSheet,
            expandedHeight = sheetHeightBelowVideo,
            dismissOnOutsideTap = false,
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            AiChatBody(
                controller = controller,
                onOpenVideo = onOpenVideo,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
            )
        }
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = controller::closeSheet,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        AiChatBody(
            controller = controller,
            onOpenVideo = onOpenVideo,
            onOpenSettings = onOpenSettings,
            modifier =
                Modifier
                    .height(LocalConfiguration.current.screenHeightDp.dp * SHEET_HEIGHT_FRACTION)
                    .navigationBarsPadding()
                    .imePadding(),
        )
    }
}

@Composable
private fun AiChatBody(
    controller: FlowAiController,
    onOpenVideo: (Video) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val playing by GlobalPlayerState.currentVideo.collectAsStateWithLifecycle()
    val pills = remember(playing, state.quizActive) { if (state.quizActive) quizPills() else pillsFor(playing) }
    val onPill: (AiPill, String) -> Unit = { pill, label ->
        when (pill.id) {
            QUIZ_PILL_ID -> controller.startQuiz(label)
            QUIZ_FINISH_PILL_ID -> controller.endQuiz()
            else -> controller.send(label)
        }
    }
    Column(modifier = modifier) {
        ChatHeader(
            subtitle = playing?.title?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.flow_ai_watching, it) },
            canStartNew = state.messages.isNotEmpty(),
            onNewChat = controller::newChat,
            onClose = controller::closeSheet,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.hasApiKey -> {
                    MissingKey(onOpenSettings = {
                        controller.closeSheet()
                        onOpenSettings()
                    })
                }

                state.messages.isEmpty() -> {
                    EmptyChat(
                        hasVideo = playing != null,
                        pills = pills,
                        onPill = onPill,
                    )
                }

                else -> {
                    Messages(
                        state = state,
                        canSeek = playing != null,
                        onOpenVideo = onOpenVideo,
                        onRegenerate = controller::regenerate,
                        onAnswer = controller::answerQuiz,
                    )
                }
            }
        }
        if (state.hasApiKey) {
            val showPills = state.messages.isNotEmpty() && !state.isSending
            AnimatedVisibility(
                visible = showPills,
                enter = fadeIn(tween(200)) + expandVertically(),
                exit = fadeOut(tween(120)) + shrinkVertically(),
            ) {
                PillRow(
                    pills = pills,
                    showFollowUps =
                        !state.quizActive &&
                            state.messages.lastOrNull()?.let { it.role == AiRole.ASSISTANT && !it.isError } == true,
                    onPill = onPill,
                    onSend = controller::send,
                )
            }
            InputBar(
                isSending = state.isSending,
                onSend = controller::send,
                onStop = controller::stop,
            )
        }
    }
}

@Composable
private fun ChatHeader(
    subtitle: String?,
    canStartNew: Boolean,
    onNewChat: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            FlowAiIcon(modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.flow_ai_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "aiSubtitle",
            ) { text ->
                if (text != null) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        AnimatedVisibility(visible = canStartNew, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.flow_ai_new_chat))
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
        }
    }
}

@Composable
private fun MissingKey(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlowAiIcon(modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.flow_ai_no_key_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.flow_ai_no_key_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.flow_ai_open_settings)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(
    hasVideo: Boolean,
    pills: List<AiPill>,
    onPill: (AiPill, String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulsingSparkle()
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.flow_ai_greeting),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(if (hasVideo) R.string.flow_ai_empty_video_body else R.string.flow_ai_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pills.forEachIndexed { index, pill ->
                key(pill.id) {
                    StaggeredAppear(index) { AiPillChip(pill = pill, onClick = onPill) }
                }
            }
        }
    }
}

/** The sparkle in a softly breathing disc, the first thing the empty sheet shows. */
@Composable
private fun PulsingSparkle() {
    val transition = rememberInfiniteTransition(label = "sparkle")
    val breathe by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "sparkleScale",
    )
    Box(
        modifier =
            Modifier
                .size(72.dp)
                .scale(breathe)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        FlowAiIcon(modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Fades and springs [content] in after a short delay that grows with [index], so a row of pills pours in. */
@Composable
private fun StaggeredAppear(
    index: Int,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 55L)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter =
            fadeIn(tween(240)) +
                scaleIn(
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                    initialScale = 0.8f,
                ) +
                slideInVertically(tween(240)) { it / 2 },
    ) { content() }
}

/** One rounded shortcut. Its label is the question that is sent. */
@Composable
private fun AiPillChip(
    pill: AiPill,
    onClick: (AiPill, String) -> Unit,
) {
    val label = stringResource(pill.label)
    AiPillChip(label = label, onClick = { onClick(pill, label) })
}

@Composable
private fun AiPillChip(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** The shortcuts that stay above the input during a chat, led by follow-ups after an answer. */
@Composable
private fun PillRow(
    pills: List<AiPill>,
    showFollowUps: Boolean,
    onPill: (AiPill, String) -> Unit,
    onSend: (String) -> Unit,
) {
    val more = stringResource(R.string.flow_ai_follow_up_more)
    val shorter = stringResource(R.string.flow_ai_follow_up_shorter)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showFollowUps) {
            item(key = "follow_more") { AiPillChip(label = "➕ $more", onClick = { onSend(more) }) }
            item(key = "follow_shorter") { AiPillChip(label = "✂️ $shorter", onClick = { onSend(shorter) }) }
        }
        items(pills, key = { it.id }) { pill -> AiPillChip(pill = pill, onClick = onPill) }
    }
}

@Composable
private fun Messages(
    state: AiChatState,
    canSeek: Boolean,
    onOpenVideo: (Video) -> Unit,
    onRegenerate: () -> Unit,
    onAnswer: (Long, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val answered = state.messages.count { it.quiz != null && it.selected != null }
    val right = state.messages.count { it.quiz != null && it.selected == it.quiz.correctIndex }
    val lastIndex = state.messages.lastIndex + if (state.isSending) 1 else 0
    LaunchedEffect(state.messages.size, state.isSending) {
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }
    val lastId = state.messages.lastOrNull()?.id
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.messages, key = { it.id }) { message ->
            val visible = remember(message.id) { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(
                visibleState = visible,
                enter =
                    fadeIn(tween(220)) +
                        slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 4 } +
                        scaleIn(tween(220), initialScale = 0.96f),
                modifier = Modifier.animateItem(),
            ) {
                MessageItem(
                    message = message,
                    isLast = message.id == lastId && !state.isSending,
                    canSeek = canSeek,
                    onOpenVideo = onOpenVideo,
                    onRegenerate = onRegenerate,
                    score = right to answered,
                    onAnswer = { option -> onAnswer(message.id, option) },
                )
            }
        }
        if (state.isSending) {
            item(key = "typing") {
                TypingIndicator(activity = state.activity, modifier = Modifier.animateItem())
            }
        }
    }
}

/** Three bouncing dots with what the assistant is doing, in the place the answer will appear. */
@Composable
private fun TypingIndicator(
    activity: String?,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { dot ->
                    val level by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 0.3f,
                        animationSpec =
                            infiniteRepeatable(
                                keyframes {
                                    durationMillis = 1000
                                    0.3f at dot * 150
                                    1f at dot * 150 + 250
                                    0.3f at dot * 150 + 500
                                },
                            ),
                        label = "dot$dot",
                    )
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .scale(0.7f + 0.5f * level)
                                .alpha(level)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        AnimatedContent(
            targetState = activity ?: stringResource(R.string.flow_ai_thinking),
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "aiActivity",
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageItem(
    message: AiMessage,
    isLast: Boolean,
    canSeek: Boolean,
    onOpenVideo: (Video) -> Unit,
    onRegenerate: () -> Unit,
    score: Pair<Int, Int>,
    onAnswer: (Int) -> Unit,
) {
    val isUser = message.role == AiRole.USER
    val seekTo: ((Long) -> Unit)? =
        if (canSeek) {
            { ms -> EnhancedPlayerManager.getInstance().seekTo(ms) }
        } else {
            null
        }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val quiz = message.quiz
        if (quiz != null) {
            QuizCard(message = message, quiz = quiz, score = score, enabled = isLast, onAnswer = onAnswer)
        } else {
            Surface(
                shape =
                    if (isUser) {
                        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                    } else {
                        RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
                    },
                color =
                    when {
                        message.isError -> MaterialTheme.colorScheme.errorContainer
                        isUser -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                contentColor =
                    when {
                        message.isError -> MaterialTheme.colorScheme.onErrorContainer
                        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                val padding = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                if (isUser || message.isError) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(text = message.text, style = MaterialTheme.typography.bodyLarge, modifier = padding)
                    }
                } else {
                    AiMarkdownText(text = message.text, onSeek = seekTo, modifier = padding)
                }
            }
        }
        message.videos.forEach { video ->
            VideoCard(video = video, onClick = { onOpenVideo(video) })
        }
        if (!isUser && !message.isError && quiz == null) {
            AnswerActions(text = message.text, showRegenerate = isLast, onRegenerate = onRegenerate)
        } else if (message.isError && isLast) {
            FilledTonalButton(onClick = onRegenerate) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.flow_ai_retry))
            }
        } else if (isUser && isLast) {
            // The turn was stopped before an answer arrived.
            FilledTonalButton(onClick = onRegenerate) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.flow_ai_retry))
            }
        }
    }
}

@Composable
private fun AnswerActions(
    text: String,
    showRegenerate: Boolean,
    onRegenerate: () -> Unit,
) {
    val context = LocalContext.current
    val currentText by rememberUpdatedState(text)
    val copiedMessage = stringResource(R.string.flow_ai_copied)
    val shareTitle = stringResource(R.string.flow_ai_share)
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("flow_ai_answer", currentText))
                android.widget.Toast
                    .makeText(context, copiedMessage, android.widget.Toast.LENGTH_SHORT)
                    .show()
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.flow_ai_copy), modifier = Modifier.size(18.dp))
        }
        IconButton(
            onClick = {
                val send =
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, currentText)
                    }
                val chooser =
                    android.content.Intent
                        .createChooser(send, shareTitle)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Outlined.Share, contentDescription = shareTitle, modifier = Modifier.size(18.dp))
        }
        if (showRegenerate) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.flow_ai_regenerate),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoCard(
    video: Video,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .width(120.dp)
                        .height(68.dp)
                        .clip(MaterialTheme.shapes.small),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta =
                    listOfNotNull(
                        video.channelName.takeIf { it.isNotBlank() },
                        video.duration.takeIf { it > 0 }?.let { timestamp(it * 1000L) },
                    ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The message box: one rounded pill that holds the text and a button that is a mic, a send arrow or a stop square. */
@Composable
private fun InputBar(
    isSending: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank() && !isSending) {
            onSend(text)
            text = ""
        }
    }
    val micUnavailable = stringResource(R.string.flow_ai_mic_unavailable)
    val listening = stringResource(R.string.flow_ai_listening)
    val speech =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken =
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.trim()
            if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrEmpty()) {
                text = if (text.isBlank()) spoken else "$text $spoken"
            }
        }
    val startVoice = {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, listening)
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            android.widget.Toast
                .makeText(context, micUnavailable, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.flow_ai_input_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(4.dp))
            val mode =
                if (isSending) {
                    InputMode.STOP
                } else if (text.isNotBlank()) {
                    InputMode.SEND
                } else {
                    InputMode.MIC
                }
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(tween(120))) togetherWith
                        (scaleOut(tween(100)) + fadeOut(tween(100)))
                },
                label = "inputAction",
            ) { current ->
                when (current) {
                    InputMode.STOP -> {
                        FilledIconButton(onClick = onStop) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.flow_ai_stop))
                        }
                    }

                    InputMode.SEND -> {
                        FilledIconButton(onClick = { submit() }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.flow_ai_send))
                        }
                    }

                    InputMode.MIC -> {
                        IconButton(
                            onClick = { startVoice() },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.flow_ai_mic))
                        }
                    }
                }
            }
        }
    }
}

private enum class InputMode { MIC, SEND, STOP }
