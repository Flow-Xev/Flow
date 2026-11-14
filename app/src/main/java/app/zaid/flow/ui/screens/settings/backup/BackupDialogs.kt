package app.zaid.flow.ui.screens.settings.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.data.backup.BackupInspection
import app.zaid.flow.data.backup.BackupSection
import app.zaid.flow.ui.components.shared.FlowAlertDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Asks how a master backup should be made. Accounts are only offered when someone is signed in.
 * [onConfirm] gets whether the accounts go in.
 */
@Composable
internal fun MasterExportDialog(
    accountCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (includeAccounts: Boolean) -> Unit,
) {
    var includeAccounts by remember { mutableStateOf(false) }

    FlowAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) },
        title = { Text(stringResource(R.string.backup_export_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_export_dialog_body), style = MaterialTheme.typography.bodyMedium)
                if (accountCount > 0) {
                    Row(
                        Modifier.fillMaxWidth().clickable { includeAccounts = !includeAccounts },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.backup_export_accounts_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.backup_export_accounts_desc, accountCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = includeAccounts, onCheckedChange = { includeAccounts = it })
                    }
                    if (includeAccounts) {
                        Text(
                            stringResource(R.string.backup_export_accounts_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(includeAccounts) }) {
                Text(stringResource(R.string.backup_export_choose_location))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/**
 * What a master backup holds and whether it is intact, with a checkbox per part, shown before
 * anything is restored. Damaged parts can't be ticked; an older backup's locked accounts ask for their password.
 */
@Composable
internal fun RestorePreviewDialog(
    inspection: BackupInspection,
    onDismiss: () -> Unit,
    onRestore: (sections: Set<BackupSection>, accountsPassword: String?) -> Unit,
) {
    val available = BackupSection.entries.filter { it in inspection.sections }
    var selected by remember {
        mutableStateOf(available.filter { it !in inspection.damagedSections }.toSet())
    }
    var password by remember { mutableStateOf("") }
    val needsPassword = inspection.accountsEncrypted && BackupSection.ACCOUNTS in selected
    val ready = selected.isNotEmpty() && (!needsPassword || password.isNotEmpty())

    FlowAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
        title = { Text(stringResource(R.string.backup_restore_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text =
                        if (inspection.createdAt > 0L) {
                            stringResource(R.string.backup_restore_made, formatBackupTime(inspection.createdAt))
                        } else {
                            stringResource(R.string.backup_restore_made_unknown)
                        },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (inspection.appVersion.isNotBlank()) {
                    Text(
                        stringResource(R.string.backup_restore_source, inspection.appVersion, inspection.device),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text =
                        when {
                            !inspection.verified -> stringResource(R.string.backup_restore_unverified)
                            inspection.isIntact -> stringResource(R.string.backup_restore_verified)
                            else -> stringResource(R.string.backup_restore_damaged)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (inspection.isIntact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                available.forEach { section ->
                    val damaged = section in inspection.damagedSections
                    val checked = section in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !damaged) { selected = if (checked) selected - section else selected + section },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !damaged)
                        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
                            Text(stringResource(section.titleRes), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text =
                                    when {
                                        damaged -> stringResource(R.string.backup_restore_section_damaged)
                                        else -> sectionDetail(section, inspection)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (damaged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (needsPassword) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.backup_restore_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.backup_restore_accounts_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    stringResource(R.string.backup_restore_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestore(selected, if (needsPassword) password else null) }, enabled = ready) {
                Text(stringResource(R.string.backup_restore_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun sectionDetail(
    section: BackupSection,
    inspection: BackupInspection,
): String =
    when (section) {
        BackupSection.LIBRARY -> {
            val c = inspection.counts
            stringResource(R.string.backup_restore_library_counts, c.history, c.subscriptions, c.playlists, c.likes, c.notes)
        }

        BackupSection.ACCOUNTS -> {
            if (inspection.accountCount > 0) {
                stringResource(R.string.backup_restore_accounts_count, inspection.accountCount)
            } else {
                stringResource(section.descriptionRes)
            }
        }

        else -> {
            stringResource(section.descriptionRes)
        }
    }

private fun formatBackupTime(epochMillis: Long): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
