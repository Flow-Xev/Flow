/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 */

package app.zaid.flow.ui.components.shared

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ChipHeight = 32.dp
private val ChipShape = RoundedCornerShape(8.dp)

/**
 * The single filter chip for every surface: home feed chips, search filters, browse tabs,
 * library content filters. Shaped like YouTube's: a 32 dp chip with 8 dp corners, filled with the
 * inverse surface when selected and a soft tonal surface otherwise — selection is shown by colour,
 * not by a check mark, so chips never change width when toggled.
 */
@Composable
fun FlowFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowChip(label = label, selected = selected, onClick = onClick, modifier = modifier, showChevron = false)
}

/**
 * A filter chip that opens a menu instead of toggling. [selected] marks that a non-default option is
 * active; the chevron tells it apart from a toggle chip side by side.
 */
@Composable
fun FlowDropdownFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowChip(label = label, selected = selected, onClick = onClick, modifier = modifier, showChevron = true)
}

@Composable
private fun FlowChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    showChevron: Boolean,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "chipContainer",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
        label = "chipContent",
    )
    // The 32 dp chip sits in a 48 dp touch target, exactly as Material's own chips do.
    Box(
        modifier = modifier.minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            shape = ChipShape,
            color = container,
            contentColor = content,
            modifier = Modifier.height(ChipHeight).semantics { this.selected = selected },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showChevron) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
