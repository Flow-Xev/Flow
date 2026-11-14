package app.zaid.flow.data.local

/**
 * What Data Saver changes. Video and Shorts are held to 720p (Auto stays available, and its adaptive
 * ceiling drops to 720p as well), and music streams at Medium at most. Thumbnails are not limited.
 * The stored quality choices are never rewritten: switching it off brings them straight back.
 */
object DataSaver {
    const val MAX_VIDEO_HEIGHT = 720

    /** Mirror of the saved setting for code that cannot suspend; kept current by the app and the setter. */
    @Volatile
    var enabled: Boolean = false

    fun capVideo(
        quality: VideoQuality,
        enabled: Boolean,
    ): VideoQuality = if (enabled && quality != VideoQuality.AUTO && quality.height > MAX_VIDEO_HEIGHT) VideoQuality.Q_720P else quality

    /** Like [capVideo], for Shorts. Auto is left alone here: [AutoQualityLadder] decides what it plays. */
    fun capShorts(
        quality: VideoQuality,
        enabled: Boolean,
    ): VideoQuality = capVideo(quality, enabled)

    fun capThumbnail(
        quality: ThumbnailQuality,
        enabled: Boolean,
    ): ThumbnailQuality = quality

    fun capMusic(
        quality: MusicAudioQuality,
        enabled: Boolean,
    ): MusicAudioQuality =
        if (enabled && (quality == MusicAudioQuality.AUTO || quality == MusicAudioQuality.HIGH)) MusicAudioQuality.MEDIUM else quality
}
