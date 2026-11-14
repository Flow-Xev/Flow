package app.zaid.flow.data.video.downloader.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** The pause, resume, retry and cancel buttons on a download's notification. */
class DownloadActionReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun downloadController(): DownloadController
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return
        val controller = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java).downloadController()
        when (intent.action) {
            ACTION_PAUSE -> controller.pause(videoId)
            ACTION_RESUME -> controller.resume(videoId)
            ACTION_RETRY -> controller.retry(videoId)
            ACTION_CANCEL -> controller.cancel(videoId)
        }
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val ACTION_PAUSE = "app.zaid.flow.download.PAUSE"
        const val ACTION_RESUME = "app.zaid.flow.download.RESUME"
        const val ACTION_RETRY = "app.zaid.flow.download.RETRY"
        const val ACTION_CANCEL = "app.zaid.flow.download.CANCEL"
    }
}
