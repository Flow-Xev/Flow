package app.zaid.flow.ui.components.shared

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zaid.flow.R

private val ChipSpacing = 8.dp

/**
 * The orders a comment section offers, and the one filter the app adds on top.
 *
 * Top and Newest are the two orders YouTube serves, each behind its own continuation. Oldest has no
 * server equivalent, so it reverses the chronological pages already loaded. Timed keeps only the
 * comments whose text points at a position in this video.
 */
@Composable
fun CommentSortChips(
    selected: CommentSortFilter,
    onSelect: (CommentSortFilter) -> Unit,
    modifier: Modifier = Modifier,
    timedOnly: Boolean = false,
    onTimedChange: ((Boolean) -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
    ) {
        CommentSortFilter.entries.forEach { filter ->
            CommentPill(
                label = stringResource(filter.labelRes),
                selected = selected == filter,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(filter)
                },
            )
        }
        if (onTimedChange != null) {
            CommentPill(
                label = stringResource(R.string.filter_timed),
                selected = timedOnly,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onTimedChange(!timedOnly)
                },
            )
        }
    }
}

/** A calm pill: inverse surface when selected, a soft tonal surface otherwise. */
@Composable
private fun CommentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (selected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.height(32.dp),
    ) {
        androidx.compose.material3.Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier =
                Modifier
                    .padding(horizontal = 14.dp)
                    .wrapContentHeight(androidx.compose.ui.Alignment.CenterVertically),
        )
    }
}
