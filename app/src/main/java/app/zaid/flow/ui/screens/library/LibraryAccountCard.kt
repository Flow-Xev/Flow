package app.zaid.flow.ui.screens.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.account.AccountProfile
import app.zaid.flow.ui.screens.settings.account.AccountAvatar

/**
 * The account in use at the top of Library. Tapping it opens a panel listing every signed-in
 * account (tap one to switch), the incognito switch, and a way into the full account settings;
 * whatever sits below it moves down while it is open. With nobody signed in it is just an
 * invitation to sign in, handled by [onToggle].
 */
@Composable
fun LibraryAccountCard(
    account: AccountProfile?,
    accounts: List<AccountProfile>,
    expanded: Boolean,
    incognito: Boolean,
    onToggle: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onIncognitoChange: (Boolean) -> Unit,
    onAddAccount: () -> Unit,
    onMoreSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (account != null) {
                    AccountAvatar(account, size = 72.dp)
                } else {
                    Icon(
                        Icons.Outlined.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = account?.displayName ?: stringResource(R.string.account_card_signed_out_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The channel (handle) when the account has one; its email otherwise.
                    val secondary =
                        if (account == null) {
                            stringResource(R.string.account_card_signed_out_hint)
                        } else {
                            account.handle?.takeIf { it.isNotBlank() } ?: account.email
                        }
                    if (!secondary.isNullOrBlank()) {
                        Text(
                            text = secondary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (account == null) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                } else {
                    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "accountChevron")
                    Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation))
                }
            }
            if (account != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = onToggle,
                        label = { Text(stringResource(R.string.library_account_switch)) },
                        leadingIcon = { Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        shape = CircleShape,
                    )
                    FilterChip(
                        selected = incognito,
                        onClick = { onIncognitoChange(!incognito) },
                        label = { Text(stringResource(R.string.account_incognito_title)) },
                        leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        shape = CircleShape,
                    )
                }
            }
            AnimatedVisibility(visible = expanded && account != null) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        accounts.forEach { item ->
                            val isActive = item.id == account?.id
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isActive) { onSwitchAccount(item.id) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AccountAvatar(item, size = 40.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val line = item.handle?.takeIf { it.isNotBlank() } ?: item.email
                                    if (!line.isNullOrBlank()) {
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (isActive) {
                                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onAddAccount)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(40.dp).padding(8.dp))
                            Text(
                                text = stringResource(R.string.account_add_another),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onIncognitoChange(!incognito) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(40.dp).padding(8.dp))
                            Text(
                                text = stringResource(R.string.account_incognito_title),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = incognito, onCheckedChange = onIncognitoChange)
                        }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onMoreSettings)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(40.dp).padding(8.dp))
                            Text(
                                text = stringResource(R.string.library_account_more_settings),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}
