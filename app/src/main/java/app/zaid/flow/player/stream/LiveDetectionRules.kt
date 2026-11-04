package app.zaid.flow.player.stream

/**
 * Decides whether a player response should be played as a live stream rather than as a VOD ladder.
 *
 * Kept pure and separate because getting it wrong is silent and expensive: a VOD misread as live is
 * handed to the player with no video/audio formats at all, so quality and audio-track selection come
 * up empty rather than failing loudly.
 */
object LiveDetectionRules {
    /**
     * @param hasHlsManifest whether `streamingData.hlsManifestUrl` is present. Deliberately weak
     *   evidence: Apple-family clients (VISIONOS, IOS) return a manifest for ordinary VODs as well
     *   as for live, so it only implies live when there is no adaptive ladder to play instead.
     * @param hasAdaptiveFormats whether the response carries any adaptive formats. A response that is
     *   neither broadcasting nor a post-live DVR and has a ladder is a VOD, including an ended
     *   live stream whose response still carries live-streamability metadata.
     */
    fun isLiveNow(
        isLive: Boolean?,
        isPostLiveDvr: Boolean?,
        hasLiveStreamability: Boolean,
        hasHlsManifest: Boolean,
        hasAdaptiveFormats: Boolean,
    ): Boolean {
        if (isLive == true || isPostLiveDvr == true) return true
        // An ended stream keeps its liveStreamability block (and, on Apple-family clients, an HLS
        // manifest) but is an ordinary VOD with a full adaptive ladder. Reading the leftover
        // metadata as "live" sends it down the live path, so it plays as a dead/empty live stream
        // instead of a normal video. With a ladder to play and no broadcast flag, it is a VOD.
        if (hasAdaptiveFormats) return false
        if (hasLiveStreamability) return true
        return hasHlsManifest
    }
}
