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

        fun onCookieCaptured(
            cookie: String,
            name: String? = null,
            onComplete: () -> Unit,
        ) {
            viewModelScope.launch {
                accountManager.signIn(cookie, name)
                // signIn already launches afterSignIn; wait a moment for it
                kotlinx.coroutines.delay(1200)
                onComplete()
            }
        }

        private companion object {
            const val MIN_INTERVAL_S = 2
            const val MAX_CODE_RENEWALS = 12
        }
    }

/** The Google sign-in web page. Fills whatever space its host gives it; back cancels. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GoogleSignInWebScreen(
    onSignedIn: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountSignInViewModel = hiltViewModel(),
) {
    var loading by remember { mutableStateOf(true) }
    var processing by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onCancel)

    Box(modifier.fillMaxSize()) {
        if (!processing) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google rejects sign-in from embedded WebViews that advertise "; wv".
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient =
                            object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    err: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        error = ctx.getString(R.string.account_sign_in_load_error, err?.description?.toString().orEmpty())
                                    }
                                }

                                override fun onPageFinished(
                                    view: WebView?,
                                    url: String?,
                                ) {
                                    loading = false
                                    // Only once the sign-in has landed on youtube.com itself. The redirect hops through
                                    // accounts.youtube.com first, and a jar read there is missing what the site sets on arrival.
                                    val host = url?.let { Uri.parse(it).host }
                                    if (!done && (host == "www.youtube.com" || host == "m.youtube.com")) {
                                        CookieManager.getInstance().flush()
                                        val cookie = CookieManager.getInstance().getCookie("https://www.youtube.com")
                                        if (cookie != null && cookie.holdsFullSession()) {
                                            done = true
                                            processing = true
                                            viewModel.onCookieCaptured(cookie) {
                                                // The session now lives in the app's own store.
                                                CookieManager.getInstance().removeAllCookies(null)
                                                onSignedIn()
                                            }
                                        }
                                    }
                                }
                            }
                        // Accounts are stored by the app, not the WebView. Start from a clean jar so
                        // adding a second account shows Google's sign-in instead of silently
                        // re-using the one that is already signed in.
                        CookieManager.getInstance().removeAllCookies {
                            post {
                                loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        error?.let {
            Text(it, Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
        }
        if ((loading || processing) && error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                if (processing) Text(stringResource(R.string.account_signing_in))
            }
        }
    }
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
