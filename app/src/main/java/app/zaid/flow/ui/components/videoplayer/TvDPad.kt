package app.zaid.flow.ui.components.videoplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * YouTube-style D-pad for navigating the YouTube app on the TV. One touch surface: tap or hold a direction
 * (holding repeats), tap the centre to select. Back and play/pause sit underneath.
 */
@Composable
internal fun TvDPad(
    playing: Boolean,
    muted: Boolean,
    reconnecting: Boolean,
    onKey: (String) -> Unit,
    onPlayPause: () -> Unit,
    onMute: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sendKey by rememberUpdatedState(onKey)
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf<String?>(null) }
    var lastDir by remember { mutableStateOf<String?>(null) }
    val pulse = remember { Animatable(1f) }
    val dirGlow by animateFloatAsState(if (pressed != null && pressed != "ENTER") 1f else 0f, tween(140), label = "dirGlow")
    val okFill by animateFloatAsState(if (pressed == "ENTER") 1f else 0.16f, tween(120), label = "okFill")
    val okScale by animateFloatAsState(
        if (pressed == "ENTER") 0.9f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "okScale",
    )
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val tint = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier =
                Modifier
                    .size(248.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val key = zoneAt(down.position, size.width.toFloat())
                            pressed = key
                            if (key != "ENTER") lastDir = key
                            scope.launch {
                                pulse.snapTo(0f)
                                pulse.animateTo(1f, tween(380))
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            sendKey(key)
                            val holdJob =
                                if (key == "ENTER") null
                                else
                                    scope.launch {
                                        delay(400)
                                        while (true) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            sendKey(key)
                                            delay(110)
                                        }
                                    }
                            waitForUpOrCancellation()
                            holdJob?.cancel()
                            pressed = null
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(248.dp)) {
                drawCircle(track.copy(alpha = 0.6f))
                val start =
                    when (lastDir) {
                        "RIGHT" -> -45f
                        "DOWN" -> 45f
                        "LEFT" -> 135f
                        "UP" -> 225f
                        else -> null
                    }
                if (start != null) drawArc(primary.copy(alpha = 0.28f * dirGlow), start, 90f, true, Offset.Zero, Size(size.width, size.height))
                // A ring that spreads from the centre with every press.
                if (pulse.value < 1f) {
                    drawCircle(
                        primary.copy(alpha = 0.4f * (1f - pulse.value)),
                        radius = size.width * (0.2f + 0.3f * pulse.value),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
                // Centre button, with a ring of background around it so it reads as a separate key.
                drawCircle(surface, radius = size.width * 0.21f)
                drawCircle(primary.copy(alpha = okFill), radius = size.width * 0.18f * okScale)
            }
            Arrow(Icons.Rounded.KeyboardArrowUp, "UP", tint, pressed == "UP", 0, -1, Modifier.align(Alignment.TopCenter))
            Arrow(Icons.Rounded.KeyboardArrowDown, "DOWN", tint, pressed == "DOWN", 0, 1, Modifier.align(Alignment.BottomCenter))
            Arrow(Icons.Rounded.KeyboardArrowLeft, "LEFT", tint, pressed == "LEFT", -1, 0, Modifier.align(Alignment.CenterStart))
            Arrow(Icons.Rounded.KeyboardArrowRight, "RIGHT", tint, pressed == "RIGHT", 1, 0, Modifier.align(Alignment.CenterEnd))
            Text(
                stringResource(R.string.lounge_select),
                style = MaterialTheme.typography.labelLarge,
                color = if (pressed == "ENTER") MaterialTheme.colorScheme.onPrimary else primary,
            )
        }

        AnimatedVisibility(reconnecting, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(stringResource(R.string.lounge_reconnecting), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey("BACK") }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.lounge_back))
            }
            FilledTonalIconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                Crossfade(targetState = playing, animationSpec = tween(160), label = "playPause") { isPlaying ->
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.lounge_pause else R.string.lounge_play),
                    )
                }
            }
            FilledTonalIconButton(onClick = onMute, modifier = Modifier.size(56.dp)) {
                Crossfade(targetState = muted, animationSpec = tween(160), label = "mute") { isMuted ->
                    Icon(if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, contentDescription = stringResource(R.string.lounge_mute))
                }
            }
        }
    }
}

@Composable
private fun Arrow(
    icon: ImageVector,
    key: String,
    tint: androidx.compose.ui.graphics.Color,
    active: Boolean,
    dx: Int,
    dy: Int,
    modifier: Modifier,
) {
    // The pressed arrow leans a little toward its direction, then springs back.
    val lean: Dp by animateDpAsState(if (active) 6.dp else 0.dp, spring(Spring.DampingRatioMediumBouncy), label = "lean")
    val color by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else tint, tween(120), label = "arrowTint")
    Icon(icon, contentDescription = key, tint = color, modifier = modifier.padding(10.dp).offset(x = lean * dx, y = lean * dy).size(36.dp))
}

internal fun zoneAt(p: Offset, width: Float): String {
    val c = width / 2f
    val dx = p.x - c
    val dy = p.y - c
    return when {
        hypot(dx, dy) < width * 0.21f -> "ENTER"
        abs(dx) > abs(dy) -> if (dx > 0) "RIGHT" else "LEFT"
        else -> if (dy > 0) "DOWN" else "UP"
    }
}
