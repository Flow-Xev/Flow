package app.zaid.flow.data.account

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.zaid.flow.data.model.Video
import app.zaid.flow.innertube.pages.isLoggedInResponse
import app.zaid.flow.innertube.pages.renderer.FeedItem
import app.zaid.flow.utils.HomeFeedLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Reads the signed-in account's real youtube.com Home by loading it in a hidden WebView (the account's
 * cookie applied) and extracting what the page holds: the page's own `ytInitialData` goes through the
 * same parser as the InnerTube feed. Returns null on any failure so callers fall back to the
 * InnerTube request.
 *
 * It is the last source Home asks (after WEB and MWEB), so it is built to be quick and to move little data:
 *  - **Lean mode** (the default) lets only the page document through. Every sub-request (scripts,
 *    styles, images, fonts, media, trackers) is answered locally with an empty 404. The document
 *    already carries `ytInitialData` inline, so nothing else is needed, and the page is read the
 *    moment that value exists instead of after a fixed wait.
 *  - **Full mode** is the fallback if lean mode ever yields nothing (for instance YouTube moving the
 *    data out of the document): the page runs its own scripts and is scrolled like before, but images,
 *    fonts, media and trackers are still blocked. Once full mode has worked where lean did not, lean
 *    is skipped for the rest of the process.
 */
@Singleton
class WebViewHomeFetcher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val json = Json { ignoreUnknownKeys = true }
        private val main = Handler(Looper.getMainLooper())

        /** Set once lean mode came back empty and full mode then succeeded. */
        @Volatile
        private var leanBroken = false

        private class Outcome(
            val page: AccountPage?,
            /** The page redirected to a sign-in or consent screen: retrying in another mode cannot help. */
            val sessionRejected: Boolean = false,
        )

        suspend fun fetch(cookie: String): AccountPage? {
            val started = System.currentTimeMillis()
            if (!leanBroken) {
                HomeFeedLog.d("webview", "start lean (timeout $LEAN_TIMEOUT_MS ms)")
                val lean = attempt(cookie, lean = true, timeoutMs = LEAN_TIMEOUT_MS)
                if (lean?.page != null) {
                    HomeFeedLog.d("webview", "lean done in ${System.currentTimeMillis() - started} ms")
                    return lean.page
                }
                if (lean?.sessionRejected == true) {
                    HomeFeedLog.warn("webview", "session not accepted; not retrying")
                    return null
                }
                HomeFeedLog.warn("webview", "lean gave no page after ${System.currentTimeMillis() - started} ms; trying full")
            }
            HomeFeedLog.d("webview", "start full (timeout $FULL_TIMEOUT_MS ms)")
            val full = attempt(cookie, lean = false, timeoutMs = FULL_TIMEOUT_MS)
            val took = System.currentTimeMillis() - started
            if (full?.page == null) {
                HomeFeedLog.warn("webview", "no page after $took ms (timeout, rejected session or empty)")
                return null
            }
            if (!leanBroken) {
                // Lean failed, full worked: stop paying for the lean attempt on every refresh.
                leanBroken = true
                HomeFeedLog.warn("webview", "lean mode disabled for this session")
            }
            HomeFeedLog.d("webview", "full done in $took ms")
            return full.page
        }

        private suspend fun attempt(
            cookie: String,
            lean: Boolean,
            timeoutMs: Long,
        ): Outcome? =
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Main) { load(cookie, lean) }
            }

        @SuppressLint("SetJavaScriptEnabled")
        private suspend fun load(
            cookie: String,
            lean: Boolean,
        ): Outcome =
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val web = WebView(context)
                var poll: Runnable? = null

                fun finish(outcome: Outcome) {
                    if (!done.compareAndSet(false, true)) return
                    main.post {
                        poll?.let { main.removeCallbacks(it) }
                        runCatching {
                            web.stopLoading()
                            web.removeJavascriptInterface(BRIDGE)
                            web.destroy()
                        }
                    }
                    if (cont.isActive) cont.resume(outcome)
                }
                cont.invokeOnCancellation { finish(Outcome(null)) }

                val cookies = CookieManager.getInstance()
                // The WebView's cookie jar outlives every fetch (and any earlier sign-in or account switch).
                // Cookies it still holds that the active account's cookie does not replace (LOGIN_INFO, SIDCC,
                // __Secure-*PSIDCC, an old visitor id...) would mix another identity into this request, so
                // the account's own cookie is applied onto a clean youtube.com jar.
                expireStaleCookies(cookies, cookie)
                cookie.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                    cookies.setCookie("https://www.youtube.com", "$it; Domain=.youtube.com; Path=/; Secure")
                }
                cookies.flush()
                HomeFeedLog.d("webview", "applied ${cookie.split(";").count { it.contains("=") }} cookies, loading https://www.youtube.com/ as desktop (lean=$lean)")

                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = !lean
                    userAgentString = DESKTOP_UA
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // Nothing visual is ever shown, so never fetch it.
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportZoom(false)
                    setSupportMultipleWindows(false)
                }
                web.layout(0, 0, VIEW_W, VIEW_H)

                val grabbing = AtomicBoolean(false)
                web.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onData(
                            initialData: String,
                            domLinks: String,
                            loggedIn: String,
                        ) {
                            val page = runCatching { build(initialData, domLinks, loggedIn) }.onFailure { Log.w(TAG, "parse failed: ${it.message}") }.getOrNull()
                            finish(Outcome(page))
                        }
                    },
                    BRIDGE,
                )

                // The document's inline script sets ytInitialData while the page is still loading, so ask for
                // it every POLL_MS from the first commit rather than waiting for onPageFinished.
                fun startGrab(view: WebView) {
                    if (done.get() || !grabbing.compareAndSet(false, true)) return
                    runCatching { view.evaluateJavascript(if (lean) GRAB_JS else SCROLL_AND_GRAB_JS, null) }
                }
                poll =
                    object : Runnable {
                        override fun run() {
                            if (done.get() || grabbing.get()) return
                            runCatching {
                                web.evaluateJavascript(READY_JS) { ready ->
                                    if (ready == "1") startGrab(web)
                                }
                            }
                            main.postDelayed(this, POLL_MS)
                        }
                    }

                web.webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            if (request == null || request.isForMainFrame) return null
                            return if (lean || shouldBlock(request)) blockedResponse() else null
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?,
                        ) {
                            if (url == null || done.get()) return
                            if (isSignInOrConsent(url)) {
                                Log.w(TAG, "Session not accepted by youtube.com ($url)")
                                finish(Outcome(null, sessionRejected = true))
                                return
                            }
                            if (url.startsWith("https://www.youtube.com")) {
                                main.removeCallbacks(poll!!)
                                main.postDelayed(poll!!, POLL_MS)
                            }
                        }

                        override fun onPageFinished(
                            view: WebView?,
                            url: String?,
                        ) {
                            if (url == null || view == null || done.get()) return
                            HomeFeedLog.d("webview", "page finished: ${url.substringBefore('?').take(80)}")
                            if (isSignInOrConsent(url)) {
                                Log.w(TAG, "Session not accepted by youtube.com ($url)")
                                finish(Outcome(null, sessionRejected = true))
                                return
                            }
                            // Backstop if the poll never saw the data: grab whatever the page has now.
                            startGrab(view)
                        }
                    }
                web.loadUrl("https://www.youtube.com/")
            }

        /** Expires every youtube.com cookie the jar holds whose name the account's [cookie] does not set. */
        private fun expireStaleCookies(
            cookies: CookieManager,
            cookie: String,
        ) {
            val wanted =
                cookie
                    .split(";")
                    .mapNotNull { it.substringBefore("=", "").trim().takeIf(String::isNotEmpty) }
                    .toSet()
            val held =
                cookies
                    .getCookie("https://www.youtube.com")
                    .orEmpty()
                    .split(";")
                    .mapNotNull { it.substringBefore("=", "").trim().takeIf(String::isNotEmpty) }
                    .filterNot { it in wanted }
            held.forEach { name ->
                // Both the host-only and the domain-wide variant, since the jar may hold either.
                cookies.setCookie("https://www.youtube.com", "$name=; Max-Age=0; Path=/; Secure")
                cookies.setCookie("https://www.youtube.com", "$name=; Max-Age=0; Domain=.youtube.com; Path=/; Secure")
            }
            if (held.isNotEmpty()) HomeFeedLog.d("webview", "expired ${held.size} stale youtube.com cookies not in the account's cookie")
        }

        private fun isSignInOrConsent(url: String) = url.contains("accounts.google") || url.contains("consent.")

        /** Full mode only: images, fonts, media and tracking calls are never needed, whatever the page asks for. */
        private fun shouldBlock(request: WebResourceRequest): Boolean {
            val url = request.url
            val host = url.host.orEmpty()
            val path = url.path.orEmpty().lowercase()
            if (BLOCKED_HOST_PARTS.any { host.contains(it) }) return true
            if (BLOCKED_PATH_PARTS.any { path.contains(it) }) return true
            return BLOCKED_EXTENSIONS.any { path.endsWith(it) } ||
                host.endsWith("ytimg.com") ||
                host.endsWith("ggpht.com") ||
                host.contains("googlevideo.com")
        }

        private fun blockedResponse() =
            WebResourceResponse(
                "text/plain",
                "utf-8",
                404,
                "Blocked",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )

        private fun build(
            initialData: String,
            domLinks: String,
            loggedIn: String,
        ): AccountPage? {
            val root = runCatching { json.parseToJsonElement(initialData) }.getOrNull()
            val parsed = root?.let { runCatching { parseAccountPage(it) }.getOrNull() }
            val items = ArrayList<FeedItem>(parsed?.items.orEmpty())
            val known = parsed?.videos.orEmpty().mapTo(HashSet()) { it.id }
            HomeFeedLog.d("webview", "ytInitialData ${initialData.length} chars, parsed videos=${parsed?.videos?.size} posts=${parsed?.posts?.size} ytcfg.LOGGED_IN='$loggedIn'")
            val links = JSONArray(domLinks)
            HomeFeedLog.d("webview", "dom links=${links.length()}")
            for (i in 0 until links.length()) {
                val o = links.getJSONObject(i)
                val id = o.getString("id")
                if (!known.add(id)) continue
                val video =
                    Video(
                        id = id,
                        title = o.optString("title").trim(),
                        channelName = "",
                        channelId = "",
                        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        duration = 0,
                        viewCount = 0,
                        uploadDate = "",
                        isShort = o.optBoolean("short"),
                    )
                if (video.title.isNotBlank() || video.isShort) {
                    items += if (video.isShort) FeedItem.ShortItem(video) else FeedItem.VideoItem(video)
                }
            }
            // ytcfg is the page's own flag; the data's tracking params say the same thing when the page's
            // scripts never ran (lean mode), so the account is still confirmed.
            val signedIn =
                when (loggedIn) {
                    "true" -> true
                    "false" -> false
                    else -> (root as? JsonObject)?.isLoggedInResponse()
                }
            // Keep "Not interested" and friends working for videos that came from the page.
            if (root != null && signedIn == true) {
                runCatching {
                    app.zaid.flow.account.AccountActions
                        .rememberFeedbackTokens(root)
                }
            }
            val page = AccountPage(items, parsed?.continuation, parsed?.chips.orEmpty(), signedIn)
            Log.w(TAG, "WebView home loggedIn=$signedIn: ${page.videos.size} videos, ${page.posts.size} posts, more=${page.continuation != null}")
            return page.takeIf { it.videos.isNotEmpty() }
        }

        private companion object {
            const val TAG = "WebViewHome"
            const val BRIDGE = "FlowBridge"

            /** Lean mode needs only the document, so it is given little time before full mode takes over. */
            const val LEAN_TIMEOUT_MS = 3_500L

            /** Full mode runs the page's scripts and scrolls it; the InnerTube request is used when it is slower. */
            const val FULL_TIMEOUT_MS = 5_500L
            const val POLL_MS = 120L
            const val VIEW_W = 1280
            const val VIEW_H = 2400
            const val DESKTOP_UA =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            val BLOCKED_HOST_PARTS =
                listOf("doubleclick", "googlesyndication", "googleadservices", "google-analytics", "googletagmanager", "adservice.google")
            val BLOCKED_PATH_PARTS =
                listOf("/api/stats/", "/ptracking", "/pagead/", "/gen_204", "/generate_204", "/csi_204", "/log_event", "/youtubei/v1/log")
            val BLOCKED_EXTENSIONS =
                listOf(
                    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico", ".avif", ".bmp",
                    ".woff", ".woff2", ".ttf", ".otf", ".eot",
                    ".mp4", ".webm", ".m4a", ".mp3", ".m3u8", ".ts", ".mpd",
                )

            /** "1" once the document's inline script has set the page data. */
            const val READY_JS = "(function(){try{return window.ytInitialData?1:0;}catch(e){return 0;}})()"

            /** Shared by both grab scripts: hands the page's data, its watch/shorts links and its login flag to the app. */
            const val GRAB_FN = """
function grab(){
  var d=''; try{d=JSON.stringify(window.ytInitialData||{});}catch(e){d='{}';}
  var seen={},out=[];
  try{
    document.querySelectorAll('a[href*="/watch?v="],a[href*="/shorts/"]').forEach(function(a){
      var h=a.getAttribute('href')||'',m=h.match(/[?&]v=([\w-]{11})/)||h.match(/\/shorts\/([\w-]{11})/);
      if(!m||seen[m[1]])return; seen[m[1]]=1;
      var t=a.getAttribute('title')||a.getAttribute('aria-label')||(a.textContent||'');
      out.push({id:m[1],title:t.replace(/\s+/g,' ').slice(0,200),short:h.indexOf('/shorts/')>=0});
    });
  }catch(e){}
  var li='';try{li=String(window.ytcfg.get('LOGGED_IN'));}catch(e){}
  FlowBridge.onData(d,JSON.stringify(out),li);
}
"""

            /** Lean mode: the data is already in the document, so read it at once. */
            const val GRAB_JS = "(function(){$GRAB_FN grab();})();"

            /** Full mode: let the page render, scroll it 3 screens (more steps = more videos), then read it. */
            const val SCROLL_AND_GRAB_JS = """
(function(){
  $GRAB_FN
  var n=0;
  function step(){window.scrollBy(0,document.documentElement.scrollHeight);if(++n<3)setTimeout(step,500);else setTimeout(grab,600);}
  setTimeout(step,700);
})();
"""
        }
    }
