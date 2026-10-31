package app.zaid.flow.ui.components.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** The GIF attached to a comment. The app's image loader decodes it animated. */
@Composable
internal fun CommentGif(
    url: String,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(MaterialTheme.shapes.medium),
    )
}
