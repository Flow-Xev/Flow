package app.zaid.flow.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import app.zaid.flow.R
import app.zaid.flow.data.local.PlaylistRepository
import app.zaid.flow.data.model.Video
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Watch later button needs from the app graph; a receiver is not created by Hilt. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WatchLaterDependencies {
    fun playlistRepository(): PlaylistRepository
}

/**
 * BroadcastReceiver for handling notification action clicks
 */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CANCEL_DOWNLOAD = "app.zaid.flow.action.CANCEL_DOWNLOAD"
        const val ACTION_RETRY_DOWNLOAD = "app.zaid.flow.action.RETRY_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "app.zaid.flow.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "app.zaid.flow.action.RESUME_DOWNLOAD"
        const val ACTION_DISMISS_NOTIFICATION = "app.zaid.flow.action.DISMISS_NOTIFICATION"
        const val ACTION_WATCH_LATER = "app.zaid.flow.action.WATCH_LATER"

        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_VIDEO_TITLE = "video_title"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_THUMBNAIL_URL = "thumbnail_url"
        const val EXTRA_PUBLISHED_AT = "published_at"
        const val EXTRA_IS_SHORT = "is_short"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.d("NotificationAction", "Received action: ${intent.action}")

        when (intent.action) {
            ACTION_CANCEL_DOWNLOAD -> handleCancelDownload(context, intent)
            ACTION_RETRY_DOWNLOAD -> handleRetryDownload(context, intent)
            ACTION_PAUSE_DOWNLOAD -> handlePauseDownload(context, intent)
            ACTION_RESUME_DOWNLOAD -> handleResumeDownload(context, intent)
            ACTION_DISMISS_NOTIFICATION -> handleDismissNotification(context, intent)
            ACTION_WATCH_LATER -> handleWatchLater(context, intent)
        }
    }

    /** The "Watch later" button on a new-upload alert: saves the video and clears the alert. */
    private fun handleWatchLater(
        context: Context,
        intent: Intent,
    ) {
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val publishedAt = intent.getLongExtra(EXTRA_PUBLISHED_AT, 0L)
        val video =
            Video(
                id = videoId,
                title = intent.getStringExtra(EXTRA_VIDEO_TITLE).orEmpty(),
                channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty(),
                channelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty(),
                thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                duration = 0,
                viewCount = 0L,
                uploadDate = "",
                timestamp = if (publishedAt > 0L) publishedAt else System.currentTimeMillis(),
                timestampIsExact = publishedAt > 0L,
                isShort = intent.getBooleanExtra(EXTRA_IS_SHORT, false),
            )
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository =
                    EntryPointAccessors
                        .fromApplication(appContext, WatchLaterDependencies::class.java)
                        .playlistRepository()
                if (!repository.isInWatchLater(videoId)) repository.addToWatchLater(video)
                if (notificationId != -1) NotificationHelper.cancelNotification(appContext, notificationId)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, R.string.toast_added_to_watch_later, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("NotificationAction", "Watch later failed for $videoId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleCancelDownload(
        context: Context,
        intent: Intent,
    ) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)

        // Cancel the notification
        if (notificationId != -1) {
            NotificationHelper.cancelNotification(context, notificationId)
        }

        // Cancel the actual download task in system DownloadManager
        if (downloadId != -1L) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            downloadManager.remove(downloadId)
            Log.d("NotificationAction", "Download cancelled in system DownloadManager: $downloadId")
        }
    }

    private fun handleRetryDownload(
        context: Context,
        intent: Intent,
    ) {
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        val videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE)

        // TODO: Retry the download
        // This would integrate with your download manager
        Log.d("NotificationAction", "Retrying download for: $videoTitle (ID: $videoId)")
    }

    private fun handlePauseDownload(
        context: Context,
        intent: Intent,
    ) {
        val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)

        // TODO: Pause the download
        Log.d("NotificationAction", "Pausing download: $downloadId")
    }

    private fun handleResumeDownload(
        context: Context,
        intent: Intent,
    ) {
        val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)

        // TODO: Resume the download
        Log.d("NotificationAction", "Resuming download: $downloadId")
    }

    private fun handleDismissNotification(
        context: Context,
        intent: Intent,
    ) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        if (notificationId != -1) {
            NotificationHelper.cancelNotification(context, notificationId)
        }
    }
}
