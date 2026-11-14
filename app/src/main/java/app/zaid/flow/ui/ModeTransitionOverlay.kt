package app.zaid.flow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MAX_CARDS = 7
private const val LIFT_MS = 200
private const val FLY_MS = 680
private const val SETTLE_MS = 220
private const val DEST_WAIT_MS = 700L

// Material "emphasized" curve: quick departure, long soft landing, and never past 1 (an overshoot
// would give a card a negative size and crash).
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** One flying card: where it starts, where it lands, and the artwork it carries. */
private class Flight(
    val url: String,
    val toUrl: String?,
    val from: Rect,
    val to: Rect,
    val fromCorner: Float,
    val toCorner: Float,
    val toFade: Boolean,
)

/**
 * The video <-> music switch. The real thumbnails of the screen being left lift off the page, the
 * screen underneath is swapped while they cover it, and each card then folds, resizes and re-rounds
 * itself into the matching card of the screen being entered (a video thumbnail becoming a Quick
 * Picks card or a Speed dial tile, and the reverse), before the page is handed back.
 */
@Composable
fun ModeTransitionOverlay() {
    val transition by AppModeState.transition.collectAsState()
    val t = transition ?: return
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val lift = remember(t) { Animatable(0f) }
    val fly = remember(t) { Animatable(0f) }
    val settle = remember(t) { Animatable(0f) }
    var flights by remember(t) { mutableStateOf<List<Flight>>(emptyList()) }

    LaunchedEffect(t) {
        val from = if (t.toMusic) ModeSide.Video else ModeSide.Music
        val to = if (t.toMusic) ModeSide.Music else ModeSide.Video
        val w = window.width.toFloat()
        val h = window.height.toFloat()
        val sources = ModeCardRegistry.visible(from, w, h, MAX_CARDS)
        // Seed with the cards sitting where they started so the first frame matches the real screen.
        flights = sources.map { Flight(it.url, null, it.rect, it.rect, it.cornerDp, it.cornerDp, false) }
        lift.animateTo(1f, tween(LIFT_MS, easing = FastOutSlowInEasing))

        t.action()

        // The new screen composes under the overlay; wait for its cards to report their positions.
        var targets = emptyList<ModeCard>()
        var waited = 0L
        while (waited < DEST_WAIT_MS) {
            delay(32)
            waited += 32
            targets = ModeCardRegistry.visible(to, w, h, MAX_CARDS)
            if (targets.size >= minOf(sources.size, 3) || (targets.isNotEmpty() && waited > 500)) break
        }
        flights =
            sources.mapIndexed { i, s ->
                val d = targets.getOrNull(i) ?: targets.lastOrNull()
                if (d == null) {
                    // Nothing to land on (still loading): shrink in place and let the page fade in.
                    val c = s.rect.center
                    Flight(s.url, null, s.rect, Rect(c.x - 20f, c.y - 20f, c.x + 20f, c.y + 20f), s.cornerDp, 20f, true)
                } else {
                    Flight(s.url, d.url, s.rect, d.rect, s.cornerDp, d.cornerDp, targets.getOrNull(i) == null)
                }
            }
        // Screens with no cards on either side just cross-fade.
        fly.animateTo(1f, tween(if (sources.isEmpty()) 1 else FLY_MS, easing = LinearEasing))
        settle.animateTo(1f, tween(SETTLE_MS, easing = FastOutSlowInEasing))
        AppModeState.transition.value = null
    }

    // The old page dims out as the cards lift, then the new page is revealed underneath while the cards
    // are still landing, so they settle onto real cards instead of flying across an empty screen.
    val reveal = ((fly.value - 0.35f) / 0.5f).coerceIn(0f, 1f)
    val pageAlpha = (lift.value / 0.6f).coerceIn(0f, 1f) * (1f - reveal) * 0.94f
    val colors = MaterialTheme.colorScheme

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha }.background(colors.background))
        flights.forEachIndexed { i, f ->
            // Cards leave one after another, like a deck unfolding.
            val local = ((fly.value * 1.25f - i * 0.045f) / 1.0f).coerceIn(0f, 1f)
            val m = Emphasized.transform(local)
            val arc = sin(PI.toFloat() * local)
            val dir = if (i % 2 == 0) 1f else -1f
            val left = lerp(f.from.left, f.to.left, m)
            val top = lerp(f.from.top, f.to.top, m) - arc * 36f
            val cw = lerp(f.from.width, f.to.width, m).coerceAtLeast(1f)
            val ch = lerp(f.from.height, f.to.height, m).coerceAtLeast(1f)
            val corner = lerp(f.fromCorner, f.toCorner, m).coerceAtLeast(0f)
            val liftScale = 1f + 0.05f * lift.value * (1f - settle.value)
            val cardAlpha = 1f - settle.value
            Box(
                modifier =
                    Modifier
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(with(density) { cw.toDp() }, with(density) { ch.toDp() })
                        .graphicsLayer {
                            rotationZ = dir * 3f * arc
                            scaleX = liftScale + 0.03f * arc
                            scaleY = liftScale + 0.03f * arc
                            alpha = cardAlpha
                            shadowElevation = 14f * lift.value * (1f - settle.value)
                            shape = RoundedCornerShape(with(density) { corner.toDp() })
                            clip = true
                        }.clip(RoundedCornerShape(with(density) { corner.toDp() }))
                        .background(colors.surfaceContainerHigh),
            ) {
                AsyncImage(
                    model = f.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Mid-flight the artwork crossfades into the destination card's own, so a wide video
                // thumbnail visibly reshapes into square cover art (and back).
                val toUrl = f.toUrl
                if (toUrl != null && toUrl != f.url) {
                    val blend = ((m - 0.3f) / 0.4f).coerceIn(0f, 1f)
                    AsyncImage(
                        model = toUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = blend },
                    )
                }
            }
        }
    }
}
