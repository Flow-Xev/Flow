package app.zaid.flow.network

import android.content.Context
import android.util.Log
import app.zaid.flow.FlowApplication
import app.zaid.flow.account.AccountIncognito
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/**
 * One place that decides what to do when a network or parsing call fails, so no screen has to.
 *
 * Every protected call goes: primary -> remedy + retry -> backups in learned order -> last good answer.
 *
 *  1. [classify] names the failure (unreadable body, timeout, rate limit, outage, no network, TLS...).
 *  2. A [Remedy] fixes the cause where one exists: drop the data-saver replay cache, back off for the
 *     time a 429 asks for, wait for a dead route to recover.
 *  3. If the primary still fails, named backups run (a different client, a different endpoint).
 *     The order is learned: strategies that rescued this kind of failure before go first and ones
 *     that kept failing drop to the end. The lessons live in SharedPreferences and survive restarts.
 *  4. Last resort: the last good answer for the same request, from disk, marked stale by the caller.
 *
 * Incognito: nothing the user searched is ever written to the last-good store, and none is replayed.
 */
object SelfHealingManager {
    private const val TAG = "SelfHeal"
    private const val PREFS = "self_healing_lessons"
    private const val STALE_DIR = "self_heal_last_good"
    private const val STALE_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    private const val STALE_MAX_FILES = 40
    private const val MAX_BACKOFF_MS = 8_000L

    enum class Failure { UNREADABLE_BODY, TIMEOUT, NO_NETWORK, RATE_LIMITED, SERVER_DOWN, REJECTED, TLS, OTHER }

    /** A named alternative way to get the same answer. */
    class Backup(
        val name: String,
        val block: suspend () -> JsonObject,
    )

    /** Set by the owner of the HTTP clients so a remedy can rebuild them. */
    @Volatile
    var rebuildClients: (() -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun classify(error: Throwable): Failure =
        when {
            error is ResponseException ->
                when (error.response.status.value) {
                    429 -> Failure.RATE_LIMITED
                    in 500..599 -> Failure.SERVER_DOWN
                    in 400..499 -> Failure.REJECTED
                    else -> Failure.OTHER
                }
            error is UnknownHostException -> Failure.NO_NETWORK
            error is SocketTimeoutException || error is io.ktor.client.plugins.HttpRequestTimeoutException -> Failure.TIMEOUT
            error is SSLException -> Failure.TLS
            error is SerializationException || error is io.ktor.client.call.NoTransformationFoundException ||
                error is io.ktor.client.call.DoubleReceiveException -> Failure.UNREADABLE_BODY
            error is IOException -> Failure.TIMEOUT
            error.cause != null && error.cause !== error -> classify(error.cause!!)
            else -> Failure.OTHER
        }

    /**
     * Runs [primary] and heals. [key] identifies the request for the last-good store and must not
     * be reused across different requests. Throws only when every path, including the stale copy, is gone.
     */
    suspend fun runJson(
        operation: String,
        key: String,
        backups: List<Backup> = emptyList(),
        primary: suspend () -> JsonObject,
    ): JsonObject {
        val incognito = AccountIncognito.enabled
        var last: Throwable? = null

        fun ok(
            via: String,
            value: JsonObject,
        ): JsonObject {
            if (via != "primary") learn(operation, last?.let(::classify) ?: Failure.OTHER, via, true)
            if (!incognito) saveLastGood(key, value)
            return value
        }

        try {
            return ok("primary", primary())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e
        }

        val failure = classify(last!!)
        Log.w(TAG, "$operation failed (${failure.name}): ${last.javaClass.simpleName}")
        applyRemedy(failure, last)

        try {
            return ok("retry", primary())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            learn(operation, failure, "retry", false)
            last = e
        }

        for (backup in orderedBackups(operation, failure, backups)) {
            try {
                return ok(backup.name, backup.block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                learn(operation, failure, backup.name, false)
                last = e
            }
        }

        if (!incognito) loadLastGood(key)?.let {
            Log.w(TAG, "$operation: serving last good answer")
            return it
        }
        throw last!!
    }

    /** The cause-specific fix, run before the retry. */
    private suspend fun applyRemedy(
        failure: Failure,
        error: Throwable,
    ) {
        when (failure) {
            Failure.UNREADABLE_BODY -> InnerTubeSaverInterceptor.clear()
            Failure.RATE_LIMITED -> delay(retryAfterMs(error))
            Failure.TIMEOUT, Failure.TLS, Failure.NO_NETWORK -> {
                runCatching { rebuildClients?.invoke() }
                delay(400)
            }
            Failure.SERVER_DOWN -> delay(800)
            Failure.REJECTED, Failure.OTHER -> Unit
        }
    }

    private fun retryAfterMs(error: Throwable): Long {
        val header = (error as? ResponseException)?.response?.headers?.get("Retry-After")?.toLongOrNull()
        return ((header ?: 2L) * 1000L).coerceIn(500L, MAX_BACKOFF_MS)
    }

    // ---- learned order ------------------------------------------------------------------------

    private fun prefs() = FlowApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun lessonKey(
        operation: String,
        failure: Failure,
        strategy: String,
    ) = "$operation|${failure.name}|$strategy"

    /** Score = successes - failures. Higher goes first; ties keep the order the caller wrote. */
    internal fun orderedBackups(
        operation: String,
        failure: Failure,
        backups: List<Backup>,
    ): List<Backup> {
        val p = runCatching { prefs() }.getOrNull() ?: return backups
        return backups.sortedByDescending { p.getInt(lessonKey(operation, failure, it.name), 0) }
    }

    private fun learn(
        operation: String,
        failure: Failure,
        strategy: String,
        success: Boolean,
    ) {
        runCatching {
            val p = prefs()
            val k = lessonKey(operation, failure, strategy)
            val score = (p.getInt(k, 0) + if (success) 1 else -1).coerceIn(-5, 10)
            p.edit().putInt(k, score).apply()
        }
    }

    /** Wipes what the app has learned. Reachable from Settings if the lessons ever go wrong. */
    fun forgetLessons() {
        runCatching { prefs().edit().clear().apply() }
    }

    // ---- last-good backup ---------------------------------------------------------------------

    private fun staleDir() = File(FlowApplication.appContext.cacheDir, STALE_DIR).apply { mkdirs() }

    private fun fileFor(key: String): File {
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(staleDir(), "$name.json")
    }

    private fun saveLastGood(
        key: String,
        value: JsonObject,
    ) {
        runCatching {
            val text = value.toString()
            if (text.length > 1_500_000) return
            fileFor(key).writeText(text)
            staleDir().listFiles()?.sortedByDescending { it.lastModified() }?.drop(STALE_MAX_FILES)?.forEach { it.delete() }
        }
    }

    private fun loadLastGood(key: String): JsonObject? =
        runCatching {
            val f = fileFor(key)
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > STALE_MAX_AGE_MS) return null
            json.parseToJsonElement(f.readText()) as? JsonObject
        }.getOrNull()

    /** Called when incognito turns on: any previously stored answer could reveal what was searched. */
    fun wipeLastGood() {
        runCatching { staleDir().listFiles()?.forEach { it.delete() } }
    }
}
