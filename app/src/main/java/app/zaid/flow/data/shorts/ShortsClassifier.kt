package app.zaid.flow.data.shorts

import java.net.HttpURLConnection
import java.net.URL
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object ShortsClassifier {
    private const val SHORTS_PATH = "/shorts/"

    /** Reel markers on an extractor item, plus its URL. */
    fun isReel(item: StreamInfoItem): Boolean = item.isShortFormContent || isReelUrl(item.url)

    /** True for a canonical Shorts watch URL. Safe on null/blank. */
    fun isReelUrl(url: String?): Boolean = url?.contains(SHORTS_PATH, ignoreCase = true) == true

    /**
     * Asks YouTube whether [videoId] is a Short: /shorts/ID answers 200 for a Short and redirects to
     * /watch for anything else. Used when the cached reel index has not seen a brand-new upload yet.
     * Returns null when the answer is unknown (offline, blocked), so callers keep their own verdict.
     */
    fun probeIsShort(videoId: String): Boolean? =
        runCatching {
            val c = URL("https://www.youtube.com/shorts/$videoId").openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.requestMethod = "HEAD"
                c.connectTimeout = 4_000
                c.readTimeout = 4_000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                when (c.responseCode) {
                    200 -> true
                    in 300..399 -> false
                    else -> null
                }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
}
