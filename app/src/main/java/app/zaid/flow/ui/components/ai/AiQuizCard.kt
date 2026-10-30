package app.zaid.flow.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.ai.AiMessage
import app.zaid.flow.ai.QuizQuestion

/**
 * One quiz question: the question, tappable options, and after a tap the right answer, the user's choice
 * and a short explanation. The tap is also sent to the AI, which answers with the next question.
 *
 * [enabled] is false while an answer is on its way or for questions already behind the latest one.
 */
@Composable
internal fun QuizCard(
    message: AiMessage,
    quiz: QuizQuestion,
    score: Pair<Int, Int>,
    enabled: Boolean,
    onAnswer: (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val selected = message.selected
    // One tap of feedback when the answer lands, a different one for right and wrong.
    LaunchedEffect(selected) {
        if (selected != null) {
            haptic.performHapticFeedback(if (selected == quiz.correctIndex) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (quiz.feedback.isNotBlank()) {
                Text(
                    text = quiz.feedback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.flow_ai_quiz_question, quiz.number),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (score.second > 0) {
                    Text(
                        text = stringResource(R.string.flow_ai_quiz_score, score.first, score.second),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = quiz.question,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            quiz.options.forEachIndexed { index, option ->
                QuizOption(
                    letter = ('A' + index).toString(),
                    text = option,
                    answered = selected != null,
                    isCorrect = index == quiz.correctIndex,
                    isChosen = index == selected,
                    enabled = enabled && selected == null,
                    onClick = { onAnswer(index) },
                )
            }
            AnimatedVisibility(
                visible = selected != null && quiz.explanation.isNotBlank(),
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(220)),
            ) {
                Text(
                    text = quiz.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuizOption(
    letter: String,
    text: String,
    answered: Boolean,
    isCorrect: Boolean,
    isChosen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue =
            when {
                answered && isCorrect -> scheme.primaryContainer
                answered && isChosen -> scheme.errorContainer
                else -> scheme.surfaceContainerHighest
            },
        animationSpec = tween(260),
        label = "quizOptionContainer",
    )
    val content by animateColorAsState(
        targetValue =
            when {
                answered && isCorrect -> scheme.onPrimaryContainer
                answered && isChosen -> scheme.onErrorContainer
                else -> scheme.onSurface
            },
        animationSpec = tween(260),
        label = "quizOptionContent",
    )
    // The options that were neither picked nor right step back, so the eye lands on the two that matter.
    val dim by animateFloatAsState(
        targetValue = if (answered && !isCorrect && !isChosen) 0.55f else 1f,
        animationSpec = tween(260),
        label = "quizOptionDim",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, if (answered && (isCorrect || isChosen)) content else scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().alpha(dim),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                val mark = remember(answered, isCorrect, isChosen) { answered && (isCorrect || isChosen) }
                if (mark) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = true,
                        enter =
                            scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(),
                    ) {
                        Icon(
                            imageVector = if (isCorrect) Icons.Default.Check else Icons.Default.Close,
                            contentDescription =
                                stringResource(
                                    if (isCorrect) R.string.flow_ai_quiz_correct else R.string.flow_ai_quiz_wrong,
                                ),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    Surface(
                        shape = CircleShape,
                        color = scheme.surfaceContainer,
                        contentColor = scheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = letter, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}
