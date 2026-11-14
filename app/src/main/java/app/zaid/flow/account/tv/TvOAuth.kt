package app.zaid.flow.account.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** What the user has to type on google.com/device to approve this phone as a "YouTube on TV". */
data class TvDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
) {
    /**
     * The verification page with the code already filled in, so a scanned QR code or a tapped link needs no typing.
     * The plain [verificationUrl] stays the fallback for a page that does not take the parameter.
     */
    val completeUrl: String
        get() {
            val separator = if ('?' in verificationUrl) '&' else '?'
            return verificationUrl + separator + "user_code=" + URLEncoder.encode(userCode, "UTF-8")
        }
}

data class TvTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
)

sealed interface TvPollResult {
    data class Success(
        val tokens: TvTokens,
    ) : TvPollResult

    data object Pending : TvPollResult

    data object SlowDown : TvPollResult

    data object Denied : TvPollResult

    data object Expired : TvPollResult

    /** The network or YouTube hiccuped; the code is still good, so the caller keeps polling. */
    data class Transient(
        val message: String,
    ) : TvPollResult

    /** Something that retrying will not fix. */
    data class Failed(
        val message: String,
    ) : TvPollResult
}

/** The outcome of trading a refresh token for a new access token. */
sealed interface TvRefreshResult {
    data class Fresh(
        val tokens: TvTokens,
    ) : TvRefreshResult

    /** Google no longer honours the grant (signed out on google.com/device, password change, revoked): only a new sign-in helps. */
    data object Revoked : TvRefreshResult

    /** No answer worth acting on (offline, a 5xx): the grant may well be fine, so nothing is signed out. */
    data object Unavailable : TvRefreshResult
}

/** Somewhere to keep the scraped OAuth client between launches, so sign-in and refresh still work if youtube.com/tv is slow or unreachable. */
interface TvCredentialCache {
    fun load(): Pair<String, String>?

    fun save(credentials: Pair<String, String>)

    fun clear()
}

/**
 * The same sign-in the YouTube app on a smart TV uses: a short code is shown, the user approves it on
 * google.com/device, and the app receives an OAuth token that the TV client (TVHTML5) accepts.
 *
 * The TV app's OAuth client id and secret are not hard-coded: they are read from the public YouTube TV
 * web app's script (the way SmartTube and yt-dlp's OAuth plugin do it), so a rotation does not break sign-in.
 * The last good pair is cached, every call retries transient failures with a back-off, and an
 * `invalid_client` answer (the pair rotated) re-reads the script once and repeats the call.
 */
class TvOAuth(
    private val cache: TvCredentialCache? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val deviceId = UUID.randomUUID().toString().replace("-", "")
    private val credentialsLock = Mutex()

    @Volatile
    private var credentials: Pair<String, String>? = null

    suspend fun requestCode(): Result<TvDeviceCode> =
        runCatching {
            val obj = withCredentialRetry { (clientId, _) ->
                val body =
                    buildJsonObject {
                        put("client_id", clientId)
                        put("scope", SCOPE)
                        put("device_id", deviceId)
                        put("device_model", "ytlr::")
                    }
                json.parseToJsonElement(postJson("$OAUTH_BASE/device/code", body, allowErrorBody = true)).jsonObject
            }
            obj.str("error")?.let { error("Google refused the sign-in code ($it)") }
            TvDeviceCode(
                deviceCode = obj.str("device_code") ?: error("No device code in the answer"),
                userCode = obj.str("user_code") ?: error("No user code in the answer"),
                verificationUrl = obj.str("verification_url") ?: DEFAULT_VERIFICATION_URL,
                expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.longOrNull?.toInt() ?: 1800,
                intervalSeconds = obj["interval"]?.jsonPrimitive?.longOrNull?.toInt() ?: 5,
            )
        }

    /**
     * One poll. The caller waits [TvDeviceCode.intervalSeconds] between calls and stops on anything but
     * Pending, SlowDown and Transient.
     */
    suspend fun poll(deviceCode: String): TvPollResult =
        try {
            val obj = withCredentialRetry { (clientId, secret) ->
                val body =
                    buildJsonObject {
                        put("client_id", clientId)
                        put("client_secret", secret)
                        put("code", deviceCode)
                        put("grant_type", "http://oauth.net/grant_type/device/1.0")
                    }
                json.parseToJsonElement(postJson("$OAUTH_BASE/token", body, allowErrorBody = true)).jsonObject
            }
            when (val error = obj.str("error")) {
                null -> TvPollResult.Success(obj.toTokens(previousRefresh = null))
                "authorization_pending" -> TvPollResult.Pending
                "slow_down" -> TvPollResult.SlowDown
                "access_denied" -> TvPollResult.Denied
                "expired_token", "invalid_grant" -> TvPollResult.Expired
                else -> TvPollResult.Failed(obj.str("error_description") ?: error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TemporaryFailure) {
            TvPollResult.Transient(e.message ?: "Temporary network problem")
        } catch (e: IOException) {
            TvPollResult.Transient(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            TvPollResult.Failed(e.message ?: e.javaClass.simpleName)
        }

    /** A fresh access token from a stored refresh token, or why there is none. Transient failures are retried here. */
    suspend fun refresh(refreshToken: String): TvRefreshResult =
        try {
            val obj = withCredentialRetry { (clientId, secret) ->
                val body =
                    buildJsonObject {
                        put("client_id", clientId)
                        put("client_secret", secret)
                        put("refresh_token", refreshToken)
                        put("grant_type", "refresh_token")
                    }
                json.parseToJsonElement(postJson("$OAUTH_BASE/token", body, allowErrorBody = true)).jsonObject
            }
            when (obj.str("error")) {
                null -> TvRefreshResult.Fresh(obj.toTokens(previousRefresh = refreshToken))
                // The grant itself is gone.
                "invalid_grant", "access_denied", "unauthorized_client" -> TvRefreshResult.Revoked
                else -> TvRefreshResult.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvRefreshResult.Unavailable
        }

    /** Tells Google to drop the grant, so a signed-out phone no longer shows up as an approved TV. Best effort: never throws. */
    suspend fun revoke(token: String) {
        try {
            withContext(Dispatchers.IO) {
                val connection = URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(("token=" + URLEncoder.encode(token, "UTF-8")).toByteArray(Charsets.UTF_8)) }
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Already gone or unreachable; the local sign-out stands either way.
        }
    }

    /**
     * Runs [call] with the OAuth client, retrying network trouble with a back-off. If Google answers
     * `invalid_client` the cached pair is stale: it is dropped, re-read from the TV page, and the call is made once more.
     */
    private suspend fun withCredentialRetry(call: suspend (Pair<String, String>) -> JsonObject): JsonObject {
        var reread = false
        while (true) {
            val answer = retrying { call(credentials()) }
            if (answer.str("error") == "invalid_client" && !reread) {
                reread = true
                forgetCredentials()
                continue
            }
            return answer
        }
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var wait = RETRY_FIRST_DELAY_MS
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = e is IOException || e is TemporaryFailure
                if (!retryable || attempt >= RETRY_ATTEMPTS) throw e
                delay(wait)
                wait *= 2
                attempt++
            }
        }
    }

    private fun JsonObject.toTokens(previousRefresh: String?): TvTokens {
        val access = str("access_token") ?: error("No access token in the answer")
        val refresh = str("refresh_token") ?: previousRefresh ?: error("No refresh token in the answer")
        val lifetime = this["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        return TvTokens(access, refresh, System.currentTimeMillis() + lifetime * 1000L)
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private suspend fun credentials(): Pair<String, String> {
        credentials?.let { return it }
        return credentialsLock.withLock {
            credentials?.let { return@withLock it }
            val found =
                try {
                    withContext(Dispatchers.IO) { retrying { scrapeCredentials() } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // youtube.com/tv is unreachable or changed shape: the pair that worked last time is the best bet.
                    runCatching { cache?.load() }.getOrNull() ?: throw e
                }
            credentials = found
            runCatching { cache?.save(found) }
            found
        }
    }

    private suspend fun forgetCredentials() {
        credentialsLock.withLock {
            credentials = null
            runCatching { cache?.clear() }
        }
    }

    private fun scrapeCredentials(): Pair<String, String> {
        val page = get("https://www.youtube.com/tv")
        val scriptPath =
            SCRIPT_REGEXES.firstNotNullOfOrNull { it.find(page)?.groupValues?.get(1) }
                ?: error("The YouTube TV page did not name its script")
        val scriptUrl =
            when {
                scriptPath.startsWith("http") -> scriptPath
                scriptPath.startsWith("//") -> "https:$scriptPath"
                else -> "https://www.youtube.com$scriptPath"
            }
        return parseCredentials(get(scriptUrl)) ?: error("The YouTube TV script held no OAuth client")
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", TV_USER_AGENT)
            connection.setRequestProperty("Accept-Language", "en-US")
            val code = connection.responseCode
            if (code == 429 || code >= 500) throw TemporaryFailure("HTTP $code")
            check(code in 200..299) { "HTTP $code" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun postJson(
        url: String,
        body: JsonObject,
        allowErrorBody: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", TV_USER_AGENT)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                // A server-side hiccup or a rate limit is worth another try; the caller backs off.
                if (code == 429 || code >= 500) throw TemporaryFailure("HTTP $code")
                // The token endpoint answers "still waiting" and "denied" with a 4xx and a JSON body.
                check(code in 200..299 || (allowErrorBody && text.isNotBlank())) { "HTTP $code" }
                text
            } finally {
                connection.disconnect()
            }
        }

    /** A failure that another attempt may well get past (HTTP 429 or 5xx). */
    private class TemporaryFailure(
        message: String,
    ) : IOException(message)

    internal companion object {
        const val OAUTH_BASE = "https://www.youtube.com/o/oauth2"
        const val DEFAULT_VERIFICATION_URL = "https://www.google.com/device"
        const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
        const val TV_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        const val RETRY_ATTEMPTS = 3
        const val RETRY_FIRST_DELAY_MS = 800L

        /** How the TV page points at its script; tried in order so a changed attribute order does not break sign-in. */
        val SCRIPT_REGEXES =
            listOf(
                Regex("""<script\s+id="base-js"\s+src="([^"]+)""""),
                Regex("""<script[^>]+id="base-js"[^>]+src="([^"]+)""""),
                Regex("""<script[^>]+src="([^"]+)"[^>]+id="base-js""""),
                Regex("""<script[^>]+src="([^"]*/tv/[^"]*\.js)""""),
            )

        /** The client id is a Google OAuth id; the secret is the next quoted string in the same object literal. */
        private val CLIENT_REGEXES =
            listOf(
                Regex("""clientId:"([-\w]+\.apps\.googleusercontent\.com)",\s*[^"]{1,40}:"([-\w]+)""""),
                Regex("""clientId\s*:\s*"([-\w]+\.apps\.googleusercontent\.com)"\s*,\s*[\w$]{1,40}\s*:\s*"([-\w]+)""""),
                Regex("\"clientId\"\\s*:\\s*\"([-\\w]+\\.apps\\.googleusercontent\\.com)\"\\s*,\\s*\"[^\"]{1,40}\"\\s*:\\s*\"([-\\w]+)\""),
            )

        /** The OAuth client id and secret named in the YouTube TV script, or null when none of the known shapes matches. */
        fun parseCredentials(script: String): Pair<String, String>? =
            CLIENT_REGEXES.firstNotNullOfOrNull { regex ->
                regex.find(script)?.let { it.groupValues[1] to it.groupValues[2] }
            }
    }
}
