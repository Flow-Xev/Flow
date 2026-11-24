package app.zaid.flow.player

object PlaybackStartupPolicy {
    fun shouldDelaySecondaryContent(
        isPlaybackLoading: Boolean,
        currentVideoId: String?,
        requestedVideoId: String,
    ): Boolean = isPlaybackLoading && currentVideoId == requestedVideoId
}
