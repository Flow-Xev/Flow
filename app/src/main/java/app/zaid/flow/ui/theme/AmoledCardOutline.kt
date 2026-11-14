package app.zaid.flow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance

/** Anything below this relative luminance (about #060606) is treated as the AMOLED black. */
private const val AMOLED_BACKGROUND_MAX_LUMINANCE = 0.002f

/** True while the active colour scheme's page background is AMOLED black. */
@Composable
@ReadOnlyComposable
fun isAmoledBlackBackground(): Boolean = MaterialTheme.colorScheme.background.luminance() < AMOLED_BACKGROUND_MAX_LUMINANCE

/**
 * Cards no longer draw an outline in any theme: YouTube separates content with spacing, not borders.
 * Kept as a no-op so existing call sites stay valid.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Modifier.amoledCardOutline(shape: Shape): Modifier = this
