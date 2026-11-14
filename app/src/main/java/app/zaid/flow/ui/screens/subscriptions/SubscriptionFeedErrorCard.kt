package app.zaid.flow.ui.screens.subscriptions

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.zaid.flow.R

/**
 * Shown when the last refresh could not reach some channels: one full-width pill (46dp tall, like the channel page's subscribe button) that reloads the feed.
 * It presses in on touch, spins its arrow on tap and gives a haptic tick; no names, logs or dismiss.
 */
@Composable
internal fun SubscriptionFeedErrorCard(
    failedChannelNames: List<String>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (failedChannelNames.isEmpty()) return

    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "retryPress",
    )
    var turns by remember { mutableFloatStateOf(0f) }
    val rotation by animateFloatAsState(
        targetValue = turns * 360f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "retrySpin",
    )
    // The pill eases in whenever it appears instead of popping into the feed.
    var shown by remember { mutableStateOf(false) }
    val appear by animateFloatAsState(if (shown) 1f else 0f, label = "retryAppear")
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }

    Row(
        modifier = modifier.fillMaxWidth().alpha(appear),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                turns += 1f
                onRetry()
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .graphicsLayer {
                        scaleX = pressScale * (0.92f + 0.08f * appear)
                        scaleY = pressScale * (0.92f + 0.08f * appear)
                    }.semantics { role = Role.Button },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
            interactionSource = interaction,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.retry),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
