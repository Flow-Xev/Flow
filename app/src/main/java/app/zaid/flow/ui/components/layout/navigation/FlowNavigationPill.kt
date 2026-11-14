package app.zaid.flow.ui.components.layout.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

private val PillItemWidth = 68.dp
private val PillItemHeight = 48.dp
private val PillPadding = 8.dp
private val PillHeight = PillItemHeight + PillPadding * 2
private val PillBottomMargin = 12.dp

private data class NavContent(val tabs: List<FlowTab>, val pill: Boolean)

/**
 * The bottom bar for both modes. In video mode it is the full-width YouTube-style bar; in music mode
 * the very same surface narrows, rounds and lifts into a small floating pill (ArchiveTune style),
 * while the icons cross-fade to the music tabs. [onHeightChanged] reports the space the bar takes
 * above the system inset, margin included, so mini players sit right above it.
 */
@Composable
internal fun FlowNavigationBarHost(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    pill: Boolean,
    onHeightChanged: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val inset = with(density) { ShortNavigationBarDefaults.windowInsets.getBottom(density).toDp() }
    // Spatial springs overshoot their target; a size, padding or corner radius must never go negative
    // (that crashed with "Padding must be non-negative"), so the morph is driven by a non-bouncy tween.
    val p by animateFloatAsState(
        targetValue = if (pill) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "navPill",
    )
    val pillWidth = PillItemWidth * MUSIC_MODE_TABS.size + PillPadding * 2
    val colors = MaterialTheme.colorScheme
    val content = NavContent(tabs, pill)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val barHeight = FlowNavigationDefaults.BarHeight
        // Reported height: what the bar occupies above the inset.
        val above = lerp(barHeight.value, (PillHeight + PillBottomMargin).value, p).dp
        val total = above + inset
        androidx.compose.runtime.SideEffect { onHeightChanged(above) }

        Box(Modifier.fillMaxWidth().height(total)) {
            val surfaceHeight = lerp(barHeight.value + inset.value, PillHeight.value, p).dp
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = lerp(0f, (PillBottomMargin + inset).value, p).dp)
                        .width(lerp(this@BoxWithConstraints.maxWidth.value, pillWidth.value, p).dp)
                        .height(surfaceHeight),
                shape = RoundedCornerShape(lerp(0f, PillHeight.value / 2f, p).dp),
                color = lerpColor(colors.background, colors.surfaceContainerHigh, p),
                tonalElevation = (3f * p).dp,
                shadowElevation = (6f * p).dp,
            ) {
                Crossfade(targetState = content, label = "navContent") { c ->
                    if (c.pill) {
                        PillItems(c.tabs, selectedTab, onTabSelected)
                    } else {
                        Box(Modifier.fillMaxSize()) {
                            FlowNavigationBarItems(c.tabs, selectedTab, onTabSelected, Modifier.windowInsetsPadding(ShortNavigationBarDefaults.windowInsets))
                            HorizontalDivider(Modifier.align(Alignment.TopCenter), 1.dp, colors.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillItems(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
) {
    val index = tabs.indexOf(selectedTab).coerceAtLeast(0)
    val x by animateDpAsState(PillItemWidth * index, tween(durationMillis = 220, easing = FastOutSlowInEasing), label = "pillIndicator")
    Box(Modifier.fillMaxSize().padding(PillPadding)) {
        Box(
            Modifier
                .offset(x = x)
                .size(PillItemWidth, PillItemHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            tabs.forEach { tab ->
                val selected = tab == selectedTab
                val tint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.motionScheme.fastEffectsSpec(),
                    label = "pillTint",
                )
                val scale by animateFloatAsState(if (selected) 1.08f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "pillScale")
                Box(
                    Modifier
                        .width(PillItemWidth)
                        .fillMaxSize()
                        .clip(CircleShape)
                        .clickable(role = Role.Tab) { onTabSelected(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = tab.icon(selected),
                        contentDescription = stringResource(tab.barLabelRes),
                        tint = tint,
                        modifier = Modifier.size(26.dp).scale(scale),
                    )
                }
            }
        }
    }
}

