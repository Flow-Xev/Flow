package app.zaid.flow.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import app.zaid.flow.R
import app.zaid.flow.data.backup.BackupCoordinator
import app.zaid.flow.data.local.LocalDataManager
import app.zaid.flow.data.local.PlayerPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** What the worker needs from the app graph; it is created by WorkManager, not by Hilt. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoBackupEntryPoint {
    fun backupCoordinator(): BackupCoordinator
}

class AutoBackupWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    companion object {
        const val WORK_NAME = "auto_backup_work"
        private const val NOTIFICATION_CHANNEL_ID = "auto_backup_channel"
        private const val NOTIFICATION_ID_SUCCESS = 5001
        private const val NOTIFICATION_ID_FAILURE = 5002

        fun scheduleBackup(
            context: Context,
            frequencyDays: Long,
        ) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            val workRequest =
                PeriodicWorkRequestBuilder<AutoBackupWorker>(frequencyDays, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, workRequest)
        }

        fun cancelBackup(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val playerPrefs = PlayerPreferences(appContext)

            val frequency = playerPrefs.autoBackupFrequency.first()
            if (frequency == LocalDataManager.AutoBackupFrequency.NONE) {
                return@withContext Result.success()
            }

            val folderUriStr =
                playerPrefs.autoBackupFolderUri.first()
                    ?: return@withContext Result.failure()

            val folderUri = Uri.parse(folderUriStr)
            val type = playerPrefs.autoBackupType.first()
            val dependencies = EntryPointAccessors.fromApplication(appContext, AutoBackupEntryPoint::class.java)
            val succeeded = dependencies.backupCoordinator().runAutoBackup(type, folderUri)

            return@withContext if (succeeded) {
                showNotification(appContext, success = true)
                Result.success()
            } else {
                showNotification(appContext, success = false)
                Result.retry()
            }
        }

    private fun showNotification(
        context: Context,
        success: Boolean,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.auto_backup_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            nm.createNotificationChannel(channel)
        }

        val titleRes = if (success) R.string.auto_backup_success else R.string.auto_backup_failed
        val notification =
            NotificationCompat
                .Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(titleRes))
                .setAutoCancel(true)
                .build()

        val notifId = if (success) NOTIFICATION_ID_SUCCESS else NOTIFICATION_ID_FAILURE
        nm.notify(notifId, notification)
    }
}
