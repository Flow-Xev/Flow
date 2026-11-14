package app.zaid.flow.ui.screens.account

import android.annotation.SuppressLint
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.account.AccountProfile
import app.zaid.flow.ui.screens.settings.account.AccountAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zaid.flow.R
import app.zaid.flow.account.AccountManager
import app.zaid.flow.account.tv.TvDeviceCode
import app.zaid.flow.account.tv.TvPollResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** What the YouTube TV code sign-in is doing, for the screen to draw. */
sealed interface TvSignInState {
    data object Idle : TvSignInState

    /** Asking Google for a code. */
    data object Requesting : TvSignInState

    /** [code] is on screen; [secondsLeft] counts down to its expiry, after which a new one is fetched on its own. */
    data class ShowingCode(
        val code: TvDeviceCode,
        val secondsLeft: Int,
        /** The last check failed to reach Google; the code is still good and polling goes on. */
        val reconnecting: Boolean,
    ) : TvSignInState

    data object SignedIn : TvSignInState

    data class Failed(
        val reason: Reason,
        val detail: String? = null,
    ) : TvSignInState {
        enum class Reason { CouldNotGetCode, Denied, Rejected }
    }
}

/** What the Google web sign-in is doing after the session cookie was captured. */
sealed interface WebSignInState {
    data object Idle : WebSignInState

    /** The session is being saved and the account's profile loaded. */
    data object Finishing : WebSignInState

    /** Signed in; [account] is who, or null if Google never answered the profile request in time. */
    data class Welcome(
        val account: AccountProfile?,
    ) : WebSignInState
}

@HiltViewModel
class AccountSignInViewModel
    @Inject
    constructor(
        private val accountManager: AccountManager,
    ) : ViewModel() {
        private val _tv = MutableStateFlow<TvSignInState>(TvSignInState.Idle)

        /** Lives in the ViewModel, so the code survives rotation and the trip to the browser and back. */
        val tv: StateFlow<TvSignInState> = _tv.asStateFlow()

        private var tvJob: Job? = null
        private val pollNow = Channel<Unit>(Channel.CONFLATED)

        /** Starts the code sign-in; does nothing while one is already running. */
        fun startTv() {
            if (tvJob?.isActive == true) return
            tvJob = viewModelScope.launch { runTv() }
        }

        /** Throws the current code away and asks for a new one. */
        fun restartTv() {
            tvJob?.cancel()
            tvJob = null
            startTv()
        }

        /** Stops the sign-in and resets the state, for when the screen is closed. */
        fun cancelTv() {
            tvJob?.cancel()
            tvJob = null
            _tv.value = TvSignInState.Idle
        }

        /** Checks right now instead of at the next interval, e.g. when the user comes back from approving the code. */
        fun pollTvNow() {
            pollNow.trySend(Unit)
        }

        private suspend fun runTv() {
            _tv.value = TvSignInState.Requesting
            var renewals = 0
            while (true) {
                val requested = accountManager.tvRequestCode()
                val code = requested.getOrNull()
                if (code == null) {
                    _tv.value = TvSignInState.Failed(TvSignInState.Failed.Reason.CouldNotGetCode, requested.exceptionOrNull()?.message)
                    return
                }
                val deadline = SystemClock.elapsedRealtime() + code.expiresInSeconds * 1000L
                var intervalMs = code.intervalSeconds.coerceAtLeast(MIN_INTERVAL_S) * 1000L
                var nextPollAt = SystemClock.elapsedRealtime() + intervalMs
                var reconnecting = false
                pollNow.tryReceive() // a stale nudge from before this code existed
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    // An expired code is replaced quietly: the user never has to tap "try again" for a timeout.
                    if (now >= deadline) break
                    _tv.value = TvSignInState.ShowingCode(code, ((deadline - now + 999L) / 1000L).toInt(), reconnecting)
                    if (now >= nextPollAt) {
                        when (val result = accountManager.tvPoll(code.deviceCode)) {
                            is TvPollResult.Success -> {
                                accountManager.signInTv(result.tokens)
                                _tv.value = TvSignInState.SignedIn
                                return
                            }
                            TvPollResult.Pending -> reconnecting = false
                            TvPollResult.SlowDown -> {
                                reconnecting = false
                                intervalMs += 5_000L
                            }
                            is TvPollResult.Transient -> reconnecting = true
                            TvPollResult.Denied -> {
                                _tv.value = TvSignInState.Failed(TvSignInState.Failed.Reason.Denied)
                                return
                            }
                            TvPollResult.Expired -> break
                            is TvPollResult.Failed -> {
                                _tv.value = TvSignInState.Failed(TvSignInState.Failed.Reason.Rejected, result.message)
                                return
                            }
                        }
                        nextPollAt = SystemClock.elapsedRealtime() + intervalMs
                    }
                    // One tick a second drives the countdown; a nudge cuts the wait short.
                    if (withTimeoutOrNull(1_000L) { pollNow.receive() } != null) nextPollAt = 0L
                }
                if (++renewals > MAX_CODE_RENEWALS) {
                    _tv.value = TvSignInState.Failed(TvSignInState.Failed.Reason.CouldNotGetCode)
                    return
                }
            }
        }

        override fun onCleared() {
            tvJob?.cancel()
        }

        private val _web = MutableStateFlow<WebSignInState>(WebSignInState.Idle)

        /** Where the Google web sign-in is once a session has been captured. */
        val web: StateFlow<WebSignInState> = _web.asStateFlow()

        /**
         * Signs the captured session in and waits until the account's name and photo are known, so the
         * welcome shows who signed in instead of a guess after a fixed delay.
         */
        fun completeWebSignIn(
            cookie: String,
            visitorData: String?,
        ) {
            if (_web.value != WebSignInState.Idle) return
            _web.value = WebSignInState.Finishing
            viewModelScope.launch {
                accountManager.signIn(cookie, visitorData = visitorData)
                val profile =
                    withTimeoutOrNull(PROFILE_WAIT_MS) { accountManager.activeAccount.first { it?.name != null } }
                        ?: accountManager.activeAccount.value
                _web.value = WebSignInState.Welcome(profile)
            }
        }

        fun resetWeb() {
            _web.value = WebSignInState.Idle
        }

        private companion object {
            const val MIN_INTERVAL_S = 2
            const val MAX_CODE_RENEWALS = 12
            const val PROFILE_WAIT_MS = 8_000L
        }
    }

/**
 * Which YouTube the Google sign-in is for. They are kept apart on purpose: YouTube reads only www.youtube.com's
 * cookies, YouTube Music also reads music.youtube.com's. Both read the page's visitor id, which the account then
 * keeps as its own and sends with every request.
 */
enum class SignInTarget(
    internal val loginUrl: String,
    internal val cookieOrigins: List<String>,
    internal val landingHosts: Set<String>,
    /** Whether the page's visitor id is read and handed to the account. */
    internal val readsVisitorData: Boolean,
) {
    YouTube(
        loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com",
        cookieOrigins = listOf("https://www.youtube.com"),
        landingHosts = setOf("www.youtube.com", "m.youtube.com"),
        readsVisitorData = true,
    ),
    Music(
        loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com",
        cookieOrigins = listOf("https://music.youtube.com", "https://www.youtube.com", "https://youtube.com"),
        landingHosts = setOf("music.youtube.com", "www.youtube.com", "m.youtube.com"),
        readsVisitorData = true,
    ),
}

private const val CONTEXT_RETRY_MS = 700L
private const val CONTEXT_ATTEMPTS = 5

// Reads the page's own visitor id (what the signed-in session is served as) from ytcfg or the inline config.
private const val VISITOR_DATA_SCRIPT =
    "(function(){try{var c=window.ytcfg;var y=window.yt&&window.yt.config_;var v=c&&c.get&&c.get('VISITOR_DATA')||y&&y.VISITOR_DATA;" +
        "if(!v){var s=document.querySelectorAll('script');for(var i=0;i<s.length&&!v;i++){var m=s[i].textContent.match(/[\"']VISITOR_DATA[\"']\\s*:\\s*[\"']([^\"']+)[\"']/);if(m)v=m[1]}}" +
        "if(v)Android.onVisitorData(v)}catch(e){}})();"

/** Receives the visitor id the page script finds; called on a WebView thread, hence volatile. */
private class SignInBridge {
    @Volatile var visitorData: String? = null

    @JavascriptInterface
    fun onVisitorData(value: String?) {
        visitorData = value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }
}

private fun String?.isSignInLandingHost(target: SignInTarget): Boolean {
    val host = this?.let { Uri.parse(it).host } ?: return false
    return host in target.landingHosts
}

/** Cookies from the target's origins, one value per name. */
private fun mergedYouTubeCookies(target: SignInTarget): String? {
    val manager = CookieManager.getInstance()
    manager.flush()
    val parts = linkedMapOf<String, String>()
    target.cookieOrigins.forEach { origin ->
        manager.getCookie(origin)?.split(';')?.forEach { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) parts.putIfAbsent(name, value)
        }
    }
    return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }.takeIf { it.isNotEmpty() }
}

/**
 * The Google sign-in web page for [target]. [SignInTarget.Music] ends on YouTube Music so the session carries
 * music.youtube.com's own cookies and the page's visitor id; [SignInTarget.YouTube] stays on www.youtube.com.
 * Shows page progress, an error card with retry, and a welcome naming the account.
 * Fills whatever space its host gives it; back cancels.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GoogleSignInWebScreen(
    target: SignInTarget,
    onSignedIn: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountSignInViewModel = hiltViewModel(),
) {
    val web by viewModel.web.collectAsStateWithLifecycle()
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val bridge = remember { SignInBridge() }

    BackHandler(onBack = onCancel)

    // Whatever state a previous visit left behind must not skip this sign-in.
    LaunchedEffect(Unit) { viewModel.resetWeb() }
    LaunchedEffect(web is WebSignInState.Welcome) {
        if (web is WebSignInState.Welcome) {
            delay(1_100)
            // The session now lives in the app's own store, not the WebView.
            CookieManager.getInstance().removeAllCookies(null)
            onSignedIn()
        }
    }

    Box(modifier.fillMaxSize()) {
        if (web is WebSignInState.Idle) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google rejects sign-in from embedded WebViews that advertise "; wv".
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(bridge, "Android")
                        webChromeClient =
                            object : WebChromeClient() {
                                override fun onProgressChanged(
                                    view: WebView?,
                                    newProgress: Int,
                                ) {
                                    progress = newProgress
                                }
                            }
                        webViewClient =
                            object : WebViewClient() {
                                private var captured = false

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    err: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        error = ctx.getString(R.string.account_sign_in_load_error, err?.description?.toString().orEmpty())
                                    }
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    error = null
                                }

                                override fun onPageFinished(
                                    view: WebView,
                                    url: String?,
                                ) {
                                    // Only once the sign-in has landed on YouTube itself. The redirect hops through
                                    // accounts.youtube.com first, and a jar read there is missing what the site sets on arrival.
                                    if (captured || !url.isSignInLandingHost(target)) return
                                    var attemptsLeft = CONTEXT_ATTEMPTS
                                    val check =
                                        object : Runnable {
                                            override fun run() {
                                                if (captured) return
                                                if (target.readsVisitorData) view.evaluateJavascript(VISITOR_DATA_SCRIPT, null)
                                                val cookie = mergedYouTubeCookies(target)
                                                if (cookie != null && cookie.holdsFullSession()) {
                                                    // The page script answers asynchronously; wait for it once, briefly.
                                                    val visitorData = bridge.visitorData.takeIf { target.readsVisitorData }
                                                    if (!target.readsVisitorData || visitorData != null || attemptsLeft <= 1) {
                                                        captured = true
                                                        viewModel.completeWebSignIn(cookie, visitorData)
                                                        return
                                                    }
                                                }
                                                if (--attemptsLeft > 0) view.postDelayed(this, CONTEXT_RETRY_MS)
                                            }
                                        }
                                    view.post(check)
                                }
                            }
                        webView = this
                        // Accounts are stored by the app, not the WebView. Start from a clean jar so
                        // adding a second account shows Google's sign-in instead of silently
                        // re-using the one that is already signed in.
                        CookieManager.getInstance().removeAllCookies { post { loadUrl(target.loginUrl) } }
                    }
                },
                onRelease = { released ->
                    released.removeJavascriptInterface("Android")
                    released.stopLoading()
                    released.destroy()
                    if (webView === released) webView = null
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (progress in 1..99 && error == null) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }

        error?.let { message ->
            SignInStatus(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    stringResource(R.string.account_sign_in_failed_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                    Button(
                        onClick = {
                            error = null
                            progress = 0
                            webView?.loadUrl(target.loginUrl)
                        },
                    ) { Text(stringResource(R.string.sign_in_tv_try_again)) }
                }
            }
        }

        when (val current = web) {
            WebSignInState.Idle -> Unit
            WebSignInState.Finishing ->
                SignInStatus(Modifier.align(Alignment.Center)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.account_signing_in), textAlign = TextAlign.Center)
                }
            is WebSignInState.Welcome ->
                SignInStatus(Modifier.align(Alignment.Center)) {
                    AccountAvatar(account = current.account, size = 72.dp)
                    Text(
                        current.account?.let { stringResource(R.string.account_sign_in_welcome, it.displayName) }
                            ?: stringResource(R.string.sign_in_tv_signed_in),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(
                            if (target == SignInTarget.Music) R.string.account_sign_in_welcome_hint else R.string.account_sign_in_welcome_hint_youtube,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
        }
    }
}

@Composable
private fun SignInStatus(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/**
 * A session YouTube can recognise: the key its request signature is built from and the session id
 * it is checked against. A jar holding only the first signs requests that are then served as a visitor's.
 */
internal fun String.holdsFullSession(): Boolean {
    val names = split(';').map { it.substringBefore('=').trim() }.toSet()
    val hasSignatureKey = "SAPISID" in names || "__Secure-3PAPISID" in names
    val hasSessionId = "SID" in names || "__Secure-3PSID" in names || "__Secure-1PSID" in names
    return hasSignatureKey && hasSessionId
}
