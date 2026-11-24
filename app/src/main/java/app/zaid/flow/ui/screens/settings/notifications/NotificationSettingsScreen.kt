package app.zaid.flow.ui.screens.settings.notifications

import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Update
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.BuildConfig
import app.zaid.flow.R
import app.zaid.flow.notification.BackgroundWorkPolicy
import app.zaid.flow.ui.components.settings.SettingsPage
import app.zaid.flow.ui.components.settings.nav
import app.zaid.flow.ui.components.settings.switch
import app.zaid.flow.ui.components.shared.FlowChoice
import app.zaid.flow.ui.components.shared.FlowChoiceDialog
import app.zaid.flow.ui.components.shared.FlowNavRow
import app.zaid.flow.ui.screens.settings.index.NotificationsIndex

private val IntervalOptions =
    listOf(
        15 to R.string.notif_interval_15m,
        30 to R.string.notif_interval_30m,
        60 to R.string.notif_interval_1h,
        120 to R.string.notif_interval_2h,
        180 to R.string.notif_interval_3h,
        360 to R.string.notif_interval_6h,
        720 to R.string.notif_interval_12h,
        1440 to R.string.notif_interval_24h,
    )

/** The background feed check, which kinds of alert Flow may post, and the system channel settings. */
@Composable
internal fun NotificationSettingsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val newVideosOn by viewModel.newVideos.collectAsStateWithLifecycle()
    val interval by viewModel.intervalMinutes.collectAsStateWithLifecycle()
    val backgroundAllowed by viewModel.backgroundAllowed.collectAsStateWithLifecycle()
    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }
    // Android 13+ starts with the permission off; without it every alert is dropped silently.
    var systemAllowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        systemAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        viewModel.refreshBackgroundWork()
        onPauseOrDispose { }
    }

    val intervalLabel =
        IntervalOptions.firstOrNull { it.first == interval }?.let { stringResource(it.second) }
            ?: stringResource(R.string.duration_minutes_short, interval)
    val intervalSummary =
        if (enabled) {
            stringResource(R.string.notif_check_interval_subtitle_template, intervalLabel)
        } else {
            stringResource(R.string.notif_check_interval_disabled)
        }
    val restrictedTitle = stringResource(R.string.notif_background_restricted_title)
    val restrictedBody = stringResource(R.string.notif_background_restricted_subtitle)

    SettingsPage(
        title = stringResource(R.string.notif_settings_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "notifications.schedule", header = R.string.notif_check_interval_section_header) {
            switch(
                NotificationsIndex.enabled,
                enabled,
                { on ->
                    viewModel.setEnabled(on)
                    if (on && !backgroundAllowed) BackgroundWorkPolicy.requestUnrestrictedBackgroundWork(context)
                },
                icon = Icons.Outlined.NotificationsActive,
            )
            if (enabled && !systemAllowed) {
                row("notifications.blocked") { shape ->
                    FlowNavRow(
                        title = stringResource(R.string.notif_system_blocked_title),
                        supportingText = stringResource(R.string.notif_system_blocked_subtitle),
                        leadingIcon = Icons.Outlined.NotificationsOff,
                        showChevron = false,
                        shape = shape,
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    )
                }
            }
            if (enabled && !backgroundAllowed) {
                row("notifications.restricted") { shape ->
                    FlowNavRow(
                        title = restrictedTitle,
                        supportingText = restrictedBody,
                        leadingIcon = Icons.Outlined.BatteryAlert,
                        showChevron = false,
                        shape = shape,
                        onClick = { BackgroundWorkPolicy.requestUnrestrictedBackgroundWork(context) },
                    )
                }
            }
            nav(
                NotificationsIndex.interval,
                value = intervalSummary,
                enabled = enabled,
                showChevron = false,
                icon = Icons.Outlined.Schedule,
                onClick = { showIntervalDialog = true },
            )
        }
        group(key = "notifications.types", header = R.string.settings_section_notification_types) {
            switch(
                NotificationsIndex.newVideos,
                viewModel.newVideos,
                viewModel::setNewVideos,
                enabled = enabled,
                icon = Icons.Outlined.Subscriptions,
            )
            switch(
                NotificationsIndex.shorts,
                viewModel.shorts,
                viewModel::setShorts,
                enabled = enabled && newVideosOn,
                icon = Icons.Outlined.Slideshow,
            )
            switch(
                NotificationsIndex.downloads,
                viewModel.downloads,
                viewModel::setDownloads,
                enabled = enabled,
                icon = Icons.Outlined.Download,
            )
            switch(
                NotificationsIndex.reminders,
                viewModel.reminders,
                viewModel::setReminders,
                enabled = enabled,
                icon = Icons.Outlined.Bedtime,
            )
            if (BuildConfig.UPDATER_ENABLED) {
                switch(
                    NotificationsIndex.updates,
                    viewModel.updates,
                    viewModel::setUpdates,
                    enabled = enabled,
                    icon = Icons.Outlined.Update,
                )
            }
            switch(
                NotificationsIndex.general,
                viewModel.general,
                viewModel::setGeneral,
                enabled = enabled,
                icon = Icons.Outlined.Notifications,
            )
        }
        group(key = "notifications.system", header = R.string.settings_section_more) {
            nav(
                NotificationsIndex.system,
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                showChevron = false,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
        }
    }

    if (showIntervalDialog) {
        FlowChoiceDialog(
            title = stringResource(R.string.notif_check_interval_dialog_title),
            description = stringResource(R.string.notif_check_interval_dialog_body),
            options = IntervalOptions.map { (minutes, label) -> FlowChoice(minutes, stringResource(label)) },
            selected = interval,
            onSelect = viewModel::setInterval,
            onDismiss = { showIntervalDialog = false },
        )
    }
}
