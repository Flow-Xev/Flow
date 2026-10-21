package app.zaid.flow.ui.components.shorts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.data.local.ThumbnailQuality
import app.zaid.flow.ui.components.shared.FlowLoadingIndicator
import app.zaid.flow.ui.components.shared.LocalThumbnailQuality
import app.zaid.flow.ui.components.shared.VideoThumbnailImage
import app.zaid.flow.ui.theme.PlayerScrim
import app.zaid.flow.ui.theme.PlayerScrimContent
import app.zaid.flow.ui.theme.PlayerScrimImmersiveBackdrop
import app.zaid.flow.ui.theme.PlayerScrimPanel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

private const val PAUSE_INDICATOR_SCALE_IN = 0.6f
private const val PAUSE_INDICATOR_SCALE_OUT = 1.2f
private const val LIKE_BURST_START_SCALE = 0.2f
private const val LIKE_BURST_END_SCALE = 1.3f
private const val LIKE_BURST_BOUNCE = 0.45f
private const val LIKE_BURST_MAX_TILT = 14f
private const val LIKE_BURST_HOLD_MS = 450L
private const val LIKE_BURST_FADE_MS = 250
private const val ICON_SWAP_SCALE = 0.7f

internal object ShortsOverlayDefaults {
    /** Text resting on video keeps a soft shadow so a bright frame cannot swallow it. */
    val TextShadow = Shadow(color = PlayerScrim, blurRadius = 4f)
    val ControlsBottomOffset = 8.dp
    val SeekBarTouchHeight = 28.dp
    val SpeedBoostTopPadding = 80.dp
    val AutoScrollBadgeTopPadding = 56.dp
    val PauseIndicatorSize = 72.dp
    val PauseIndicatorIconSize = 40.dp
    val LikeBurstSize = 100.dp
    val BufferingIndicatorSize = 44.dp
}

/** The reel's poster, shown until its first frame and never while it is buffering mid-play. */
@Composable
internal fun ShortsReelPoster(
    visible: Boolean,
    videoId: String,
    thumbnailUrl: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        modifier = modifier,
    ) {
        // The player owns this frame: with thumbnails off it still loads the smallest portrait tier.
        val quality = LocalThumbnailQuality.current
        CompositionLocalProvider(
            LocalThumbnailQuality provides (if (quality == ThumbnailQuality.OFF) ThumbnailQuality.LOW else quality),
        ) {
            VideoThumbnailImage(
                videoId = videoId,
                model = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                portrait = true,
            )
        }
    }
}

@Composable
internal fun ShortsAutoScrollBadge(
    visible: Boolean,
    seconds: Int,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        modifier = modifier,
    ) {
        Surface(color = PlayerScrimPanel, shape = CircleShape) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = PlayerScrimContent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.shorts_auto_scroll_active_template, seconds),
                    color = PlayerScrimContent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun ShortsPauseIndicator(
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            scaleIn(MaterialTheme.motionScheme.fastSpatialSpec(), initialScale = PAUSE_INDICATOR_SCALE_IN) +
                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit =
            scaleOut(MaterialTheme.motionScheme.defaultEffectsSpec(), targetScale = PAUSE_INDICATOR_SCALE_OUT) +
                fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .size(ShortsOverlayDefaults.PauseIndicatorSize)
                    .background(PlayerScrimImmersiveBackdrop, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val iconSwapSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (scaleIn(iconSwapSpec, initialScale = ICON_SWAP_SCALE) + fadeIn(iconSwapSpec)) togetherWith
                        (scaleOut(iconSwapSpec, targetScale = ICON_SWAP_SCALE) + fadeOut(iconSwapSpec))
                },
                label = "shorts_play_pause",
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = stringResource(if (playing) R.string.cd_play else R.string.cd_pause),
                    tint = PlayerScrimContent,
                    modifier = Modifier.size(ShortsOverlayDefaults.PauseIndicatorIconSize),
                )
            }
        }
    }
}

/** The same indicator the player shows in its play slot while it waits on the network. */
@Composable
internal fun ShortsBufferingIndicator(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        modifier = modifier,
    ) {
        FlowLoadingIndicator(modifier = Modifier.size(ShortsOverlayDefaults.BufferingIndicatorSize))
    }
}

/** The red YouTube uses for a liked heart. */
internal val ShortsLikeRed = Color(0xFFFF0000)

/**
 * The heart that pops out of a double tap, centred where the finger landed ([origin], or the middle
 * of the reel when unspecified): it springs up with a little overshoot and tilt, holds, then swells
 * and fades away.
 */
@Composable
internal fun ShortsLikeBurst(
    visible: Boolean,
    origin: Offset,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val scale = remember { Animatable(LIKE_BURST_START_SCALE) }
    val alpha = remember { Animatable(1f) }
    val tilt = remember { (Random.nextFloat() * 2f - 1f) * LIKE_BURST_MAX_TILT }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                scale.animateTo(1f, spring(dampingRatio = LIKE_BURST_BOUNCE, stiffness = Spring.StiffnessMedium))
            }
            delay(LIKE_BURST_HOLD_MS)
            launch { alpha.animateTo(0f, tween(LIKE_BURST_FADE_MS)) }
            scale.animateTo(LIKE_BURST_END_SCALE, tween(LIKE_BURST_FADE_MS))
        }
        onFinished()
    }
    val sizePx = with(LocalDensity.current) { ShortsOverlayDefaults.LikeBurstSize.toPx() }
    Box(modifier = modifier.fillMaxSize()) {
        Icon(
            imageVector = ShortsHeartFilled,
            contentDescription = stringResource(R.string.cd_liked),
            tint = ShortsLikeRed,
            modifier =
                Modifier
                    .then(
                        if (origin.isSpecified) {
                            Modifier.offset { IntOffset((origin.x - sizePx / 2f).roundToInt(), (origin.y - sizePx / 2f).roundToInt()) }
                        } else {
                            Modifier.align(Alignment.Center)
                        },
                    ).size(ShortsOverlayDefaults.LikeBurstSize)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        this.alpha = alpha.value
                        rotationZ = tilt
                    },
        )
    }
}
