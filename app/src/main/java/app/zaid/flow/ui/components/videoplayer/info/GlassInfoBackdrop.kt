package app.zaid.flow.ui.components.videoplayer.info

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Where the blur starts dissolving, as a share of the distance down to the action row. */
private const val FADE_START_SHARE = 0.45f

/** Used until the action row has been measured. */
private const val FALLBACK_FADE_END_DP = 120

/**
 * Lets the action row tell the backdrop where it starts, so the blur is fully gone by the top of the
 * like / share row whatever the title length or font scale. It stores the row's offset *inside the
 * backdrop*, which does not change while scrolling, so scrolling never invalidates the blur layer.
 */
@Stable
internal class GlassFadeAnchor {
    private var backdrop: LayoutCoordinates? = null
    private var row: LayoutCoordinates? = null

    var fadeEndPx by mutableFloatStateOf(Float.NaN)
        private set

    fun backdropPlaced(coordinates: LayoutCoordinates) {
        backdrop = coordinates
        update()
    }

    fun rowPlaced(coordinates: LayoutCoordinates) {
        row = coordinates
        update()
    }

    private fun update() {
        val b = backdrop?.takeIf { it.isAttached } ?: return
        val r = row?.takeIf { it.isAttached } ?: return
        val y = b.localPositionOf(r, Offset.Zero).y
        if (fadeEndPx.isNaN() || abs(y - fadeEndPx) > 1f) fadeEndPx = y
    }
}

internal val LocalGlassFadeAnchor = compositionLocalOf<GlassFadeAnchor?> { null }

/**
 * Meeting point of the player surface (which samples the playing frame) and the glass backdrop (which shows it):
 * [wanted] is set while the backdrop is on screen, [frame] carries the latest sampled picture.
 */
internal object GlassFrameBus {
    var wanted by mutableStateOf(false)
    var frame by mutableStateOf<ImageBitmap?>(null)
}

/** Marks the row the glass blur must finish fading before. No-op outside [GlassInfoBackdrop]. */
@Composable
internal fun Modifier.glassFadeEnd(): Modifier {
    val anchor = LocalGlassFadeAnchor.current ?: return this
    return onGloballyPositioned { anchor.rowPlaced(it) }
}

/**
 * The "glass" look of the player page's info section: the video's own frame, enlarged and blurred, sits
 * behind the title and channel, under a scrim of the theme's surface colour so text stays readable in
 * light and dark. The pills inside turn translucent through a theme whose container roles are tinted
 * from the same tokens, so no colour here is hard-coded.
 *
 * The blur starts dissolving under the title and is completely gone by the top of the action row
 * (marked with [glassFadeEnd]), fading into the page background (black in dark, white in light) with
 * an eased alpha ramp. There is no bottom edge to see: the backdrop is faded to transparent, so
 * whatever sits behind it shows through exactly.
 *
 * [tail] is laid out below [content] on the plain page background.
 *
 * With [enabled] false (or no image) the content is laid out as a plain column, exactly as before.
 */
@Composable
fun GlassInfoBackdrop(
    enabled: Boolean,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    tail: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!enabled || imageUrl.isNullOrBlank()) {
        Column(modifier) {
            content()
            tail()
        }
        return
    }
    val scheme = MaterialTheme.colorScheme
    val glassScheme =
        remember(scheme) {
            scheme.copy(
                surfaceContainerLow = scheme.onSurface.copy(alpha = 0.08f),
                surfaceVariant = scheme.onSurface.copy(alpha = 0.10f),
                surfaceContainerHigh = scheme.onSurface.copy(alpha = 0.12f),
                secondaryContainer = scheme.onSurface.copy(alpha = 0.16f),
                onSecondaryContainer = scheme.onSurface,
            )
        }
    val context = LocalContext.current
    // A tiny decode is already soft; on Android 12+ the blur finishes the job. Older versions keep the soft decode.
    val request =
        remember(imageUrl) {
            ImageRequest
                .Builder(context)
                .data(imageUrl)
                .size(Size(96, 54))
                .build()
        }
    val anchor = remember { GlassFadeAnchor() }
    // Asks the player surface for the playing frame only while this backdrop is on screen.
    var onScreen by remember { mutableStateOf(true) }
    DisposableEffect(onScreen) {
        GlassFrameBus.wanted = onScreen
        onDispose { GlassFrameBus.wanted = false }
    }
    DisposableEffect(Unit) { onDispose { GlassFrameBus.frame = null } }
    LaunchedEffect(imageUrl) { GlassFrameBus.frame = null }
    CompositionLocalProvider(LocalGlassFadeAnchor provides anchor) {
        MaterialTheme(colorScheme = glassScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            Column(modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().onGloballyPositioned {
                        anchor.backdropPlaced(it)
                        onScreen = it.isAttached && it.boundsInWindow().let { b -> b.height > 0f && b.bottom > 0f }
                    },
                ) {
                    // Image and scrim share one offscreen layer so the fade mask applies to both together.
                    Box(
                        Modifier
                            .matchParentSize()
                            .clipToBounds()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                // Solid under the title, eased to nothing by the top of the action row.
                                val end =
                                    anchor.fadeEndPx
                                        .takeUnless { it.isNaN() }
                                        ?.coerceIn(1f, size.height)
                                        ?: FALLBACK_FADE_END_DP.dp.toPx().coerceAtMost(size.height)
                                val t = (end * FADE_START_SHARE / size.height).coerceIn(0f, 1f)
                                val e = (end / size.height).coerceIn((t + 0.001f).coerceAtMost(1f), 1f)

                                fun at(p: Float) = t + (e - t) * p
                                drawRect(
                                    brush =
                                        Brush.verticalGradient(
                                            0f to Color.Black,
                                            t to Color.Black,
                                            at(0.18f) to Color.Black.copy(alpha = 0.92f),
                                            at(0.38f) to Color.Black.copy(alpha = 0.70f),
                                            at(0.58f) to Color.Black.copy(alpha = 0.42f),
                                            at(0.78f) to Color.Black.copy(alpha = 0.16f),
                                            at(0.92f) to Color.Black.copy(alpha = 0.04f),
                                            e to Color.Transparent,
                                            1f to Color.Transparent,
                                        ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    ) {
                        AsyncImage(
                            model = request,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .clipToBounds()
                                    .then(
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            Modifier.blur(28.dp, BlurredEdgeTreatment.Unbounded)
                                        } else {
                                            Modifier
                                        },
                                    ),
                        )
                        LiveFrameLayer({ GlassFrameBus.frame }, Modifier.matchParentSize())
                        Box(Modifier.matchParentSize().background(scheme.surface.copy(alpha = 0.62f)))
                    }
                    Column(content = content)
                }
                Column(content = tail)
            }
        }
    }
}

/** The sampler's frame is stored dimmed for the ambient glow; this restores the picture's own brightness. */
private val LiveFrameBrightness =
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                1.7f, 0f, 0f, 0f, 0f,
                0f, 1.7f, 0f, 0f, 0f,
                0f, 0f, 1.7f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

/**
 * Eases the displayed picture toward the latest sampled frame on its own clock. The sampler only produces a new
 * target every few seconds (and snaps to its first one after a restart), so without this the colours would move in
 * steps. Two small bitmaps alternate so the render thread never draws the one being written.
 */
private class GlassSmoother {
    private var w = 0
    private var h = 0
    private var cur = FloatArray(0)
    private var tgt = FloatArray(0)
    private var px = IntArray(0)
    private var buffers: Array<Bitmap>? = null
    private var next = 0

    val hasPicture get() = cur.isNotEmpty()

    fun setTarget(frame: ImageBitmap) {
        val src = frame.asAndroidBitmap()
        if (w != src.width || h != src.height) {
            w = src.width
            h = src.height
            cur = FloatArray(0)
            tgt = FloatArray(w * h * 3)
            px = IntArray(w * h)
            buffers = arrayOf(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        }
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            tgt[i * 3] = ((c shr 16) and 0xFF).toFloat()
            tgt[i * 3 + 1] = ((c shr 8) and 0xFF).toFloat()
            tgt[i * 3 + 2] = (c and 0xFF).toFloat()
        }
        if (cur.isEmpty()) cur = tgt.copyOf()
    }

    /** Moves [dtMs] closer to the target and returns the picture, or null when there is none. */
    fun step(
        dtMs: Float,
        moving: (Boolean) -> Unit,
    ): ImageBitmap? {
        val bufs = buffers ?: return null
        if (cur.isEmpty()) return null
        val a = 1f - exp(-dtMs / GLASS_TAU_MS)
        var maxDelta = 0f
        for (i in cur.indices) {
            val d = tgt[i] - cur[i]
            cur[i] += d * a
            if (abs(d) > maxDelta) maxDelta = abs(d)
        }
        for (i in px.indices) {
            px[i] = (0xFF shl 24) or
                (cur[i * 3].toInt().coerceIn(0, 255) shl 16) or
                (cur[i * 3 + 1].toInt().coerceIn(0, 255) shl 8) or
                cur[i * 3 + 2].toInt().coerceIn(0, 255)
        }
        val out = bufs[next]
        next = 1 - next
        out.setPixels(px, 0, w, 0, 0, w, h)
        moving(maxDelta > 0.6f)
        return out.asImageBitmap()
    }
}

private const val GLASS_TAU_MS = 1100f
private const val GLASS_STEP_MS = 50L

/**
 * Own composable so the state read of the latest frame recomposes only this layer, never the info content above
 * it. The picture follows the sampled frame through [GlassSmoother], so colours drift instead of jumping, and the
 * layer fades in and out over the thumbnail (a new video has no frame until its first sample).
 */
@Composable
private fun LiveFrameLayer(
    frameProvider: () -> ImageBitmap?,
    modifier: Modifier,
) {
    val target = frameProvider()
    val smoother = remember { GlassSmoother() }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        smoother.setTarget(target)
        var last = SystemClock.elapsedRealtime()
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            var stillMoving = false
            shown = smoother.step((now - last).toFloat().coerceIn(1f, 500f)) { stillMoving = it }
            last = now
            if (!stillMoving) break
            delay(GLASS_STEP_MS)
        }
    }
    val alpha by animateFloatAsState(if (target != null) 1f else 0f, tween(if (target != null) 800 else 400), label = "glassLiveFrame")
    val picture = shown
    if (picture != null && alpha > 0.01f) {
        Image(
            bitmap = picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
            colorFilter = LiveFrameBrightness,
            modifier =
                modifier
                    .graphicsLayer { this.alpha = alpha }
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(28.dp, BlurredEdgeTreatment.Unbounded)
                        } else {
                            Modifier
                        },
                    ),
        )
    }
}
