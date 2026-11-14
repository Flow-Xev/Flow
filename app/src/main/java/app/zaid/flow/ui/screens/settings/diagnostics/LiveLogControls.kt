package app.zaid.flow.ui.screens.settings.diagnostics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.ui.components.shared.FlowFilterChip

private val ChipSpacing = 8.dp
private val EdgePadding = 4.dp

/** What the live log shows (all, warnings, errors), whether it is holding still, and whether it follows the end. */
@Composable
internal fun LiveLogControls(
    filter: LogFilter,
    paused: Boolean,
    follow: Boolean,
    onFilter: (LogFilter) -> Unit,
    onTogglePaused: () -> Unit,
    onToggleFollow: () -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
    ) {
        LogFilter.entries.forEach { option ->
            FlowFilterChip(label = stringResource(option.label()), selected = filter == option, onClick = { onFilter(option) })
        }
        FlowFilterChip(
            label = stringResource(if (paused) R.string.diagnostics_paused else R.string.diagnostics_live),
            selected = paused,
            onClick = onTogglePaused,
        )
        FlowFilterChip(label = stringResource(R.string.diagnostics_follow), selected = follow, onClick = onToggleFollow)
    }
}

private fun LogFilter.label(): Int =
    when (this) {
        LogFilter.ALL -> R.string.diagnostics_filter_all
        LogFilter.WARNINGS -> R.string.diagnostics_filter_warnings
        LogFilter.ERRORS -> R.string.diagnostics_filter_errors
    }
