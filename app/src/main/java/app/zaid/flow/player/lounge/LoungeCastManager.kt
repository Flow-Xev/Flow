package app.zaid.flow.player.lounge

import android.content.Context
import app.zaid.flow.FlowApplication
import app.zaid.flow.innertube.update.ClientUpdateSettings
import app.zaid.flow.innertube.update.ClientUpdater
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A TV that has been linked with its "Link with TV code". */
data class LoungeTv(
    val screenId: String,
    val name: String,
    val loungeToken: String,
)

/** What the TV reports about its playback, for the remote screen. */
data class LoungeRemoteState(
    val videoId: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 50,
    val muted: Boolean = false,
    /** When [positionMs] was reported, so the screen can keep counting while playing. */
    val updatedAtMs: Long = 0,
)

/**
 * Casts the way YouTube's own Cast button does: the phone links to the TV's YouTube app (Settings > Link with
 * TV code), then sends it "play this video" over YouTube's Lounge channel. The TV plays the video itself.
 *
 * This is the same unofficial channel the TV remote features of SmartTube and similar apps use; YouTube can change it.
 */
object LoungeCastManager {
    private const val BASE = "https://www.youtube.com/api/lounge"
    private const val PREFS = "lounge_cast"
    private const val KEY_TVS = "tvs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST = "last_tv"
    private const val KEY_MANIFEST = "manifest_profiles"
    private const val KEY_PROFILE = "profile_index"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    private val lock = Mutex()

    private val _savedTvs = MutableStateFlow<List<LoungeTv>>(emptyList())
    val savedTvs: StateFlow<List<LoungeTv>> = _savedTvs.asStateFlow()

    private val _connected = MutableStateFlow<LoungeTv?>(null)
    val connected: StateFlow<LoungeTv?> = _connected.asStateFlow()

    private val _remote = MutableStateFlow(LoungeRemoteState())
    val remote: StateFlow<LoungeRemoteState> = _remote.asStateFlow()

    /** Whether the link to the TV is healthy; the remote shows "Reconnecting" while it is not. */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // D-pad presses go through one queue and one sender, so a held key on a slow network can never pile up
    // requests: the oldest presses are dropped, the newest are kept, order is preserved.
    private val keys = Channel<String>(8, BufferOverflow.DROP_OLDEST)
    private var keySender: Job? = null

    private var learned: List<LoungeProfile> = emptyList()
    private var profileIndex = 0
    private var activeProfile = LoungeProfile.FACTORY.first()
    private var listenJob: Job? = null
    private var lastAid = 0
    private var volumeBeforeMute = 50

    private val listenHttp by lazy { http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }

    private var deviceId: String = UUID.randomUUID().toString()
    @Volatile private var sid: String? = null
    @Volatile private var gsession: String? = null
    private var rid = 1
    private var ofs = 0

    /** Reads the TVs linked earlier. */
    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
        learned = LoungeProfile.parseManifest(prefs.getString(KEY_MANIFEST, null)?.let { """{"lounge":{"profiles":$it}}""" }.orEmpty())
        profileIndex = prefs.getInt(KEY_PROFILE, 0)
        _savedTvs.value =
            runCatching {
                val array = JSONArray(prefs.getString(KEY_TVS, "[]"))
                List(array.length()) { i ->
                    val o = array.getJSONObject(i)
                    LoungeTv(o.getString("id"), o.getString("name"), o.getString("token"))
                }
            }.getOrDefault(emptyList())
    }

    private fun save(
        context: Context,
        tvs: List<LoungeTv>,
    ) {
        _savedTvs.value = tvs
        val array = JSONArray()
        tvs.forEach { array.put(JSONObject().put("id", it.screenId).put("name", it.name).put("token", it.loungeToken)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TVS, array.toString()).apply()
    }

    fun forget(
        context: Context,
        tv: LoungeTv,
    ) {
        save(context, _savedTvs.value.filterNot { it.screenId == tv.screenId })
        if (_connected.value?.screenId == tv.screenId) _connected.value = null
        if (prefs(context).getString(KEY_LAST, null) == tv.screenId) rememberLast(context, null)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rememberLast(
        context: Context,
        screenId: String?,
    ) = prefs(context).edit().apply { if (screenId == null) remove(KEY_LAST) else putString(KEY_LAST, screenId) }.apply()

    /** Best effort: true/false when YouTube says whether the TV is on, null when it cannot tell. */
    private fun isOnline(tv: LoungeTv): Boolean? =
        runCatching {
            val body = post("$BASE/pairing/get_screen_availability", FormBody.Builder().add("lounge_token", tv.loungeToken).build())
            when (JSONObject(body).optJSONArray("screens")?.optJSONObject(0)?.optString("status")) {
                "online" -> true
                "offline" -> false
                else -> null
            }
        }.getOrNull()

    /**
     * Reconnects to the TV used last, like YouTube does: no code, no tap. Does nothing when already connected.
     * Fails quietly when the TV is off or not reachable, and the next call tries again.
     */
    suspend fun resume(context: Context): Result<LoungeTv> =
        withContext(Dispatchers.IO) {
            _connected.value?.let { return@withContext Result.success(it) }
            lock.withLock {
                runCatching {
                    val id = prefs(context).getString(KEY_LAST, null) ?: error("No TV remembered")
                    val saved = _savedTvs.value.firstOrNull { it.screenId == id } ?: error("No TV remembered")
                    if (isOnline(saved) == false) error("The TV is off")
                    val fresh = refreshToken(context, saved)
                    bindResilient(fresh)
                    _connected.value = fresh
                    _remote.value = LoungeRemoteState()
                    startListening(fresh)
                    fresh
                }
            }
        }

    /** Connects to a previously paired TV without starting or requiring video playback. */
    suspend fun connect(context: Context, tv: LoungeTv): Result<LoungeTv> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val fresh = refreshToken(context, tv)
                    if (_connected.value?.screenId != fresh.screenId || sid == null) bindResilient(fresh)
                    _connected.value = fresh
                    rememberLast(context, fresh.screenId)
                    _remote.value = LoungeRemoteState()
                    startListening(fresh)
                    fresh
                }
            }
        }

    /** Links the TV that shows [code] (12 digits, from YouTube on the TV: Settings > Link with TV code). */
    suspend fun pair(
        context: Context,
        code: String,
    ): Result<LoungeTv> =
        withContext(Dispatchers.IO) {
            runCatching {
                val digits = code.filter(Char::isDigit)
                require(digits.length == 12) { "The TV code is 12 digits" }
                val body = post("$BASE/pairing/get_screen", FormBody.Builder().add("pairing_code", digits).build())
                val screen = JSONObject(body).optJSONObject("screen") ?: error("That code was not accepted. It may have expired.")
                val tv =
                    LoungeTv(
                        screenId = screen.getString("screenId"),
                        name = screen.optString("name").ifBlank { "YouTube on TV" },
                        loungeToken = screen.getString("loungeToken"),
                    )
                save(context, _savedTvs.value.filterNot { it.screenId == tv.screenId } + tv)
                tv
            }
        }

    /** Sends [videoId] to [tv], starting at [positionMs]. */
    suspend fun cast(
        context: Context,
        tv: LoungeTv,
        videoId: String,
        positionMs: Long,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val fresh = refreshToken(context, tv)
                    if (_connected.value?.screenId != fresh.screenId || sid == null) bind(fresh)
                    _connected.value = fresh
                    rememberLast(context, fresh.screenId)
                    _remote.value = LoungeRemoteState(videoId = videoId, positionMs = positionMs, updatedAtMs = System.currentTimeMillis())
                    startListening(fresh)
                    command(
                        fresh,
                        "setPlaylist",
                        mapOf(
                            "videoId" to videoId,
                            "videoIds" to videoId,
                            "currentIndex" to "0",
                            "currentTime" to (positionMs / 1000).toString(),
                            "audioOnly" to "false",
                            "listId" to "",
                            "playerParams" to "",
                        ),
                    )
                }
            }
        }

    suspend fun play(): Result<Unit> = simple("play")

    suspend fun pause(): Result<Unit> = simple("pause")

    suspend fun seekTo(positionMs: Long): Result<Unit> {
        _remote.value = _remote.value.copy(positionMs = positionMs, updatedAtMs = System.currentTimeMillis())
        return simple("seekTo", mapOf("newTime" to (positionMs / 1000).toString()))
    }

    /** Presses a D-pad key on the TV's YouTube app: UP, DOWN, LEFT, RIGHT, ENTER or BACK. */
    suspend fun dpad(key: String): Result<Unit> = simple("dpadCommand", mapOf("key" to key))

    /** Queues a D-pad press. Never blocks, never throws; the sender retries and reconnects on its own. */
    fun pressKey(key: String) {
        if (keySender?.isActive != true) {
            keySender =
                scope.launch {
                    for (k in keys) {
                        val result = dpad(k)
                        if (result.isFailure) delay(250)
                    }
                }
        }
        keys.trySend(key)
    }

    suspend fun next(): Result<Unit> = simple("next")

    suspend fun previous(): Result<Unit> = simple("previous")

    /** Sets the TV player's volume, 0 to 100. */
    suspend fun setVolume(volume: Int): Result<Unit> {
        val v = volume.coerceIn(0, 100)
        _remote.value = _remote.value.copy(volume = v, muted = v == 0)
        return simple("setVolume", mapOf("volume" to v.toString()))
    }

    /** Mutes by setting the volume to 0, and restores the earlier level when unmuted. */
    suspend fun toggleMute(): Result<Unit> {
        val now = _remote.value
        return if (now.muted || now.volume == 0) {
            setVolume(volumeBeforeMute.takeIf { it > 0 } ?: 30)
        } else {
            volumeBeforeMute = now.volume
            setVolume(0)
        }
    }

    /** Stops playback on the TV and ends the remote session. */
    suspend fun stop(): Result<Unit> {
        runCatching { rememberLast(FlowApplication.appContext, null) }
        val result = simple("stopVideo")
        listenJob?.cancel()
        listenJob = null
        while (keys.tryReceive().isSuccess) { /* drop queued presses */ }
        _reconnecting.value = false
        _remote.value = LoungeRemoteState()
        sid = null
        gsession = null
        _connected.value = null
        return result
    }

    private suspend fun simple(
        name: String,
        params: Map<String, String> = emptyMap(),
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val tv = _connected.value ?: error("Not connected to a TV")
                    command(tv, name, params)
                }
            }
        }

    private fun refreshToken(
        context: Context,
        tv: LoungeTv,
    ): LoungeTv {
        val body = post("$BASE/pairing/get_lounge_token_batch", FormBody.Builder().add("screen_ids", tv.screenId).build())
        val token =
            JSONObject(body).optJSONArray("screens")?.optJSONObject(0)?.optString("loungeToken")?.takeIf { it.isNotBlank() }
                ?: error("YouTube no longer knows this TV. Link it again with a new code.")
        val updated = tv.copy(loungeToken = token)
        save(context, _savedTvs.value.map { if (it.screenId == tv.screenId) updated else it })
        return updated
    }

    private fun identity(tv: LoungeTv): Array<Pair<String, String>> =
        arrayOf(
            "device" to "REMOTE_CONTROL",
            "id" to deviceId,
            "name" to "Flow",
            "app" to activeProfile.app,
            "mdx-version" to activeProfile.mdx,
            "loungeIdToken" to tv.loungeToken,
            "VER" to activeProfile.ver,
            "CVER" to "1",
        )

    /**
     * Called by the client updater with the manifest text: profiles it announces are tried before the compiled-in
     * ones. Malformed input changes nothing.
     */
    fun learnProfiles(manifestText: String) {
        val found = LoungeProfile.parseManifest(manifestText)
        if (found.isEmpty() || found == learned) return
        learned = found
        val array = JSONArray(found.map { JSONObject().put("app", it.app).put("mdx", it.mdx).put("ver", it.ver) })
        FlowApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANIFEST, array.toString()).putInt(KEY_PROFILE, 0).apply()
        profileIndex = 0
    }

    /**
     * Opens the session. A TV that refuses the current identity (400/404/410 or no session) is tried with the
     * next profile; the one that works is remembered. When every profile fails, the client updater gets one
     * (rate-limited) chance to learn newer ones, and the first error is reported.
     */
    private fun bindResilient(tv: LoungeTv) {
        var first: Exception? = null
        repeat(2) { round ->
            val ladder = LoungeProfile.ladder(learned)
            for (step in ladder.indices) {
                val idx = (profileIndex + step) % ladder.size
                activeProfile = ladder[idx]
                try {
                    bind(tv)
                    if (idx != profileIndex) {
                        profileIndex = idx
                        FlowApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PROFILE, idx).apply()
                        runCatching { ClientUpdateSettings.update { it.withLog("TV remote now uses ${activeProfile.app}/${activeProfile.mdx}") } }
                    }
                    return
                } catch (e: LoungeHttpException) {
                    if (e.code !in intArrayOf(400, 404, 410)) throw e
                    first = first ?: e
                } catch (e: IllegalStateException) {
                    first = first ?: e
                }
            }
            if (round == 0) runCatching { runBlocking { ClientUpdater.emergencyRefresh() } }
        }
        throw first ?: IOException("Could not reach the TV")
    }

    private fun bind(tv: LoungeTv) {
        sid = null
        gsession = null
        rid = 1
        ofs = 0
        lastAid = 0
        val url =
            "$BASE/bc/bind".toUrl(
                *identity(tv),
                "RID" to (rid++).toString(),
                "zx" to UUID.randomUUID().toString().take(12),
            )
        val text = post(url, FormBody.Builder().add("count", "0").build())
        sid = Regex("\"c\"\\s*,\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: error("The TV did not accept the connection")
        gsession = Regex("\"S\"\\s*,\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: error("The TV did not accept the connection")
    }

    private fun command(
        tv0: LoungeTv,
        name: String,
        params: Map<String, String>,
    ) {
        var tv = _connected.value ?: tv0
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                val currentSid = sid ?: run { bindResilient(tv); sid!! }
                val url =
                    "$BASE/bc/bind".toUrl(
                        *identity(tv),
                        "SID" to currentSid,
                        "gsessionid" to gsession.orEmpty(),
                        "RID" to (rid++).toString(),
                        "AID" to "0",
                        "zx" to UUID.randomUUID().toString().take(12),
                    )
                val form = FormBody.Builder().add("count", "1").add("ofs", ofs.toString()).add("req0__sc", name)
                params.forEach { (k, v) -> form.add("req0_$k", v) }
                post(url, form.build())
                ofs++
                _reconnecting.value = false
                return
            } catch (e: LoungeHttpException) {
                last = e
                when (e.code) {
                    // The TV dropped the session: rebuild it.
                    400, 404, 410 -> { sid = null; gsession = null }
                    // The token went stale: fetch a fresh one, then rebuild.
                    401, 403 -> {
                        tv = refreshToken(FlowApplication.appContext, tv).also { _connected.value = it }
                        sid = null
                        gsession = null
                    }
                    // 429 / 5xx: back off and try again.
                    else -> if (e.code == 429 || e.code in 500..599) Thread.sleep(400L * (attempt + 1)) else throw e
                }
            } catch (e: IOException) {
                // Network hiccup: a short pause, and a fresh session if it keeps failing.
                last = e
                Thread.sleep(300L * (attempt + 1))
                if (attempt >= 1) { sid = null; gsession = null }
            }
            _reconnecting.value = true
        }
        _reconnecting.value = true
        throw last ?: IOException("Could not reach the TV")
    }

    private fun startListening(tv: LoungeTv) {
        listenJob?.cancel()
        lastAid = 0
        listenJob =
            scope.launch {
                var failures = 0
                while (isActive && _connected.value?.screenId == tv.screenId) {
                    try {
                        listenOnce(_connected.value ?: tv)
                        failures = 0
                        _reconnecting.value = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _reconnecting.value = true
                        // YouTube forgot the TV: nothing to reconnect to. Otherwise keep trying for about three minutes.
                        if (e.message?.contains("no longer knows") == true || ++failures > 20) {
                            _connected.value = null
                            _reconnecting.value = false
                            return@launch
                        }
                    }
                    delay(minOf(1500L * (failures + 1), 10_000L))
                }
            }
    }

    /** One long-poll: the TV keeps the request open and writes its state changes into it. */
    private suspend fun listenOnce(tv: LoungeTv) {
        // Ask for the current state so the screen is right straight away.
        runCatching { lock.withLock { command(tv, "getNowPlaying", emptyMap()); command(tv, "getVolume", emptyMap()) } }
        val currentSid = sid ?: throw IOException("No session with the TV")
        val url =
            "$BASE/bc/bind".toUrl(
                *identity(tv),
                "SID" to currentSid,
                "gsessionid" to gsession.orEmpty(),
                "RID" to "rpc",
                "AID" to lastAid.toString(),
                "CI" to "0",
                "TYPE" to "xmlhttp",
                "zx" to UUID.randomUUID().toString().take(12),
            )
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Origin", "https://www.youtube.com").get().build()
        listenHttp.newCall(request).execute().use { r ->
            if (!r.isSuccessful) {
                // The session ended on the TV's side: the next command rebuilds it.
                if (r.code == 400 || r.code == 404 || r.code == 410) { sid = null; gsession = null }
                throw IOException("TV answered HTTP ${r.code}")
            }
            val reader = r.body.charStream().buffered()
            while (true) {
                val header = StringBuilder()
                while (true) {
                    val c = reader.read()
                    if (c < 0) return
                    if (c == '\n'.code) break
                    header.append(c.toChar())
                }
                val size = header.toString().trim().toIntOrNull() ?: continue
                val buffer = CharArray(size)
                var got = 0
                while (got < size) {
                    val n = reader.read(buffer, got, size - got)
                    if (n < 0) return
                    got += n
                }
                runCatching { handleFrame(String(buffer)) }
            }
        }
    }

    private fun handleFrame(text: String) {
        val frame = JSONArray(text)
        for (i in 0 until frame.length()) {
            val entry = frame.optJSONArray(i) ?: continue
            lastAid = maxOf(lastAid, entry.optInt(0, lastAid))
            val message = entry.optJSONArray(1) ?: continue
            val payload = message.optJSONObject(1) ?: continue
            val now = System.currentTimeMillis()
            when (message.optString(0)) {
                "onStateChange", "nowPlaying" -> {
                    val state = payload.optString("state")
                    val cur = _remote.value
                    _remote.value =
                        cur.copy(
                            videoId = payload.optString("videoId").ifBlank { cur.videoId },
                            playing = state == "1",
                            buffering = state == "3",
                            positionMs = ((payload.optString("currentTime").toDoubleOrNull() ?: (cur.positionMs / 1000.0)) * 1000).toLong(),
                            durationMs = ((payload.optString("duration").toDoubleOrNull() ?: (cur.durationMs / 1000.0)) * 1000).toLong(),
                            updatedAtMs = now,
                        )
                }
                "onVolumeChanged" -> {
                    val v = payload.optString("volume").toIntOrNull() ?: _remote.value.volume
                    _remote.value = _remote.value.copy(volume = v, muted = payload.optString("muted") == "true" || v == 0)
                }
            }
        }
    }

    private class LoungeHttpException(
        val code: Int,
        message: String,
    ) : Exception(message)

    private fun String.toUrl(vararg query: Pair<String, String>): String {
        val builder = okhttp3.HttpUrl.Builder().scheme("https").host("www.youtube.com").addPathSegments("api/lounge/bc/bind")
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun post(
        url: String,
        body: FormBody,
    ): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Origin", "https://www.youtube.com")
                .post(body)
                .build()
        http.newCall(request).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw LoungeHttpException(r.code, "YouTube TV answered HTTP ${r.code}")
            return text
        }
    }
}
