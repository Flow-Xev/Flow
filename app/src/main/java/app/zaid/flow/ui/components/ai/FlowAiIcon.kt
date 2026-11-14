package app.zaid.flow.ui.components.ai

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// A large four-point sparkle with a small one at its top right: the mark for "AI".
private const val SPARKLE_LARGE =
    "M10.5 5C11.1 9.6 14.4 12.9 19 13.5C14.4 14.1 11.1 17.4 10.5 22C9.9 17.4 6.6 14.1 2 13.5C6.6 12.9 9.9 9.6 10.5 5Z"
private const val SPARKLE_SMALL =
    "M18.5 2C18.8 4 19.5 4.7 21.5 5C19.5 5.3 18.8 6 18.5 8C18.2 6 17.5 5.3 15.5 5C17.5 4.7 18.2 4 18.5 2Z"

/** The Flow AI mark. Drawn in [tint], which defaults to the theme's primary. */
@Composable
fun FlowAiIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val large = remember { PathParser().parsePathString(SPARKLE_LARGE).toPath() }
    val small = remember { PathParser().parsePathString(SPARKLE_SMALL).toPath() }
    Canvas(modifier = modifier) {
        drawContext.canvas.save()
        drawContext.canvas.scale(size.width / 24f, size.height / 24f)
        drawPath(path = large, color = tint)
        drawPath(path = small, color = tint)
        drawContext.canvas.restore()
    }
}

/** The same sparkle as an [ImageVector], for icon slots such as the player's action row. */
val FlowAiSparkleVector: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "FlowAiSparkle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            listOf(SPARKLE_LARGE, SPARKLE_SMALL).forEach { data ->
                addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color.Black))
            }
        }.build()
}
