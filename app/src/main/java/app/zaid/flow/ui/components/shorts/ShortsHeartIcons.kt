package app.zaid.flow.ui.components.shorts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * YouTube's heart: full, round lobes and a softly rounded bottom tip rather than the sharp point of
 * the stock Material heart. The rounding comes from a round stroke join on the same outline.
 */
private fun PathBuilder.heartOutline() {
    moveTo(19f, 14f)
    curveToRelative(1.49f, -1.46f, 3f, -3.21f, 3f, -5.5f)
    arcTo(5.5f, 5.5f, 0f, false, false, 16.5f, 3f)
    curveToRelative(-1.76f, 0f, -3f, 0.5f, -4.5f, 2f)
    curveToRelative(-1.5f, -1.5f, -2.74f, -2f, -4.5f, -2f)
    arcTo(5.5f, 5.5f, 0f, false, false, 2f, 8.5f)
    curveToRelative(0f, 2.3f, 1.5f, 4.05f, 3f, 5.5f)
    lineToRelative(7f, 7f)
    close()
}

/** The unliked heart: a smooth outline. */
internal val ShortsHeartOutline: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "ShortsHeartOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { heartOutline() }
        .build()
}

/** The liked heart: the same shape, filled, with the round join smoothing the tip. */
internal val ShortsHeartFilled: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "ShortsHeartFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { heartOutline() }
        .build()
}
