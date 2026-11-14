package app.zaid.flow.ui.screens.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.account.AccountProfile
import app.zaid.flow.ui.screens.settings.account.AccountAvatar

/**
 * The account in use at the top of Library, laid out like YouTube's own "You" page: a large avatar
 * beside the name and handle, with two full-width actions underneath — switch account and
 * incognito. Switch account opens a panel listing every signed-in account (tap one to switch) and
 * a way into the full account settings; whatever sits below moves down while it is open. With
 * nobody signed in it is just an invitation to sign in, handled by [onToggle].
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
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().animateContentSize().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Identity: tapping it goes to the full account settings, or to sign-in when signed out.
        Surface(
            onClick = if (account == null) onToggle else onMoreSettings,
            shape = RoundedCornerShape(24.dp),
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    if (account != null) {
                        AccountAvatar(account, size = 88.dp)
                    } else {
                        Icon(
                            Icons.Outlined.AccountCircle,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(88.dp),
                        )
                    }
                    if (incognito && account != null) {
                        Surface(
                            shape = CircleShape,
                            color = scheme.inverseSurface,
                            contentColor = scheme.inverseOnSurface,
                            border = BorderStroke(2.dp, scheme.background),
                            modifier = Modifier.size(30.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.VisibilityOff, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = account?.displayName ?: stringResource(R.string.account_card_signed_out_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val secondary =
                        if (account == null) {
                            stringResource(R.string.account_card_signed_out_hint)
                        } else {
                            account.handle?.takeIf { it.isNotBlank() } ?: account.email
                        }
                    if (!secondary.isNullOrBlank()) {
                        Text(
                            text = secondary,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                            maxLines = if (account == null) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (account != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "accountChevron")
                AccountAction(
                    text = stringResource(R.string.library_account_switch),
                    icon = Icons.Outlined.SwitchAccount,
                    filled = true,
                    modifier = Modifier.weight(1f),
                    onClick = onToggle,
                    trailing = {
                        Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(18.dp).rotate(chevron))
                    },
                )
                AccountAction(
                    text = stringResource(R.string.account_incognito_title),
                    icon = Icons.Outlined.VisibilityOff,
                    filled = incognito,
                    modifier = Modifier.weight(1f),
                    onClick = { onIncognitoChange(!incognito) },
                )
            }
        }

        AnimatedVisibility(visible = expanded && account != null) {
            Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerLow) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    accounts.forEach { item ->
                        val isActive = item.id == account?.id
                        Surface(
                            onClick = { onSwitchAccount(item.id) },
                            enabled = !isActive,
                            shape = RoundedCornerShape(22.dp),
                            color = if (isActive) scheme.secondaryContainer else Color.Transparent,
                            contentColor = if (isActive) scheme.onSecondaryContainer else scheme.onSurface,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AccountAvatar(item, size = 44.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val line = item.handle?.takeIf { it.isNotBlank() } ?: item.email
                                    if (!line.isNullOrBlank()) {
                                        Text(
                                            line,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = LocalContentColor.current.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                                if (isActive) Icon(Icons.Outlined.CheckCircle, null)
                            }
                        }
                    }
                    PanelRow(stringResource(R.string.account_add_another), Icons.Outlined.Add, onAddAccount)
                    PanelRow(stringResource(R.string.library_account_more_settings), Icons.Outlined.Settings, onMoreSettings)
                }
            }
        }
    }
}

/** A full-width pill action: solid when [filled], outlined otherwise, like the buttons on YouTube's own page. */
@Composable
private fun AccountAction(
    text: String,
    icon: ImageVector,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (filled) scheme.onSurface else Color.Transparent, label = "accountActionContainer")
    val content by animateColorAsState(if (filled) scheme.surface else scheme.onSurface, label = "accountActionContent")
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = if (filled) null else BorderStroke(1.dp, scheme.outline),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            trailing?.invoke()
        }
    }
}

@Composable
private fun PanelRow(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, modifier = Modifier.size(24.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
    }
}
