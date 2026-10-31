package app.zaid.flow.ui.components.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private const val PARTICLE_COUNT = 8
private const val BURST_MS = 520

/**
 * A like that feels pressed: when [active] turns on the icon shrinks, then springs past full size with a
 * small tilt (thumbs tip up by [tiltDegrees]); turning it off is a quick dip and a soft return.
 * Nothing plays on first composition, so a video that is already liked does not pop when it opens.
 */
@Composable
fun Modifier.likePop(
    active: Boolean,
    tiltDegrees: Float = -16f,
): Modifier {
    val scale = remember { Animatable(1f) }
    val tilt = remember { Animatable(0f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!settled) {
            settled = true
            return@LaunchedEffect
        }
        coroutineScope {
            if (active) {
                launch {
                    tilt.snapTo(tiltDegrees)
                    tilt.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                }
                scale.snapTo(0.6f)
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium))
            } else {
                scale.animateTo(0.82f, tween(70))
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        rotationZ = tilt.value
    }
}

/**
 * A ring of small dots that flies outwards and fades when [active] turns on. Give it a canvas larger than
 * the icon it sits behind (for example with `requiredSize`) so the dots have room.
 */
@Composable
fun LikeParticles(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    reach: Dp = 28.dp,
) {
    val progress = remember { Animatable(1f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!settled) {
            settled = true
            return@LaunchedEffect
        }
        if (active) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(BURST_MS, easing = FastOutSlowInEasing))
        }
    }
    Canvas(modifier = modifier) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val reachPx = reach.toPx()
        val radius = 2.6f.dp.toPx() * (1f - p)
        val distance = reachPx * (0.45f + 0.55f * p)
        // A soft ring that expands ahead of the dots and fades, so the burst reads as one pulse.
        val ringProgress = (p * 1.4f).coerceAtMost(1f)
        drawCircle(
            color = color.copy(alpha = (1f - ringProgress) * 0.55f),
            radius = reachPx * (0.25f + 0.55f * ringProgress),
            center = center,
            style = Stroke(width = 2.2f.dp.toPx() * (1f - ringProgress) + 0.5f),
        )
        for (i in 0 until PARTICLE_COUNT) {
            val angle = Math.toRadians(i * (360.0 / PARTICLE_COUNT) + 22.5)
            drawCircle(
                color = color.copy(alpha = 1f - p),
                radius = radius,
                center = Offset(center.x + (cos(angle) * distance).toFloat(), center.y + (sin(angle) * distance).toFloat()),
            )
        }
    }
}
