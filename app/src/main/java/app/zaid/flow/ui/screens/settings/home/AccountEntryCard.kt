package app.zaid.flow.ui.screens.settings.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.account.AccountProfile
import app.zaid.flow.ui.screens.settings.account.AccountAvatar

private val CardPadding = 16.dp
private val AvatarSize = 56.dp

/**
 * The way into Account: who is signed in (photo, name, email) and how many other accounts are on
 * the device, or an invitation to sign in. It is its own card, apart from the persona card.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccountEntryCard(
    account: AccountProfile?,
    accountCount: Int,
    selected: Boolean,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        selected = selected,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(CardPadding),
            horizontalArrangement = Arrangement.spacedBy(CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (account != null) {
                AccountAvatar(account, size = AvatarSize)
            } else {
                Icon(
                    Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(AvatarSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = account?.displayName ?: stringResource(R.string.account_card_signed_out_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account?.email ?: account?.handle ?: stringResource(R.string.account_card_signed_out_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (account == null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (accountCount > 1) {
                    Text(
                        text = stringResource(R.string.account_card_more, accountCount - 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
}
