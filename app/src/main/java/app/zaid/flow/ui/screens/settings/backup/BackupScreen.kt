package app.zaid.flow.ui.screens.settings.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.data.backup.BackupInspection
import app.zaid.flow.data.backup.BackupOperation
import app.zaid.flow.data.backup.ExportKind
import app.zaid.flow.data.backup.ImportKind
import app.zaid.flow.data.backup.UnrecognizedBackupException
import app.zaid.flow.data.local.LocalDataManager.AutoBackupFrequency
import app.zaid.flow.data.local.LocalDataManager.AutoBackupType
import app.zaid.flow.ui.components.settings.SettingsPage
import app.zaid.flow.ui.components.settings.SettingsTabs
import app.zaid.flow.ui.components.shared.FlowConnectedToggleGroup
import app.zaid.flow.ui.components.shared.FlowProgressBanner
import app.zaid.flow.ui.components.shared.FlowToggleOption
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val HeaderSpacing = 8.dp
private const val MAX_FOUND_NAMES = 4

/**
 * Export, import and automatic backups on one page. Work runs in the backup coordinator, so it
 * keeps going when this page closes; progress and results show here whenever the page is open.
 */
@Composable
internal fun BackupScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    tab: String?,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val frequency by viewModel.frequency.collectAsStateWithLifecycle()
    val type by viewModel.type.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val lastRun by viewModel.lastRun.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(tab ?: SettingsTabs.BACKUP_EXPORT) }
    var pendingExport by rememberSaveable { mutableStateOf<ExportKind?>(null) }
    var pendingImport by rememberSaveable { mutableStateOf<ImportKind?>(null) }
    var showMasterExport by remember { mutableStateOf(false) }
    var restorePreview by remember { mutableStateOf<Pair<Uri, BackupInspection>?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notMasterBackup = stringResource(R.string.backup_inspect_failed)
    val busy = stringResource(R.string.backup_busy)
    val start: (Boolean) -> Unit = { started -> if (!started) scope.launch { snackbarHostState.showSnackbar(busy) } }

    LaunchedEffect(operation) {
        val message =
            when (val current = operation) {
                is BackupOperation.Succeeded -> current.message
                is BackupOperation.Failed -> current.message
                else -> return@LaunchedEffect
            }
        viewModel.dismiss()
        // Dismissing changes this effect's key, which would cancel a snackbar shown from here.
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val onExported: (Uri?) -> Unit = { uri ->
        val kind = pendingExport
        pendingExport = null
        val includeAccounts = viewModel.pendingIncludeAccounts
        viewModel.pendingIncludeAccounts = false
        if (uri != null && kind != null) {
            if (kind == ExportKind.MASTER) {
                start(viewModel.coordinator.exportMaster(uri, includeAccounts))
            } else {
                start(kind.start(viewModel.coordinator, uri))
            }
        }
    }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), onExported)
    val zipExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), onExported)
    val importPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val kind = pendingImport
            pendingImport = null
            if (uri != null && kind == ImportKind.MASTER) {
                // A master backup is read first, so what is in it can be chosen before anything changes.
                scope.launch {
                    viewModel.inspectMaster(uri).fold(
                        onSuccess = { restorePreview = uri to it },
                        onFailure = { error ->
                            val message =
                                when {
                                    error is UnrecognizedBackupException && error.found.isNotEmpty() -> {
                                        notMasterBackup + " " +
                                            context.getString(
                                                R.string.backup_inspect_found,
                                                error.found.take(MAX_FOUND_NAMES).joinToString(", "),
                                            )
                                    }

                                    error is UnrecognizedBackupException -> {
                                        notMasterBackup
                                    }

                                    else -> {
                                        context.getString(
                                            R.string.backup_inspect_unreadable,
                                            error.message ?: error.javaClass.simpleName,
                                        )
                                    }
                                }
                            snackbarHostState.showSnackbar(message)
                        },
                    )
                }
            } else if (uri != null && kind != null) {
                start(kind.start(viewModel.coordinator, listOf(uri)))
            }
        }
    val multiImportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val kind = pendingImport
            pendingImport = null
            if (uris.isNotEmpty() && kind != null) start(kind.start(viewModel.coordinator, uris))
        }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(viewModel::setFolder)
        }

    val autoState =
        AutoBackupState(
            frequency = frequency,
            type = type,
            folderName = folder?.let { viewModel.folderName(it) },
            noFolderLabel = stringResource(R.string.auto_backup_no_folder),
            lastRun = lastRun.takeIf { it > 0 }?.let { stringResource(R.string.auto_backup_last_run, formatLastRun(it)) },
            running = operation is BackupOperation.Running,
            frequencyOptions =
                listOf(
                    FlowToggleOption(AutoBackupFrequency.NONE, stringResource(R.string.auto_backup_frequency_none)),
                    FlowToggleOption(AutoBackupFrequency.DAILY, stringResource(R.string.auto_backup_frequency_daily)),
                    FlowToggleOption(AutoBackupFrequency.WEEKLY, stringResource(R.string.auto_backup_frequency_weekly)),
                    FlowToggleOption(AutoBackupFrequency.MONTHLY, stringResource(R.string.auto_backup_frequency_monthly)),
                ),
            typeLabels =
                mapOf(
                    AutoBackupType.APP_DATA to stringResource(R.string.auto_backup_type_app_data),
                    AutoBackupType.BRAIN to stringResource(R.string.auto_backup_type_brain),
                    AutoBackupType.MASTER to stringResource(R.string.auto_backup_type_master),
                ),
        )

    SettingsPage(
        title = stringResource(R.string.settings_backup_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
        header = {
            Column {
                FlowConnectedToggleGroup(
                    options =
                        listOf(
                            FlowToggleOption(
                                SettingsTabs.BACKUP_EXPORT,
                                stringResource(R.string.settings_backup_tab_export),
                                Icons.Outlined.FileUpload,
                            ),
                            FlowToggleOption(
                                SettingsTabs.BACKUP_IMPORT,
                                stringResource(R.string.settings_backup_tab_import),
                                Icons.Outlined.FileDownload,
                            ),
                            FlowToggleOption(
                                SettingsTabs.BACKUP_AUTO,
                                stringResource(R.string.settings_backup_tab_auto),
                                Icons.Outlined.Schedule,
                            ),
                        ),
                    selected = selectedTab,
                    onSelected = { selectedTab = it },
                    modifier = Modifier.padding(bottom = HeaderSpacing),
                )
                (operation as? BackupOperation.Running)?.let { running ->
                    FlowProgressBanner(
                        headline = running.headline,
                        current = running.current,
                        total = running.total,
                        note = stringResource(R.string.import_running_background),
                        modifier = Modifier.padding(bottom = HeaderSpacing),
                    )
                }
            }
        },
    ) {
        when (selectedTab) {
            SettingsTabs.BACKUP_IMPORT -> {
                importPage { kind ->
                    pendingImport = kind
                    if (kind.takesSeveralFiles) multiImportPicker.launch(kind.mimeTypes) else importPicker.launch(kind.mimeTypes)
                }
            }

            SettingsTabs.BACKUP_AUTO -> {
                autoBackupPage(
                    state = autoState,
                    onFrequency = viewModel::setFrequency,
                    onType = viewModel::setType,
                    onPickFolder = { folderPicker.launch(folder?.let(Uri::parse)) },
                    onBackUpNow = viewModel::backUpNow,
                )
            }

            else -> {
                exportPage { kind ->
                    pendingExport = kind
                    when {
                        kind == ExportKind.MASTER -> showMasterExport = true
                        kind.mimeType == "application/zip" -> zipExport.launch(kind.fileName())
                        else -> jsonExport.launch(kind.fileName())
                    }
                }
            }
        }
    }

    if (showMasterExport) {
        MasterExportDialog(
            accountCount = viewModel.accountCount,
            onDismiss = {
                showMasterExport = false
                pendingExport = null
                viewModel.pendingIncludeAccounts = false
            },
            onConfirm = { includeAccounts ->
                showMasterExport = false
                viewModel.pendingIncludeAccounts = includeAccounts
                zipExport.launch(ExportKind.MASTER.fileName())
            },
        )
    }

    restorePreview?.let { (uri, inspection) ->
        RestorePreviewDialog(
            inspection = inspection,
            onDismiss = { restorePreview = null },
            onRestore = { sections, password ->
                restorePreview = null
                start(viewModel.coordinator.importMaster(uri, sections, password))
            },
        )
    }
}

private fun formatLastRun(epochMillis: Long): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
