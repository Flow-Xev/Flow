package app.zaid.flow.player.quality

/**
 * Pure rules for not downloading what the viewer cannot see or does not need: quality capped to the
 * player window, and a shorter forward buffer on metered links.
 */
object DataSavingPolicy {
    private val HEIGHTS = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160)

    /** A little under-sized picture is invisible; this slack stops a 1079 px window asking for 1440p. */
    private const val WINDOW_SLACK = 0.95

    /** No cap before the window has been measured, and never below 240p (a mini player still looks sane). */
    const val NO_CAP = 0
    private const val MIN_CAP = 240

    /**
     * The tallest quality worth fetching for a player window of [widthPx] x [heightPx]: the smallest
     * standard height that fills what a 16:9 picture could show there. 0 when the window is unknown.
     */
    fun windowHeightCap(
        widthPx: Int,
        heightPx: Int,
    ): Int {
        if (widthPx <= 0 || heightPx <= 0) return NO_CAP
        val shown = minOf(heightPx.toDouble(), widthPx * 9.0 / 16.0) * WINDOW_SLACK
        return (HEIGHTS.firstOrNull { it >= shown } ?: HEIGHTS.last()).coerceAtLeast(MIN_CAP)
    }

    const val METERED_MAX_BUFFER_MS = 20_000
    const val METERED_MIN_BUFFER_MS = 10_000

    /** Forward buffer (min, max) in ms: unchanged on free networks, held short on metered ones. */
    fun forwardBuffer(
        minMs: Int,
        maxMs: Int,
        metered: Boolean,
    ): Pair<Int, Int> =
        if (!metered) {
            minMs to maxMs
        } else {
            minOf(minMs, METERED_MIN_BUFFER_MS) to minOf(maxMs, METERED_MAX_BUFFER_MS)
        }
}
