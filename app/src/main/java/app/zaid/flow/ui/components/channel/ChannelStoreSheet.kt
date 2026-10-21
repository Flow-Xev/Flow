package app.zaid.flow.ui.components.channel

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.innertube.pages.channel.ChannelProduct
import app.zaid.flow.innertube.pages.channel.linkLabel
import app.zaid.flow.ui.components.shared.FlowSheetHeader
import app.zaid.flow.ui.components.shared.rememberFlowSheetState
import app.zaid.flow.ui.theme.extendedColors
import coil3.compose.AsyncImage

/** Lists a channel's tagged items; tapping one opens its link (web, mail, phone or app link). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelStoreSheet(
    products: List<ChannelProduct>,
    isLoading: Boolean,
    hasError: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    val noBrowser = stringResource(R.string.channel_store_no_browser)

    fun open(url: String) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Any failure to launch (no handler app, a restricted profile, a bad URI) is a toast, never a crash.
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFlowSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .navigationBarsPadding(),
        ) {
            FlowSheetHeader(
                title = stringResource(R.string.channel_store_title),
                onClose = onDismiss,
                leadingIcon = Icons.Rounded.ShoppingBag,
            )
            when {
                isLoading -> {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                hasError -> {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.channel_store_error), color = MaterialTheme.extendedColors.textSecondary)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.channel_store_retry)) }
                    }
                }

                products.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.channel_store_empty), color = MaterialTheme.extendedColors.textSecondary)
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding =
                            androidx.compose.foundation.layout
                                .PaddingValues(vertical = 8.dp),
                    ) {
                        items(products, key = { it.url }, contentType = { "product" }) { product ->
                            ProductRow(product = product, onClick = { open(product.url) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductRow(
    product: ChannelProduct,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (product.imageUrl.isNullOrBlank()) {
                Icon(Icons.Rounded.ShoppingBag, contentDescription = null, tint = MaterialTheme.extendedColors.textSecondary)
            } else {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = product.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(76.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = product.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            product.price?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            (product.merchant ?: linkLabel(product.url)).let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.extendedColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = stringResource(R.string.channel_store_open_link),
            tint = MaterialTheme.extendedColors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}
