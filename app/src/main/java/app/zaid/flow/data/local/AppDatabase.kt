package app.zaid.flow.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room.databaseBuilder
import androidx.room.RoomDatabase
import app.zaid.flow.data.local.dao.CacheDao
import app.zaid.flow.data.local.dao.DownloadCollectionDao
import app.zaid.flow.data.local.dao.DownloadDao
import app.zaid.flow.data.local.dao.HomeFeedCacheDao
import app.zaid.flow.data.local.dao.MusicGraphDao
import app.zaid.flow.data.local.dao.NoteDao
import app.zaid.flow.data.local.dao.NotificationDao
import app.zaid.flow.data.local.dao.PlaylistDao
import app.zaid.flow.data.local.dao.RecognitionHistoryDao
import app.zaid.flow.data.local.dao.SubscriptionGroupDao
import app.zaid.flow.data.local.dao.SyncLogDao
import app.zaid.flow.data.local.dao.SyncPeerDao
import app.zaid.flow.data.local.dao.VideoDao
import app.zaid.flow.data.local.dao.WatchHistoryDao
import app.zaid.flow.data.local.entity.DownloadCollectionEntity
import app.zaid.flow.data.local.entity.DownloadCollectionItemEntity
import app.zaid.flow.data.local.entity.DownloadEntity
import app.zaid.flow.data.local.entity.DownloadItemEntity
import app.zaid.flow.data.local.entity.HomeFeedCacheEntity
import app.zaid.flow.data.local.entity.MusicGraphAlbumEntity
import app.zaid.flow.data.local.entity.MusicGraphArtistEntity
import app.zaid.flow.data.local.entity.MusicGraphEdgeEntity
import app.zaid.flow.data.local.entity.MusicGraphPlaylistEntity
import app.zaid.flow.data.local.entity.MusicGraphTrackEntity
import app.zaid.flow.data.local.entity.MusicHomeCacheEntity
import app.zaid.flow.data.local.entity.MusicHomeChipEntity
import app.zaid.flow.data.local.entity.NoteEntity
import app.zaid.flow.data.local.entity.NotificationEntity
import app.zaid.flow.data.local.entity.PlaylistEntity
import app.zaid.flow.data.local.entity.PlaylistVideoCrossRef
import app.zaid.flow.data.local.entity.RecognitionHistoryEntity
import app.zaid.flow.data.local.entity.SubscriptionFeedEntity
import app.zaid.flow.data.local.entity.SubscriptionGroupEntity
import app.zaid.flow.data.local.entity.SyncLogEntity
import app.zaid.flow.data.local.entity.SyncPeerEntity
import app.zaid.flow.data.local.entity.VideoEntity
import app.zaid.flow.data.local.entity.WatchHistoryEntity
import app.zaid.flow.data.local.migrations.MIGRATIONS
import app.zaid.flow.data.local.migrations.Migration24To25

@Database(
    entities = [
        VideoEntity::class,
        PlaylistEntity::class,
        PlaylistVideoCrossRef::class,
        NotificationEntity::class,
        SubscriptionFeedEntity::class,
        MusicHomeCacheEntity::class,
        MusicHomeChipEntity::class,
        DownloadEntity::class,
        DownloadItemEntity::class,
        WatchHistoryEntity::class,
        HomeFeedCacheEntity::class,
        SubscriptionGroupEntity::class,
        RecognitionHistoryEntity::class,
        SyncLogEntity::class,
        SyncPeerEntity::class,
        MusicGraphTrackEntity::class,
        MusicGraphArtistEntity::class,
        MusicGraphAlbumEntity::class,
        MusicGraphPlaylistEntity::class,
        MusicGraphEdgeEntity::class,
        NoteEntity::class,
        DownloadCollectionEntity::class,
        DownloadCollectionItemEntity::class,
    ],
    autoMigrations = [
        AutoMigration(from = 24, to = 25, spec = Migration24To25::class),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30),
        AutoMigration(from = 30, to = 31),
        AutoMigration(from = 31, to = 32),
        AutoMigration(from = 32, to = 33),
    ],
    version = 33,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun notificationDao(): NotificationDao

    abstract fun noteDao(): NoteDao

    abstract fun cacheDao(): CacheDao

    abstract fun downloadDao(): DownloadDao

    abstract fun downloadCollectionDao(): DownloadCollectionDao

    abstract fun watchHistoryDao(): WatchHistoryDao

    abstract fun homeFeedCacheDao(): HomeFeedCacheDao

    abstract fun subscriptionGroupDao(): SubscriptionGroupDao

    abstract fun recognitionHistoryDao(): RecognitionHistoryDao

    abstract fun syncLogDao(): SyncLogDao

    abstract fun syncPeerDao(): SyncPeerDao

    abstract fun musicGraphDao(): MusicGraphDao

    companion object {
        @Volatile
        @Suppress("ktlint:standard:property-naming")
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                val instance =
                    databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "flow_database",
                    ).addMigrations(*MIGRATIONS)
                        .fallbackToDestructiveMigration(false)
                        .build()
                INSTANCE = instance
                instance
            }
    }
}
