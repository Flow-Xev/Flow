package app.zaid.flow.ui.screens.settings.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.zaid.flow.account.AccountProfile
import coil3.compose.SubcomposeAsyncImage

/** A circular account photo that falls back to the account's initial on a tinted disc. */
@Composable
internal fun AccountAvatar(
    account: AccountProfile?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val fallback: @Composable () -> Unit = {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = account?.initial ?: "?",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
    Box(modifier.size(size).clip(CircleShape)) {
        val url = account?.avatarUrl?.takeIf { it.isNotBlank() }
        if (url == null) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = account?.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                loading = { fallback() },
                error = { fallback() },
            )
        }
    }
}
