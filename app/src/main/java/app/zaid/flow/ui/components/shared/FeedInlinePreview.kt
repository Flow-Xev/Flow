package app.zaid.flow.ui.components.shared

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.zaid.flow.data.model.Video
import app.zaid.flow.ui.components.shared.card.INLINE_PREVIEW_DWELL_MS
import app.zaid.flow.ui.components.shared.card.InlineVideoPreviewController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Drives the inline preview of one feed grid, shared by Home and Search so they cannot drift apart.
 *
 * A preview starts only once the grid has been still and one card has stayed the most visible for
 * [INLINE_PREVIEW_DWELL_MS]; scrolling, leaving the screen, the setting being turned off, or the
 * main player taking over all stop it.
 *
 * [videoAtIndex] is read through [rememberUpdatedState] and deliberately not a key: a feed that
 * grows or has channel details filled in must not restart the preview that is already playing.
 */
@Composable
internal fun FeedInlinePreviewEffect(
    gridState: LazyGridState,
    controller: InlineVideoPreviewController?,
    videoAtIndex: (Int) -> Video?,
) {
    if (controller == null) return
    var resumed by remember(controller) { mutableStateOf(false) }
    LifecycleResumeEffect(controller) {
        resumed = true
        onPauseOrDispose {
            resumed = false
            controller.stop()
        }
    }
    val latestVideoAtIndex by rememberUpdatedState(videoAtIndex)
    val allowed = controller.settingsAllow && !controller.blocked

    LaunchedEffect(gridState, controller, resumed, allowed) {
        if (!resumed || !allowed) {
            android.util.Log.i(
                "InlinePreview",
                "feed idle: resumed=$resumed mode=${controller.mode} onWifi=${controller.onWifi} blocked=${controller.blocked}",
            )
            controller.stop()
            return@LaunchedEffect
        }
        try {
            snapshotFlow {
                if (gridState.isScrollInProgress) {
                    null
                } else {
                    mostVisibleFeedVideo(
                        layoutInfo = gridState.layoutInfo,
                        retainedVideoId = controller.activeVideoId,
                    ) { index -> latestVideoAtIndex(index) }
                }
            }.distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { video ->
                    controller.stop()
                    if (video == null) return@collectLatest
                    delay(INLINE_PREVIEW_DWELL_MS)
                    controller.play(video)
                    // The network may still be coming up (or validating) when the feed opens, so a card
                    // skipped only for device conditions is tried again rather than left on its thumbnail.
                    var retries = 0
                    while (controller.activeVideoId != video.id &&
                        controller.skippedForConditions &&
                        retries < INLINE_PREVIEW_MAX_RETRIES
                    ) {
                        delay(INLINE_PREVIEW_RETRY_MS)
                        controller.play(video)
                        retries++
                    }
                }
        } finally {
            controller.stop()
        }
    }
}

private const val INLINE_PREVIEW_RETRY_MS = 2_000L
private const val INLINE_PREVIEW_MAX_RETRIES = 5
