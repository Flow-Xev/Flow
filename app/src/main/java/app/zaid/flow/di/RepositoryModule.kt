package app.zaid.flow.di

import android.content.Context
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.repository.YouTubeRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun provideYouTubeRepository(playerPreferences: PlayerPreferences): YouTubeRepository = YouTubeRepository.getInstance(playerPreferences)

    @Provides
    @Singleton
    fun provideSubscriptionRepository(
        @ApplicationContext context: Context,
    ): app.zaid.flow.data.local.SubscriptionRepository =
        app.zaid.flow.data.local.SubscriptionRepository
            .getInstance(context)

    @Provides
    @Singleton
    fun provideLikedVideosRepository(
        @ApplicationContext context: Context,
    ): app.zaid.flow.data.local.LikedVideosRepository =
        app.zaid.flow.data.local.LikedVideosRepository
            .getInstance(context)

    @Provides
    @Singleton
    fun provideViewHistory(
        @ApplicationContext context: Context,
    ): app.zaid.flow.data.local.ViewHistory =
        app.zaid.flow.data.local.ViewHistory
            .getInstance(context)

    @Provides
    @Singleton
    fun provideHomeFeedCacheRepository(
        @ApplicationContext context: Context,
    ): app.zaid.flow.data.local.HomeFeedCacheRepository =
        app.zaid.flow.data.local
            .HomeFeedCacheRepository(context)

    @Provides
    @Singleton
    fun provideMusicPlaylistRepository(
        @ApplicationContext context: Context,
    ): app.zaid.flow.data.music.PlaylistRepository =
        app.zaid.flow.data.music
            .PlaylistRepository(context)

    // VideoDownloadManager is now @Singleton @Inject — Hilt provides it automatically
    @Provides
    @Singleton
    fun providePlayerPreferences(
        @ApplicationContext context: Context,
    ): app.zaid.flow.data.local.PlayerPreferences =
        app.zaid.flow.data.local
            .PlayerPreferences(context)

    /**
     * Transitional: [app.zaid.flow.data.local.BackupRepository] still builds its own
     * collaborators, so it is provided here rather than injected through its constructor. One
     * instance serves the whole app.
     */
    @Provides
    @Singleton
    fun provideBackupRepository(
        @ApplicationContext context: Context,
    ): app.zaid.flow.data.local.BackupRepository =
        app.zaid.flow.data.local
            .BackupRepository(context)
}
