package app.zaid.flow.ui.screens.settings.account

import android.content.ClipData
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.account.AccountManager
import app.zaid.flow.account.AccountProfile
import app.zaid.flow.ui.components.settings.SettingsPage
import app.zaid.flow.ui.components.shared.FlowAlertDialog
import app.zaid.flow.ui.screens.account.AccountSignInScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel
    @Inject
    constructor(
        private val accountManager: AccountManager,
    ) : ViewModel() {
        val accounts = accountManager.accounts
        val activeAccount = accountManager.activeAccount
        val syncing = accountManager.syncing
        val lastSync = accountManager.lastSync
        val incognito = accountManager.incognito

        fun setIncognito(enabled: Boolean) = accountManager.setIncognito(enabled)

        fun refreshProfile() = accountManager.refreshProfile()

        fun switchTo(id: String) = accountManager.switchTo(id)

        fun remove(id: String) = accountManager.removeAccount(id)

        fun signOutAll() = accountManager.signOutAll()

        fun syncNow() = accountManager.syncNow()
    }

/**
 * The account page: the active profile up top, every account on the device below it with one-tap
 * switching, and adding another account. The Google sign-in flow replaces the page while it runs.
 */
@Composable
internal fun AccountScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val active by viewModel.activeAccount.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()
    val incognito by viewModel.incognito.collectAsStateWithLifecycle()

    var signingIn by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<AccountProfile?>(null) }
    var confirmSignOutAll by rememberSaveable { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val copiedMessage = stringResource(R.string.account_email_copied)
    val switchedFormat = stringResource(R.string.account_switched_to)

    LaunchedEffect(active?.id) { if (active != null) viewModel.refreshProfile() }

    if (signingIn) {
        AccountSignInScreen(
            onSignedIn = { signingIn = false },
            onCancel = { signingIn = false },
        )
        return
    }

    SettingsPage(
        title = stringResource(R.string.settings_account_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbar,
    ) {
        if (active == null) {
            item("account.signed_out") {
                SignedOutCard(onSignIn = { signingIn = true })
            }
            return@SettingsPage
        }
        val current = active ?: return@SettingsPage

        item("account.status") {
            ProfileCard(
                account = current,
                onCopyEmail = { email ->
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("email", email)))
                        snackbar.showSnackbar(copiedMessage)
                    }
                },
            )
        }

        item("account.sync") {
            SyncCard(
                syncing = syncing,
                lastSync = lastSync,
                onSyncNow = viewModel::syncNow,
            )
        }

        item("account.incognito") {
            IncognitoCard(enabled = incognito, onToggle = viewModel::setIncognito)
        }

        header("account.accounts.header", R.string.account_your_accounts)
        item("account.accounts") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                accounts.forEach { account ->
                    AccountRow(
                        account = account,
                        isActive = account.id == current.id,
                        onClick = {
                            if (account.id != current.id) {
                                viewModel.switchTo(account.id)
                                scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    snackbar.showSnackbar(switchedFormat.format(account.displayName))
                                }
                            }
                        },
                        onRemove = { confirmRemove = account },
                    )
                }
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = { signingIn = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.account_add_another))
                }
            }
        }

        header("account.manage.header", R.string.account_manage)
        item("account.manage") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { confirmRemove = current },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.account_sign_out_this))
                }
                if (accounts.size > 1) {
                    TextButton(
                        onClick = { confirmSignOutAll = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.account_sign_out_all),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    confirmRemove?.let { target ->
        FlowAlertDialog(
            onDismissRequest = { confirmRemove = null },
            icon = { Icon(Icons.Outlined.PersonRemove, contentDescription = null) },
            title = { Text(stringResource(R.string.account_remove_title, target.displayName)) },
            text = { Text(stringResource(R.string.account_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(target.id)
                    confirmRemove = null
                }) { Text(stringResource(R.string.account_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    if (confirmSignOutAll) {
        FlowAlertDialog(
            onDismissRequest = { confirmSignOutAll = false },
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
            title = { Text(stringResource(R.string.account_sign_out_all_title)) },
            text = { Text(stringResource(R.string.account_sign_out_all_body, accounts.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.signOutAll()
                    confirmSignOutAll = false
                }) { Text(stringResource(R.string.account_sign_out_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOutAll = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    account: AccountProfile,
    onCopyEmail: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AccountAvatar(account, size = 88.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                text = account.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.account_active),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(16.dp))

            account.email?.let { email ->
                DetailRow(
                    icon = Icons.Outlined.Email,
                    label = stringResource(R.string.account_email),
                    value = email,
                    trailing = {
                        IconButton(onClick = { onCopyEmail(email) }) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.account_copy_email),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
            }
            account.handle?.let { handle ->
                DetailRow(
                    icon = Icons.Outlined.AlternateEmail,
                    label = stringResource(R.string.account_channel),
                    value = handle,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun IncognitoCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.account_incognito_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.account_incognito_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SyncCard(
    syncing: Boolean,
    lastSync: Long,
    onSyncNow: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.account_sync_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text =
                            when {
                                syncing -> {
                                    stringResource(R.string.account_syncing)
                                }

                                lastSync > 0L -> {
                                    stringResource(
                                        R.string.account_last_synced,
                                        DateUtils
                                            .getRelativeTimeSpanString(
                                                lastSync,
                                                System.currentTimeMillis(),
                                                DateUtils.MINUTE_IN_MILLIS,
                                            ).toString(),
                                    )
                                }

                                else -> {
                                    stringResource(R.string.account_never_synced)
                                }
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onSyncNow, enabled = !syncing) {
                    Text(stringResource(R.string.account_sync_now))
                }
            }
            if (syncing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SyncChip(Icons.Outlined.Subscriptions, stringResource(R.string.account_sync_subscriptions))
                SyncChip(Icons.Outlined.History, stringResource(R.string.account_sync_history))
                SyncChip(Icons.Outlined.ThumbUp, stringResource(R.string.account_sync_likes))
            }
        }
    }
}

@Composable
private fun SyncChip(
    icon: ImageVector,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccountRow(
    account: AccountProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(account, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = account.email ?: account.handle
                if (sub != null) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isActive) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.account_active),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.PersonRemove,
                        contentDescription = stringResource(R.string.account_sign_out),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SignedOutCard(onSignIn: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.account_signed_out_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_account_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_sign_in_google))
            }
        }
    }
}
