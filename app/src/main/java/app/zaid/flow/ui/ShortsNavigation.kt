package app.zaid.flow.ui

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.shorts.queue.ShortsQueueSource
import app.zaid.flow.data.shorts.queue.openAtVideoId

const val SHORTS_ROUTE_PATTERN = "shorts?src={src}"

const val SHORTS_ROUTE_ARG = "src"

const val SHORTS_ROUTE_KEY = "shorts"

fun NavController.openShorts(
    source: ShortsQueueSource,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    navigate("shorts?$SHORTS_ROUTE_ARG=${Uri.encode(source.encode())}", builder)
}

/**
 * Opens [source] in the Shorts player, or the tapped reel in the video player when the user has
 * switched the Shorts player off. Every Shorts entry point takes this one decision.
 */
internal fun NavHostController.openShortsOrPlayer(
    source: ShortsQueueSource,
    disableShortsPlayer: Boolean,
) {
    val tappedId = source.openAtVideoId
    if (disableShortsPlayer && tappedId != null) navigateToPlayer(tappedId) else openShorts(source)
}

/** A reel goes to the Shorts player unless that is switched off; everything else goes to [playVideo]. */
internal fun NavHostController.openVideoOrShorts(
    video: Video,
    disableShortsPlayer: Boolean,
    playVideo: (Video) -> Unit,
) {
    if (video.isShort && !disableShortsPlayer) openShorts(ShortsQueueSource.SeededFeed(video.id)) else playVideo(video)
}

const val SOUND_ROUTE_PATTERN = "sound?id={id}&params={params}&title={title}&video={video}"

fun NavController.openSound(
    browseId: String,
    params: String,
    title: String,
    videoId: String = "",
) {
    val previous = previousBackStackEntry
    if (previous?.destination?.route == SOUND_ROUTE_PATTERN && browseId.isNotBlank() && previous.arguments?.getString("id") == browseId) {
        popBackStack()
        return
    }
    navigate("sound?id=${Uri.encode(browseId)}&params=${Uri.encode(params)}&title=${Uri.encode(title)}&video=${Uri.encode(videoId)}")
}
