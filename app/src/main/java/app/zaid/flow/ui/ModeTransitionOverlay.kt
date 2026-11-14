package app.zaid.flow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

private const val FADE_OUT_MS = 140
private const val FADE_IN_MS = 220
private const val SWAP_SETTLE_MS = 90L

/**
 * The video <-> music switch, kept deliberately simple: the current screen fades to the page colour,
 * the screens are swapped underneath, and the new one fades in. Touches are blocked while it runs.
 */
@Composable
fun ModeTransitionOverlay() {
    val transition by AppModeState.transition.collectAsState()
    val t = transition ?: return
    val cover = remember(t) { Animatable(0f) }

    LaunchedEffect(t) {
        cover.animateTo(1f, tween(FADE_OUT_MS, easing = FastOutSlowInEasing))
        t.action()
        // Let the new screen compose and lay out before it is revealed.
        delay(SWAP_SETTLE_MS)
        cover.animateTo(0f, tween(FADE_IN_MS, easing = LinearOutSlowInEasing))
        AppModeState.transition.value = null
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }.graphicsLayer { alpha = cover.value }
                .background(MaterialTheme.colorScheme.background),
    )
}
