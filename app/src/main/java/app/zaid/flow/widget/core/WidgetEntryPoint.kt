package app.zaid.flow.widget.core

import android.content.Context
import app.zaid.flow.widget.core.refresh.WidgetContentSync
import app.zaid.flow.widget.downloads.DownloadsSource
import app.zaid.flow.widget.onrepeat.OnRepeatSource
import app.zaid.flow.widget.playlist.PlaylistWidgetSource
import app.zaid.flow.widget.recent.RecentlyPlayedSource
import app.zaid.flow.widget.week.WeekSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Glance widgets can't use constructor injection (the framework instantiates them),
 * so Hilt singletons are reached through this entry point.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun recentlyPlayedSource(): RecentlyPlayedSource

    fun downloadsSource(): DownloadsSource

    fun onRepeatSource(): OnRepeatSource

    fun playlistWidgetSource(): PlaylistWidgetSource

    fun weekSource(): WeekSource

    fun widgetContentSync(): WidgetContentSync
}

fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
