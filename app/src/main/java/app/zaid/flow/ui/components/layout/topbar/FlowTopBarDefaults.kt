package app.zaid.flow.ui.components.layout.topbar

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared look of every non-player top bar, modelled on YouTube's own: a flat 56 dp bar painted on
 * the screen background (no tint when content scrolls under it), a 20 sp semibold title and
 * full-strength icons.
 *
 * The values are not Material defaults on purpose: Flow paints screens on `colorScheme.background`
 * rather than `surface`, and [WindowInsets] owns no status-bar padding because the root `Scaffold`
 * in `FlowApp` already consumes `WindowInsets.systemBars`. Letting a bar apply its own insets would
 * double the status-bar gap on every screen.
 */
object FlowTopBarDefaults {
    /** Status bar padding is applied once by the root scaffold, never by an individual bar. */
    val WindowInsets: WindowInsets = WindowInsets(0.dp)

    /** YouTube's bar height; the Search tab's row (48 dp buttons + 4 dp padding) already matches it. */
    val Height: Dp = 56.dp

    /** Gap before a title that has no back arrow or leading slot, so it lines up with feed content. */
    val TitleStartInset: Dp = 16.dp

    /** Gap between the back arrow / leading slot and the title. */
    val TitleGap: Dp = 4.dp

    /** Outer padding so 48 dp icon buttons sit where YouTube puts them. */
    val HorizontalPadding: Dp = 4.dp

    val containerColor: Color
        @Composable get() = MaterialTheme.colorScheme.background

    val titleColor: Color
        @Composable get() = MaterialTheme.colorScheme.onBackground

    /** YouTube draws its bar icons in the primary text colour, not a muted one. */
    val iconColor: Color
        @Composable get() = MaterialTheme.colorScheme.onBackground

    val titleStyle: TextStyle
        @Composable get() =
            MaterialTheme.typography.titleLarge.copy(
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            )

    val subtitleStyle: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall
}
