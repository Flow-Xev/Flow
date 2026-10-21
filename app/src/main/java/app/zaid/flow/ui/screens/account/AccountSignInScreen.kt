package app.zaid.flow.ui.screens.account

import android.annotation.SuppressLint
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountSignInViewModel
    @Inject
    constructor(
        private val accountManager: AccountManager,
    ) : ViewModel() {
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
    }

/** The Google sign-in web page. Fills whatever space its host gives it; back cancels. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AccountSignInScreen(
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
                                    if (url != null && url.contains("youtube.com") && !done) {
                                        val cookie = CookieManager.getInstance().getCookie(url)
                                        if (cookie != null &&
                                            (cookie.contains("SAPISID") || cookie.contains("__Secure-3PAPISID"))
                                        ) {
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
