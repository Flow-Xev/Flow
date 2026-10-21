package app.zaid.flow.innertube

import android.util.Log
import app.zaid.flow.FlowApplication
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.innertube.models.Context
import app.zaid.flow.innertube.models.MediaInfo
import app.zaid.flow.innertube.models.ReturnYouTubeDislikeResponse
import app.zaid.flow.innertube.models.YouTubeClient
import app.zaid.flow.innertube.models.YouTubeLocale
import app.zaid.flow.innertube.models.body.*
import app.zaid.flow.innertube.models.body.CreateCommentBody
import app.zaid.flow.innertube.models.normalizeYouTubeHostLanguage
import app.zaid.flow.innertube.models.response.NextResponse
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.innertube.utils.parseCookieString
import app.zaid.flow.innertube.utils.sha1
import app.zaid.flow.utils.FlowTrace
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException
import java.net.Proxy
import java.util.*
import kotlin.io.encoding.Base64

private const val TAG = "InnerTube"

/**
 * Provide access to InnerTube endpoints.
 * For making HTTP requests, not parsing response.
 */
class InnerTube {
    private var httpClient = createClient()

    var locale =
        sanitizeLocale(
            YouTubeLocale(
                gl = Locale.getDefault().country,
                hl = Locale.getDefault().toLanguageTag(),
            ),
        )
        set(value) {
            field = sanitizeLocale(value)
        }
    var visitorData: String? = null
        set(value) {
            if (field == value) return
            field = value
            onVisitorDataChanged?.invoke(value)
        }

    /** Every change of identity, including a refused one dropped to null, so it can be persisted. */
    var onVisitorDataChanged: ((String?) -> Unit)? = null
    var dataSyncId: String? = null
    var cookie: String? = null
        set(value) {
            field = value
            cookieMap = if (value == null) emptyMap() else parseCookieString(value)
        }
    private var cookieMap = emptyMap<String, String>()

    /**
     * The secret the SAPISIDHASH is built from. Sign-in accepts a cookie holding only
     * `__Secure-3PAPISID` (same value as `SAPISID`), so the header builders fall back to it
     * instead of silently sending the request without auth.
     */
    private val authSapisid: String?
        get() = (cookieMap["SAPISID"] ?: cookieMap["__Secure-3PAPISID"])?.takeIf { it.isNotBlank() }

    var proxy: Proxy? = null
        set(value) {
            if (field == value) return
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    var proxyAuth: String? = null
        set(value) {
            if (field == value) return
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    var cacheDirectory: java.io.File? = null
        set(value) {
            if (field == value) return
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    var useLoginForBrowse: Boolean = false

    private fun sanitizeLocale(value: YouTubeLocale): YouTubeLocale =
        YouTubeLocale(
            gl = sanitizeCountryCode(value.gl),
            hl = sanitizeLanguageCode(value.hl),
        )

    private fun sanitizeCountryCode(value: String): String {
        val normalized = value.trim().uppercase(Locale.US)
        return if (normalized.matches(Regex("[A-Z]{2}"))) {
            normalized
        } else {
            Locale
                .getDefault()
                .country
                .trim()
                .uppercase(Locale.US)
                .takeIf { it.matches(Regex("[A-Z]{2}")) }
                ?: "US"
        }
    }

    private fun sanitizeLanguageCode(value: String): String = normalizeYouTubeHostLanguage(value)

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient() =
        HttpClient(OkHttp) {
            expectSuccess = true

            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }

            install(ContentEncoding) {
                gzip(0.9F)
                deflate(0.8F)
            }

            // PERFORMANCE OPTIMIZED: Enhanced network configuration
            engine {
                config {
                    // Aggressive connection pool for faster connection reuse
                    connectionPool(
                        okhttp3.ConnectionPool(
                            15, // Increased from 10 - more connections available
                            5, // keepAliveDuration
                            java.util.concurrent.TimeUnit.MINUTES,
                        ),
                    )

                    addInterceptor(app.zaid.flow.network.NetworkLogInterceptor)

                    // Faster timeout configurations - fail fast, retry smart
                    connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)

                    // Enable HTTP/2 for multiplexing (parallel streams on single connection)
                    protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))

                    // Retry on connection failure
                    retryOnConnectionFailure(true)

                    // High concurrency dispatcher
                    dispatcher(
                        okhttp3.Dispatcher().apply {
                            maxRequests = 48
                            maxRequestsPerHost = 8
                        },
                    )

                    // Cache configuration for better performance
                    cache(
                        okhttp3.Cache(
                            directory = cacheDirectory ?: java.io.File(System.getProperty("java.io.tmpdir"), "http_cache"),
                            maxSize = 50L * 1024L * 1024L, // 50 MB
                        ),
                    )
                }

                // Qualified: a bare `proxy` here is the engine config's own, which starts null.
                this@InnerTube.proxy?.let { proxy = it }

                // Fix proxy auth
                proxyAuth?.let { auth ->
                    config {
                        proxyAuthenticator { _, response ->
                            response.request
                                .newBuilder()
                                .header("Proxy-Authorization", auth)
                                .build()
                        }
                    }
                }
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 45000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 30000
            }

            defaultRequest {
                url(YouTubeClient.API_URL_YOUTUBE_MUSIC)
                // Add common headers for better compatibility
                header("Accept", "application/json")
                header("Accept-Language", "en-US,en;q=0.9")
                header("Cache-Control", "no-cache")
            }
        }

    /**
     * Simple retry wrapper for transient IO errors (socket aborts, timeouts).
     * Retries the given block up to [maxAttempts] times with exponential backoff.
     * Cancellation is respected since [delay] will throw if the coroutine is cancelled.
     */
    private suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelay: Long = 500L,
        factor: Double = 2.0,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelay
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                attempt++
                if (attempt >= maxAttempts) {
                    FlowTrace.e(
                        FlowTrace.INNERTUBE,
                        e,
                    ) { "InnerTube request failed after $attempt attempts: ${e.javaClass.simpleName}: ${e.message}" }
                    throw e
                }
                FlowTrace.w(FlowTrace.INNERTUBE, e) {
                    "InnerTube request attempt $attempt/$maxAttempts failed (${e.javaClass.simpleName}: ${e.message}), retrying in $currentDelay ms"
                }
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
    }

    private fun HttpRequestBuilder.ytClient(
        client: YouTubeClient,
        setLogin: Boolean = false,
        apiUrl: String? = null,
    ) {
        val useMainSite = apiUrl != null && apiUrl != YouTubeClient.API_URL_YOUTUBE_MUSIC
        val origin = if (useMainSite) YouTubeClient.ORIGIN_YOUTUBE else YouTubeClient.ORIGIN_YOUTUBE_MUSIC
        val referer = if (useMainSite) YouTubeClient.REFERER_YOUTUBE else YouTubeClient.REFERER_YOUTUBE_MUSIC
        contentType(ContentType.Application.Json)
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", client.clientId)
            append("X-YouTube-Client-Version", client.clientVersion)
            append("X-Origin", origin)
            append("Referer", referer)
            visitorData?.let { append("X-Goog-Visitor-Id", it) }
            if (setLogin && client.loginSupported) {
                cookie?.let { cookie ->
                    append("cookie", cookie)
                    val sapisid = authSapisid ?: return@let
                    val currentTime = System.currentTimeMillis() / 1000
                    val sapisidHash = sha1("$currentTime $sapisid $origin")
                    append("Authorization", "SAPISIDHASH ${currentTime}_$sapisidHash")
                }
            }
        }
        userAgent(client.userAgent)
        parameter("prettyPrint", false)
    }

    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
    ) = withRetry {
        httpClient.post("search") {
            ytClient(client, setLogin = useLoginForBrowse)
            setBody(
                SearchBody(
                    context =
                        client.toContext(
                            locale,
                            visitorData,
                            if (useLoginForBrowse) dataSyncId else null,
                        ),
                    query = query,
                    params = params,
                ),
            )
            parameter("continuation", continuation)
            parameter("ctoken", continuation)
        }
    }

    /**
     * Search the main YouTube site (www.youtube.com), not the music endpoint. Needed to
     * reach renderers the music search omits, e.g. the Shorts shelf.
     */
    suspend fun webSearch(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
        anonymous: Boolean = false,
        includeVisitorData: Boolean = !anonymous,
    ) = withRetry {
        withVisitorDataFallback(includeVisitorData) { requestVisitorData ->
            httpClient.post("https://www.youtube.com/youtubei/v1/search") {
                headers {
                    append("X-YouTube-Client-Name", client.clientId)
                    append("X-YouTube-Client-Version", client.clientVersion)
                    if (anonymous) {
                        append(HttpHeaders.Origin, YouTubeClient.ORIGIN_YOUTUBE)
                        append("Referer", YouTubeClient.ORIGIN_YOUTUBE)
                        append(HttpHeaders.Cookie, "SOCS=CAE=")
                        append(HttpHeaders.AcceptLanguage, "${locale.hl}, en;q=0.9")
                    } else {
                        append("X-Origin", YouTubeClient.ORIGIN_YOUTUBE)
                        append("Referer", YouTubeClient.REFERER_YOUTUBE)
                        // Signed in: the search is made as the account, so YouTube records it in search history.
                        if (isSignedIn) appendAccountAuth(YouTubeClient.ORIGIN_YOUTUBE)
                    }
                    requestVisitorData?.let { append("X-Goog-Visitor-Id", it) }
                }
                contentType(io.ktor.http.ContentType.Application.Json)
                userAgent(client.userAgent)
                parameter("prettyPrint", false)
                setBody(
                    SearchBody(
                        context = client.toContext(locale, requestVisitorData, null),
                        query = query,
                        params = params,
                        continuation = continuation,
                    ),
                )
            }
        }
    }

    /**
     * Typeahead suggestions. Not an InnerTube endpoint — a JSONP array from the suggest host — but
     * it rides the same client so it honours the app's locale, proxy and connection pool.
     */
    suspend fun searchSuggestions(query: String) =
        withRetry {
            httpClient.get("https://suggestqueries-clients6.youtube.com/complete/search") {
                parameter("client", "youtube")
                parameter("ds", "yt")
                parameter("hl", locale.hl)
                parameter("gl", locale.gl)
                parameter("q", query)
                if (isSignedIn) headers { appendAccountAuth(YouTubeClient.ORIGIN_YOUTUBE) }
                userAgent(YouTubeClient.WEB.userAgent)
            }
        }

    private suspend fun webBrowse(
        client: YouTubeClient,
        body: (String?) -> BrowseBody,
    ) = mainSitePost(client, "browse", body = body)

    /** True while a Google account's cookie is applied, so requests can ask for that account's data. */
    val isSignedIn: Boolean
        get() = !cookie.isNullOrBlank()

    /**
     * Adds the signed-in account's cookie and SAPISIDHASH, the way youtube.com itself authenticates.
     * The hash is bound to the origin it is sent from, so it must be www.youtube.com for these calls.
     */
    private fun HeadersBuilder.appendAccountAuth(origin: String) {
        val rawCookie = cookie ?: return
        append("cookie", rawCookie)
        append("X-Goog-AuthUser", "0")
        val sapisid = authSapisid ?: return
        val now = System.currentTimeMillis() / 1000
        append("Authorization", "SAPISIDHASH ${now}_${sha1("$now $sapisid $origin")}")
    }

    /**
     * A browse call made as the signed-in account (home feed, history, subscriptions...). The anonymous
     * visitor id is left off: with the account's cookie the account is the identity, and an unrelated
     * visitor id would pull the response back towards anonymous recommendations.
     */
    suspend fun accountBrowse(
        browseId: String? = null,
        continuation: String? = null,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "browse", login = true) { requestVisitorData ->
        BrowseBody(
            context = client.toContext(locale, requestVisitorData, null),
            browseId = if (continuation == null) browseId else null,
            params = null,
            continuation = continuation,
        )
    }

    private suspend inline fun <reified T> mainSitePost(
        client: YouTubeClient,
        path: String,
        login: Boolean = false,
        crossinline body: (String?) -> T,
    ) = withRetry {
        val asAccount = login || isSignedIn
        withVisitorDataFallback(includeVisitorData = !asAccount) { requestVisitorData ->
            httpClient.post("https://www.youtube.com/youtubei/v1/$path") {
                headers {
                    append("X-YouTube-Client-Name", client.clientId)
                    append("X-YouTube-Client-Version", client.clientVersion)
                    append("X-Origin", YouTubeClient.ORIGIN_YOUTUBE)
                    append("Referer", YouTubeClient.REFERER_YOUTUBE)
                    requestVisitorData?.let { append("X-Goog-Visitor-Id", it) }
                    if (asAccount) appendAccountAuth(YouTubeClient.ORIGIN_YOUTUBE)
                }
                contentType(ContentType.Application.Json)
                userAgent(client.userAgent)
                parameter("prettyPrint", false)
                setBody(body(requestVisitorData))
            }
        }
    }

    /**
     * Search for videos within a specific YouTube channel using the /browse endpoint.
     * Uses the channel's Search Tab (params = "EgZzZWFyY2jyBgQKAloA") with the query as a
     * top-level body field.
     *
     * @param channelId  The channel's browse ID, e.g. "UCxxxxxxxxxxxxxxxxxx"
     * @param query      The search term
     * @param continuation  Pagination token returned from a previous search call
     */
    suspend fun channelSearch(
        client: YouTubeClient,
        channelId: String,
        query: String,
        continuation: String? = null,
    ) = webBrowse(client) { requestVisitorData ->
        BrowseBody(
            context = client.toContext(locale, requestVisitorData, null),
            browseId = if (continuation == null) channelId else null,
            params = if (continuation == null) "EgZzZWFyY2jyBgQKAloA" else null,
            query = if (continuation == null) query else null,
            continuation = continuation,
        )
    }

    /**
     * Browse a YouTube channel tab through the YouTube.com WEB /browse endpoint.
     *
     * Initial calls pass [channelId] and [params]; continuation calls pass only
     * [continuation].
     */
    suspend fun channelBrowse(
        client: YouTubeClient,
        channelId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ) = webBrowse(client) { requestVisitorData ->
        BrowseBody(
            context = client.toContext(locale, requestVisitorData, null),
            browseId = if (continuation == null) channelId else null,
            params = if (continuation == null) params else null,
            continuation = continuation,
        )
    }

    /** The HTML of youtube.com's sound page for a reel (/source/<id>/shorts), which embeds its feed as ytInitialData. */
    suspend fun soundPageHtml(
        client: YouTubeClient,
        url: String,
    ): String =
        httpClient
            .get(url) {
                userAgent(client.userAgent)
                headers {
                    append("Accept-Language", locale.hl)
                    if (isSignedIn) appendAccountAuth(YouTubeClient.ORIGIN_YOUTUBE)
                }
            }.bodyAsText()

    /** What a youtube.com link points at, as the endpoint the site would follow for it. */
    suspend fun resolveUrl(
        client: YouTubeClient,
        url: String,
    ) = mainSitePost(client, "navigation/resolve_url") { requestVisitorData ->
        ResolveUrlBody(context = client.toContext(locale, requestVisitorData, null), url = url)
    }

    private suspend fun <T> withVisitorDataFallback(
        includeVisitorData: Boolean = true,
        block: suspend (String?) -> T,
    ): T {
        val requestVisitorData =
            visitorData?.takeIf { includeVisitorData && it.isNotBlank() }
                ?: return block(null)
        return try {
            block(requestVisitorData)
        } catch (error: ClientRequestException) {
            if (error.response.status != HttpStatusCode.BadRequest) throw error
            val response = block(null)
            if (visitorData == requestVisitorData) {
                visitorData = null
            }
            Log.w(TAG, "InnerTube rejected visitor data; request succeeded without it")
            FlowTrace.w(FlowTrace.INNERTUBE) { "visitor data dropped after a 400 from InnerTube" }
            response
        }
    }

    suspend fun postCommentsBrowse(
        client: YouTubeClient,
        postId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ) = webBrowse(client) { requestVisitorData ->
        BrowseBody(
            context = client.toContext(locale, requestVisitorData, null),
            browseId = if (continuation == null) "FEpost_detail" else null,
            params = if (continuation == null) params else null,
            continuation = continuation,
            canonicalBaseUrl =
                if (continuation == null && params == null) {
                    postId?.let { "/post/$it" }
                } else {
                    null
                },
        )
    }

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String? = null,
        localeOverride: YouTubeLocale? = null,
        apiUrl: String? = null,
    ) = withRetry {
        httpClient.post(apiUrl?.let { "${it}player" } ?: "player") {
            ytClient(client, setLogin = true, apiUrl = apiUrl)
            setBody(
                PlayerBody(
                    context =
                        client.toContext(localeOverride ?: locale, visitorData, dataSyncId).let {
                            if (client.isEmbedded) {
                                it.copy(
                                    thirdParty =
                                        Context.ThirdParty(
                                            embedUrl = "https://www.youtube.com/watch?v=$videoId",
                                        ),
                                )
                            } else {
                                it
                            }
                        },
                    videoId = videoId,
                    playlistId = playlistId,
                    playbackContext =
                        if (client.useSignatureTimestamp && signatureTimestamp != null) {
                            PlayerBody.PlaybackContext(
                                PlayerBody.PlaybackContext.ContentPlaybackContext(
                                    signatureTimestamp,
                                ),
                            )
                        } else {
                            null
                        },
                    serviceIntegrityDimensions = poToken?.let { PlayerBody.ServiceIntegrityDimensions(it) },
                ),
            )
        }
    }

    /**
     * The attested main-site player request behind the SABR path.
     *
     * [client] must be a web-like client served from `www.youtube.com/youtubei/v1` — WEB or MWEB.
     * The Android/iOS clients live on the gapis host with a different header set and do not belong
     * here.
     */
    suspend fun playerWeb(
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        visitorData: String?,
        locale: YouTubeLocale,
        cpn: String?,
        reloadToken: String? = null,
        client: YouTubeClient = YouTubeClient.WEB,
        login: Boolean = false,
    ) = withRetry {
        httpClient.post("https://www.youtube.com/youtubei/v1/player") {
            headers {
                // Members-only videos only play for the signed-in account that holds the membership.
                if (login) {
                    cookie?.let { cookie ->
                        append("cookie", cookie)
                        authSapisid?.let { sapisid ->
                            val now = System.currentTimeMillis() / 1000
                            append("Authorization", "SAPISIDHASH ${now}_${sha1("$now $sapisid https://www.youtube.com")}")
                        }
                    }
                }
                append("X-Goog-Api-Format-Version", "1")
                append("X-YouTube-Client-Name", client.clientId)
                append("X-YouTube-Client-Version", client.clientVersion)
                append("X-Origin", "https://www.youtube.com")
                append("Referer", "https://www.youtube.com/")
                visitorData?.let { append("X-Goog-Visitor-Id", it) }
            }
            contentType(ContentType.Application.Json)
            userAgent(client.userAgent)
            parameter("prettyPrint", false)
            setBody(
                PlayerBody(
                    context = client.toContext(locale, visitorData, null),
                    videoId = videoId,
                    playlistId = null,
                    playbackContext =
                        if (signatureTimestamp != null || reloadToken != null) {
                            PlayerBody.PlaybackContext(
                                contentPlaybackContext =
                                    signatureTimestamp?.let {
                                        PlayerBody.PlaybackContext.ContentPlaybackContext(
                                            signatureTimestamp = it,
                                            referer = "https://www.youtube.com/watch?v=$videoId",
                                            vis = 0,
                                            splay = false,
                                            lactMilliseconds = "-1",
                                            html5Preference = "HTML5_PREF_WANTS",
                                        )
                                    },
                                reloadPlaybackContext =
                                    reloadToken?.let {
                                        PlayerBody.PlaybackContext.ReloadPlaybackContext(
                                            PlayerBody.PlaybackContext.ReloadPlaybackContext.ReloadPlaybackParams(it),
                                        )
                                    },
                            )
                        } else {
                            null
                        },
                    serviceIntegrityDimensions = poToken?.let { PlayerBody.ServiceIntegrityDimensions(it) },
                    contentCheckOk = true,
                    racyCheckOk = true,
                    cpn = cpn,
                ),
            )
        }
    }

    suspend fun registerPlayback(
        url: String,
        cpn: String,
        playlistId: String?,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ) = httpClient.get(url) {
        ytClient(client, true)
        parameter("ver", "2")
        parameter("c", client.clientName)
        parameter("cpn", cpn)

        if (playlistId != null) {
            parameter("list", playlistId)
            parameter("referrer", "https://music.youtube.com/playlist?list=$playlistId")
        }
    }

    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
        formData: BrowseBody.FormData? = null,
    ) = withRetry {
        httpClient.post("browse") {
            ytClient(client, setLogin = setLogin || useLoginForBrowse)
            setBody(
                BrowseBody(
                    context =
                        client.toContext(
                            locale,
                            visitorData,
                            if (setLogin || useLoginForBrowse) dataSyncId else null,
                        ),
                    browseId = browseId,
                    params = params,
                    continuation = continuation,
                    formData = formData,
                ),
            )
        }
    }

    suspend fun reel(
        client: YouTubeClient,
        sequenceParams: String,
        login: Boolean = false,
    ) = mainSitePost(client, "reel/reel_watch_sequence", login = login) { requestVisitorData ->
        ReelBody(
            context = client.toContext(locale, requestVisitorData, null),
            sequenceParams = sequenceParams,
        )
    }

    /**
     * One reel's overlay. Only the id is required: the WEB overlay is complete without a signature
     * timestamp or the reel's own `playerParams`, which matter only to the inline player response
     * this never asks for.
     */
    suspend fun reelItemWatch(
        client: YouTubeClient,
        videoId: String,
        playerParams: String? = null,
        disablePlayerResponse: Boolean = true,
    ) = mainSitePost(client, "reel/reel_item_watch") { requestVisitorData ->
        ReelItemWatchBody(
            context = client.toContext(locale, requestVisitorData, null),
            playerRequest = ReelItemWatchBody.PlayerRequest(videoId = videoId, params = playerParams),
            disablePlayerResponse = disablePlayerResponse,
        )
    }

    suspend fun next(
        client: YouTubeClient,
        videoId: String?,
        playlistId: String?,
        playlistSetVideoId: String?,
        index: Int?,
        params: String?,
        continuation: String? = null,
    ) = withRetry {
        httpClient.post("next") {
            ytClient(client, setLogin = true)
            setBody(
                NextBody(
                    context = client.toContext(locale, visitorData, dataSyncId),
                    videoId = videoId,
                    playlistId = playlistId,
                    playlistSetVideoId = playlistSetVideoId,
                    index = index,
                    params = params,
                    continuation = continuation,
                ),
            )
        }
    }

    private fun HttpRequestBuilder.webYouTubeHeaders(client: YouTubeClient) {
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", client.clientId)
            append("X-YouTube-Client-Version", client.clientVersion)
            append("X-Origin", "https://www.youtube.com")
            append("Referer", "https://www.youtube.com/")
            // Signed in: the account is the identity (comment tokens, live chat, history); otherwise anonymous.
            if (isSignedIn) appendAccountAuth(YouTubeClient.ORIGIN_YOUTUBE) else visitorData?.let { append("X-Goog-Visitor-Id", it) }
        }
        contentType(ContentType.Application.Json)
        userAgent(client.userAgent)
        parameter("prettyPrint", false)
    }

    suspend fun nextForLiveChat(videoId: String) =
        withRetry {
            val client = YouTubeClient.WEB
            httpClient.post("https://www.youtube.com/youtubei/v1/next") {
                webYouTubeHeaders(client)
                setBody(
                    NextBody(
                        context = client.toContext(locale, if (isSignedIn) null else visitorData, null),
                        videoId = videoId,
                        playlistId = null,
                        playlistSetVideoId = null,
                        index = null,
                        params = null,
                        continuation = null,
                    ),
                )
            }
        }

    /** Live viewer count and like text for a stream on air (`updated_metadata`). */
    suspend fun updatedMetadata(videoId: String) =
        withRetry {
            val client = YouTubeClient.WEB
            httpClient.post("https://www.youtube.com/youtubei/v1/updated_metadata") {
                webYouTubeHeaders(client)
                setBody(
                    NextBody(
                        context = client.toContext(locale, if (isSignedIn) null else visitorData, null),
                        videoId = videoId,
                        playlistId = null,
                        playlistSetVideoId = null,
                        index = null,
                        params = null,
                        continuation = null,
                    ),
                )
            }
        }

    /**
     * The watch page for [videoId] or one of its continuations, on the main site.
     *
     * The comment section only exists on the web watch response, so this posts to the same host
     * and headers as [nextForLiveChat] rather than the music API the default request points at.
     */
    suspend fun nextWatch(
        videoId: String? = null,
        continuation: String? = null,
    ) = withRetry {
        val client = YouTubeClient.WEB
        httpClient.post("https://www.youtube.com/youtubei/v1/next") {
            webYouTubeHeaders(client)
            setBody(
                NextBody(
                    context = client.toContext(locale, if (isSignedIn) null else visitorData, null),
                    videoId = videoId,
                    playlistId = null,
                    playlistSetVideoId = null,
                    index = null,
                    params = null,
                    continuation = continuation,
                ),
            )
        }
    }

    suspend fun getLiveChat(
        continuation: String,
        offsetMs: Long? = null,
    ) = withRetry {
        val client = YouTubeClient.WEB
        httpClient.post("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat") {
            webYouTubeHeaders(client)
            setBody(
                GetLiveChatBody(
                    context = client.toContext(locale, if (isSignedIn) null else visitorData, null),
                    continuation = continuation,
                    currentPlayerState =
                        offsetMs?.let {
                            GetLiveChatBody.CurrentPlayerState(playerOffsetMs = it.toString())
                        },
                ),
            )
        }
    }

    /** The recorded chat of a finished stream or premiere; [offsetMs] is the video position it should follow. */
    suspend fun getLiveChatReplay(
        continuation: String,
        offsetMs: Long? = null,
    ) = withRetry {
        val client = YouTubeClient.WEB
        httpClient.post("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat_replay") {
            webYouTubeHeaders(client)
            setBody(
                GetLiveChatBody(
                    context = client.toContext(locale, if (isSignedIn) null else visitorData, null),
                    continuation = continuation,
                    currentPlayerState =
                        offsetMs?.let {
                            GetLiveChatBody.CurrentPlayerState(playerOffsetMs = it.toString())
                        },
                ),
            )
        }
    }

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
    ) = withRetry {
        httpClient.post("music/get_search_suggestions") {
            ytClient(client, setLogin = true)
            setBody(
                GetSearchSuggestionsBody(
                    context = client.toContext(locale, visitorData, null),
                    input = input,
                ),
            )
        }
    }

    suspend fun getQueue(
        client: YouTubeClient,
        videoIds: List<String>?,
        playlistId: String?,
    ) = withRetry {
        httpClient.post("music/get_queue") {
            ytClient(client, setLogin = true)
            setBody(
                GetQueueBody(
                    context = client.toContext(locale, visitorData, null),
                    videoIds = videoIds,
                    playlistId = playlistId,
                ),
            )
        }
    }

    suspend fun getTranscript(
        client: YouTubeClient,
        videoId: String,
    ) = httpClient.post("https://music.youtube.com/youtubei/v1/get_transcript") {
        parameter("key", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3")
        headers {
            append("Content-Type", "application/json")
        }
        setBody(
            GetTranscriptBody(
                context = client.toContext(locale, null, null),
                params = Base64.Default.encode("\n${11.toChar()}$videoId".toByteArray()),
            ),
        )
    }

    suspend fun getSwJsData() = httpClient.get("https://music.youtube.com/sw.js_data")

    suspend fun accountMenu(client: YouTubeClient) =
        httpClient.post("account/account_menu") {
            ytClient(client, setLogin = true)
            setBody(AccountMenuBody(client.toContext(locale, visitorData, dataSyncId)))
        }

    suspend fun likeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = httpClient.post("like/like") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.VideoTarget(videoId),
            ),
        )
    }

    suspend fun unlikeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = httpClient.post("like/removelike") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.VideoTarget(videoId),
            ),
        )
    }

    suspend fun dislikeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = httpClient.post("like/dislike") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.VideoTarget(videoId),
            ),
        )
    }

    /**
     * Sends one of the player's own stats pings (`videostatsPlaybackUrl` / `videostatsWatchtimeUrl`) as
     * the signed-in account, which is what adds a video to that account's watch history and feeds
     * its recommendations. [extra] carries the per-ping fields (position, segment, state).
     */
    suspend fun pingPlaybackStats(
        url: String,
        cpn: String,
        extra: Map<String, String> = emptyMap(),
        client: YouTubeClient = YouTubeClient.WEB,
    ) = httpClient.get(url) {
        // Same as youtube.com itself: the account's cookie, the site's origin, no anonymous visitor id.
        headers {
            append("Referer", YouTubeClient.REFERER_YOUTUBE)
            append("Origin", YouTubeClient.ORIGIN_YOUTUBE)
            appendAccountAuth(YouTubeClient.ORIGIN_YOUTUBE)
        }
        userAgent(client.userAgent)
        parameter("ver", "2")
        parameter("c", client.clientName)
        parameter("cpn", cpn)
        extra.forEach { (key, value) -> parameter(key, value) }
    }

    /** Like, dislike or clear a rating ([path] is like/like, like/dislike or like/removelike) as the account, on youtube.com. */
    suspend fun accountRate(
        path: String,
        videoId: String,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, path, login = true) { requestVisitorData ->
        LikeBody(
            context = client.toContext(locale, requestVisitorData, null),
            target = LikeBody.Target.VideoTarget(videoId),
        )
    }

    suspend fun accountCreateComment(
        params: String,
        text: String,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "comment/create_comment", login = true) { v ->
        CreateCommentBody(
            context = client.toContext(locale, v, null),
            commentText = text,
            createCommentParams = params,
        )
    }

    suspend fun accountCreateReply(
        params: String,
        text: String,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "comment/create_comment_reply", login = true) { v ->
        app.zaid.flow.innertube.models.body.CreateReplyBody(
            context = client.toContext(locale, v, null),
            commentText = text,
            createReplyParams = params,
        )
    }

    /** Saves new text for one of the account's own comments. [params] is the token YouTube gave for that comment. */
    suspend fun accountUpdateComment(
        params: String,
        text: String,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "comment/update_comment", login = true) { v ->
        app.zaid.flow.innertube.models.body.UpdateCommentBody(
            context = client.toContext(locale, v, null),
            commentText = text,
            updateCommentParams = params,
        )
    }

    /** Runs a comment action token from a comment's menu; deleting one of the account's own comments is one of them. */
    suspend fun accountCommentAction(
        token: String,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "comment/perform_comment_action", login = true) { v ->
        app.zaid.flow.innertube.models.body.CommentActionBody(
            context = client.toContext(locale, v, null),
            actions = listOf(token),
        )
    }

    /** Posts a chat message as the account; [params] comes from the chat's own send button. */
    suspend fun accountSendLiveChatMessage(
        params: String,
        text: String,
        clientMessageId: String,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "live_chat/send_message", login = true) { v ->
        app.zaid.flow.innertube.models.body.SendLiveChatMessageBody(
            context = client.toContext(locale, v, null),
            params = params,
            richMessage =
                app.zaid.flow.innertube.models.body.SendLiveChatMessageBody.RichMessage(
                    listOf(
                        app.zaid.flow.innertube.models.body.SendLiveChatMessageBody
                            .Segment(text),
                    ),
                ),
            clientMessageId = clientMessageId,
        )
    }

    suspend fun accountGetAddToPlaylist(
        videoId: String,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "playlist/get_add_to_playlist", login = true) { v ->
        app.zaid.flow.innertube.models.body.GetAddToPlaylistBody(
            context = client.toContext(locale, v, null),
            videoIds = listOf(videoId),
        )
    }

    suspend fun accountEditPlaylist(
        playlistId: String,
        action: app.zaid.flow.innertube.models.body.AccountPlaylistAction,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "browse/edit_playlist", login = true) { v ->
        app.zaid.flow.innertube.models.body.AccountEditPlaylistBody(
            context = client.toContext(locale, v, null),
            playlistId = playlistId.removePrefix("VL"),
            actions = listOf(action),
        )
    }

    suspend fun accountCreatePlaylist(
        title: String,
        videoId: String,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "playlist/create", login = true) { v ->
        app.zaid.flow.innertube.models.body.CreatePlaylistBody(
            context = client.toContext(locale, v, null),
            title = title,
            videoIds = listOf(videoId),
        )
    }

    suspend fun accountModifyChannelPreference(
        params: String,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "notification/modify_channel_preference", login = true) { v ->
        app.zaid.flow.innertube.models.body.ModifyChannelPreferenceBody(
            context = client.toContext(locale, v, null),
            params = params,
        )
    }

    suspend fun accountSubscribe(
        channelId: String,
        subscribe: Boolean,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, if (subscribe) "subscription/subscribe" else "subscription/unsubscribe", login = true) { v ->
        SubscribeBody(channelIds = listOf(channelId), context = client.toContext(locale, v, null))
    }

    suspend fun accountFeedback(
        token: String,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "feedback", login = true) { v ->
        app.zaid.flow.innertube.models.body.FeedbackBody(
            context = client.toContext(locale, v, null),
            feedbackTokens = listOf(token),
        )
    }

    /** A /player response as the account (for its playback-tracking URLs); no streams are used from it. */
    suspend fun accountPlayer(
        videoId: String,
        signatureTimestamp: Int?,
        client: YouTubeClient = YouTubeClient.WEB,
    ) = mainSitePost(client, "player", login = true) { requestVisitorData ->
        PlayerBody(
            context = client.toContext(locale, requestVisitorData, null),
            videoId = videoId,
            playlistId = null,
            // Without a signatureTimestamp the response is UNPLAYABLE and has no tracking URLs.
            playbackContext =
                signatureTimestamp?.let {
                    PlayerBody.PlaybackContext(
                        PlayerBody.PlaybackContext.ContentPlaybackContext(
                            signatureTimestamp = it,
                            referer = "https://www.youtube.com/watch?v=$videoId",
                        ),
                    )
                },
        )
    }

    suspend fun createComment(
        client: YouTubeClient,
        videoId: String,
        text: String,
    ) = httpClient.post("comment/create_comment") {
        ytClient(client, setLogin = true)
        setBody(
            CreateCommentBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                commentText = text,
            ),
        )
    }

    suspend fun subscribeChannel(
        client: YouTubeClient,
        channelId: String,
    ) = httpClient.post("subscription/subscribe") {
        ytClient(client, setLogin = true)
        setBody(
            SubscribeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                channelIds = listOf(channelId),
            ),
        )
    }

    suspend fun unsubscribeChannel(
        client: YouTubeClient,
        channelId: String,
    ) = httpClient.post("subscription/unsubscribe") {
        ytClient(client, setLogin = true)
        setBody(
            SubscribeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                channelIds = listOf(channelId),
            ),
        )
    }

    suspend fun likePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("like/like") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.PlaylistTarget(playlistId),
            ),
        )
    }

    suspend fun unlikePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("like/removelike") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.PlaylistTarget(playlistId),
            ),
        )
    }

    suspend fun addToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions =
                    listOf(
                        Action.AddVideoAction(addedVideoId = videoId),
                    ),
            ),
        )
    }

    suspend fun addPlaylistToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        addPlaylistId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions =
                    listOf(
                        Action.AddPlaylistAction(addedFullListId = addPlaylistId),
                    ),
            ),
        )
    }

    suspend fun removeFromPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions =
                    listOf(
                        Action.RemoveVideoAction(
                            removedVideoId = videoId,
                            setVideoId = setVideoId,
                        ),
                    ),
            ),
        )
    }

    suspend fun moveSongPlaylist(
        client: YouTubeClient,
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
                actions =
                    listOf(
                        Action.MoveVideoAction(
                            movedSetVideoIdSuccessor = successorSetVideoId,
                            setVideoId = setVideoId,
                        ),
                    ),
            ),
        )
    }

    suspend fun createPlaylist(
        client: YouTubeClient,
        title: String,
    ) = httpClient.post("playlist/create") {
        ytClient(client, true)
        setBody(
            CreatePlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                title = title,
            ),
        )
    }

    suspend fun renamePlaylist(
        client: YouTubeClient,
        playlistId: String,
        name: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
                actions =
                    listOf(
                        Action.RenamePlaylistAction(
                            playlistName = name,
                        ),
                    ),
            ),
        )
    }

    suspend fun getUploadCustomThumbnailLink(
        client: YouTubeClient,
        contentLength: Int,
    ) = httpClient.post("https://music.youtube.com/playlist_image_upload/playlist_custom_thumbnail") {
        ytClient(client, setLogin = true)
        headers {
            append("X-Goog-Upload-Command", "start")
            append("X-Goog-Upload-Protocol", "resumable")
            append("X-Goog-Upload-Header-Content-Length", contentLength.toString())
        }
    }

    suspend fun uploadCustomThumbnail(
        client: YouTubeClient,
        uploadId: String,
        image: ByteArray,
    ) = httpClient.post("https://music.youtube.com/playlist_image_upload/playlist_custom_thumbnail") {
        ytClient(client, setLogin = true)
        parameter("upload_id", uploadId)
        parameter("upload_protocol", "resumable")
        headers {
            append("X-Goog-Upload-Command", "upload, finalize")
            append("X-Goog-Upload-Offset", "0")
        }
        setBody(image)
    }

    suspend fun setThumbnailPlaylist(
        client: YouTubeClient,
        playlistId: String,
        blobId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
                actions =
                    listOf(
                        Action.SetCustomThumbnailAction(
                            addedCustomThumbnail =
                                Action.SetCustomThumbnailAction.AddedCustomThumbnail(
                                    playlistScottyEncryptedBlobId = blobId,
                                ),
                        ),
                    ),
            ),
        )
    }

    suspend fun removeThumbnailPlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
                actions =
                    listOf(
                        Action.RemoveCustomThumbnailAction(),
                    ),
            ),
        )
    }

    suspend fun deletePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("playlist/delete") {
        println("deleting $playlistId")
        ytClient(client, setLogin = true)
        setBody(
            PlaylistDeleteBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
            ),
        )
    }

    suspend fun returnYouTubeDislike(videoId: String) =
        httpClient.get("https://returnyoutubedislikeapi.com/Votes?videoId=$videoId") {
            contentType(ContentType.Application.Json)
        }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> =
        runCatching {
            val response = next(client = YouTubeClient.WEB, videoId, null, null, null, null, null).body<NextResponse>()
            val playerResponse =
                player(
                    client = YouTubeClient.ANDROID,
                    videoId = videoId,
                    playlistId = null,
                    signatureTimestamp = null,
                ).body<PlayerResponse>()

            val baseForInfo =
                response.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find {
                        it?.videoSecondaryInfoRenderer != null
                    }?.videoSecondaryInfoRenderer

            val baseForTitle =
                response.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find {
                        it?.videoPrimaryInfoRenderer != null
                    }?.videoPrimaryInfoRenderer

            val rytdEnabled = PlayerPreferences(FlowApplication.appContext).rytdEnabled.first()
            val returnYouTubeDislikeResponse =
                if (rytdEnabled) {
                    returnYouTubeDislike(videoId).body<ReturnYouTubeDislikeResponse>()
                } else {
                    null
                }

            val bestAudio =
                playerResponse.streamingData
                    ?.adaptiveFormats
                    ?.filter { it.isAudio }
                    ?.maxByOrNull { it.bitrate }
                    ?: playerResponse.streamingData
                        ?.formats
                        ?.filter { it.isAudio }
                        ?.maxByOrNull { it.bitrate }

            return@runCatching MediaInfo(
                videoId = videoId,
                title =
                    baseForTitle
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                author =
                    baseForInfo
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                authorId =
                    baseForInfo
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.navigationEndpoint
                        ?.browseEndpoint
                        ?.browseId,
                authorThumbnail =
                    baseForInfo
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.thumbnail
                        ?.thumbnails
                        ?.find {
                            it.height == 48
                        }?.url
                        ?.replace("s48", "s960"),
                description = baseForInfo?.attributedDescription?.content,
                subscribers =
                    baseForInfo
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.subscriberCountText
                        ?.simpleText
                        ?.split(" ")
                        ?.firstOrNull(),
                uploadDate = baseForTitle?.dateText?.simpleText,
                viewCount = returnYouTubeDislikeResponse?.viewCount,
                like = returnYouTubeDislikeResponse?.likes,
                dislike = returnYouTubeDislikeResponse?.dislikes,
                durationSeconds =
                    playerResponse.videoDetails?.lengthSeconds?.toIntOrNull()
                        ?: bestAudio
                            ?.approxDurationMs
                            ?.toLongOrNull()
                            ?.div(1000L)
                            ?.toInt(),
                mimeType = bestAudio?.mimeType,
                bitrate = bestAudio?.bitrate?.toLong(),
                sampleRate = bestAudio?.audioSampleRate,
                frameRate = bestAudio?.fps,
                width = bestAudio?.width,
                height = bestAudio?.height,
                contentLength = bestAudio?.contentLength.toString(),
                qualityLabel = bestAudio?.qualityLabel,
                videoId_tag = bestAudio?.itag,
            )
        }
}
