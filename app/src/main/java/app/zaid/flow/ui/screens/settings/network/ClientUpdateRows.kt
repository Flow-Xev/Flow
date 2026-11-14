package app.zaid.flow.ui.screens.settings.network

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.innertube.update.ClientUpdateMode
import app.zaid.flow.innertube.update.ClientUpdateSettings
import app.zaid.flow.innertube.update.ClientUpdateWorker
import app.zaid.flow.innertube.update.ClientUpdater
import app.zaid.flow.ui.components.settings.SettingsGroupScope
import app.zaid.flow.ui.components.shared.FlowChoice
import app.zaid.flow.ui.components.shared.FlowChoiceDialog
import app.zaid.flow.ui.components.shared.FlowNavRow
import kotlinx.coroutines.launch

/** Rows for the "YouTube client updates" group: mode, new-feature policy, manual check, reset. */
internal fun SettingsGroupScope.clientUpdateRows() {
    row("network.client_updates.mode") { shape -> ModeRow(shape) }
    row("network.client_updates.features") { shape -> FeaturesRow(shape) }
    row("network.client_updates.check") { shape -> CheckRow(shape) }
    row("network.client_updates.log") { shape -> LogRow(shape) }
    row("network.client_updates.reset") { shape -> ResetRow(shape) }
}

private fun modeLabel(mode: ClientUpdateMode): Int =
    when (mode) {
        ClientUpdateMode.AUTO -> R.string.client_updates_mode_auto
        ClientUpdateMode.MANUAL -> R.string.client_updates_mode_manual
        ClientUpdateMode.OFF -> R.string.client_updates_mode_off
    }

@Composable
private fun ModeRow(shape: Shape) {
    val context = LocalContext.current
    remember { ClientUpdateSettings.ensureLoaded() }
    val cfg by ClientUpdateSettings.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf(false) }
    FlowNavRow(
        title = stringResource(R.string.client_updates_mode_title),
        supportingText = stringResource(modeLabel(cfg.mode)),
        leadingIcon = Icons.Outlined.SystemUpdateAlt,
        showChevron = false,
        shape = shape,
        onClick = { dialog = true },
    )
    if (dialog) {
        FlowChoiceDialog(
            title = stringResource(R.string.client_updates_mode_title),
            description = stringResource(R.string.client_updates_mode_body),
            options = ClientUpdateMode.entries.map { FlowChoice(it, stringResource(modeLabel(it))) },
            selected = cfg.mode,
            onSelect = { mode ->
                ClientUpdateSettings.update { it.copy(mode = mode) }
                ClientUpdateWorker.sync(context)
                dialog = false
            },
            onDismiss = { dialog = false },
        )
    }
}

@Composable
private fun FeaturesRow(shape: Shape) {
    val cfg by ClientUpdateSettings.state.collectAsStateWithLifecycle()
    val waiting = cfg.pending.values.sumOf { it.size }
    FlowNavRow(
        title = stringResource(R.string.client_updates_features_title),
        supportingText =
            when {
                cfg.autoFeatures -> stringResource(R.string.client_updates_features_on)
                waiting > 0 -> stringResource(R.string.client_updates_features_waiting, waiting)
                else -> stringResource(R.string.client_updates_features_off)
            },
        leadingIcon = Icons.Outlined.AutoAwesome,
        showChevron = false,
        shape = shape,
        onClick = {
            val turnOn = !cfg.autoFeatures
            ClientUpdateSettings.update { it.copy(autoFeatures = turnOn) }
            if (turnOn) ClientUpdateSettings.approvePending()
        },
    )
}

@Composable
private fun CheckRow(shape: Shape) {
    val cfg by ClientUpdateSettings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val offText = stringResource(R.string.client_updates_result_off)
    val currentText = stringResource(R.string.client_updates_result_current)
    val failedText = stringResource(R.string.client_updates_result_failed)
    val rejectedText = stringResource(R.string.client_updates_result_rejected)
    val appliedFmt = stringResource(R.string.client_updates_result_applied)
    val idle =
        if (cfg.lastCheck == 0L) {
            stringResource(R.string.client_updates_never)
        } else {
            stringResource(R.string.client_updates_last, DateUtils.getRelativeTimeSpanString(cfg.lastCheck).toString())
        }
    FlowNavRow(
        title = stringResource(R.string.client_updates_check_title),
        supportingText = if (busy) stringResource(R.string.client_updates_checking) else result ?: idle,
        leadingIcon = Icons.Outlined.Refresh,
        showChevron = false,
        shape = shape,
        onClick = {
            if (busy) return@FlowNavRow
            scope.launch {
                busy = true
                val report = ClientUpdater.check(apply = true)
                result =
                    when (report.status) {
                        ClientUpdater.Status.OFF -> offText
                        ClientUpdater.Status.FAILED -> failedText
                        ClientUpdater.Status.UP_TO_DATE -> currentText
                        ClientUpdater.Status.REJECTED -> rejectedText
                        else -> appliedFmt.format(ClientUpdateSettings.state.value.lastSummary)
                    }
                busy = false
            }
        },
    )
}

@Composable
private fun ResetRow(shape: Shape) {
    remember { ClientUpdateSettings.ensureLoaded() }
    val cfg by ClientUpdateSettings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by rememberSaveable { mutableStateOf(false) }
    FlowNavRow(
        title = stringResource(R.string.client_updates_reset_title),
        supportingText =
            stringResource(
                if (cfg.versions.isEmpty()) R.string.client_updates_reset_already else R.string.client_updates_reset_body,
            ),
        leadingIcon = Icons.Outlined.History,
        showChevron = false,
        shape = shape,
        onClick = { confirm = true },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.client_updates_reset_confirm_title)) },
            text = { Text(stringResource(R.string.client_updates_reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        scope.launch { ClientUpdater.resetToFactory() }
                    },
                ) { Text(stringResource(R.string.client_updates_reset_action)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun LogRow(shape: Shape) {
    val context = LocalContext.current
    val cfg by ClientUpdateSettings.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val probation = cfg.probation
    FlowNavRow(
        title = stringResource(R.string.client_updates_log_title),
        supportingText =
            if (probation != null) {
                stringResource(R.string.client_updates_log_probation, probation.clients.sorted().joinToString())
            } else {
                stringResource(R.string.client_updates_log_body)
            },
        leadingIcon = Icons.AutoMirrored.Outlined.ListAlt,
        showChevron = false,
        shape = shape,
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.client_updates_log_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (cfg.history.isEmpty()) Text(stringResource(R.string.client_updates_log_empty))
                    cfg.history.asReversed().forEach { line ->
                        val stamp = line.substringBefore('|').toLongOrNull()
                        val text = line.substringAfter('|', line)
                        val whenText =
                            stamp?.let {
                                DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)
                            }
                        Text(listOfNotNull(whenText, text).joinToString("  "), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.client_updates_close)) } },
        )
    }
}
