package app.zaid.flow.ui.components.layout.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import app.zaid.flow.R

private const val MAX_BAR_TABS = 5

/** YouTube's bar draws its glyphs in a 28dp box (Home 25 x 23dp, Shorts 21 x 26dp), measured from its screenshot. */
private val NavIconSize = 28.dp

/**
 * The bottom navigation bar as YouTube draws it: icons only, same white for every tab, the selected
 * one switching from the outlined to the filled glyph. No labels, no indicator, no scaling; the bar
 * sits on the page colour and is separated from it by a hairline.
 */
@Composable
internal fun FlowNavigationBar(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            FlowNavigationBarItems(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                modifier = Modifier.windowInsetsPadding(ShortNavigationBarDefaults.windowInsets),
            )
            HorizontalDivider(
                modifier = Modifier.align(Alignment.TopCenter),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** The icon row of the full-width bar, without its surface, so the bar host can animate the surface. */
@Composable
internal fun FlowNavigationBarItems(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barTabs = if (tabs.size <= MAX_BAR_TABS) tabs else tabs.take(MAX_BAR_TABS - 1)
    val overflowTabs = tabs.drop(barTabs.size)
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        barTabs.forEach { tab ->
            BarItem(
                onClick = { onTabSelected(tab) },
                icon = tab.icon(tab == selectedTab),
                label = stringResource(tab.barLabelRes),
            )
        }
        if (overflowTabs.isNotEmpty()) {
            OverflowItem(tabs = overflowTabs, selectedTab = selectedTab, onTabSelected = onTabSelected)
        }
    }
}

@Composable
private fun RowScope.BarItem(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    Box(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxSize()
                .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The label is kept for screen readers only.
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(NavIconSize),
        )
    }
}

@Composable
private fun RowScope.OverflowItem(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f).fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            BarItem(
                onClick = { expanded = true },
                icon = Icons.Filled.MoreHoriz,
                label = stringResource(R.string.nav_more),
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
                        text = { Text(text = stringResource(tab.barLabelRes)) },
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
