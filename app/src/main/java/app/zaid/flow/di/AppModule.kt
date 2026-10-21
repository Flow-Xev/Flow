package app.zaid.flow.di

import android.content.Context
import app.zaid.flow.BuildConfig
import app.zaid.flow.data.localmedia.MediaStoreThumbnailFetcher
import app.zaid.flow.data.recommendation.FlowNeuroEngine
import app.zaid.flow.innertube.InnerTube
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.player.cache.PlayerCacheManager
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import coil3.video.VideoFrameDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import javax.inject.Singleton

/** The image cache folder under the app's cache directory. */
const val IMAGE_CACHE_DIR = "image_cache"

private const val AUTOMATIC_DISK_SHARE = 0.02

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideYouTube(): YouTube = YouTube

    /** The same client the [YouTube] facade uses, so sign-in cookies apply app-wide. */
    @Provides
    @Singleton
    fun provideInnerTube(): InnerTube = YouTube.innerTube

    /** Delegates to the legacy singleton so injected consumers share the one engine the app already runs. */
    @Provides
    @Singleton
    fun provideFlowNeuroEngine(
        @ApplicationContext context: Context,
    ): FlowNeuroEngine = FlowNeuroEngine.getInstance(context)

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                // Coil keeps its own disk cache; the shared client's HTTP cache would hold a second copy of every thumbnail.
                val imageClient = okHttpClient.newBuilder().cache(null).build()
                add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }))
                add(VideoFrameDecoder.Factory())
                // Animated GIFs in comments: the platform decoder on API 28+, Coil's own below that.
                if (android.os.Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
                add(MediaStoreThumbnailFetcher.Factory(context))
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, 0.10)
                    .build()
            }.diskCache {
                // Coil builds this lazily on its first disk access, off the main thread, so the size
                // setting is read there rather than on the cold-start path.
                val limit = PlayerCacheManager.configuredLimits(context).artworkBytes
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve(IMAGE_CACHE_DIR).toOkioPath())
                    .apply { if (limit != null) maxSizeBytes(limit) else maxSizePercent(AUTOMATIC_DISK_SHARE) }
                    .build()
            }.crossfade(true)
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .build()
}
