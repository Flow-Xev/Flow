package app.zaid.flow.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry

internal object FlowNavTransitions {
    // One fixed-length ease for every spatial move, so pages glide and settle without a bounce. A tween
    // rather than a spring: a spring over a zero-width offset (the first frame, before measuring) has
    // no finite duration and crashed with "AnimationVector cannot contain a NaN", and the page underneath trails the one on top by a few percent for depth.
    private fun <T> glide() = tween<T>(durationMillis = GLIDE_MS, easing = FastOutSlowInEasing)

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(animationSpec = tween(260, easing = FastOutSlowInEasing)) +
            slideInHorizontally(initialOffsetX = { (it * PAGE_PARALLAX).toInt() }, animationSpec = glide())
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing)) +
            slideOutHorizontally(targetOffsetX = { -(it * UNDER_PARALLAX).toInt() }, animationSpec = glide())
    }

    private fun NavBackStackEntry.isShorts() = destination.route?.startsWith("shorts") == true

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (initialState.isShorts()) {
            shortsPopEnter(this)
        } else {
            fadeIn(animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                slideInHorizontally(initialOffsetX = { -(it * UNDER_PARALLAX).toInt() }, animationSpec = glide())
        }
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = tween(220, easing = FastOutLinearInEasing)) +
            slideOutHorizontally(targetOffsetX = { (it * PAGE_PARALLAX).toInt() }, animationSpec = glide())
    }

    // Shorts: the reel feed rises in from just below and grows to full size, and on leaving it shrinks
    // back and glides down as it fades while the page underneath eases up to meet it, so closing it
    // feels like putting the phone down rather than a page flip.
    val shortsEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)) +
            scaleIn(initialScale = 0.9f, animationSpec = glide()) +
            slideInVertically(initialOffsetY = { (it * 0.06f).toInt() }, animationSpec = glide())
    }
    val shortsExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing)) +
            scaleOut(targetScale = 0.96f, animationSpec = glide())
    }

    // Leaving Shorts: a short, fixed-length crossfade. The reel fades and shrinks a little while the
    // page underneath fades in from slightly smaller, both on tweens so nothing is left settling
    // after the fade ends, which is what read as a snap.
    val shortsPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(animationSpec = tween(SHORTS_POP_MS, easing = FastOutSlowInEasing)) +
            scaleIn(initialScale = 0.94f, animationSpec = tween(SHORTS_POP_MS, easing = FastOutSlowInEasing))
    }
    val shortsPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = tween(SHORTS_POP_MS, easing = FastOutSlowInEasing)) +
            scaleOut(targetScale = 0.92f, animationSpec = tween(SHORTS_POP_MS, easing = FastOutSlowInEasing))
    }

    // A back gesture seeks these by its progress, so every part shares one linear duration and
    // follows the finger; a spring's open-ended duration let the fades finish a quarter of the way
    // in and left a cancelled gesture's state behind for the next one (#1069).
    val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = {
        fadeIn(animationSpec = tween(PREDICTIVE_BACK_DURATION_MS, easing = LinearEasing)) +
            slideInHorizontally(
                initialOffsetX = { -(it * UNDER_PARALLAX).toInt() },
                animationSpec = tween(PREDICTIVE_BACK_DURATION_MS, easing = LinearEasing),
            )
    }
    val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = {
        fadeOut(animationSpec = tween(PREDICTIVE_BACK_DURATION_MS, easing = LinearEasing)) +
            slideOutHorizontally(
                targetOffsetX = { (it * PAGE_PARALLAX).toInt() },
                animationSpec = tween(PREDICTIVE_BACK_DURATION_MS, easing = LinearEasing),
            )
    }
}

private const val PREDICTIVE_BACK_DURATION_MS = 300
private const val SHORTS_POP_MS = 280
private const val GLIDE_MS = 300
private const val PAGE_PARALLAX = 0.22f
private const val UNDER_PARALLAX = 0.08f
