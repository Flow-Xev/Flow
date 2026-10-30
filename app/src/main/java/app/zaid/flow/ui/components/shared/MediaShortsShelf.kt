package app.zaid.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.model.distinctByNonBlankKey
import app.zaid.flow.data.shorts.ShortDetailsResolver
import app.zaid.flow.ui.theme.amoledCardOutline
import app.zaid.flow.ui.theme.extendedColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce

private const val SHOWN_DEBOUNCE_MS = 500L
private const val SHELF_DETAIL_ATTEMPTS = 4
private const val SHELF_DETAIL_RETRY_MS = 3_000L
private const val CARD_LOOKUP_SETTLE_MS = 500L
private const val COLUMN_KEY_SEPARATOR = "|"

private val HeaderHorizontalPadding = 12.dp
private val HeaderVerticalPadding = 8.dp
private val HeaderIconSize = 24.dp
private val HeaderIconSpacing = 4.dp
private val StripContentPadding = PaddingValues(horizontal = 12.dp)
private val ShelfShape = RoundedCornerShape(28.dp)

/**
 * A horizontal strip of reels under the Shorts heading. [title] is the server's shelf title where
 * one exists; the branded default otherwise.
 */
@OptIn(FlowPreview::class)
@Composable
fun MediaShortsShelf(
    shorts: List<Video>,
    onShortClick: (shelf: List<Video>, tapped: Video) -> Unit,
    modifier: Modifier = Modifier,
    gridRows: Int = 1,
    title: String? = null,
    onSeeAllClick: (() -> Unit)? = null,
    onShortsShown: ((ids: List<String>) -> Unit)? = null,
) {
    val uniqueShorts = remember(shorts) { shorts.distinctByNonBlankKey(Video::id) }
    if (uniqueShorts.isEmpty()) return
    val rowState = rememberLazyListState()
    if (onShortsShown != null) {
        val currentOnShortsShown by rememberUpdatedState(onShortsShown)
        LaunchedEffect(rowState) {
            snapshotFlow {
                rowState.layoutInfo.visibleItemsInfo.flatMap { (it.key as? String)?.split(COLUMN_KEY_SEPARATOR).orEmpty() }
            }
                .debounce(SHOWN_DEBOUNCE_MS)
                .collect { currentOnShortsShown(it) }
        }
    }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .clip(ShelfShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                // On AMOLED black the shelf's fill does not separate it from the page; the same hairline the
                // video cards get outlines it. After clip and background, before the inner padding.
                .amoledCardOutline(ShelfShape)
                .padding(bottom = 12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (onSeeAllClick != null) Modifier.clickable(onClick = onSeeAllClick) else Modifier)
                    .padding(horizontal = HeaderHorizontalPadding, vertical = HeaderVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.extendedColors.shortsAccent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_shorts),
                    contentDescription = stringResource(R.string.shorts),
                    tint = MaterialTheme.extendedColors.shortsAccent,
                    modifier = Modifier.size(HeaderIconSize),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title ?: stringResource(R.string.shorts),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (onSeeAllClick != null) {
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(HeaderIconSize),
                )
            }
        }

        // With two rows, each lazy item is one column holding both cards, so the rows scroll together.
        val columns = remember(uniqueShorts, gridRows) { uniqueShorts.chunked(gridRows.coerceAtLeast(1)) }
        LazyRow(
            state = rowState,
            contentPadding = StripContentPadding,
            horizontalArrangement = Arrangement.spacedBy(ShortCardDefaults.StripSpacing),
        ) {
            items(columns, key = { column -> column.joinToString(COLUMN_KEY_SEPARATOR) { it.id } }) { column ->
                Column(verticalArrangement = Arrangement.spacedBy(ShortCardDefaults.StripSpacing)) {
                    column.forEach { short ->
                        ShelfShortCard(short = short, shelf = uniqueShorts, onShortClick = onShortClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfShortCard(
    short: Video,
    shelf: List<Video>,
    onShortClick: (shelf: List<Video>, tapped: Video) -> Unit,
) {
    // Reels built from the sequence arrive without a title or view count; fill them in
    // as each card scrolls into view, so only the visible ones cost a request.
    val resolved by produceState(initialValue = short, short.id) {
        // Cards scrolled past quickly leave composition before this wakes, so they cost no request.
        if (ShortDetailsResolver.needsDetails(short)) delay(CARD_LOOKUP_SETTLE_MS)
        // A lookup can fail on a bad network or a blocked client; try a few times
        // rather than leaving the card without a title for good.
        var current = short
        var attempt = 0
        while (true) {
            current = ShortDetailsResolver.resolve(current)
            value = current
            if (!ShortDetailsResolver.needsDetails(current) || ++attempt >= SHELF_DETAIL_ATTEMPTS) break
            delay(SHELF_DETAIL_RETRY_MS * attempt)
        }
    }
    MediaShortCard(
        video = resolved,
        onClick = { onShortClick(shelf, resolved) },
        titleMaxLines = 2,
    )
}
