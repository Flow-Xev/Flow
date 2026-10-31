package app.zaid.flow.ui.theme

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * The AMOLED ladder puts the page on pure black (#000000) and a card's fill only a few steps above it
 * (#050505 to #1C1C1C), so a card has no visible edge there. Every other variant's background is lighter.
 * Anything below this relative luminance (about #060606) is treated as the AMOLED black.
 */
private const val AMOLED_BACKGROUND_MAX_LUMINANCE = 0.002f

/** True while the active colour scheme's page background is AMOLED black. */
@Composable
@ReadOnlyComposable
fun isAmoledBlackBackground(): Boolean = MaterialTheme.colorScheme.background.luminance() < AMOLED_BACKGROUND_MAX_LUMINANCE

/**
 * A hairline in the scheme's neutral `outlineVariant` around a card of [shape], drawn only on the AMOLED
 * black background where the card's fill alone does not separate it from the page. Light and Dark keep
 * their tonal separation and get no border. Place it after the card's `clip` and `background`.
 */
@Composable
fun Modifier.amoledCardOutline(shape: Shape): Modifier =
    if (isAmoledBlackBackground()) {
        border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = shape)
    } else {
        this
    }
