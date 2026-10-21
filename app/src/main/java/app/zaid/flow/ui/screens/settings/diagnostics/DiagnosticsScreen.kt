package app.zaid.flow.ui.screens.settings.diagnostics

import android.content.Intent
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.ui.components.settings.SettingsListScope
import app.zaid.flow.ui.components.settings.SettingsPage
import app.zaid.flow.ui.components.settings.info
import app.zaid.flow.ui.components.settings.nav
import app.zaid.flow.ui.components.settings.switch
import app.zaid.flow.ui.components.shared.FlowAlertDialog
import app.zaid.flow.ui.components.shared.FlowConnectedToggleGroup
import app.zaid.flow.ui.components.shared.FlowEmptyState
import app.zaid.flow.ui.components.shared.FlowLoadingIndicator
import app.zaid.flow.ui.components.shared.FlowToggleOption
import app.zaid.flow.ui.screens.settings.index.DiagnosticsIndex
import app.zaid.flow.utils.copyPlainText
import app.zaid.flow.utils.sharedFileUri
import kotlinx.coroutines.launch

private enum class DiagnosticsTab { SESSION, FULL_LOG, CRASHES }

private enum class DiagnosticsDialog { CLEAR_CRASHES, CLEAR_FULL_LOG, RESET_SESSION }

private val StateHeight = 200.dp

/** Far more than one row is tall, so the list always lands on its last line. */
private const val END_SCROLL_PX = 100_000f

/** The device, a shareable report, the session's warnings and errors, and saved crash reports. */
@Composable
internal fun DiagnosticsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val crashes by viewModel.crashes.collectAsStateWithLifecycle()
    val fullLog by viewModel.fullLog.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var follow by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableStateOf(DiagnosticsTab.SESSION) }
    var dialog by rememberSaveable { mutableStateOf<DiagnosticsDialog?>(null) }

    val device = viewModel.device
    val androidValue = stringResource(R.string.settings_diagnostics_android_value, device.androidRelease, device.sdk)
    val versionValue = device.versionName?.let { "$it (${device.versionCode})" } ?: stringResource(R.string.unknown)
    val copiedMessage = stringResource(R.string.diagnostics_copied)
    val subject = stringResource(R.string.diagnostics_report_subject)
    val shareTitle = stringResource(R.string.diagnostics_share)
    val shareLogTitle = stringResource(R.string.diagnostics_share_log)
    val logSubject = stringResource(R.string.diagnostics_log_subject)
    val noLogMessage = stringResource(R.string.diagnostics_no_log_to_share)
    val tabs =
        listOf(
            FlowToggleOption(DiagnosticsTab.SESSION, stringResource(R.string.diagnostics_tab_session), Icons.Outlined.BugReport),
            FlowToggleOption(DiagnosticsTab.FULL_LOG, stringResource(R.string.diagnostics_tab_full_log), Icons.Outlined.Description),
            FlowToggleOption(DiagnosticsTab.CRASHES, stringResource(R.string.diagnostics_tab_crashes), Icons.Outlined.Warning),
        )

    // Dragging the list up means the reader wants to look at something, so stop chasing the end.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    val following by rememberUpdatedState(follow && tab == DiagnosticsTab.FULL_LOG && !paused)
    LaunchedEffect(fullLog, follow) {
        if (following && fullLog is LogState.Lines) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) {
                listState.scrollToItem(last)
                listState.scrollBy(END_SCROLL_PX)
            }
        }
    }

    SettingsPage(
        title = stringResource(R.string.diagnostics_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
        listState = listState,
    ) {
        group(key = "diagnostics.logging", header = R.string.settings_section_logging) {
            switch(
                DiagnosticsIndex.fullLogging,
                viewModel.fullLoggingEnabled,
                viewModel::setFullLogging,
                icon = Icons.Outlined.Terminal,
            )
        }
        group(key = "diagnostics.device", header = R.string.settings_section_this_device) {
            info(DiagnosticsIndex.model, "${device.manufacturer} ${device.model}", Icons.Outlined.PhoneAndroid)
            info(DiagnosticsIndex.android, androidValue, Icons.Outlined.Android)
            info(DiagnosticsIndex.appVersion, versionValue, Icons.Outlined.Info)
        }
        group(key = "diagnostics.report", header = R.string.settings_section_report) {
            nav(DiagnosticsIndex.copy, icon = Icons.Outlined.ContentCopy, showChevron = false, onClick = {
                scope.launch {
                    clipboard.copyPlainText(subject, viewModel.report())
                    snackbarHostState.showSnackbar(copiedMessage)
                }
            })
            nav(DiagnosticsIndex.share, icon = Icons.Outlined.Share, showChevron = false, onClick = {
                scope.launch {
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, subject)
                            .putExtra(Intent.EXTRA_TEXT, viewModel.report())
                    context.startActivity(Intent.createChooser(send, shareTitle))
                }
            })
            nav(
                DiagnosticsIndex.resetSession,
                icon = Icons.Outlined.Restore,
                showChevron = false,
                onClick = { dialog = DiagnosticsDialog.RESET_SESSION },
            )
        }
        item("diagnostics.tabs") {
            FlowConnectedToggleGroup(options = tabs, selected = tab, onSelected = { tab = it })
        }
        when (tab) {
            DiagnosticsTab.SESSION -> {
                logSection("diagnostics.session", session, R.string.diagnostics_no_session_logs)
            }

            DiagnosticsTab.FULL_LOG -> {
                item("diagnostics.full_log_controls") {
                    LiveLogControls(
                        filter = filter,
                        paused = paused,
                        follow = follow,
                        onFilter = viewModel::setFilter,
                        onTogglePaused = viewModel::togglePaused,
                        onToggleFollow = { follow = !follow },
                    )
                }
                group(key = "diagnostics.full_log_actions") {
                    nav(DiagnosticsIndex.shareLog, icon = Icons.Outlined.Share, showChevron = false, onClick = {
                        scope.launch {
                            val file = viewModel.exportFullLog()
                            if (file == null) {
                                snackbarHostState.showSnackbar(noLogMessage)
                            } else {
                                val send =
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_SUBJECT, logSubject)
                                        .putExtra(Intent.EXTRA_STREAM, sharedFileUri(context, file))
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, shareLogTitle))
                            }
                        }
                    })
                    nav(
                        DiagnosticsIndex.clearLog,
                        icon = Icons.Outlined.DeleteForever,
                        showChevron = false,
                        onClick = { dialog = DiagnosticsDialog.CLEAR_FULL_LOG },
                    )
                }
                logSection("diagnostics.full_log", fullLog, R.string.diagnostics_no_full_log)
            }

            DiagnosticsTab.CRASHES -> {
                if (crashes is LogState.Lines) {
                    group(key = "diagnostics.crash_actions") {
                        nav(
                            DiagnosticsIndex.clearCrashes,
                            icon = Icons.Outlined.DeleteForever,
                            showChevron = false,
                            onClick = { dialog = DiagnosticsDialog.CLEAR_CRASHES },
                        )
                    }
                }
                logSection("diagnostics.crashes", crashes, R.string.diagnostics_no_crashes)
            }
        }
    }

    when (dialog) {
        DiagnosticsDialog.CLEAR_CRASHES -> {
            DiagnosticsConfirmDialog(
                title = stringResource(R.string.diagnostics_clear_confirm_title),
                body = stringResource(R.string.diagnostics_clear_confirm_body),
                confirm = stringResource(R.string.clear),
                destructive = true,
                onConfirm = viewModel::clearCrashes,
                onDismiss = { dialog = null },
            )
        }

        DiagnosticsDialog.CLEAR_FULL_LOG -> {
            DiagnosticsConfirmDialog(
                title = stringResource(R.string.diagnostics_clear_log_confirm_title),
                body = stringResource(R.string.diagnostics_clear_log_confirm_body),
                confirm = stringResource(R.string.clear),
                destructive = true,
                onConfirm = viewModel::clearFullLog,
                onDismiss = { dialog = null },
            )
        }

        DiagnosticsDialog.RESET_SESSION -> {
            val doneMessage = stringResource(R.string.diagnostics_reset_session_done)
            DiagnosticsConfirmDialog(
                title = stringResource(R.string.diagnostics_reset_session_confirm_title),
                body = stringResource(R.string.diagnostics_reset_session_confirm_body),
                confirm = stringResource(R.string.reset),
                destructive = false,
                onConfirm = {
                    scope.launch {
                        viewModel.resetSession()
                        snackbarHostState.showSnackbar(doneMessage)
                    }
                },
                onDismiss = { dialog = null },
            )
        }

        null -> {
            Unit
        }
    }
}

private fun SettingsListScope.logSection(
    key: String,
    state: LogState,
    emptyMessage: Int,
) {
    when (state) {
        LogState.Loading -> {
            item("$key.loading") {
                Box(Modifier.fillMaxWidth().height(StateHeight), contentAlignment = Alignment.Center) { FlowLoadingIndicator() }
            }
        }

        LogState.Empty -> {
            item("$key.empty") {
                Box(Modifier.fillMaxWidth().height(StateHeight)) {
                    FlowEmptyState(title = stringResource(emptyMessage), icon = Icons.Outlined.CheckCircle)
                }
            }
        }

        is LogState.Lines -> {
            group(key = key) {
                state.chunks.forEachIndexed { index, chunk ->
                    row("$key.$index") { shape -> LogChunk(chunk, shape) }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
