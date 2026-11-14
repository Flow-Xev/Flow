package app.zaid.flow.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import app.zaid.flow.util.AppIcons
import kotlin.math.roundToInt

// SVG path data — a single unbreakable token, so the line-length rule cannot be satisfied by wrapping.
@Suppress("ktlint:standard:max-line-length")
private const val FLOW_LOGO_BG_PATH = "M21.58 7.16C21.33 6.22 20.59 5.48 19.65 5.23C17.96 4.77 12 4.77 12 4.77C12 4.77 6.04 4.77 4.35 5.23C3.41 5.48 2.67 6.22 2.42 7.16C1.96 8.85 1.96 12.38 1.96 12.38C1.96 12.38 1.96 15.91 2.42 17.6C2.67 18.54 3.41 19.28 4.35 19.53C6.04 19.99 12 19.99 12 19.99C12 19.99 17.96 19.99 19.65 19.53C20.59 19.28 21.33 18.54 21.58 17.6C22.04 15.91 22.04 12.38 22.04 12.38C22.04 12.38 22.04 8.85 21.58 7.16Z"
private const val FLOW_LOGO_GLYPH_PATH = "M10 7L18 7L17.2 9.5H12.8L12.2 11.5H16L15.2 14H11.5L10.5 17H7.5L10 7Z"

@Suppress("ktlint:standard:max-line-length")
private const val FLOW_INCOGNITO_GLYPH_PATH = "M17.06 13C15.2 13 13.64 14.33 13.24 16.1C12.29 15.69 11.42 15.8 10.76 16.09C10.35 14.31 8.79 13 6.94 13C4.77 13 3 14.79 3 17C3 19.21 4.77 21 6.94 21C9 21 10.68 19.38 10.84 17.32C11.18 17.08 12.07 16.63 13.16 17.34C13.34 19.39 15 21 17.06 21C19.23 21 21 19.21 21 17C21 14.79 19.23 13 17.06 13M6.94 19.86C5.38 19.86 4.13 18.58 4.13 17S5.39 14.14 6.94 14.14C8.5 14.14 9.75 15.42 9.75 17S8.5 19.86 6.94 19.86M17.06 19.86C15.5 19.86 14.25 18.58 14.25 17S15.5 14.14 17.06 14.14C18.62 14.14 19.88 15.42 19.88 17S18.61 19.86 17.06 19.86M22 10.5H2V12H22V10.5M15.53 2.63C15.31 2.14 14.75 1.88 14.22 2.05L12 2.79L9.77 2.05L9.72 2.04C9.19 1.89 8.63 2.17 8.43 2.68L6 9H18L15.56 2.68L15.53 2.63Z"

/** Long-press on the Home logo or on the wordmark beside it switches Deep Flow. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.deepFlowLongPress(
    label: String,
    onLongPress: () -> Unit,
): Modifier = combinedClickable(onLongClickLabel = label, onClick = {}, onLongClick = onLongPress)

/**
 * Launcher icons whose foreground layer is a single flat colour (white or near-black) that only reads
 * against its own background. Without that background they are tinted with the theme's text colour.
 */
private val FLAT_FOREGROUND_SUFFIXES = setOf(".IconGhost", ".IconDynamic", ".IconMonochrome")

/** Adaptive icons are drawn on a 108dp canvas. */
private const val ADAPTIVE_CANVAS_DP = 108f

/** Pixels with less alpha than this are treated as empty when measuring the art. */
private const val ART_ALPHA_THRESHOLD = 8

/**
 * Draws only the foreground layer of the launcher icon: no background fill and no launcher mask.
 * The art is trimmed to its own bounds and scaled to fill the bitmap, so every icon is as large in the
 * header as the logo itself instead of sitting inside the launcher's safe-zone padding.
 */
private fun renderForegroundOnly(
    context: Context,
    iconRes: Int,
    sizePx: Int,
): Bitmap? {
    val icon = ContextCompat.getDrawable(context, iconRes)?.mutate() ?: return null
    val foreground = (icon as? AdaptiveIconDrawable)?.foreground?.mutate() ?: return icon.toBitmap(sizePx, sizePx)

    val fullPx = (sizePx * (ADAPTIVE_CANVAS_DP / 24f)).roundToInt().coerceAtLeast(sizePx)
    val full = Bitmap.createBitmap(fullPx, fullPx, Bitmap.Config.ARGB_8888)
    foreground.setBounds(0, 0, fullPx, fullPx)
    foreground.draw(android.graphics.Canvas(full))

    val pixels = IntArray(fullPx * fullPx)
    full.getPixels(pixels, 0, fullPx, 0, 0, fullPx, fullPx)
    var minX = fullPx
    var minY = fullPx
    var maxX = -1
    var maxY = -1
    for (y in 0 until fullPx) {
        for (x in 0 until fullPx) {
            if ((pixels[y * fullPx + x] ushr 24) > ART_ALPHA_THRESHOLD) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < minX || maxY < minY) return full

    val side = maxOf(maxX - minX + 1, maxY - minY + 1).toFloat()
    val centerX = (minX + maxX + 1) / 2f
    val centerY = (minY + maxY + 1) / 2f
    val scale = sizePx / side

    val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.scale(scale, scale)
    canvas.translate(-(centerX - side / 2f), -(centerY - side / 2f))
    canvas.drawBitmap(full, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    return out
}

/**
 * The Home header logo. It is the launcher icon picked in Appearance settings, drawn from its
 * foreground art only (no background); Deep Flow swaps it for the incognito glyph.
 */
@Composable
internal fun FlowHeaderLogoIcon(
    isDeepFlowActive: Boolean,
    iconSuffix: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val launcherIcon = AppIcons.icon(iconSuffix ?: AppIcons.DEFAULT_SUFFIX)
    val iconRes = launcherIcon.iconRes
    val iconBitmap =
        remember(iconRes, sizePx) {
            runCatching { renderForegroundOnly(context, iconRes, sizePx)?.asImageBitmap() }.getOrNull()
        }
    val flatTint =
        if (launcherIcon.suffix in FLAT_FOREGROUND_SUFFIXES) {
            ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
        } else {
            null
        }
    Crossfade(
        targetState = isDeepFlowActive || iconBitmap == null,
        animationSpec = tween(durationMillis = 250),
        modifier = modifier.size(size),
        label = "headerLogo",
    ) { showIncognito ->
        if (showIncognito) {
            FlowDeepFlowLogo(isDeepFlowActive = isDeepFlowActive, modifier = Modifier.size(size))
        } else {
            Image(
                bitmap = iconBitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = flatTint,
                modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
private fun FlowDeepFlowLogo(
    isDeepFlowActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    val bgPath =
        remember {
            PathParser().parsePathString(FLOW_LOGO_BG_PATH).toPath()
        }
    val glyphPath =
        remember {
            PathParser().parsePathString(FLOW_LOGO_GLYPH_PATH).toPath()
        }
    val incognitoPath =
        remember {
            PathParser().parsePathString(FLOW_INCOGNITO_GLYPH_PATH).toPath()
        }

    val glyphAlpha by animateFloatAsState(
        targetValue = if (isDeepFlowActive) 0f else 1f,
        animationSpec = tween(durationMillis = 250),
        label = "deepFlowGlyphAlpha",
    )
    val incognitoAlpha by animateFloatAsState(
        targetValue = if (isDeepFlowActive) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "deepFlowIncognitoAlpha",
    )

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        drawContext.canvas.save()
        drawContext.canvas.scale(sx, sy)
        drawPath(path = bgPath, color = primaryColor)

        if (glyphAlpha > 0f) {
            drawPath(path = glyphPath, color = onPrimaryColor.copy(alpha = glyphAlpha))
        }
        if (incognitoAlpha > 0f) {
            val incognitoScale = 0.65f
            val incognitoOffset = 12f * (1f - incognitoScale)
            drawContext.canvas.save()
            drawContext.canvas.translate(incognitoOffset, incognitoOffset)
            drawContext.canvas.scale(incognitoScale, incognitoScale)
            drawPath(path = incognitoPath, color = onPrimaryColor.copy(alpha = incognitoAlpha))
            drawContext.canvas.restore()
        }

        drawContext.canvas.restore()
    }
}
