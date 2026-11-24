package app.zaid.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.utils.NetworkState
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.combine

private val MaxImageHeight = 200.dp
private const val FALLBACK_ASPECT = 4f / 3f

/**
 * The GIF (or picture) under a comment's text, at the shape YouTube gives for it. It plays a couple
 * of times and stops; a tap opens it full size. With Data Saver on, or GIFs kept to Wi-Fi while off
 * it, nothing downloads until the person asks. [width] and [height] are 0 when YouTube gave no size.
 */
@Composable
fun FlowCommentImage(
    url: String,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val settings by remember(preferences) {
        combine(preferences.commentGifsEnabled, preferences.commentGifsWifiOnly, ::Pair)
    }.collectAsStateWithLifecycle(initialValue = null)
    val (enabled, wifiOnly) = settings ?: return
    if (!enabled) return

    val loadsNow = remember(wifiOnly) { !NetworkState.isDataSaverOn(context) && (!wifiOnly || NetworkState.isOnWifi(context)) }
    var requested by rememberSaveable(url) { mutableStateOf(false) }
    var showFullSize by remember { mutableStateOf(false) }
    val aspect = if (width > 0 && height > 0) width.toFloat() / height else FALLBACK_ASPECT

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .padding(top = 8.dp)
                .heightIn(max = MaxImageHeight)
                .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (loadsNow || requested) {
            AsyncImage(
                model = rememberLoopCappedImageRequest(url),
                contentDescription = stringResource(R.string.comment_gif_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clickable { showFullSize = true },
            )
        } else {
            FilledTonalButton(onClick = { requested = true }) { Text(stringResource(R.string.comment_gif_show)) }
        }
    }

    if (showFullSize) FullSizeImageDialog(imageUrl = url, onDismiss = { showFullSize = false })
}
