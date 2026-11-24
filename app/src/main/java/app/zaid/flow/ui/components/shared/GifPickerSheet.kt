package app.zaid.flow.ui.components.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.zaid.flow.data.gif.GifResult
import app.zaid.flow.data.gif.GifSearch
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.zaid.flow.data.gif.GifSettings

private val Categories = listOf("Reactions", "Happy", "Love", "LOL", "Wow", "Sad", "Angry", "Thanks", "Facepalm", "Dance", "Applause")

/** Bottom sheet that searches GIFs; [onPick] gets the chosen one and the sheet closes itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifPickerSheet(
    onPick: (GifResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var query by remember { mutableStateOf("") }
    var activeQuery by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<GifResult>() }
    var loading by remember { mutableStateOf(true) }
    var endReached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var requestedOffset by remember { mutableStateOf(0) }
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val recents by remember(context) { GifSettings.recents(context) }.collectAsState(initial = emptyList())
    var showRecents by remember { mutableStateOf(false) }
    val shown = if (showRecents) recents else results

    // Typing waits a moment before searching, so each keystroke does not hit the network.
    LaunchedEffect(query) {
        if (query.trim() != activeQuery) delay(400)
        activeQuery = query.trim()
    }
    LaunchedEffect(activeQuery, requestedOffset) {
        loading = true
        GifSearch
            .load(context, activeQuery, requestedOffset)
            .onSuccess { page ->
                if (requestedOffset == 0) results.clear()
                results.addAll(page.filter { new -> results.none { it.id == new.id } })
                endReached = page.isEmpty()
                error = null
            }.onFailure { error = it.message ?: "Couldn't load GIFs" }
        loading = false
    }
    LaunchedEffect(activeQuery) { requestedOffset = 0 }

    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            !showRecents && results.isNotEmpty() && last >= results.size - 6
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd && !loading && !endReached && error == null) requestedOffset = results.size
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search GIFs") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { activeQuery = query.trim() }),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            if (recents.isNotEmpty()) {
                item {
                    FilterChip(selected = showRecents, onClick = { showRecents = !showRecents }, label = { Text("Recent") })
                }
            }
            item {
                FilterChip(
                    selected = !showRecents && query.isBlank(),
                    onClick = {
                        showRecents = false
                        query = ""
                    },
                    label = { Text("Trending") },
                )
            }
            items(Categories.size) { index ->
                val category = Categories[index]
                FilterChip(
                    selected = !showRecents && query.equals(category, ignoreCase = true),
                    onClick = {
                        showRecents = false
                        query = category
                    },
                    label = { Text(category) },
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(420.dp).padding(top = 8.dp)) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalItemSpacing = 8.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { gif ->
                    AsyncImage(
                        model = gif.previewUrl,
                        contentDescription = "GIF",
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(gif.width.coerceAtLeast(1).toFloat() / gif.height.coerceAtLeast(1))
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    scope.launch { GifSettings.addRecent(context, gif) }
                                    onPick(gif)
                                    onDismiss()
                                },
                    )
                }
            }
            when {
                showRecents -> {
                    Unit
                }

                error != null && results.isEmpty() -> {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                }

                loading && results.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                !loading && results.isEmpty() -> {
                    Text("No GIFs found", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        // GIPHY's terms ask for visible attribution.
        Text(
            "Powered by GIPHY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
