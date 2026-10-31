package app.zaid.flow.ui.screens.home.chips

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import app.zaid.flow.data.account.AccountFeedRepository
import app.zaid.flow.data.account.AccountPage
import app.zaid.flow.data.local.HomeFeedCacheFilters
import app.zaid.flow.data.local.HomeFeedCacheRepository
import app.zaid.flow.data.local.LikedVideosRepository
import app.zaid.flow.data.local.SubscriptionRepository
import app.zaid.flow.data.local.ViewHistory
import app.zaid.flow.data.local.dao.VideoDao
import app.zaid.flow.data.model.Playlist
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.recommendation.ChannelMemoryRepository
import app.zaid.flow.data.recommendation.FeedExclusions
import app.zaid.flow.data.recommendation.FlowNeuroEngine
import app.zaid.flow.data.recommendation.GraphSeedInput
import app.zaid.flow.data.recommendation.GraphSeedSelector
import app.zaid.flow.data.recommendation.InterestChip
import app.zaid.flow.data.repository.YouTubeRepository
import app.zaid.flow.data.repository.needsChannelMetadata
import app.zaid.flow.data.stats.VideoStatsRecorder
import app.zaid.flow.data.subscriptions.SubscriptionFeedRepository
import app.zaid.flow.innertube.YouTubeSearchParams
import app.zaid.flow.innertube.pages.renderer.FeedItem
import app.zaid.flow.ui.screens.home.HomeFeedSources
import app.zaid.flow.ui.screens.home.enrichAvatars
import app.zaid.flow.ui.screens.home.filterValid
import app.zaid.flow.ui.screens.home.filterWatched
import app.zaid.flow.ui.screens.home.toResumeVideo
import app.zaid.flow.ui.screens.home.withChannelMetadataFrom
import app.zaid.flow.utils.PerformanceDispatcher
import app.zaid.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** What a chip load needs from the Home feed it sits on. */
internal class ChipFeedContext(
    val filters: suspend () -> HomeFeedCacheFilters,
    val exclusions: suspend () -> FeedExclusions,
    val watched: () -> Set<String>,
)

/**
 * The chips above Home and what each one shows. Nothing here runs for the All chip: a chip's
 * sources are fetched only when it is opened, then kept for its time to live.
 */
class HomeChipFeeds
    @Inject
    constructor(
        private val repository: YouTubeRepository,
        private val feedSources: HomeFeedSources,
        private val subscriptionFeed: SubscriptionFeedRepository,
        private val subscriptions: SubscriptionRepository,
        private val channelMemory: ChannelMemoryRepository,
        private val viewHistory: ViewHistory,
        private val likedVideos: LikedVideosRepository,
        private val videoStats: VideoStatsRecorder,
        private val homeFeedCache: HomeFeedCacheRepository,
        private val videoDao: VideoDao,
        private val accountFeed: AccountFeedRepository,
    ) {
        private val chipsState = MutableStateFlow(HomeChipsState())
        internal val state: StateFlow<HomeChipsState> = chipsState.asStateFlow()

        private val cache = ChipResultCache()
        private lateinit var scope: CoroutineScope
        private lateinit var savedState: SavedStateHandle
        private lateinit var context: ChipFeedContext
        private var io: CoroutineDispatcher = PerformanceDispatcher.diskIO
        private var network: CoroutineDispatcher = PerformanceDispatcher.networkIO
        private var loadJob: Job? = null
        private val enrichPermits = Semaphore(ENRICH_CONCURRENCY)
        private val enrichInFlight = ConcurrentHashMap.newKeySet<String>()
        private val enrichAttempts = ConcurrentHashMap<String, Int>()

        @Volatile
        private var interests: List<InterestChip> = emptyList()

        /** YouTube's chips in its order, and the token that opens each one's feed. */
        @Volatile
        private var chipTitles: List<String> = emptyList()

        @Volatile
        private var chipTokens: Map<String, String> = emptyMap()

        @Volatile
        private var chipsLoadedAt = 0L

        @Volatile
        private var hasMixSeeds = false

        @Volatile
        private var hasWatched = false

        internal fun attach(
            scope: CoroutineScope,
            savedState: SavedStateHandle,
            context: ChipFeedContext,
            io: CoroutineDispatcher = PerformanceDispatcher.diskIO,
            network: CoroutineDispatcher = PerformanceDispatcher.networkIO,
        ) {
            this.scope = scope
            this.savedState = savedState
            this.context = context
            this.io = io
            this.network = network
            chipsState.update { it.copy(selected = savedState[SELECTED_KEY] ?: HomeChip.All.key) }
            refreshChips()
        }

        /** Reads YouTube's own chip bar. Only the chips are fetched here, never a chip's content. */
        fun refreshChips() {
            scope.launch(network) {
                try {
                    // The chip bar is an extra: it waits while a video plays rather than share its data.
                    app.zaid.flow.utils.PlaybackDataGate.awaitNoVideo()
                    loadYouTubeChips(force = false)
                    publishChips()
                    val selected = chipFor(chipsState.value.selected)
                    if (selected != null && selected != HomeChip.All && chipsState.value.feed == null) load(selected, force = false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Log.d(TAG, "Chip refresh failed: ${error.message}")
                }
            }
        }

        private suspend fun loadYouTubeChips(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && chipTitles.isNotEmpty() && now - chipsLoadedAt < CHIPS_TTL_MS) return
            val page = accountFeed.youtubeHome() ?: return
            if (page.chips.isEmpty()) return
            chipTokens = page.chips.associate { it.title to it.token }
            chipTitles = page.chips.map { it.title }
            chipsLoadedAt = now
        }

        fun select(key: String) {
            val chip = chipFor(key) ?: HomeChip.All
            savedState[SELECTED_KEY] = chip.key
            loadJob?.cancel()
            if (chip == HomeChip.All) {
                chipsState.update { it.copy(selected = chip.key, feed = null, isRefreshing = false) }
                publishChips()
                return
            }
            (chip as? HomeChip.Interest)?.let { interest ->
                scope.launch { FlowNeuroEngine.noteSessionTopics(interest.interest.topics.take(HomeChipParams.SESSION_TOPICS)) }
            }
            chipsState.update { it.copy(selected = chip.key, feed = cache.get(chip, System.currentTimeMillis()) ?: ChipFeed.Loading) }
            load(chip, force = false)
        }

        /** Pull to refresh on a chip refreshes that chip alone. */
        fun refreshSelected() {
            val chip = chipFor(chipsState.value.selected) ?: return
            if (chip == HomeChip.All) return
            chipsState.update { it.copy(isRefreshing = true) }
            load(chip, force = true)
        }

        private fun chipFor(key: String): HomeChip? =
            when {
                key == HomeChip.All.key -> HomeChip.All
                key.startsWith(HomeChip.YOUTUBE_KEY_PREFIX) -> HomeChip.YouTube(key.removePrefix(HomeChip.YOUTUBE_KEY_PREFIX))
                else -> null
            }

        private fun publishChips() {
            val now = System.currentTimeMillis()
            chipsState.update { state ->
                val all = visibleChips(chipTitles, emptySet(), selected = state.selected)
                val chips = visibleChips(chipTitles, cache.emptyKeys(all, now), selected = state.selected)
                if (chips.none { it.key == state.selected }) {
                    state.copy(chips = chips, selected = HomeChip.All.key, feed = null)
                } else {
                    state.copy(chips = chips)
                }
            }
        }

        private fun load(
            chip: HomeChip,
            force: Boolean,
        ) {
            val now = System.currentTimeMillis()
            if (!force) {
                cache.get(chip, now)?.let { cached ->
                    chipsState.update { if (it.selected == chip.key) it.copy(feed = cached) else it }
                    return
                }
            }
            loadJob?.cancel()
            loadJob =
                scope.launch(network) {
                    val feed =
                        try {
                            fetch(chip)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            Log.d(TAG, "Chip ${chip.key} failed: ${error.message}")
                            ChipFeed.Failed
                        }
                    if (feed != ChipFeed.Failed) cache.put(chip, feed, System.currentTimeMillis())
                    chipsState.update { if (it.selected == chip.key) it.copy(feed = feed, isRefreshing = false) else it }
                    publishChips()
                }
        }

        private suspend fun fetch(chip: HomeChip): ChipFeed =
            when (chip) {
                HomeChip.All -> ChipFeed.Empty
                is HomeChip.Interest -> videos(interestVideos(chip.interest))
                is HomeChip.YouTube -> youtubeChip(chip)
                HomeChip.NewToYou -> videos(newToYou())
                HomeChip.RecentlyUploaded -> videos(recentlyUploaded())
                HomeChip.Mixes -> mixes().let { if (it.isEmpty()) ChipFeed.Empty else ChipFeed.Mixes(it) }
                HomeChip.Watched -> videos(watchAgainVideos(HomeChipParams.MAX_VIDEOS))
            }

        private fun videos(list: List<Video>): ChipFeed = if (list.isEmpty()) ChipFeed.Empty else ChipFeed.Videos(list)

        private suspend fun userSubs(): Set<String> = subscriptions.getAllSubscriptions().first().mapTo(HashSet()) { it.channelId }

        /** The feed's own hygiene, then the engine's ranking: no Shorts, nothing watched or hidden. */
        private suspend fun ranked(videos: List<Video>): List<Video> {
            val exclusions = context.exclusions()
            val pool =
                videos
                    .distinctBy { it.id }
                    .filterValid()
                    .filter { !it.isShort }
                    .filterWatched(context.watched())
                    .filterNot(exclusions::hidesFromRecommendations)
            return if (pool.isEmpty()) pool else withSubscriptionAvatars(FlowNeuroEngine.rank(pool, userSubs()))
        }

        /** Subscription-store rows carry no avatar; the subscription list already knows each one. */
        private suspend fun withSubscriptionAvatars(videos: List<Video>): List<Video> {
            if (videos.none { it.channelThumbnailUrl.isBlank() }) return videos
            val avatars =
                subscriptions
                    .getAllSubscriptions()
                    .first()
                    .filter { it.channelThumbnail.isNotEmpty() && !ThumbnailUrlResolver.isUnusableChannelAvatar(it.channelThumbnail) }
                    .associate { it.channelId to it.channelThumbnail }
            return videos.enrichAvatars(avatars)
        }

        /**
         * Fills a card the grid has just shown: a Watched video learns its views, upload date and
         * avatar from its id (kept for a week), any other the avatar of its channel. Only cards on
         * screen ask, a few at a time.
         */
        fun enrichVisible(video: Video) {
            val chip = chipFor(chipsState.value.selected) ?: return
            val needsDetails = chip == HomeChip.Watched && video.uploadDate.isBlank()
            val needsChannel = video.needsChannelMetadata()
            if (!needsDetails && !needsChannel) return
            if ((enrichAttempts[video.id] ?: 0) >= ENRICH_ATTEMPTS || !enrichInFlight.add(video.id)) return
            enrichAttempts.merge(video.id, 1, Int::plus)
            scope.launch(network) {
                app.zaid.flow.utils.PlaybackDataGate.awaitNoVideo()
                val enriched =
                    try {
                        enrichPermits.withPermit {
                            if (needsDetails) {
                                repository.refreshVideoMetadata(video)?.also { homeFeedCache.saveVideoMetadata(listOf(it)) }
                            } else {
                                repository.enrichMissingChannelMetadata(listOf(video), limit = 1).firstOrNull()
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        Log.d(TAG, "Filling ${video.id} failed: ${error.message}")
                        null
                    } finally {
                        enrichInFlight.remove(video.id)
                    }
                if (enriched == null || enriched == video) return@launch
                chipsState.update { state ->
                    val feed = state.feed as? ChipFeed.Videos ?: return@update state
                    val updated = ChipFeed.Videos(feed.videos.map { if (it.id == video.id) enriched else it })
                    if (state.selected == chip.key) cache.replace(chip, updated)
                    state.copy(feed = updated)
                }
            }
        }

        /** Metadata the device already holds for these ids: saved playlists and the week's fetches. */
        private suspend fun withStoredMetadata(videos: List<Video>): List<Video> {
            val ids = videos.map { it.id }
            val fetched = runCatching { homeFeedCache.loadVideoMetadata(ids) }.getOrDefault(emptyMap())
            val saved =
                runCatching { videoDao.getVideosByIds(ids) }
                    .getOrDefault(emptyList())
                    .filter { it.uploadDate.isNotBlank() }
                    // A saved row's timestamp is when it was saved, not when it was uploaded.
                    .associate { it.id to it.toDomain().copy(timestamp = 0L) }
            return videos.map { video -> (fetched[video.id] ?: saved[video.id])?.let { video.withDetailsFrom(it) } ?: video }
        }

        private fun Video.withDetailsFrom(known: Video): Video =
            withChannelMetadataFrom(known).copy(
                viewCount = known.viewCount.takeIf { it > 0 } ?: viewCount,
                uploadDate = known.uploadDate.ifBlank { uploadDate },
                timestamp = if (known.uploadDate.isNotBlank()) known.timestamp else timestamp,
                timestampIsExact = if (known.uploadDate.isNotBlank()) known.timestampIsExact else timestampIsExact,
            )

        private suspend fun search(
            queries: List<String>,
            params: String? = null,
        ): List<Video> =
            coroutineScope {
                queries
                    .map { query ->
                        async {
                            withTimeoutOrNull(HomeChipParams.SEARCH_TIMEOUT_MS) {
                                runCatching { repository.searchVideos(query, params = params).first }.getOrDefault(emptyList())
                            }.orEmpty()
                        }
                    }.awaitAll()
                    .flatten()
            }

        private suspend fun related(seedIds: List<String>): List<Video> =
            coroutineScope {
                seedIds
                    .map { id -> async { runCatching { feedSources.relatedVideos(id, context.filters) }.getOrDefault(emptyList()) } }
                    .awaitAll()
                    .flatten()
            }

        private fun GraphSeedInput.asVideo() =
            Video(
                id = id,
                title = title,
                channelName = "",
                channelId = channelId,
                thumbnailUrl = "",
                duration = durationSec,
                viewCount = 0,
                uploadDate = "",
            )

        private suspend fun storedUploads(): List<Video> =
            runCatching { subscriptionFeed.observeFeed().first() }.getOrDefault(emptyList()) +
                runCatching { channelMemory.storedUploads() }.getOrDefault(emptyList())

        private suspend fun interestVideos(interest: InterestChip): List<Video> {
            val history = feedSources.historySeedInputs()
            val clusterOf = FlowNeuroEngine.clusterKeys(history.map { it.asVideo() })
            val seeds =
                GraphSeedSelector
                    .select(history.filter { clusterOf[it.id] == interest.representative }, HomeChipParams.RELATED_SEEDS)
            val stored = storedUploads().filter { !it.isShort }
            val storedClusters = FlowNeuroEngine.clusterKeys(stored)
            val pool =
                search(interestQueries(interest)) +
                    related(seeds) +
                    stored.filter { storedClusters[it.id] == interest.representative }
            return ranked(pool).take(HomeChipParams.MAX_VIDEOS)
        }

        private suspend fun newToYou(): List<Video> {
            val strongSeeds = GraphSeedSelector.select(feedSources.historySeedInputs(), HomeChipParams.RELATED_SEEDS)
            val pool = search(FlowNeuroEngine.explorationQueries(HomeChipParams.QUERIES_PER_INTEREST)) + related(strongSeeds)
            val brain = FlowNeuroEngine.getBrainSnapshot()
            val known =
                buildSet {
                    viewHistory.getRecentVideoHistory(HomeChipParams.HISTORY_LOOKBACK, includeShorts = true).mapTo(this) { it.channelId }
                    addAll(userSubs())
                    addAll(brain.blockedChannels)
                    addAll(brain.suppressedChannels.keys)
                    brain.channelMemory.entries
                        .filterValues { it.rejectedAt > 0L }
                        .keys
                        .let(::addAll)
                    runCatching { channelMemory.remembered() }.getOrDefault(emptyList()).mapTo(this) { it.channelId }
                }
            val unknown = fromUnknownChannels(ranked(pool), known)
            val rejected = FlowNeuroEngine.rejectedByPattern(unknown)
            return unknown.filterNot { it.id in rejected }.take(HomeChipParams.MAX_VIDEOS)
        }

        private suspend fun recentlyUploaded(): List<Video> {
            val now = System.currentTimeMillis()
            val week =
                YouTubeSearchParams.build(
                    contentType = YouTubeSearchParams.ContentType.VIDEO,
                    uploadDate = YouTubeSearchParams.UploadDate.THIS_WEEK,
                )
            val pool = storedUploads() + search(interests.take(2).mapNotNull { interestQueries(it, 1).firstOrNull() }, week)
            return freshnessOrder(ranked(uploadedThisWeek(pool, now)), now).take(HomeChipParams.MAX_VIDEOS)
        }

        /**
         * What YouTube itself shows under one of its home chips, in its order. A feed of videos stays a
         * feed of videos; a feed of Mix playlists becomes the Mixes grid. Only the app's hygiene is
         * applied: no Shorts, nothing the viewer hid.
         */
        private suspend fun youtubeChip(chip: HomeChip.YouTube): ChipFeed {
            var token = chipTokens[chip.title]
            if (token == null) {
                loadYouTubeChips(force = true)
                token = chipTokens[chip.title]
            }
            if (token == null) return ChipFeed.Failed
            // A chip's token can go stale; read the bar again once and retry before giving up.
            var page = accountFeed.youtubeChipPage(token)
            if (page == null) {
                loadYouTubeChips(force = true)
                page = chipTokens[chip.title]?.let { accountFeed.youtubeChipPage(it) } ?: return ChipFeed.Failed
            }
            val items = page.items.toMutableList()
            var next = page.continuation
            var extraPages = 0
            while (items.count { it is FeedItem.VideoItem } < CHIP_MIN_VIDEOS && next != null && extraPages < CHIP_EXTRA_PAGES) {
                page = accountFeed.youtubeChipPage(next) ?: break
                items += page.items
                next = page.continuation
                extraPages++
            }
            val combined = AccountPage(items, next)
            val videos = plain(combined.videos.filter { !it.isShort })
            if (videos.isNotEmpty()) return ChipFeed.Videos(videos)
            val playlists = items.filterIsInstance<FeedItem.PlaylistItem>().map { it.playlist }
            if (playlists.isEmpty()) return ChipFeed.Empty
            return mixesFrom(playlists).let { if (it.isEmpty()) ChipFeed.Empty else ChipFeed.Mixes(it) }
        }

        /** YouTube's Mix playlists (RD + a video id): each plays that video, then what follows it. */
        private suspend fun mixesFrom(playlists: List<Playlist>): List<HomeMix> {
            val exclusions = context.exclusions()
            return coroutineScope {
                playlists
                    .take(YOUTUBE_MIXES_MAX)
                    .map { playlist ->
                        async {
                            val seedId =
                                playlist.id
                                    .removePrefix("RDAMVM")
                                    .removePrefix("RDMM")
                                    .removePrefix("RD")
                            if (seedId.length != VIDEO_ID_LENGTH) return@async null
                            val seed =
                                Video(
                                    id = seedId,
                                    title =
                                        playlist.name
                                            .removePrefix("Mix - ")
                                            .removePrefix("Mix – ")
                                            .removePrefix("Mix: "),
                                    channelName = "",
                                    channelId = "",
                                    thumbnailUrl =
                                        playlist.thumbnailUrl.ifBlank {
                                            ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(
                                                seedId,
                                            )
                                        },
                                    duration = 0,
                                    viewCount = 0,
                                    uploadDate = "",
                                )
                            val next =
                                runCatching { feedSources.relatedVideos(seedId, context.filters) }
                                    .getOrDefault(emptyList())
                                    .filterValid()
                                    .filter { !it.isShort && it.id != seedId }
                                    .filterNot(exclusions::hidesFromRecommendations)
                                    .distinctBy { it.id }
                                    .take(HomeChipParams.MIX_LENGTH - 1)
                            HomeMix(seed, listOf(seed) + next)
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .filter { it.videos.size >= HomeChipParams.MIX_MIN_VIDEOS }
            }
        }

        private suspend fun plain(videos: List<Video>): List<Video> {
            val exclusions = context.exclusions()
            val pool =
                videos
                    .distinctBy { it.id }
                    .filter { !it.isShort && (it.duration > 0 || it.isLive || it.isUpcoming) }
                    .filterNot(exclusions::hidesFromRecommendations)
                    // Streams that have not started go after everything else, in the order YouTube gave.
                    .sortedBy { it.isUpcoming }
            return withSubscriptionAvatars(pool).take(HomeChipParams.MAX_VIDEOS)
        }

        private suspend fun watchAgainVideos(limit: Int): List<Video> {
            val now = System.currentTimeMillis()
            val history = viewHistory.getRecentVideoHistory(HomeChipParams.HISTORY_LOOKBACK, includeShorts = false)
            if (history.isEmpty()) return emptyList()
            val rewatches = HashMap<String, Int>()
            runCatching { videoStats.snapshot() }.getOrNull()?.months?.values?.forEach { month ->
                month.videoViews.forEach { (id, views) -> rewatches.merge(id, views, Int::plus) }
            }
            val taste = FlowNeuroEngine.tasteAffinity(history.map { it.toResumeVideo() })
            val disliked = runCatching { likedVideos.dislikedVideoIds() }.getOrDefault(emptySet())
            val exclusions = context.exclusions()
            val ordered =
                watchAgain(history, rewatches, taste, disliked, now)
                    .asSequence()
                    // History has no upload date; a zero time keeps the card from claiming "now".
                    .map { it.toResumeVideo().copy(timestamp = 0L).withThumbnail() }
                    .filterNot(exclusions::hidesFromRecommendations)
                    .take(limit)
                    .toList()
            return withSubscriptionAvatars(withStoredMetadata(ordered))
        }

        private suspend fun mixSeedCandidates(): List<MixSeedCandidate> {
            val now = System.currentTimeMillis()
            val history =
                viewHistory
                    .getRecentVideoHistory(HomeChipParams.HISTORY_LOOKBACK, includeShorts = false)
                    .filter { GraphSeedSelector.isRealWatch(it.duration / 1000L, it.progressPercentage.toDouble()) }
            val likes = runCatching { likedVideos.getLikedVideosFlow().first() }.getOrDefault(emptyList())
            val videos =
                history.map { it.toResumeVideo() to it.timestamp } +
                    likes.map {
                        Video(
                            id = it.videoId,
                            title = it.title,
                            channelName = it.channelName,
                            channelId = it.channelId.orEmpty(),
                            thumbnailUrl = it.thumbnail,
                            duration = it.durationSeconds,
                            viewCount = 0,
                            uploadDate = "",
                        ) to it.likedAt
                    }
            if (videos.isEmpty()) return emptyList()
            val clusters = FlowNeuroEngine.clusterKeys(videos.map { it.first })
            val likedIds = likes.mapTo(HashSet()) { it.videoId }
            val candidates =
                videos.map { (video, at) ->
                    val liked = video.id in likedIds
                    MixSeedCandidate(
                        // High quality first; the mix card falls back to hqdefault where hq720 is missing.
                        video = video.copy(thumbnailUrl = ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(video.id)),
                        cluster = clusters[video.id] ?: video.channelId,
                        strength = if (liked) LIKED_SEED_STRENGTH else 1.0,
                        at = at,
                        longTerm = liked || now - at > RECENT_SEED_MS,
                    )
                }
            val brain = FlowNeuroEngine.getBrainSnapshot()
            val disliked = runCatching { likedVideos.dislikedVideoIds() }.getOrDefault(emptySet())
            return mixSeeds(candidates, disliked + brain.suppressedVideoIds.keys, brain.blockedChannels + brain.suppressedChannels.keys)
        }

        /**
         * The viewer's own seeds first; when there are too few (a new install, a cleared history),
         * popular videos from a YouTube search fill in so Mixes is never an empty chip.
         */
        private suspend fun mixSeedsWithFallback(): List<MixSeedCandidate> {
            val own = mixSeedCandidates()
            if (own.size >= HomeChipParams.MIXES_MIN) return own
            val params = YouTubeSearchParams.build(contentType = YouTubeSearchParams.ContentType.VIDEO)
            val ownIds = own.mapTo(HashSet()) { it.video.id }
            val extra =
                search(listOf("music mix", "top hits", "chill music", "workout music", "gaming music", "podcast"), params)
                    .filter { !it.isShort && it.duration > 0 && it.channelId.isNotBlank() && it.id !in ownIds }
                    .distinctBy { it.channelId }
                    .take(HomeChipParams.MIXES_MAX - own.size)
                    .map {
                        MixSeedCandidate(
                            video = it.copy(thumbnailUrl = ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(it.id)),
                            cluster = "yt:${it.channelId}",
                            strength = 1.0,
                            at = 0L,
                            longTerm = true,
                        )
                    }
            return own + extra
        }

        private suspend fun mixes(): List<HomeMix> {
            val seeds = mixSeedsWithFallback()
            val exclusions = context.exclusions()
            val disliked = runCatching { likedVideos.dislikedVideoIds() }.getOrDefault(emptySet())
            return coroutineScope {
                seeds
                    .map { seed ->
                        async {
                            val next =
                                runCatching { feedSources.relatedVideos(seed.video.id, context.filters) }
                                    .getOrDefault(emptyList())
                                    .filterValid()
                                    .filter { !it.isShort && it.id != seed.video.id && it.id !in disliked }
                                    .filterNot(exclusions::hidesFromRecommendations)
                                    .distinctBy { it.id }
                                    .take(HomeChipParams.MIX_LENGTH - 1)
                            HomeMix(seed.video, listOf(seed.video) + next)
                        }
                    }.awaitAll()
                    .filter { it.videos.size >= HomeChipParams.MIX_MIN_VIDEOS }
            }
        }

        /** Some stored records lack a thumbnail; every YouTube video has one at a known address. */
        private fun Video.withThumbnail(): Video =
            if (thumbnailUrl.isNotBlank()) this else copy(thumbnailUrl = ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(id))

        private companion object {
            const val TAG = "HomeChipFeeds"
            const val ENRICH_CONCURRENCY = 3
            const val CHIPS_TTL_MS = 10L * 60L * 1000L
            const val CHIP_MIN_VIDEOS = 12
            const val CHIP_EXTRA_PAGES = 2
            const val YOUTUBE_MIXES_MAX = 8
            const val VIDEO_ID_LENGTH = 11

            // A failed fill is tried once more when the card is next shown, then left alone.
            const val ENRICH_ATTEMPTS = 2
            const val LIKED_SEED_STRENGTH = 2.0
            const val SELECTED_KEY = "home_selected_chip"
            const val RECENT_SEED_MS = 14L * 24L * 60L * 60L * 1000L
        }
    }
