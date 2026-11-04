package app.zaid.flow.ui.screens.account

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.ui.screens.sync.QrCodeImage
import kotlinx.coroutines.delay

private enum class SignInMethod { Google, Tv }

/**
 * Tapping "Sign in" lands here: a pop-up to choose how, then the chosen flow.
 *  - Google: the normal Google sign-in page (a web session).
 *  - YouTube TV: a code approved on google.com/device, which gives the TV client's own recommendations.
 */
@Composable
fun AccountSignInScreen(
    onSignedIn: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Skips the choice and opens the YouTube TV code page, e.g. to renew a TV sign-in that was revoked. */
    startWithTv: Boolean = false,
) {
    var method by rememberSaveable { mutableStateOf<SignInMethod?>(if (startWithTv) SignInMethod.Tv else null) }
    when (method) {
        null ->
            SignInMethodDialog(
                onGoogle = { method = SignInMethod.Google },
                onTv = { method = SignInMethod.Tv },
                onDismiss = onCancel,
            )
        SignInMethod.Google ->
            GoogleSignInWebScreen(
                onSignedIn = onSignedIn,
                onCancel = { method = null },
                modifier = modifier,
            )
        SignInMethod.Tv ->
            TvCodeSignInScreen(
                onSignedIn = onSignedIn,
                onCancel = { if (startWithTv) onCancel() else method = null },
                modifier = modifier,
            )
    }
}

@Composable
private fun SignInMethodDialog(
    onGoogle: () -> Unit,
    onTv: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_in_choose_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MethodRow(
                    title = stringResource(R.string.sign_in_method_google_title),
                    subtitle = stringResource(R.string.sign_in_method_google_subtitle),
                    onClick = onGoogle,
                )
                MethodRow(
                    title = stringResource(R.string.sign_in_method_tv_title),
                    subtitle = stringResource(R.string.sign_in_method_tv_subtitle),
                    onClick = onTv,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun MethodRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Shows the code (and a QR code that opens the approval page with the code already filled in), waits for it to
 * be approved on google.com/device, then signs the account in. The sign-in itself runs in the ViewModel, so it
 * survives rotation and the trip to the browser; coming back from the browser checks at once.
 */
@Composable
private fun TvCodeSignInScreen(
    onSignedIn: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountSignInViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val state by viewModel.tv.collectAsStateWithLifecycle()

    BackHandler(onBack = onCancel)

    LaunchedEffect(Unit) { viewModel.startTv() }
    // Back from approving the code in the browser: ask Google straight away instead of waiting out the interval.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.pollTvNow() }
    // Closing the screen ends the sign-in, but rotating it must not.
    DisposableEffect(Unit) {
        onDispose {
            if ((context.findActivity())?.isChangingConfigurations != true) viewModel.cancelTv()
        }
    }
    LaunchedEffect(state is TvSignInState.SignedIn) {
        if (state is TvSignInState.SignedIn) {
            delay(900)
            onSignedIn()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.sign_in_tv_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.sign_in_tv_instructions),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        when (val current = state) {
            is TvSignInState.Failed -> {
                val message =
                    when (current.reason) {
                        TvSignInState.Failed.Reason.Denied -> stringResource(R.string.sign_in_tv_denied)
                        TvSignInState.Failed.Reason.CouldNotGetCode -> stringResource(R.string.sign_in_tv_error_generic)
                        TvSignInState.Failed.Reason.Rejected ->
                            stringResource(R.string.sign_in_tv_rejected, current.detail ?: stringResource(R.string.sign_in_tv_unknown_error))
                    }
                Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::restartTv) { Text(stringResource(R.string.sign_in_tv_try_again)) }
            }
            TvSignInState.SignedIn -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.sign_in_tv_signed_in), style = MaterialTheme.typography.titleMedium)
                }
            }
            is TvSignInState.ShowingCode -> {
                val code = current.code
                QrCodeImage(
                    text = code.completeUrl,
                    modifier = Modifier.size(180.dp).clip(MaterialTheme.shapes.large),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sign_in_tv_scan_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable { copyCode(context, code.userCode) },
                ) {
                    Text(
                        code.userCode,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 3.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { copyCode(context, code.userCode) }) {
                        Text(stringResource(R.string.sign_in_tv_copy_code))
                    }
                    Button(onClick = {
                        copyCode(context, code.userCode)
                        // The page opens with the code already in the box; the code is on the clipboard as a fallback.
                        runCatching { uriHandler.openUri(code.completeUrl) }
                            .onFailure { runCatching { uriHandler.openUri(code.verificationUrl) } }
                    }) { Text(stringResource(R.string.sign_in_tv_open_page)) }
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(if (current.reconnecting) R.string.sign_in_tv_reconnecting else R.string.sign_in_tv_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.sign_in_tv_expires_in, formatCountdown(current.secondsLeft)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TvSignInState.Idle, TvSignInState.Requesting -> CircularProgressIndicator()
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
    }
}

/** 905 seconds as "15:05". */
internal fun formatCountdown(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun copyCode(
    context: Context,
    code: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("tv_code", code))
    Toast.makeText(context, context.getString(R.string.sign_in_tv_code_copied), Toast.LENGTH_SHORT).show()
}
