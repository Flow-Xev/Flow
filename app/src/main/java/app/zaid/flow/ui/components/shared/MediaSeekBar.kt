package app.zaid.flow.ui.components.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.zaid.flow.data.model.SponsorBlockSegment
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamSegment

private val EdgeAlignedHeight = 14.dp
private val ExpandedRowHeight = 32.dp

/** Where the edge-aligned track sits between hairline (0) and fully swollen (1) while the controls are up: about 5dp. */
private const val CONTROLS_TRACK_EXPANSION = 0.4f
private const val SEEK_SWELL_MS = 220
private const val SEEK_HALO_MS = 180

/** Colours the track needs, resolved once in composition so the draw lambda stays theme-free. */
internal data class SeekTrackColors(
    val track: Color,
    val buffered: Color,
    val active: Color,
    val playhead: Color,
)

/**
 * Seek bar with buffered progress, chapter gaps and SponsorBlock segments painted over the track.
 *
 * Two shapes, and they are built differently on purpose:
 *
 * - Expanded (in the controls overlay) is a real [Slider]. Its custom look lives in the `track`
 *   slot, so Material owns dragging, press-to-position, keyboard and accessibility.
 * - Edge-aligned (the thin strip under the video when controls are hidden) is
 *   [EdgeAlignedSeekbar], which cannot be a [Slider] — its documentation records why.
 *
 * Both read the playhead out of one [SliderState]: it is a snapshot-backed float, so the track and
 * the thumb pick a tick up in the layout and draw phases and this composable is not invalidated.
 */
@Composable
fun MediaSeekBar(
    /**
     * Progress provider rather than a value: the playhead is written several times a second, and
     * reading it — here or at the call site — subscribes a composable to that tick. It is collected
     * into [SliderState] instead, so nothing recomposes between drags.
     */
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    chapters: List<StreamSegment> = emptyList(),
    sponsorSegments: List<SponsorBlockSegment> = emptyList(),
    /**
     * Per-category colour overrides chosen by the user in SponsorBlock settings. Categories absent
     * from the map fall back to the shared defaults.
     */
    sponsorColors: Map<String, Color> = emptyMap(),
    duration: Long = 0L,
    bufferedValue: Float = 0f,
    edgeAligned: Boolean = false,
    /**
     * Hairline resting state, like YouTube's strip under the video while its controls are hidden.
     * When false the edge-aligned bar is drawn thick with its thumb showing, as it is with the
     * controls up; true keeps it thin until a finger lands on it.
     */
    thin: Boolean = edgeAligned,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val thumbFillColor = MaterialTheme.colorScheme.surface
    val trackColors =
        SeekTrackColors(
            track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
            buffered = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.56f),
            active = primaryColor,
            playhead = thumbFillColor,
        )
    val thumbStateLayerColor = primaryColor.copy(alpha = 0.18f)

    var edgePointerActive by remember { mutableStateOf(false) }

    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isInteracting = isPressed || isDragged || edgePointerActive
    val showThickState = isInteracting || (edgeAligned && !thin)

    // Seeded without read observation so the bar is correct on its first frame without that read
    // subscribing this composable to the playhead for the rest of its life.
    val sliderState = remember { SliderState(value = Snapshot.withoutReadObservation { value() }) }

    // While the thumb is held the component shows the dragged position; otherwise it follows the
    // playhead. `isScrubbing` is only ever set in the same handler that writes the state, so the
    // displayed value can never be a stale leftover from a previous drag.
    val isScrubbing = remember { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    LaunchedEffect(sliderState) {
        // The playhead only ticks a few times a second, so painting it raw makes the bar step.
        // Each tick glides there instead: a linear tween about one tick long reads as continuous
        // motion, while a big jump (a seek) eases in quickly. A finger on the bar always wins.
        snapshotFlow { currentValue() }.collectLatest { raw ->
            if (isScrubbing.value) return@collectLatest
            // A 0/0 playhead (player released or duration not known yet) arrives as NaN, and
            // coerceIn lets NaN through. Animatable throws on a NaN target, so the bar would
            // take the whole app down: treat anything non-finite as "start of the track".
            val fraction = raw.toSeekFraction()
            val from = sliderState.value.toSeekFraction()
            if (kotlin.math.abs(fraction - from) < 0.0005f) {
                sliderState.value = fraction
                return@collectLatest
            }
            val smooth = kotlin.math.abs(fraction - from) < 0.03f
            Animatable(from).animateTo(
                targetValue = fraction,
                animationSpec =
                    if (smooth) {
                        tween(durationMillis = 400, easing = LinearEasing)
                    } else {
                        tween(durationMillis = 260, easing = FastOutSlowInEasing)
                    },
            ) {
                if (!isScrubbing.value) sliderState.value = this.value
            }
        }
    }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val onScrub =
        remember(sliderState) {
            { newValue: Float ->
                isScrubbing.value = true
                sliderState.value = newValue
                currentOnValueChange(newValue)
            }
        }
    val onScrubFinished: () -> Unit =
        remember(sliderState) {
            {
                isScrubbing.value = false
                currentOnValueChangeFinished?.invoke()
                Unit
            }
        }
    sliderState.onValueChange = onScrub
    sliderState.onValueChangeFinished = onScrubFinished

    // Animated in the draw phase rather than through Modifier.height: the track used to grow by
    // animating a layout constraint, which forced a layout pass on every frame of the touch
    // response. The Canvas is a fixed box and only the painted band changes size.
    val trackExpansion = remember { Animatable(0f) }
    val thumbScale = remember { Animatable(0f) }
    val thumbHalo = remember { Animatable(0f) }

    // One effect drives the whole "bar swells, thumb comes out" gesture so the three parts start
    // together and finish together. Short tweens, not springs: a spring's long tail left the thumb
    // creeping for half a second after the finger landed, and the halo used to pop in with no
    // animation at all. Growing decelerates into place; shrinking leaves a touch quicker.
    LaunchedEffect(isInteracting, showThickState) {
        val expansionTarget =
            when {
                isInteracting -> 1f
                showThickState -> CONTROLS_TRACK_EXPANSION
                else -> 0f
            }
        val thumbTarget = if (showThickState) 1f else 0f
        val haloTarget = if (isInteracting) 1f else 0f

        fun <T> spec(
            growing: Boolean,
            ms: Int,
        ) = tween<T>(
            durationMillis = if (growing) ms else (ms * 0.85f).toInt(),
            easing = if (growing) FastOutSlowInEasing else FastOutLinearInEasing,
        )
        coroutineScope {
            launch { trackExpansion.animateTo(expansionTarget, spec(expansionTarget > trackExpansion.value, SEEK_SWELL_MS)) }
            launch { thumbScale.animateTo(thumbTarget, spec(thumbTarget > thumbScale.value, SEEK_SWELL_MS)) }
            launch { thumbHalo.animateTo(haloTarget, spec(haloTarget > thumbHalo.value, SEEK_HALO_MS)) }
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (edgeAligned) Alignment.BottomCenter else Alignment.TopStart,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(if (edgeAligned) EdgeAlignedHeight else ExpandedRowHeight),
            contentAlignment = if (edgeAligned) Alignment.BottomCenter else Alignment.Center,
        ) {
            if (edgeAligned) {
                EdgeAlignedSeekbar(
                    displayValueProvider = { sliderState.value },
                    enabled = enabled,
                    chapters = chapters,
                    sponsorSegments = sponsorSegments,
                    sponsorColors = sponsorColors,
                    duration = duration,
                    bufferedValue = bufferedValue,
                    colors = trackColors,
                    expansionProvider = { trackExpansion.value },
                    thumbScaleProvider = { thumbScale.value },
                    onScrub = onScrub,
                    onPointerActiveChange = { active ->
                        edgePointerActive = active
                        if (!active) onScrubFinished()
                    },
                )
            } else {
                @OptIn(ExperimentalMaterial3Api::class)
                Slider(
                    state = sliderState,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    interactionSource = interactionSource,
                    colors = SliderDefaults.colors(thumbColor = thumbFillColor),
                    track = { state ->
                        Canvas(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(ActiveTrackHeight),
                        ) {
                            drawSeekTrack(
                                fraction = state.coercedValueAsFraction,
                                expansion = trackExpansion.value,
                                chapters = chapters,
                                sponsorSegments = sponsorSegments,
                                sponsorColors = sponsorColors,
                                duration = duration,
                                bufferedValue = bufferedValue,
                                colors = trackColors,
                                bottomAligned = false,
                            )
                        }
                    },
                    thumb = {
                        Box(
                            modifier =
                                Modifier
                                    .size(16.dp)
                                    .graphicsLayer {
                                        val scale = thumbScale.value
                                        scaleX = scale
                                        scaleY = scale
                                    }.drawBehind {
                                        val halo = thumbHalo.value
                                        if (halo > 0f) {
                                            drawCircle(
                                                color = thumbStateLayerColor.copy(alpha = thumbStateLayerColor.alpha * halo),
                                                radius = 20.dp.toPx() * (0.6f + 0.4f * halo),
                                            )
                                        }
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .matchParentSize()
                                        .background(thumbFillColor, CircleShape)
                                        .border(3.dp, primaryColor, CircleShape),
                            )
                        }
                    },
                )
            }
        }
    }
}

/** A seek position as a usable fraction: NaN and infinities (0/0 durations) become 0, the rest is clamped to 0..1. */
internal fun Float.toSeekFraction(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
