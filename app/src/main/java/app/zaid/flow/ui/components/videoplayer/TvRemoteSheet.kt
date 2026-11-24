package app.zaid.flow.ui.components.videoplayer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.player.lounge.LoungeCastManager
import app.zaid.flow.player.lounge.LoungeTv
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Remote control for a TV that is playing a cast video, laid out like YouTube's own: the TV's name, what is
 * playing, a seek bar, the transport buttons and the TV's volume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvRemoteSheet(
    tv: LoungeTv,
    videoId: String,
    videoTitle: String,
    channelName: String,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by LoungeCastManager.remote.collectAsStateWithLifecycle()
    val reconnecting by LoungeCastManager.reconnecting.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The TV reports its position now and then; between reports the bar keeps counting while it plays.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.playing) {
        while (state.playing) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val livePosition =
        if (state.playing) state.positionMs + (now - state.updatedAtMs).coerceAtLeast(0) else state.positionMs
    val duration = state.durationMs.coerceAtLeast(1)

    var dragging by remember { mutableStateOf<Float?>(null) }
    var volumeDrag by remember { mutableStateOf<Float?>(null) }
    var pad by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cast, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.lounge_casting_to, tv.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { pad = !pad }) {
                    Crossfade(targetState = pad, animationSpec = tween(180), label = "padToggle") { showPad ->
                        Icon(
                            if (showPad) Icons.Rounded.Tune else Icons.Rounded.Gamepad,
                            contentDescription = stringResource(if (showPad) R.string.lounge_remote else R.string.lounge_dpad),
                        )
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel)) }
            }

            AnimatedContent(
                targetState = pad,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 90)) + slideInVertically(tween(260)) { it / 14 }) togetherWith
                        fadeOut(tween(110)) using SizeTransform(clip = false) { _, _ -> tween(260) }
                },
                label = "remoteMode",
            ) { showPad ->
            if (showPad) {
                TvDPad(
                    playing = state.playing,
                    muted = state.muted || state.volume == 0,
                    onKey = LoungeCastManager::pressKey,
                    reconnecting = reconnecting,
                    onPlayPause = { scope.launch { if (state.playing) LoungeCastManager.pause() else LoungeCastManager.play() } },
                    onMute = { scope.launch { LoungeCastManager.toggleMute() } },
                )
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.videoId != null) {
                    AsyncImage(
                        model = "https://i.ytimg.com/vi/${state.videoId}/mqdefault.jpg",
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(128.dp).height(72.dp).clip(MaterialTheme.shapes.medium),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    if (state.videoId == videoId) Text(videoTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.videoId == videoId && channelName.isNotBlank()) {
                        Text(channelName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }

            // Seek bar
            Column {
                Slider(
                    value = dragging ?: (livePosition.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { fraction -> scope.launch { LoungeCastManager.seekTo((fraction * duration).toLong()) } }
                        dragging = null
                    },
                    enabled = state.durationMs > 0,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val shown = dragging?.let { (it * duration).toLong() } ?: livePosition
                    Text(formatClock(shown), style = MaterialTheme.typography.labelMedium)
                    Text(if (state.durationMs > 0) formatClock(state.durationMs) else "--:--", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Transport
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { LoungeCastManager.previous() } }) { Icon(Icons.Rounded.SkipPrevious, contentDescription = null, modifier = Modifier.size(32.dp)) }
                IconButton(onClick = { scope.launch { LoungeCastManager.seekTo((livePosition - 10_000).coerceAtLeast(0)) } }) { Icon(Icons.Rounded.Replay10, contentDescription = null, modifier = Modifier.size(32.dp)) }
                FilledIconButton(
                    onClick = { scope.launch { if (state.playing) LoungeCastManager.pause() else LoungeCastManager.play() } },
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                ) {
                    if (state.buffering) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (state.playing) R.string.lounge_pause else R.string.lounge_play),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                IconButton(onClick = { scope.launch { LoungeCastManager.seekTo((livePosition + 10_000).coerceAtMost(state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) } }) { Icon(Icons.Rounded.Forward10, contentDescription = null, modifier = Modifier.size(32.dp)) }
                IconButton(onClick = { scope.launch { LoungeCastManager.next() } }) { Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(32.dp)) }
            }

            // TV volume
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { LoungeCastManager.toggleMute() } }) {
                    Icon(
                        if (state.muted || state.volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeDown,
                        contentDescription = stringResource(R.string.lounge_mute),
                    )
                }
                Slider(
                    value = volumeDrag ?: (state.volume / 100f),
                    onValueChange = { volumeDrag = it },
                    onValueChangeFinished = {
                        volumeDrag?.let { scope.launch { LoungeCastManager.setVolume((it * 100).toInt()) } }
                        volumeDrag = null
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { scope.launch { LoungeCastManager.setVolume(state.volume + 10) } }) {
                    Icon(Icons.Rounded.VolumeUp, contentDescription = stringResource(R.string.lounge_volume_up))
                }
            }
            Text(
                text = stringResource(R.string.lounge_volume_label, ((volumeDrag ?: (state.volume / 100f)) * 100).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )

            }
            }

            TextButton(onClick = onStop, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.dlna_stop_casting))
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
