package app.zaid.flow.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.zaid.flow.MainActivity
import app.zaid.flow.R
import app.zaid.flow.data.local.AppDatabase
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.ThumbnailQuality
import app.zaid.flow.data.local.entity.NotificationEntity
import app.zaid.flow.data.update.AppRelease
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Comprehensive notification helper for the app
 * Handles all notification channels and provides methods for showing various notification types
 */
object NotificationHelper {
    // Notification Channel IDs
    const val CHANNEL_DOWNLOADS = "downloads_channel"
    // v2: Android freezes a channel's importance after first creation, so the old low-key
    // "subscriptions_channel" could never be promoted to heads-up. A new id gets a fresh start.
    const val CHANNEL_SUBSCRIPTIONS = "subscriptions_channel_v2"
    private const val LEGACY_CHANNEL_SUBSCRIPTIONS = "subscriptions_channel"
    const val CHANNEL_PLAYBACK = "playback_channel"
    const val CHANNEL_MUSIC_PLAYBACK = "music_playback_channel"
    const val CHANNEL_GENERAL = "general_channel"
    const val CHANNEL_REMINDERS = "reminders_channel"
    const val CHANNEL_UPDATES = "updates_channel"
    const val EXTRA_OPEN_UPDATE = "app.zaid.flow.extra.OPEN_UPDATE"
    const val CHANNEL_IMPORTS = "imports_channel"

    // Notification IDs
    const val NOTIFICATION_DOWNLOAD_PROGRESS = 1001
    const val NOTIFICATION_DOWNLOAD_COMPLETE = 1002
    const val NOTIFICATION_DOWNLOAD_FAILED = 1003
    const val NOTIFICATION_NEW_VIDEO = 2000 // Base ID, per-video IDs are this + (videoId hash & 0xFFFF)
    const val NOTIFICATION_NEW_VIDEO_SUMMARY = 1999 // Group summary; kept below the per-video range
    private const val GROUP_NEW_VIDEOS = "new_videos"
    const val NOTIFICATION_PLAYBACK = 3001
    const val NOTIFICATION_MUSIC_PLAYBACK = 3002
    const val NOTIFICATION_GENERAL = 4000
    const val NOTIFICATION_REMINDER = 5000
    const val NOTIFICATION_IMPORT_PROGRESS = 6001
    const val NOTIFICATION_IMPORT_COMPLETE = 6002
    private const val NOTIFICATION_BITMAP_MAX_PX = 512
    private const val UPLOAD_PICTURE_WIDTH_PX = 720
    private const val UPLOAD_ACCENT_COLOR = 0xFFFF0000.toInt()

    private var channelsCreated = false

    /**
     * Store notification in database
     */
    private suspend fun storeNotification(
        context: Context,
        entity: NotificationEntity,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                db.notificationDao().insertNotification(entity)
            } catch (e: Exception) {
                android.util.Log.e("NotificationHelper", "Failed to store notification", e)
            }
        }
    }

    /**
     * Initialize all notification channels
     * Should be called once when the app starts (e.g., in Application.onCreate())
     */
    fun createNotificationChannels(context: Context) {
        if (channelsCreated) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Downloads channel - High importance for active downloads
            val downloadsChannel =
                NotificationChannel(
                    CHANNEL_DOWNLOADS,
                    context.getString(R.string.notification_channel_downloads),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_downloads_description)
                    setShowBadge(true)
                    enableLights(true)
                    enableVibration(false)
                }

            // Subscriptions channel - Default importance for new videos
            val subscriptionsChannel =
                NotificationChannel(
                    CHANNEL_SUBSCRIPTIONS,
                    context.getString(R.string.notification_channel_new_videos),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notification_channel_new_videos_description)
                    setShowBadge(true)
                    enableLights(true)
                    enableVibration(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }

            // Video playback channel - Low importance for background playback
            val playbackChannel =
                NotificationChannel(
                    CHANNEL_PLAYBACK,
                    context.getString(R.string.notification_channel_video_playback),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_video_playback_description)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }

            // Music playback channel - Low importance for background music
            val musicPlaybackChannel =
                NotificationChannel(
                    CHANNEL_MUSIC_PLAYBACK,
                    context.getString(R.string.notification_channel_music_playback),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_music_playback_description)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }

            // General notifications channel
            val generalChannel =
                NotificationChannel(
                    CHANNEL_GENERAL,
                    context.getString(R.string.notification_channel_general),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_general_description)
                    setShowBadge(true)
                }

            val remindersChannel =
                NotificationChannel(
                    CHANNEL_REMINDERS,
                    context.getString(R.string.notification_channel_reminders),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notification_channel_break_reminders_description)
                    setShowBadge(true)
                }

            // Updates channel
            val updatesChannel =
                NotificationChannel(
                    CHANNEL_UPDATES,
                    context.getString(R.string.notification_channel_updates),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_updates_description)
                    setShowBadge(true)
                }

            val importsChannel =
                NotificationChannel(
                    CHANNEL_IMPORTS,
                    context.getString(R.string.notification_channel_imports),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_import_description)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }

            runCatching { notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_SUBSCRIPTIONS) }

            notificationManager.createNotificationChannels(
                listOf(
                    downloadsChannel,
                    subscriptionsChannel,
                    playbackChannel,
                    musicPlaybackChannel,
                    generalChannel,
                    remindersChannel,
                    updatesChannel,
                    importsChannel,
                ),
            )

            channelsCreated = true
        }
    }

    /**
     * Check if notification permission is granted (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        if (!runBlocking { PlayerPreferences(context).notificationsEnabled.first() }) {
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // ========== IMPORT NOTIFICATIONS ==========

    /**
     * Show (or update) the import-in-progress notification.
     * When total == 0 the progress bar is indeterminate.
     */
    fun showImportProgress(
        context: Context,
        label: String,
        current: Int,
        total: Int,
    ) {
        if (!hasNotificationPermission(context)) return
        val contentText = if (total > 0) "$current / $total" else "Starting…"
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_IMPORTS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_importing, label))
                .setContentText(contentText)
                .apply {
                    if (total > 0) {
                        setProgress(total, current, false)
                    } else {
                        setProgress(0, 0, true)
                    }
                }.setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_IMPORT_PROGRESS, builder.build())
    }

    /** Replace the progress notification with a one-shot completion notification. */
    fun showImportComplete(
        context: Context,
        label: String,
        count: Int,
        message: String? = null,
    ) {
        if (!hasNotificationPermission(context)) return
        // cancel progress first
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_IMPORT_PROGRESS)
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_IMPORTS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_import_complete))
                .setContentText(message ?: context.getString(R.string.notification_imported, count, label.lowercase()))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_IMPORT_COMPLETE, builder.build())
    }

    /** Cancel the ongoing import progress notification (e.g. on error). */
    fun cancelImportNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_IMPORT_PROGRESS)
    }

    // ========== DOWNLOAD NOTIFICATIONS ==========

    /**
     * Show download progress notification
     */
    fun showDownloadProgress(
        context: Context,
        videoTitle: String,
        progress: Int,
        downloadSpeed: String? = null,
        largeIcon: Bitmap? = null,
        downloadId: Long = -1,
        notificationId: Int = NOTIFICATION_DOWNLOAD_PROGRESS,
    ) {
        if (!hasNotificationPermission(context)) return

        val cancelIntent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_CANCEL_DOWNLOAD
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(NotificationActionReceiver.EXTRA_DOWNLOAD_ID, downloadId)
            }
        val cancelPendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val contentText =
            if (downloadSpeed != null) {
                "$progress% • $downloadSpeed"
            } else {
                "$progress%"
            }

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_downloading, videoTitle))
                .setContentText(contentText)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    android.R.drawable.ic_delete,
                    context.getString(R.string.cancel),
                    cancelPendingIntent,
                ).setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    /**
     * Show download complete notification
     */
    suspend fun showDownloadComplete(
        context: Context,
        videoTitle: String,
        filePath: String? = null,
        thumbnailUrl: String? = null,
        notificationId: Int = NOTIFICATION_DOWNLOAD_COMPLETE,
    ) {
        if (!hasNotificationPermission(context)) return
        if (!PlayerPreferences(context).notifDownloadsEnabled.first()) return

        // Intent to open the downloaded file or app
        val openIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_downloads", true)
            }
        val openPendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_download_complete))
                .setContentText(videoTitle)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)

        // Cancel progress notification
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_DOWNLOAD_PROGRESS)

        // Load thumbnail if provided
        if (!thumbnailUrl.isNullOrEmpty()) {
            val bitmap = getBitmapFromUrl(context, thumbnailUrl)
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
            }
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    /**
     * Show download failed notification
     */
    fun showDownloadFailed(
        context: Context,
        videoTitle: String,
        errorMessage: String? = null,
        notificationId: Int = NOTIFICATION_DOWNLOAD_FAILED,
    ) {
        if (!hasNotificationPermission(context)) return
        if (!runBlocking { PlayerPreferences(context).notifDownloadsEnabled.first() }) return

        // Intent to retry download
        val retryIntent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_RETRY_DOWNLOAD
                putExtra(NotificationActionReceiver.EXTRA_VIDEO_TITLE, videoTitle)
            }
        val retryPendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_download_failed))
                .setContentText(videoTitle)
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText("$videoTitle\n${errorMessage ?: context.getString(R.string.error_generic_hint)}"),
                ).addAction(
                    android.R.drawable.ic_menu_rotate,
                    context.getString(R.string.retry),
                    retryPendingIntent,
                ).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()

        // Cancel progress notification
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_DOWNLOAD_PROGRESS)
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    // ========== SUBSCRIPTION NOTIFICATIONS ==========

    /**
     * Represents a single new video found during a subscription check cycle.
     */
    data class NewVideoEntry(
        val channelName: String,
        val videoTitle: String,
        val videoId: String,
        val thumbnailUrl: String?,
        val publishedAtMillis: Long = 0L,
        /** A Short opens in the Shorts player instead of the watch page. */
        val isShort: Boolean = false,
        val channelId: String = "",
    )

    /**
     * Dispatcher for subscription update notifications.
     */
    suspend fun showSubscriptionUpdates(
        context: Context,
        videos: List<NewVideoEntry>,
    ): Boolean {
        if (videos.isEmpty()) return true
        // Still record them for the in-app bell even when the system can't show a banner.
        if (!PlayerPreferences(context).notifNewVideosEnabled.first()) return true

        videos.forEach { v ->
            storeNotification(
                context,
                NotificationEntity(
                    videoId = v.videoId,
                    title = v.videoTitle,
                    channelName = v.channelName,
                    thumbnailUrl = v.thumbnailUrl,
                    type = "NEW_VIDEO",
                ),
            )
        }

        if (!hasNotificationPermission(context)) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        val manager = NotificationManagerCompat.from(context)
        val multiple = videos.size > 1

        videos.forEach { v -> postNewUpload(context, manager, v) }

        if (!multiple) return true

        val summaryIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val summaryPendingIntent =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_NEW_VIDEO_SUMMARY,
                summaryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val inboxStyle =
            NotificationCompat
                .InboxStyle()
                .setBigContentTitle(
                    context.resources.getQuantityString(
                        R.plurals.notification_new_videos_from_subscriptions,
                        videos.size,
                        videos.size,
                    ),
                )
        videos.take(6).forEach { v ->
            inboxStyle.addLine("${v.channelName}: ${v.videoTitle}")
        }
        if (videos.size > 6) {
            inboxStyle.setSummaryText(context.getString(R.string.notification_more, videos.size - 6))
        }

        val summaryNotification =
            NotificationCompat
                .Builder(context, CHANNEL_SUBSCRIPTIONS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_new_videos))
                .setContentText(
                    context.resources.getQuantityString(
                        R.plurals.notification_new_videos_from_subscriptions,
                        videos.size,
                        videos.size,
                    ),
                ).setContentIntent(summaryPendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setStyle(inboxStyle)
                .setGroup(GROUP_NEW_VIDEOS)
                .setGroupSummary(true)
                .setNumber(videos.size)
                .build()

        manager.notify(NOTIFICATION_NEW_VIDEO_SUMMARY, summaryNotification)
        return true
    }

    /**
     * Posts one new-upload alert laid out like YouTube's own: the video title on top, the channel
     * under it, the wide thumbnail when expanded (with a play button over it, and a Shorts badge
     * for a Short), and Play / Watch later buttons. Tapping the alert or Play opens the video in
     * the player, or a Short in the Shorts feed.
     */
    private suspend fun postNewUpload(
        context: Context,
        manager: NotificationManagerCompat,
        v: NewVideoEntry,
    ) {
        val notifId = NOTIFICATION_NEW_VIDEO + v.videoId.hashCode().and(0xFFFF)
        val openIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("notification_video_id", v.videoId)
                putExtra("video_id", v.videoId)
                putExtra("video_title", v.videoTitle)
                putExtra("is_short", v.isShort)
                // MainActivity clears the alert, so the Play button dismisses it as a tap does.
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
            }
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // The request codes differ because PendingIntents that differ only in extras are one and the same.
        val tapPendingIntent = PendingIntent.getActivity(context, notifId * 3, openIntent, immutable)
        val playPendingIntent = PendingIntent.getActivity(context, notifId * 3 + 1, openIntent, immutable)
        val watchLaterPendingIntent =
            PendingIntent.getBroadcast(
                context,
                notifId * 3 + 2,
                Intent(context, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_WATCH_LATER
                    putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
                    putExtra(NotificationActionReceiver.EXTRA_VIDEO_ID, v.videoId)
                    putExtra(NotificationActionReceiver.EXTRA_VIDEO_TITLE, v.videoTitle)
                    putExtra(NotificationActionReceiver.EXTRA_CHANNEL_NAME, v.channelName)
                    putExtra(NotificationActionReceiver.EXTRA_CHANNEL_ID, v.channelId)
                    putExtra(NotificationActionReceiver.EXTRA_THUMBNAIL_URL, v.thumbnailUrl)
                    putExtra(NotificationActionReceiver.EXTRA_PUBLISHED_AT, v.publishedAtMillis)
                    putExtra(NotificationActionReceiver.EXTRA_IS_SHORT, v.isShort)
                },
                immutable,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_SUBSCRIPTIONS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setColor(UPLOAD_ACCENT_COLOR)
                .setContentTitle(v.videoTitle)
                .setContentText(v.channelName)
                .setSubText(
                    context.getString(
                        if (v.isShort) R.string.notification_new_short else R.string.notification_channel_new_videos,
                    ),
                ).setContentIntent(tapPendingIntent)
                .setAutoCancel(true)
                // Heads-up on Android < 8; on 8+ the channel's IMPORTANCE_HIGH decides.
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setStyle(NotificationCompat.BigTextStyle().bigText(v.channelName).setBigContentTitle(v.videoTitle))
                .setGroup(GROUP_NEW_VIDEOS)
                .addAction(R.drawable.ic_notification_play, context.getString(R.string.play), playPendingIntent)
                .addAction(
                    R.drawable.ic_notification_watch_later,
                    context.getString(R.string.watch_later),
                    watchLaterPendingIntent,
                )
        if (v.publishedAtMillis > 0L) builder.setWhen(v.publishedAtMillis).setShowWhen(true)

        val thumbnailUrl = v.thumbnailUrl?.takeIf { it.isNotBlank() } ?: "https://i.ytimg.com/vi/${v.videoId}/hqdefault.jpg"
        getBitmapFromUrl(context, thumbnailUrl, followThumbnailSetting = true, maxPx = UPLOAD_PICTURE_WIDTH_PX)?.let { source ->
            val wide = cropToWidescreen(source)
            builder.setLargeIcon(wide)
            builder.setStyle(
                NotificationCompat
                    .BigPictureStyle()
                    .bigPicture(composeUploadPicture(context, wide, v.isShort))
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(v.videoTitle)
                    .setSummaryText(v.channelName),
            )
        }
        manager.notify(notifId, builder.build())
    }

    /**
     * YouTube serves 4:3 thumbnails with black bars above and below the picture; a 16:9 centre crop
     * removes exactly those bars and gives the shape the expanded alert shows.
     */
    private fun cropToWidescreen(source: Bitmap): Bitmap {
        val targetHeight = (source.width * 9f / 16f).toInt()
        if (targetHeight >= source.height) {
            // Already wider than 16:9: trim the sides instead.
            val targetWidth = (source.height * 16f / 9f).toInt().coerceAtMost(source.width)
            if (targetWidth == source.width) return source
            return Bitmap.createBitmap(source, (source.width - targetWidth) / 2, 0, targetWidth, source.height)
        }
        return Bitmap.createBitmap(source, 0, (source.height - targetHeight) / 2, source.width, targetHeight)
    }

    /** The expanded picture: the 16:9 thumbnail with a play button in the middle and a Shorts badge on a Short. */
    private fun composeUploadPicture(
        context: Context,
        wide: Bitmap,
        isShort: Boolean,
    ): Bitmap {
        val width = UPLOAD_PICTURE_WIDTH_PX
        val height = width * 9 / 16
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(wide, Rect(0, 0, wide.width, wide.height), Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))

        // Play button: a dark disc with a white triangle, the same mark the player shows.
        val cx = width / 2f
        val cy = height / 2f
        val radius = height * 0.15f
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) })
        val half = radius * 0.46f
        val triangle =
            Path().apply {
                moveTo(cx - half * 0.7f, cy - half)
                lineTo(cx - half * 0.7f, cy + half)
                lineTo(cx + half * 1.05f, cy)
                close()
            }
        canvas.drawPath(
            triangle,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeJoin = Paint.Join.ROUND
                strokeWidth = radius * 0.16f
                style = Paint.Style.FILL_AND_STROKE
            },
        )

        if (isShort) {
            val label = context.getString(R.string.shorts)
            val textPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = height * 0.075f
                }
            val padding = height * 0.035f
            val margin = height * 0.04f
            val textWidth = textPaint.measureText(label)
            val pill =
                RectF(
                    margin,
                    height - margin - textPaint.textSize - padding * 2,
                    margin + textWidth + padding * 2.4f,
                    height - margin,
                )
            canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UPLOAD_ACCENT_COLOR })
            canvas.drawText(label, pill.left + padding * 1.2f, pill.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
        return out
    }

    /**
     * Show notification for new video from subscribed channel
     */
    suspend fun showNewVideoNotification(
        context: Context,
        channelName: String,
        videoTitle: String,
        videoId: String,
        thumbnailUrl: String? = null,
        channelId: String,
    ) {
        if (!hasNotificationPermission(context)) return
        if (!PlayerPreferences(context).notifNewVideosEnabled.first()) return

        storeNotification(
            context,
            NotificationEntity(
                videoId = videoId,
                title = videoTitle,
                channelName = channelName,
                thumbnailUrl = thumbnailUrl,
                type = "NEW_VIDEO",
            ),
        )

        postNewUpload(
            context,
            NotificationManagerCompat.from(context),
            NewVideoEntry(
                channelName = channelName,
                videoTitle = videoTitle,
                videoId = videoId,
                thumbnailUrl = thumbnailUrl,
                channelId = channelId,
            ),
        )
    }

    /**
     * Show grouped notification summary for multiple new videos
     */
    fun showNewVideosSummary(
        context: Context,
        videoCount: Int,
    ) {
        if (!hasNotificationPermission(context)) return
        if (!runBlocking { PlayerPreferences(context).notifNewVideosEnabled.first() }) return

        val summaryNotification =
            NotificationCompat
                .Builder(context, CHANNEL_SUBSCRIPTIONS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_new_videos))
                .setContentText(
                    context.resources.getQuantityString(
                        R.plurals.notification_new_videos_from_subscriptions,
                        videoCount,
                        videoCount,
                    ),
                ).setGroup("new_videos")
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_NEW_VIDEO, summaryNotification)
    }

    // ========== UPDATE NOTIFICATIONS ==========

    /**
     * Show notification for new app update
     */
    fun showUpdateNotification(
        context: Context,
        release: AppRelease,
    ) {
        if (!hasNotificationPermission(context)) return
        if (!runBlocking { PlayerPreferences(context).notifUpdatesEnabled.first() }) return

        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_UPDATE, true)
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_update_available, release.version))
                .setContentText(context.getString(R.string.notification_tap_to_update))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()

        NotificationManagerCompat.from(context).notify(9999, notification)
    }

    // ========== GENERAL NOTIFICATIONS ==========

    /**
     * Show a simple notification
     */
    fun showSimpleNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = NOTIFICATION_GENERAL,
    ) {
        if (!hasNotificationPermission(context)) return
        if (!runBlocking { PlayerPreferences(context).notifGeneralEnabled.first() }) return

        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_GENERAL)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    /**
     * Show watch later reminder notification
     */
    fun showWatchLaterReminder(
        context: Context,
        videoTitle: String,
        videoId: String,
        thumbnailUrl: String? = null,
    ) {
        if (!hasNotificationPermission(context)) return
        if (!runBlocking { PlayerPreferences(context).notifGeneralEnabled.first() }) return

        val notificationId = NOTIFICATION_GENERAL + videoId.hashCode().and(0xFFF)

        val watchIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("video_id", videoId)
            }
        val watchPendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId,
                watchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_GENERAL)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_watch_later_reminder))
                .setContentText(videoTitle)
                .setContentIntent(watchPendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    // ========== UTILITY FUNCTIONS ==========

    /**
     * Cancel a specific notification
     */
    fun cancelNotification(
        context: Context,
        notificationId: Int,
    ) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /**
     * Show reminder notification (Bedtime, Take a break)
     */
    fun showReminderNotification(
        context: Context,
        title: String,
        message: String,
    ) {
        if (!hasNotificationPermission(context)) return
        if (!runBlocking { PlayerPreferences(context).notifRemindersEnabled.first() }) return

        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_REMINDERS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(context)) {
                notify(NOTIFICATION_REMINDER, builder.build())
            }
        } catch (e: SecurityException) {
            // Should be covered by hasNotificationPermission check, but safety first
            e.printStackTrace()
        }
    }

    fun showUpcomingVideoLiveNotification(
        context: Context,
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
    ) {
        if (!hasNotificationPermission(context)) return
        if (!runBlocking { PlayerPreferences(context).notifRemindersEnabled.first() }) return

        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_video_id", videoId)
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                videoId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val liveText = context.getString(R.string.notification_channel_live, channelName, title)
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_video_live))
                .setContentText(liveText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(liveText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

        runBlocking {
            val bitmap = thumbnailUrl?.let { getBitmapFromUrl(context, it, followThumbnailSetting = true) }
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
            }
        }

        try {
            with(NotificationManagerCompat.from(context)) {
                notify(NOTIFICATION_REMINDER + videoId.hashCode(), builder.build())
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    /**
     * Cancel all notifications
     */
    fun cancelAllNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /**
     * Load bitmap from URL for notification large icon/picture.
     *
     * Uses the app's shared Coil ImageLoader so notification artwork reuses the memory/disk
     * cache the feed already populated instead of refetching through a second image stack.
     * Hardware bitmaps are disabled because notification bitmaps must be parcelable to
     * SystemUI, and INEXACT precision keeps the "never upscale" behaviour of the previous
     * centerInside/onlyScaleDown request. With [followThumbnailSetting], a viewer who turned
     * thumbnails off gets one only if it is already cached.
     */
    suspend fun getBitmapFromUrl(
        context: Context,
        url: String,
        followThumbnailSetting: Boolean = false,
        maxPx: Int = NOTIFICATION_BITMAP_MAX_PX,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                if (url.isEmpty()) return@withContext null
                val cacheOnly =
                    followThumbnailSetting && PlayerPreferences(context).currentThumbnailQuality() == ThumbnailQuality.OFF
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(url)
                        .size(maxPx)
                        .scale(Scale.FIT)
                        .precision(Precision.INEXACT)
                        .allowHardware(false)
                        .apply { if (cacheOnly) networkCachePolicy(CachePolicy.DISABLED) }
                        .build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
                    ?.image
                    ?.toBitmap()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
}
