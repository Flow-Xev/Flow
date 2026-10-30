package app.zaid.flow.ui.screens.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.ui.components.shared.FlowFilterChip
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.graphics.Color
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Games on the Home row before the "All" button. */
internal const val PLAYABLES_ROW_SIZE = 5
private const val FEED_TTL_MS = 6 * 60 * 60 * 1000L
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

internal data class Playable(
    val id: String,
    val title: String,
    val thumb: String,
    val channel: String,
    val desc: String,
) {
    val url: String get() = "https://www.youtube.com/playables/$id"
}

/** Global UI switches so the chip, the feed row and the host can talk without extra plumbing. */
internal object PlayablesNav {
    var showAll by mutableStateOf(false)
    var playing by mutableStateOf<Playable?>(null)

    /** Download progress 0..1 per game id while a download runs. */
    val progress = mutableStateMapOf<String, Float>()
}

/** Everything the app remembers about games lives in its own storage: prefs plus filesDir/games. */
internal class PlayableStore(private val ctx: Context) {
    /** Same id the account manager derives from the Google session, so every account keeps its own games data. */
    private val acct: String? =
        YouTube.cookie?.takeIf { it.isNotBlank() }?.let { c ->
            val key = Regex("(?:^|;\\s*)__Secure-3PAPISID=([^;]+)").find(c)?.groupValues?.get(1)
                ?: Regex("(?:^|;\\s*)SAPISID=([^;]+)").find(c)?.groupValues?.get(1)
            key?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(8).joinToString("") { b -> "%02x".format(b) } }
        }
    private val sp = ctx.getSharedPreferences(if (acct == null) "flow_playables" else "flow_playables_$acct", Context.MODE_PRIVATE).also { new ->
        // Data saved before accounts were separated moves to the first signed-in account, once.
        val old = ctx.getSharedPreferences("flow_playables", Context.MODE_PRIVATE)
        if (acct != null && new.all.isEmpty() && !old.getBoolean("claimed", false) && old.all.isNotEmpty()) {
            val e = new.edit()
            old.all.forEach { (k, v) ->
                when (v) { is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); is Boolean -> e.putBoolean(k, v); is String -> e.putString(k, v) }
            }
            e.apply(); old.edit().putBoolean("claimed", true).apply()
        }
    }
    /** Account name shown nowhere but kept so a signed-in session is clearly the owner of this data. */
    val signedIn: Boolean get() = acct != null
    val dir: File get() = File(ctx.filesDir, "games").apply { mkdirs() }

    fun plays(id: String) = sp.getInt("p_$id", 0)
    fun addPlay(id: String) = sp.edit().putInt("p_$id", plays(id) + 1).putLong("t_$id", System.currentTimeMillis()).apply()
    fun timeMs(id: String) = sp.getLong("ms_$id", 0L)
    fun addTime(id: String, ms: Long) { if (ms > 0) sp.edit().putLong("ms_$id", timeMs(id) + ms).apply() }
    fun lastPlayed(id: String) = sp.getLong("t_$id", 0L)
    /** The one game this account opened most recently, or null if none was played yet. */
    fun recentId(): String? = sp.all.filterKeys { it.startsWith("t_") }.maxByOrNull { it.value as Long }?.key?.removePrefix("t_")
    fun liked(id: String) = sp.getBoolean("l_$id", false)
    fun likes(id: String) = sp.getInt("lc_$id", 0)
    fun toggleLike(id: String): Boolean {
        val now = !liked(id)
        sp.edit().putBoolean("l_$id", now).putInt("lc_$id", (likes(id) + if (now) 1 else -1).coerceAtLeast(0)).apply()
        return now
    }
    fun best(id: String) = sp.getInt("b_$id", 0)
    fun setBest(id: String, v: Int) { if (v > best(id)) sp.edit().putInt("b_$id", v).apply() }
    fun downloaded(id: String) = sp.getBoolean("d_$id", false)
    fun setDownloaded(id: String, v: Boolean, g: Playable? = null) {
        val e = sp.edit().putBoolean("d_$id", v)
        if (v && g != null) e.putString("m_$id", JSONObject().put("i", g.id).put("t", g.title).put("h", g.thumb).put("c", g.channel).put("d", g.desc).toString()) else if (!v) e.remove("m_$id")
        e.apply()
    }

    /** Games saved for offline, newest metadata kept in the app's own prefs so they outlive the feed. */
    fun downloadedGames(): List<Playable> =
        sp.all.filterKeys { it.startsWith("m_") }.values.mapNotNull {
            runCatching { JSONObject(it as String).let { o -> Playable(o.getString("i"), o.getString("t"), o.optString("h"), o.optString("c"), o.optString("d")) } }.getOrNull()
        }

    fun sizeOf(id: String): Long = File(dir, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun comments(id: String): List<String> =
        runCatching { JSONArray(sp.getString("c_$id", "[]")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
    fun addComment(id: String, text: String) =
        sp.edit().putString("c_$id", JSONArray(listOf(text) + comments(id)).toString()).apply()

    fun feed(): List<Playable>? =
        runCatching {
            val a = JSONArray(sp.getString("feed", null) ?: return null)
            List(a.length()) { i -> a.getJSONObject(i).let { Playable(it.getString("i"), it.getString("t"), it.optString("h"), it.optString("c"), it.optString("d")) } }
        }.getOrNull()
    fun feedFresh() = System.currentTimeMillis() - sp.getLong("feed_at", 0) < FEED_TTL_MS
    fun saveFeed(list: List<Playable>) =
        sp.edit().putString("feed", JSONArray(list.map { JSONObject().put("i", it.id).put("t", it.title).put("h", it.thumb).put("c", it.channel).put("d", it.desc) }).toString())
            .putLong("feed_at", System.currentTimeMillis()).apply()

    fun fileFor(id: String, url: String): File =
        File(File(dir, id).apply { mkdirs() }, MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })

    fun deleteGame(id: String) { File(dir, id).deleteRecursively(); setDownloaded(id, false) }
}

internal object PlayablesRepo {
    private val idRx = Regex("/playables/([A-Za-z0-9_-]{8,})")

    /** The signed-in account's own Playables feed (so it is personal), re-ranked by what you play and like. */
    suspend fun load(store: PlayableStore): List<Playable> =
        withContext(Dispatchers.IO) {
            val cached = store.feed()
            val list =
                if (cached != null && store.feedFresh()) {
                    cached
                } else {
                    runCatching { fetch() }.getOrNull()?.takeIf { it.isNotEmpty() }?.also(store::saveFeed) ?: cached.orEmpty()
                }
            val recent = store.recentId()
            list.sortedByDescending {
                (if (it.id == recent) 1000 else 0) + store.plays(it.id) * 3 + (if (store.liked(it.id)) 5 else 0) + (if (store.downloaded(it.id)) 2 else 0)
            }
        }

    private fun fetch(): List<Playable> {
        val c = URL("https://www.youtube.com/playables?hl=en").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", MOBILE_UA)
        YouTube.cookie?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Cookie", it) }
        val html = c.inputStream.bufferedReader().use { it.readText() }
        val at = html.indexOf("ytInitialData = ").takeIf { it >= 0 } ?: return emptyList()
        val start = html.indexOf('{', at)
        val root = JSONTokener(html.substring(start)).nextValue()
        val out = LinkedHashMap<String, Playable>()
        walk(root, out)
        return out.values.toList()
    }

    /** Post-order, so the innermost object holding a title and a /playables/ link wins. */
    private fun walk(n: Any?, out: MutableMap<String, Playable>): Boolean {
        var found = false
        when (n) {
            is JSONObject -> {
                n.keys().forEach { if (walk(n.opt(it), out)) found = true }
                if (!found && (n.has("title") || n.has("headline"))) {
                    val id = scan(n, 7) { idRx.containsMatchIn(it) }?.let { idRx.find(it)?.groupValues?.get(1) }
                    val title = text(n.opt("title") ?: n.opt("headline"))
                    if (id != null && title.isNotBlank()) {
                        val thumb = (scan(n, 7) { it.contains("ytimg") || it.contains("ggpht") || it.contains("googleusercontent") }.orEmpty())
                            .let { if (it.startsWith("//")) "https:$it" else it }
                        val channel = text(n.opt("subtitle") ?: n.opt("shortBylineText") ?: n.opt("longBylineText"))
                        val desc = text(n.opt("description") ?: n.opt("descriptionSnippet"))
                        out.putIfAbsent(id, Playable(id, title, thumb, channel.ifBlank { "YouTube Playables" }, desc))
                        found = true
                    }
                }
            }
            is JSONArray -> for (i in 0 until n.length()) if (walk(n.opt(i), out)) found = true
        }
        return found
    }

    private fun scan(n: Any?, depth: Int, pred: (String) -> Boolean): String? {
        if (depth < 0) return null
        return when (n) {
            is String -> n.takeIf(pred)
            is JSONObject -> n.keys().asSequence().firstNotNullOfOrNull { scan(n.opt(it), depth - 1, pred) }
            is JSONArray -> (0 until n.length()).firstNotNullOfOrNull { scan(n.opt(it), depth - 1, pred) }
            else -> null
        }
    }

    private fun text(x: Any?): String =
        when (x) {
            is String -> x
            is JSONObject ->
                x.optString("simpleText").ifBlank {
                    x.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.getJSONObject(it).optString("text") } }
                        ?: x.optString("content")
                }
            else -> ""
        }
}

/** Small pill laid over the corner of the thumbnail of the most recently played game. */
@Composable
private fun RecentTag(modifier: Modifier = Modifier) {
    Text(
        "Recently played", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.padding(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
internal fun rememberPlayables(): List<Playable> {
    val ctx = LocalContext.current.applicationContext
    val store = remember(YouTube.cookie) { PlayableStore(ctx) }
    return produceState(emptyList<Playable>(), PlayablesNav.playing == null, YouTube.cookie) { value = PlayablesRepo.load(store) }.value
}

// ───────────────────────── Home row ─────────────────────────

@Composable
internal fun PlayablesRow(modifier: Modifier = Modifier) {
    val games = rememberPlayables()
    if (games.isEmpty()) return
    val store = remember(YouTube.cookie) { PlayableStore(LocalContext.current.applicationContext) }
    val recentId = store.recentId()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Playables", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { PlayablesNav.showAll = true }) { Text("All") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(games.take(PLAYABLES_ROW_SIZE), key = { it.id }) { g ->
                Column(Modifier.width(112.dp).clickable { PlayablesNav.playing = g }) {
                    Box {
                        AsyncImage(
                            model = g.thumb, contentDescription = g.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        if (g.id == recentId) RecentTag(Modifier.align(Alignment.BottomStart))
                    }
                    Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    Text("▶ ${store.plays(g.id)} · ♥ ${store.likes(g.id)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ───────────────────────── Host: full list + player ─────────────────────────

@Composable
internal fun PlayablesHost() {
    if (PlayablesNav.showAll) PlayablesListDialog()
    PlayablesNav.playing?.let { GamePlayerDialog(it, onClose = { PlayablesNav.playing = null }) }
}

@Composable
private fun PlayablesListDialog() {
    val ctx = LocalContext.current.applicationContext
    val store = remember(YouTube.cookie) { PlayableStore(ctx) }
    val games = rememberPlayables()
    var tab by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val recentId = store.recentId()
    val shown = if (tab == 0) games else games.filter { store.downloaded(it.id) }
    Dialog(onDismissRequest = { PlayablesNav.showAll = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ PlayablesNav.showAll = false }) { Icon(Icons.Filled.Close, "Close") }
                Text("Playables", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowFilterChip("All", tab == 0, { tab = 0 })
                FlowFilterChip("Downloaded", tab == 1, { tab = 1 })
            }
            LazyVerticalGrid(
                GridCells.Adaptive(150.dp), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.id }) { g ->
                    val dl = tick.let { store.downloaded(g.id) }
                    val isRecent = g.id == recentId
                    Column(Modifier.clickable { PlayablesNav.playing = g }) {
                        Box {
                            AsyncImage(
                                g.thumb, g.title, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            if (isRecent) RecentTag(Modifier.align(Alignment.BottomStart))
                            val pr = PlayablesNav.progress[g.id]
                            if (pr != null) androidx.compose.material3.CircularProgressIndicator({ pr }, Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp), strokeWidth = 2.dp)
                            else IconButton(
                                onClick = {
                                    if (dl) { store.deleteGame(g.id); tick++ } else scope.launch { downloadGame(store, g); tick++ }
                                },
                                modifier = Modifier.align(Alignment.TopEnd).size(40.dp),
                            ) { Icon(if (dl) Icons.Filled.CheckCircle else Icons.Outlined.Download, if (dl) "Remove download" else "Download") }
                        }
                        Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                        Text("▶ ${store.plays(g.id)} · ♥ ${store.likes(g.id)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ───────────────────────── Offline cache ─────────────────────────

private val assetRx = Regex("""(?:src|href)=["']([^"']+\.(?:js|css|png|jpg|jpeg|webp|svg|woff2?|json|mp3|ogg|wasm|data))["']""")

private fun fetchBytes(url: String, ua: String = MOBILE_UA): Pair<ByteArray, String>? =
    runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Encoding", "gzip")
        if (c.responseCode != 200 || c.contentLengthLong > MAX_ASSET_BYTES) return null
        val raw = if (c.contentEncoding == "gzip") java.util.zip.GZIPInputStream(c.inputStream) else c.inputStream
        raw.use { it.readBytes() } to (c.contentType ?: "application/octet-stream")
    }.getOrNull()

private const val MAX_ASSET_BYTES = 3_000_000
private const val MAX_GAME_BYTES = 25_000_000L

/** Saves the game shell and its linked assets (gzip, 4 in parallel, size-capped) into the app's own storage. */
private suspend fun downloadGame(store: PlayableStore, g: Playable) {
    if (PlayablesNav.progress.containsKey(g.id)) return
    PlayablesNav.progress[g.id] = 0f
    try {
        withContext(Dispatchers.IO) {
            fun save(url: String, b: ByteArray, t: String) {
                store.fileFor(g.id, url).apply { writeBytes(b) }
                File(store.fileFor(g.id, url).path + ".type").writeText(t)
            }
            val (html, type) = fetchBytes(g.url) ?: return@withContext
            save(g.url, html, type)
            val refs = assetRx.findAll(String(html)).map { it.groupValues[1] }.distinct().take(60).toList()
            val gate = Semaphore(4)
            var done = 0
            var total = html.size.toLong()
            coroutineScope {
                refs.map { ref ->
                    async {
                        gate.withPermit {
                            val abs = runCatching { URL(URL(g.url), ref).toString() }.getOrNull()
                            if (abs != null && total < MAX_GAME_BYTES && !store.fileFor(g.id, abs).exists()) {
                                fetchBytes(abs)?.let { (b, t) -> save(abs, b, t); total += b.size }
                            }
                            PlayablesNav.progress[g.id] = (++done).toFloat() / refs.size.coerceAtLeast(1)
                        }
                    }
                }.awaitAll()
            }
            store.setDownloaded(g.id, true, g)
        }
    } finally {
        PlayablesNav.progress.remove(g.id)
    }
}

private fun fmtSize(b: Long) = if (b >= 1_000_000) "%.1f MB".format(b / 1e6) else "${b / 1000} KB"

/** Library → Downloads → Games: everything saved for offline play, with size and delete. Hidden when empty. */
@Composable
fun DownloadedGamesShelf(modifier: Modifier = Modifier) {
    val store = remember(YouTube.cookie) { PlayableStore(LocalContext.current.applicationContext) }
    var tick by remember { mutableIntStateOf(0) }
    val busy = PlayablesNav.progress.size
    val games = remember(tick, busy, PlayablesNav.playing) { store.downloadedGames() }
    if (games.isEmpty() && busy == 0) return
    val recent = store.recentId()
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Games", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(games, key = { it.id }) { g ->
                Column(Modifier.width(112.dp).clickable { PlayablesNav.playing = g }) {
                    Box {
                        AsyncImage(
                            g.thumb, g.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        if (g.id == recent) RecentTag(Modifier.align(Alignment.BottomStart))
                        IconButton({ store.deleteGame(g.id); tick++ }, Modifier.align(Alignment.TopEnd).size(36.dp)) { Icon(Icons.Filled.Close, "Delete download") }
                    }
                    Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    Text(fmtSize(store.sizeOf(g.id)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ───────────────────────── Game player ─────────────────────────

private enum class Outcome { NONE, WIN, LOSE }

private val gameHosts = listOf("youtube.com", "google.com", "gstatic.com", "ytimg.com", "googleusercontent.com", "ggpht.com", "googlevideo.com", "googleapis.com", "youtube-nocookie.com")
private fun trusted(host: String?) = host != null && gameHosts.any { host == it || host.endsWith(".$it") }

private fun isOnline(c: Context): Boolean =
    (c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).let { cm ->
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GamePlayerDialog(game: Playable, onClose: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val store = remember(YouTube.cookie) { PlayableStore(ctx) }
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf(Outcome.NONE) }
    var liked by remember { mutableStateOf(store.liked(game.id)) }
    var likeCount by remember { mutableIntStateOf(store.likes(game.id)) }
    var sheet by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(store.best(game.id)) }
    var comments by remember { mutableStateOf(store.comments(game.id)) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var downloaded by remember { mutableStateOf(store.downloaded(game.id)) }
    val dlProgress = PlayablesNav.progress[game.id]
    val loading = progress < 100 && error == null
    val offlineNow = remember(reload) { !isOnline(ctx) }

    LaunchedEffect(game.id, reload) { store.addPlay(game.id) }
    LaunchedEffect(dlProgress) { if (dlProgress == null) downloaded = store.downloaded(game.id) }

    // Time spent in this game, saved to the account's own stats when the player closes.
    DisposableEffect(game.id) {
        val t0 = System.currentTimeMillis()
        onDispose { store.addTime(game.id, System.currentTimeMillis() - t0) }
    }
    // The game never keeps running behind the app or behind the details sheet.
    val owner = LocalLifecycleOwner.current
    var appPaused by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) appPaused = true else if (e == Lifecycle.Event.ON_RESUME) appPaused = false
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
    val paused = appPaused || sheet || outcome != Outcome.NONE || confirmExit
    LaunchedEffect(paused, web) { web?.let { if (paused) it.onPause() else it.onResume() } }

    // Screen stays on while playing; fullscreen hides the system bars.
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(window, immersive) {
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        if (immersive) {
            c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(WindowInsetsCompat.Type.systemBars())
        } else c?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun share() {
        ctx.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${game.title} · ${game.url}"), null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    fun toggleDownload() {
        if (downloaded) { store.deleteGame(game.id); downloaded = false } else scope.launch { downloadGame(store, game); downloaded = store.downloaded(game.id) }
    }

    // Back: leave fullscreen first, then ask before quitting so progress is not lost by accident.
    Dialog(
        onDismissRequest = { if (immersive) immersive = false else confirmExit = true },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).let { if (immersive) it else it.statusBarsPadding().navigationBarsPadding() }) {
            AnimatedVisibility(!immersive) {
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ confirmExit = true }) { Icon(Icons.Filled.Close, "Exit") }
                        Column(Modifier.weight(1f).clickable { sheet = true }.padding(vertical = 6.dp)) {
                            Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            Text(
                                "${game.channel} · Best $best · tap for details", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton({ liked = store.toggleLike(game.id); likeCount = store.likes(game.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Like", Modifier.size(20.dp))
                            Text(" $likeCount", fontSize = 12.sp)
                        }
                        if (dlProgress != null) {
                            androidx.compose.material3.CircularProgressIndicator({ dlProgress }, Modifier.padding(horizontal = 10.dp).size(22.dp), strokeWidth = 2.dp)
                        } else IconButton(::toggleDownload) {
                            Icon(if (downloaded) Icons.Filled.CheckCircle else Icons.Outlined.Download, if (downloaded) "Remove download" else "Download")
                        }
                        IconButton({ immersive = true }) { Icon(Icons.Filled.Fullscreen, "Fullscreen") }
                    }
                    if (loading) LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                androidx.compose.runtime.key(reload) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            WebView(c).apply {
                                layoutParams = ViewGroup.LayoutParams(-1, -1)
                                setBackgroundColor(0xFF000000.toInt())
                                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                                isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                                isHapticFeedbackEnabled = false
                                setOnLongClickListener { true } // no text-select / context menu while tapping fast
                                val offline = store.downloaded(game.id)
                                settings.apply {
                                    javaScriptEnabled = true; domStorageEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                    // Saved or no-network games read the cache first, so they start instantly and use no data.
                                    cacheMode = if (offline || !isOnline(c)) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
                                    userAgentString = MOBILE_UA
                                    allowFileAccess = false; allowContentAccess = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
                                    setSupportZoom(false); builtInZoomControls = false; textZoom = 100
                                    useWideViewPort = true; loadWithOverviewMode = true
                                }
                                val wv = this
                                CookieManager.getInstance().apply {
                                    setAcceptThirdPartyCookies(wv, true)
                                    // Same Google session as the app, so the game saves progress and score to the account itself.
                                    YouTube.cookie?.takeIf { it.isNotBlank() }?.split(";")?.forEach { k -> setCookie("https://www.youtube.com", k.trim() + "; Domain=.youtube.com; Secure") }
                                    flush()
                                }
                                val startedAt = System.currentTimeMillis()
                                var lastEvent = 0L
                                fun handle(msg: String) {
                                    val m = msg.lowercase()
                                    Regex("\"score\"\\s*:\\s*(\\d+)").find(m)?.groupValues?.get(1)?.toIntOrNull()?.let {
                                        store.setBest(game.id, it); wv.post { best = store.best(game.id) }
                                    }
                                    val now = System.currentTimeMillis()
                                    if (now - startedAt < 5000 || now - lastEvent < 3000) return
                                    val o = when {
                                        listOf("gameover", "game_over", "lose", "lost", "failed", "defeat").any { it in m } -> Outcome.LOSE
                                        listOf("victory", "levelcomplete", "level_complete", "\"win", "won", "success").any { it in m } -> Outcome.WIN
                                        else -> return
                                    }
                                    lastEvent = now
                                    wv.post { outcome = o }
                                }
                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun e(msg: String) = handle(msg.take(4000))
                                }, "Flow")
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(v: WebView, p: Int) { progress = p }
                                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                        if (m.message().length < 600) handle(m.message())
                                        return true
                                    }
                                    override fun onPermissionRequest(r: PermissionRequest) = r.deny() // games never get camera/mic
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) { error = null }
                                    override fun onPageFinished(v: WebView, url: String?) {
                                        v.evaluateJavascript("(function(){if(window.__flow)return;window.__flow=1;addEventListener('message',function(e){try{Flow.e(typeof e.data==='string'?e.data:JSON.stringify(e.data))}catch(x){}})})()", null)
                                    }
                                    // The game stays in the game: other sites never take over the window.
                                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = !trusted(r.url.host)
                                    override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                                        if (r.isForMainFrame) error = e.description?.toString() ?: "Could not load"
                                    }
                                    override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                                        if (r.isForMainFrame && e.statusCode >= 400) error = "Server error ${e.statusCode}"
                                    }
                                    override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                                        error = "The game crashed"; web = null; v.destroy(); return true
                                    }
                                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                                        if (!offline || r.method != "GET") return null
                                        val url = r.url.toString()
                                        if (!url.startsWith("http") || "youtubei" in url) return null
                                        val f = store.fileFor(game.id, url)
                                        val tf = File(f.path + ".type")
                                        if (!f.exists()) {
                                            val (b, t) = fetchBytes(url) ?: return null
                                            f.writeBytes(b); tf.writeText(t)
                                        }
                                        val type = tf.takeIf { it.exists() }?.readText()?.substringBefore(';')
                                            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url)) ?: "application/octet-stream"
                                        return WebResourceResponse(type, "utf-8", f.inputStream())
                                    }
                                }
                                web = this
                                loadUrl(game.url)
                            }
                        },
                        onRelease = { it.stopLoading(); it.removeJavascriptInterface("Flow"); it.destroy(); if (web === it) web = null },
                    )
                }

                // Branded loading cover until the page is up.
                AnimatedVisibility(loading && error == null, exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(game.thumb, game.title, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)))
                            Text(game.title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                            LinearProgressIndicator({ progress / 100f }, Modifier.padding(top = 12.dp).width(140.dp))
                            if (offlineNow && !downloaded) Text("You're offline", color = Color.White.copy(0.7f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }

                error?.let { msg ->
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Couldn't load ${game.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (!downloaded && offlineNow) "You're offline. Download games while online to play them anywhere." else msg,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ error = null; progress = 0; reload++ }) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Text(" Retry") }
                            OutlinedButton(onClose) { Text("Exit") }
                        }
                    }
                }

                if (immersive) {
                    IconButton(
                        { immersive = false },
                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)),
                    ) { Icon(Icons.Filled.FullscreenExit, "Exit fullscreen", tint = Color.White) }
                }
            }
        }

        if (confirmExit) {
            AlertDialog(
                onDismissRequest = { confirmExit = false }, title = { Text("Leave ${game.title}?") },
                text = { Text("Your best score ($best) is saved to this account.") },
                confirmButton = { TextButton(onClose) { Text("Exit") } },
                dismissButton = { TextButton({ confirmExit = false }) { Text("Keep playing") } },
            )
        }

        when (outcome) {
            Outcome.WIN ->
                AlertDialog(
                    onDismissRequest = {}, title = { Text("You won! 🎉") }, text = { Text("Best score: $best. Play again or exit?") },
                    confirmButton = { TextButton({ outcome = Outcome.NONE; progress = 0; reload++ }) { Text("Play") } },
                    dismissButton = { TextButton(onClose) { Text("Exit") } },
                )
            Outcome.LOSE ->
                AlertDialog(
                    onDismissRequest = {}, title = { Text("Want to win then?") }, text = { Text("One more try — you've got this.") },
                    confirmButton = { TextButton({ outcome = Outcome.NONE; progress = 0; reload++ }) { Text("Yes, try again") } },
                    dismissButton = { TextButton(onClose) { Text("Exit") } },
                )
            Outcome.NONE -> Unit
        }

        if (sheet) {
            var draft by remember { mutableStateOf("") }
            var more by remember { mutableStateOf(false) }
            val mins = (store.timeMs(game.id) / 60000).toInt()
            ModalBottomSheet(onDismissRequest = { sheet = false }) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Text(game.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(game.channel, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                        Text(
                            "▶ ${store.plays(game.id)} plays · ♥ $likeCount · Best $best · ${if (mins < 1) "<1" else mins} min played",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ liked = store.toggleLike(game.id); likeCount = store.likes(game.id) }) {
                                Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, Modifier.size(18.dp)); Text(if (liked) " Liked" else " Like")
                            }
                            OutlinedButton(::share) { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Text(" Share") }
                            OutlinedButton(::toggleDownload) {
                                Icon(if (downloaded) Icons.Filled.CheckCircle else Icons.Outlined.Download, null, Modifier.size(18.dp))
                                Text(if (downloaded) " Saved" else " Save")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton({ sheet = false; progress = 0; reload++ }) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Text(" Restart") }
                            OutlinedButton({ sheet = false; immersive = true }) { Icon(Icons.Filled.Fullscreen, null, Modifier.size(18.dp)); Text(" Fullscreen") }
                        }
                        Text(
                            game.desc.ifBlank { "No description available." }, maxLines = if (more) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 16.dp).clickable { more = !more },
                        )
                        if (game.desc.length > 120) Text(if (more) "Show less" else "More", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.clickable { more = !more })
                        Spacer(Modifier.height(16.dp))
                        Text("Comments (${comments.size})", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            draft, { draft = it }, placeholder = { Text("Add a comment…") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            trailingIcon = {
                                IconButton({
                                    if (draft.isNotBlank()) { store.addComment(game.id, draft.trim().take(500)); comments = store.comments(game.id); draft = "" }
                                }) { Icon(Icons.Outlined.PlayArrow, "Post") }
                            },
                        )
                    }
                    items(comments.take(50)) { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
}
