package app.zaid.flow.ui.screens.music.collection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.zaid.flow.R
import app.zaid.flow.data.music.model.MusicTrack
import app.zaid.flow.ui.components.music.header.MusicSectionAction
import app.zaid.flow.ui.components.music.header.MusicSectionHeader
import app.zaid.flow.ui.components.music.item.MusicItemDensity
import app.zaid.flow.ui.components.music.item.MusicTrackItem
import app.zaid.flow.ui.components.shared.flowSegmentShape
import app.zaid.flow.ui.theme.Dimensions

/** The suggestions under a playlist; [onAdd] is set only where songs can be added. */
internal class CollectionSuggestions(
    val state: SuggestionsState,
    val onRefresh: () -> Unit,
    val onPlay: (MusicTrack) -> Unit,
    val onMenu: (MusicTrack) -> Unit,
    val onAdd: ((MusicTrack) -> Unit)?,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun LazyListScope.collectionSuggestions(suggestions: CollectionSuggestions) {
    val state = suggestions.state
    if (state.tracks.isEmpty() && !state.isLoading) return
    item(key = "suggestions-header", contentType = "suggestions-header") {
        MusicSectionHeader(
            title = stringResource(R.string.music_collection_suggestions),
            action = MusicSectionAction.Refresh(suggestions.onRefresh).takeUnless { state.isLoading },
        )
    }
    if (state.isLoading) {
        item(key = "suggestions-loading", contentType = "loading") {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        }
        return
    }
    itemsIndexed(
        items = state.tracks,
        key = { _, track -> "suggestion:${track.videoId}" },
        contentType = { _, _ -> "suggestion" },
    ) { index, track ->
        MusicTrackItem(
            track = track,
            onClick = { suggestions.onPlay(track) },
            onLongClick = { suggestions.onMenu(track) },
            modifier = Modifier.padding(horizontal = Dimensions.ContentPaddingHorizontal),
            density = MusicItemDensity.Compact,
            shape = flowSegmentShape(index = index, count = state.tracks.size),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            showMenu = false,
            trailingContent =
                suggestions.onAdd?.let { add ->
                    {
                        IconButton(onClick = { add(track) }) {
                            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add_to_playlist))
                        }
                    }
                },
        )
    }
}
