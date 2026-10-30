package app.zaid.flow.ui.components.layout.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zaid.flow.R
import app.zaid.flow.ui.utils.LocalWindowSizeClass
import app.zaid.flow.ui.utils.isMediumWidth

private const val MAX_BAR_TABS = 5

/**
 * The bottom navigation bar: icon over label with no selection bubble, as YouTube draws it. A sixth
 * and seventh enabled tab move behind a "More" item; the rail has room for all of them.
 */
@Composable
internal fun FlowNavigationBar(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalItems = LocalWindowSizeClass.current.isMediumWidth
    val barTabs = if (tabs.size <= MAX_BAR_TABS) tabs else tabs.take(MAX_BAR_TABS - 1)
    val overflowTabs = tabs.drop(barTabs.size)

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(ShortNavigationBarDefaults.windowInsets),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                barTabs.forEach { tab ->
                    val selected = tab == selectedTab
                    BarItem(
                        selected = selected,
                        onClick = { onTabSelected(tab) },
                        icon = tab.icon(selected),
                        label = stringResource(tab.labelRes),
                        horizontal = horizontalItems,
                    )
                }
                if (overflowTabs.isNotEmpty()) {
                    OverflowItem(
                        tabs = overflowTabs,
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                        horizontal = horizontalItems,
                    )
                }
            }
            // YouTube separates its bar from the page with a hairline instead of a tonal fill.
            HorizontalDivider(
                modifier = Modifier.align(Alignment.TopCenter),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun RowScope.BarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    horizontal: Boolean,
) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navColor",
    )
    val iconScale by animateFloatAsState(if (selected) 1.1f else 1f, label = "navIconScale")
    val indicatorWidth by animateFloatAsState(if (selected) 20f else 0f, label = "navIndicator")
    val content: @Composable () -> Unit = {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp).scale(iconScale))
        Text(
            text = label,
            color = color,
            // Material's default 24sp line height would leave a gap under the icon; a line box
            // trimmed to the glyphs keeps the label right beneath it.
            style =
                TextStyle(
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (horizontal) Modifier.padding(start = 8.dp) else Modifier,
        )
    }
    val itemModifier =
        Modifier
            .weight(1f)
            .fillMaxSize()
            .clickable(role = Role.Tab, onClick = onClick)
    Box(itemModifier) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(indicatorWidth.dp)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)),
        )
        if (horizontal) {
            Row(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterVertically) { content() }
        } else {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { content() }
        }
    }
}

@Composable
private fun RowScope.OverflowItem(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    horizontal: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f).fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            BarItem(
                selected = selectedTab in tabs,
                onClick = { expanded = true },
                icon = Icons.Filled.MoreHoriz,
                label = stringResource(R.string.nav_more),
                horizontal = horizontal,
            )
        }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(index = 0, count = 1)) {
                tabs.forEachIndexed { index, tab ->
                    DropdownMenuItem(
                        selected = tab == selectedTab,
                        onClick = {
                            expanded = false
                            onTabSelected(tab)
                        },
                        text = { Text(text = stringResource(tab.labelRes)) },
                        shapes = MenuDefaults.itemShape(index = index, count = tabs.size),
                        leadingIcon = {
                            Icon(
                                imageVector = tab.icon(selected = false),
                                contentDescription = null,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        selectedLeadingIcon = {
                            Icon(
                                imageVector = tab.icon(selected = true),
                                contentDescription = null,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                    )
                }
            }
        }
    }
}
