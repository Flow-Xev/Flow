package app.zaid.flow

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import app.zaid.flow.account.AccountManager
import app.zaid.flow.data.local.CONTENT_LANGUAGE_FOLLOW_APP
import app.zaid.flow.data.local.DataSaver
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.SubscriptionRepository
import app.zaid.flow.data.repository.NewPipeDownloader
import app.zaid.flow.data.repository.YouTubeRepository
import app.zaid.flow.discord.DiscordPresenceRuntime
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.models.YouTubeLocale
import app.zaid.flow.innertube.models.normalizeYouTubeHostLanguage
import app.zaid.flow.innertube.pages.NewPipeExtractor
import app.zaid.flow.network.AppProxyManager
import app.zaid.flow.network.VpnStateMonitor
import app.zaid.flow.notification.NotificationHelper
import app.zaid.flow.notification.SubscriptionCheckWorker
import app.zaid.flow.player.quality.AutoQualityBounds
import app.zaid.flow.utils.AppLanguageManager
import app.zaid.flow.utils.FlowCrashHandler
import app.zaid.flow.utils.PerformanceDispatcher
import app.zaid.flow.utils.cipher.PipePipeNsigDecoder
import app.zaid.flow.utils.newPipeContentCountry
import app.zaid.flow.utils.newPipeLocalization
import app.zaid.flow.utils.normalizeYouTubeCountry
import app.zaid.flow.utils.potoken.NewPipePoTokenProvider
import app.zaid.flow.utils.potoken.VisitorIdentityStore
import app.zaid.flow.utils.potoken.WebPoTokenSession
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.security.Security
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class FlowApplication :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var vpnStateMonitor: VpnStateMonitor

    @Inject
    lateinit var visitorIdentityStore: VisitorIdentityStore

    /** Built at launch so a saved sign-in is applied to InnerTube before any feed makes a request. */
    @Inject
    lateinit var accountManager: AccountManager

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    companion object {
        private const val TAG = "FlowApplication"
        private const val CACHE_ENFORCE_DELAY_MS = 8_000L
        private const val ARTWORK_SETTLE_MS = 3_000L
        lateinit var appContext: Context
            private set
    }

    override fun attachBaseContext(base: Context) {
        val selectedLanguage = AppLanguageManager.loadSelectedLanguageTag(base)
        super.attachBaseContext(AppLanguageManager.wrapContext(base, selectedLanguage))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        YouTube.cacheDirectory = cacheDir.resolve("innertube_http_cache")
        // Debug builds only: where raw InnerTube responses are written for inspection.
        if (app.zaid.flow.BuildConfig.DEBUG) {
            app.zaid.flow.data.debug.DebugResponseDump.directory =
                getExternalFilesDir(null)?.resolve("flow-debug")
        }

        DiscordPresenceRuntime.initialize(this, okHttpClient)

        app.zaid.flow.player.network.StreamingNetwork.start(this)

        val playerPreferences = PlayerPreferences(this)

        // Keeps Data Saver readable from code that cannot suspend, such as the track selector's size cap.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            playerPreferences.dataSaverEnabled.collect { DataSaver.enabled = it }
        }

        // Same for Auto's quality floor and ceiling, which the stream pickers read without suspending.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            playerPreferences.autoQualityFloor.collect { AutoQualityBounds.floor = it }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            playerPreferences.autoQualityCeiling.collect { AutoQualityBounds.ceiling = it }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            playerPreferences.shortsAutoQualityFloor.collect { AutoQualityBounds.shortsFloor = it }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            playerPreferences.shortsAutoQualityCeiling.collect { AutoQualityBounds.shortsCeiling = it }
        }

        // Keeps the media caches inside their size settings: applies the stored limits shortly after
        // launch, and again whenever a limit is changed, so a smaller limit frees space immediately
        // rather than at the next restart. Also drops video and songs nobody has played for a while.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            kotlinx.coroutines.delay(CACHE_ENFORCE_DELAY_MS)
            app.zaid.flow.data.local.CacheJanitor
                .sweep(this@FlowApplication)
            combine(
                playerPreferences.mediaCacheSizeMb,
                playerPreferences.musicCacheSizeMb,
                playerPreferences.artworkCacheSizeMb,
            ) { video, music, artwork ->
                app.zaid.flow.data.local.MediaCacheLimits(
                    videoBytes =
                        app.zaid.flow.data.local.MediaCacheSizes
                            .mediaBytes(video),
                    musicBytes =
                        app.zaid.flow.data.local.MediaCacheSizes
                            .mediaBytes(music),
                    artworkBytes =
                        app.zaid.flow.data.local.MediaCacheSizes
                            .artworkBytes(artwork),
                )
            }.distinctUntilChanged().collect { limits ->
                app.zaid.flow.player.cache.PlayerCacheManager
                    .publishLimits(limits)
                runCatching {
                    app.zaid.flow.player.cache.PlayerCacheManager
                        .preload(this@FlowApplication)
                    app.zaid.flow.player.cache.SharedPlayerCacheProvider
                        .enforce(limits)
                }.onFailure { Log.w(TAG, "Could not enforce the cache limits", it) }
            }
        }

        // Upkeep: the limits above are applied at launch and when a setting changes, but a cache only
        // stays inside its ceiling and expires old items if somebody keeps checking. Every few
        // minutes this re-applies the size limit and drops video and songs nobody has played for
        // [PlayerConfig.VIDEO_CACHE_MAX_IDLE_HOURS] / [MUSIC_CACHE_MAX_IDLE_HOURS], and makes sure
        // the image cache is not sitting above its ceiling either.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            kotlinx.coroutines.delay(CACHE_ENFORCE_DELAY_MS * 2)
            while (true) {
                runCatching {
                    val limits =
                        app.zaid.flow.player.cache.PlayerCacheManager
                            .configuredLimits(this@FlowApplication)
                    app.zaid.flow.player.cache.SharedPlayerCacheProvider
                        .enforce(limits)
                    app.zaid.flow.data.local.CacheJanitor
                        .sweep(this@FlowApplication)
                    val artworkLimit = limits.artworkBytes
                    val artwork = coil3.SingletonImageLoader.get(this@FlowApplication).diskCache
                    if (artworkLimit != null && artwork != null && artwork.size > artworkLimit) {
                        // Coil trims itself right after a write; still over a moment later means it fell behind.
                        kotlinx.coroutines.delay(ARTWORK_SETTLE_MS)
                        if (artwork.size > artworkLimit) artwork.clear()
                    }
                }.onFailure { Log.w(TAG, "Cache upkeep failed", it) }
                kotlinx.coroutines.delay(
                    java.util.concurrent.TimeUnit.MINUTES
                        .toMillis(app.zaid.flow.player.config.PlayerConfig.CACHE_UPKEEP_INTERVAL_MINUTES),
                )
            }
        }

        // Injects modern TLS/SSL certificates so OkHttp and Ktor don't crash
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.N_MR1) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Install crash handler for real-time monitoring
        FlowCrashHandler.install(this)
        // Record everything this process logs, at every level, for the Diagnostics screen
        app.zaid.flow.utils.FlowLogger
            .start(this)
        app.zaid.flow.utils.AppEventLogger
            .install(this)

        try {
            // Seeded from the device locale so the very first extraction is already localized; the
            // stored app-language/region preference takes over as soon as it loads below.
            val localization = newPipeLocalization(Locale.getDefault().toLanguageTag())
            NewPipe.init(
                NewPipeDownloader.getInstance(this),
                localization,
                newPipeContentCountry(Locale.getDefault().country),
            )
            YoutubeStreamExtractor.setPoTokenProvider(NewPipePoTokenProvider)
            Log.d(TAG, "NewPipe initialized with ${localization.localizationCode}")
        } catch (e: Exception) {
            // Log error but don't crash the app
            Log.e(TAG, "Failed to initialize NewPipe", e)
        }

        try {
            app.zaid.flow.utils.cipher.CipherDeobfuscator
                .initialize(this)
            Log.d(TAG, "CipherDeobfuscator initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CipherDeobfuscator", e)
        }

        PipePipeNsigDecoder.initialize(this)

        // Initialize notification channels
        NotificationHelper.createNotificationChannels(this)
        Log.d(TAG, "Notification channels created")

        /*
        try {
            // Initialize YoutubeDL
            com.yausername.youtubedl_android.YoutubeDL.getInstance().init(this)
            Log.d(TAG, "YoutubeDL initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YoutubeDL", e)
        }
         */

        // Schedule periodic subscription checks for new videos
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val savedIntervalMinutes = playerPreferences.subscriptionCheckIntervalMinutes.first()
            SubscriptionCheckWorker.schedulePeriodicCheck(
                this@FlowApplication,
                intervalMinutes = savedIntervalMinutes.toLong(),
            )

            // Schedule periodic update checks (every 12 hours) — github flavor only
            if (BuildConfig.UPDATER_ENABLED) {
                app.zaid.flow.notification.UpdateCheckWorker
                    .schedulePeriodicCheck(this@FlowApplication)
            }
        }

        Log.d(TAG, "Workers scheduled successfully")

        // Fetch and cache visitor data for the lifetime of the install.
        // The X-Goog-Visitor-Id header prevents YouTube from returning empty
        // search results on tablets and fresh Android 16 installs (Issue #223).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var nsigWarmed = false
            playerPreferences.proxyConfig
                .distinctUntilChanged()
                .flatMapLatest { config ->
                    if (config.watchesVpn()) {
                        vpnStateMonitor.vpnActive().map { vpnActive -> config to vpnActive }
                    } else {
                        flowOf(config to false)
                    }
                }.collectLatest { (proxyConfig, vpnActive) ->
                    applyProxyConfig(proxyConfig, vpnActive)
                    // Ordered after the first proxy application so the warm-up honours it. Resolving
                    // the remote n-decoder player id is a round trip that the first video of a session
                    // would otherwise pay on its path to first frame; it is persisted for 24h, so on
                    // most launches this is only a disk read.
                    if (!nsigWarmed) {
                        nsigWarmed = true
                        PipePipeNsigDecoder.warmUp()
                    }
                }
        }

        YouTube.onVisitorDataChanged = visitorIdentityStore::save
        WebPoTokenSession.bindIdentityStore(visitorIdentityStore)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val restored = visitorIdentityStore.restore()
                if (restored != null) {
                    YouTube.visitorData = restored
                    Log.d(TAG, "visitorData restored")
                } else {
                    YouTube
                        .visitorData()
                        .onSuccess { data ->
                            if (!data.isNullOrEmpty()) {
                                YouTube.visitorData = data
                                Log.d(TAG, "visitorData fetched")
                            }
                        }.onFailure { e ->
                            Log.w(TAG, "visitorData fetch failed: ${e.message}")
                        }
                }
            } catch (e: Exception) {
                Log.w(TAG, "visitorData init error: ${e.message}")
            }
            try {
                WebPoTokenSession.prewarm()
            } catch (e: Exception) {
                Log.w(TAG, "WebPoTokenSession prewarm failed: ${e.message}")
            }
            // A cold player script is a 3 MB download the first web-client extraction would
            // otherwise wait on.
            app.zaid.flow.utils.cipher.CipherDeobfuscator
                .ensureSignatureTimestamp()
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(
                playerPreferences.appLanguage,
                playerPreferences.contentLanguage,
                playerPreferences.trendingRegion,
            ) { appLanguage, contentLanguage, region ->
                val language = if (contentLanguage == CONTENT_LANGUAGE_FOLLOW_APP) appLanguage else contentLanguage
                YouTubeLocale(gl = normalizeYouTubeCountry(region), hl = normalizeYouTubeHostLanguage(language))
            }.collectLatest { newLocale ->
                YouTube.locale = newLocale
                NewPipe.setupLocalization(
                    newPipeLocalization(newLocale.hl),
                    ContentCountry(newLocale.gl),
                )
                Log.d(TAG, "Dynamic YouTube Locale updated: gl=${newLocale.gl}, hl=${newLocale.hl}")
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var lastRegion: String? = null
            playerPreferences.trendingRegion.collectLatest { region ->
                if (lastRegion != null && lastRegion != region) {
                    Log.d(TAG, "Trending region changed from $lastRegion to $region. Invalidate visitor data.")
                    YouTube.visitorData = null

                    YouTube
                        .visitorData()
                        .onSuccess { data ->
                            if (!data.isNullOrEmpty()) {
                                YouTube.visitorData = data
                                Log.d(TAG, "Fresh visitorData fetched for region: $region")
                            }
                        }.onFailure { e ->
                            Log.w(TAG, "Failed to fetch fresh visitorData: ${e.message}")
                        }
                }
                lastRegion = region
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = SubscriptionRepository.getInstance(this@FlowApplication)
                val youtubeRepository = YouTubeRepository.getInstance(playerPreferences)
                val repaired =
                    repository.repairVideoThumbnailSubscriptions { channelId ->
                        // Startup's own fetches hold the InnerTube connections for a while; nothing waits on this.
                        withTimeoutOrNull(20_000L) {
                            youtubeRepository.fetchChannelAvatarById(channelId)
                        }.orEmpty()
                    }
                if (repaired > 0) {
                    Log.i(TAG, "Repaired $repaired subscription thumbnails")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Subscription thumbnail repair failed: ${e.message}")
            }
        }
    }

    private fun applyProxyConfig(
        config: app.zaid.flow.network.AppProxyConfig,
        vpnActive: Boolean,
    ) {
        AppProxyManager.update(config, vpnActive)
        YouTube.proxy = AppProxyManager.currentProxy()
        YouTube.proxyAuth = AppProxyManager.currentHttpProxyAuthorizationHeader()
        NewPipeExtractor.invalidateClient()
    }

    override fun onTerminate() {
        DiscordPresenceRuntime.shutdown()
        super.onTerminate()
        // Clean up performance dispatcher resources
        PerformanceDispatcher.shutdown()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        FlowCrashHandler.recordPhase("memory", "FlowApplication.onLowMemory")
        releaseVolatileMemory()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        FlowCrashHandler.recordPhase("memory", "FlowApplication.onTrimMemory level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            releaseVolatileMemory()
        }
    }

    private fun releaseVolatileMemory() {
        if (::imageLoader.isInitialized) {
            imageLoader.memoryCache?.clear()
        }
        if (::okHttpClient.isInitialized) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                okHttpClient.connectionPool.evictAll()
            }
        }
    }
}
