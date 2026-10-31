package app.zaid.flow.data.local

/** How much each on-disk cache may hold, as read from the size settings. */
data class MediaCacheLimits(
    /** The video and Shorts cache; always a real ceiling. */
    val videoBytes: Long,
    /** The song and animated artwork cache; always a real ceiling. */
    val musicBytes: Long,
    /** The image cache; null leaves the size to Coil. */
    val artworkBytes: Long?,
) {
    companion object {
        val DEFAULT =
            MediaCacheLimits(
                videoBytes = MediaCacheSizes.mediaBytes(MediaCacheSizes.DEFAULT_MEDIA_MB),
                musicBytes = MediaCacheSizes.mediaBytes(MediaCacheSizes.DEFAULT_MEDIA_MB),
                artworkBytes = MediaCacheSizes.artworkBytes(MediaCacheSizes.ARTWORK_AUTOMATIC_MB),
            )
    }
}

/** The size settings of the media and image caches, in megabytes as stored. */
object MediaCacheSizes {
    /** Older versions stored 0 for "Unlimited"; it is no longer offered and reads as [DEFAULT_MEDIA_MB]. */
    const val UNLIMITED_MB = 0
    const val ARTWORK_AUTOMATIC_MB = 0
    const val DEFAULT_MEDIA_MB = 500

    val MEDIA_OPTIONS_MB = listOf(100, 200, 500, 1024, 2048, 5120)
    val ARTWORK_OPTIONS_MB = listOf(ARTWORK_AUTOMATIC_MB, 100, 200, 500, 1024)

    private const val BYTES_PER_MB = 1024L * 1024L

    /** The setting a stored value stands for: a cache can no longer be unbounded, so 0 becomes the default. */
    fun normalizeMedia(megabytes: Int): Int = if (megabytes <= UNLIMITED_MB) DEFAULT_MEDIA_MB else megabytes

    /** Always positive, so the media caches always evict. */
    fun mediaBytes(megabytes: Int): Long = normalizeMedia(megabytes) * BYTES_PER_MB

    fun artworkBytes(megabytes: Int): Long? = if (megabytes <= ARTWORK_AUTOMATIC_MB) null else megabytes * BYTES_PER_MB
}
