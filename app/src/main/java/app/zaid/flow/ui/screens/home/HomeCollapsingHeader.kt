package app.zaid.flow.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * Home's header the way YouTube's behaves: the bar and the chip row below it slide off the top as
 * the feed scrolls down and come back on the first scroll up.
 *
 * [state] is driven by an enter-always scroll behaviour on the feed. [expandedHeight] is the header's
 * full height (bar plus chip row when it is shown) and becomes the state's collapse limit. The
 * offset is read in the layout phase, so scrolling never recomposes the header, and the feed below
 * simply gets the space the header gives up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeCollapsingHeader(
    state: TopAppBarState,
    expandedHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val limitPx = with(LocalDensity.current) { expandedHeight.toPx() }
    SideEffect {
        if (state.heightOffsetLimit != -limitPx) state.heightOffsetLimit = -limitPx
    }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val offset = state.heightOffset.roundToInt()
                    layout(placeable.width, (placeable.height + offset).coerceAtLeast(0)) {
                        placeable.place(0, offset)
                    }
                },
        content = content,
    )
}
