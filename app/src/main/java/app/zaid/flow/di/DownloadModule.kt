package app.zaid.flow.di

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.zaid.flow.player.cache.PlayerCacheManager
import app.zaid.flow.player.cache.SharedPlayerCacheProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides
    @Singleton
    fun provideDatabaseProvider(
        @ApplicationContext context: Context,
    ): DatabaseProvider = SharedPlayerCacheProvider.databaseProvider(context)

    @Provides
    @Singleton
    @DownloadCache
    fun provideDownloadCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): SimpleCache {
        val downloadContentDirectory = File(context.getExternalFilesDir(null), "downloads")
        return SimpleCache(downloadContentDirectory, NoOpCacheEvictor(), databaseProvider)
    }

    @Provides
    @Singleton
    @PlayerCache
    fun providePlayerCache(
        @ApplicationContext context: Context,
    ): SimpleCache =
        SharedPlayerCacheProvider.getOrCreate(
            context,
            maxCacheSizeBytes = PlayerCacheManager.configuredLimits(context).videoBytes,
        )

    @Provides
    @Singleton
    @MusicCache
    fun provideMusicCache(
        @ApplicationContext context: Context,
    ): SimpleCache =
        SharedPlayerCacheProvider.getOrCreateMusic(
            context,
            maxCacheSizeBytes = PlayerCacheManager.configuredLimits(context).musicBytes,
        )
}
