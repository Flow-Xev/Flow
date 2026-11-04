package app.zaid.flow.ui.screens.home.chips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.ui.components.shared.FlowFilterChip

/** Height of the chip row; the Home header needs it to know how far it can collapse. */
internal val HomeChipRowHeight = 48.dp
private val ChipRowPadding = 12.dp
private val ChipSpacing = 8.dp

/**
 * The chips under the Home bar, laid out like YouTube's: a flat row of 8 dp-cornered chips. It
 * does not scroll away by itself — `HomeCollapsingHeader` slides the bar and this row off together.
 */
@Composable
internal fun HomeChipRow(
    state: HomeChipsState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    LazyRow(
        modifier = modifier.fillMaxWidth().height(HomeChipRowHeight),
        contentPadding = PaddingValues(horizontal = ChipRowPadding),
        horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(state.chips, key = { it.key }) { chip ->
            val label = chip.label()
            val description = stringResource(R.string.home_chip_description, label)
            FlowFilterChip(
                label = label,
                selected = chip.key == state.selected,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(chip.key)
                },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

@Composable
private fun HomeChip.label(): String =
    when (this) {
        HomeChip.All -> stringResource(R.string.home_chip_all)
        is HomeChip.Interest -> interest.label
        is HomeChip.YouTube -> title
        HomeChip.NewToYou -> stringResource(R.string.home_chip_new_to_you)
        HomeChip.RecentlyUploaded -> stringResource(R.string.home_chip_recently_uploaded)
        HomeChip.Mixes -> stringResource(R.string.home_chip_mixes)
        HomeChip.Watched -> stringResource(R.string.home_chip_watched)
    }
