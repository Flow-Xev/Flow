package app.zaid.flow.player.quality

import app.zaid.flow.player.config.PlayerConfig
import app.zaid.flow.player.stream.VideoCodecUtils
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Auto's rules for picking a stream. The height comes from [AutoQualityLadder], which maps the
 * connection speed to a quality, held to the viewer's floor and ceiling; this picks the stream at
 * that height (or the nearest below it) in the best-ranked codec.
 */
internal object AutoQualityPolicy {
    /** The stream at the height the ladder names for [estimateBps]. */
    fun initialPick(
        streams: List<VideoStream>,
        estimateBps: Long,
        preferredCodec: String?,
    ): VideoStream? {
        if (streams.isEmpty()) return null
        return pickAtHeight(streams, bestHeight(streams, estimateBps), null, preferredCodec, estimateBps)
    }

    /** Straight to the ladder's height, when the estimate has risen above the current one. */
    fun stepUp(
        current: VideoStream,
        streams: List<VideoStream>,
        estimateBps: Long,
        preferredCodec: String?,
    ): VideoStream? {
        val target = bestHeight(streams, estimateBps)
        if (target <= height(current)) return null
        return pickAtHeight(streams, target, current, preferredCodec, estimateBps)
    }

    /** Straight to the ladder's height, when the estimate has fallen below the current one. */
    fun stepDownForBandwidth(
        current: VideoStream,
        streams: List<VideoStream>,
        estimateBps: Long,
        preferredCodec: String?,
    ): VideoStream? {
        val target = bestHeight(streams, estimateBps)
        if (target >= height(current)) return null
        return pickAtHeight(streams, target, current, preferredCodec, estimateBps)
    }

    /**
     * The tallest height with a stream whose own bitrate fits the usable share of [estimateBps], so a
     * leaner codec earns a taller picture at the same speed. A stream with no known bitrate falls back
     * to the speed ladder. Held to the viewer's floor and ceiling; the shortest height there is when none fits.
     */
    private fun bestHeight(
        streams: List<VideoStream>,
        estimateBps: Long,
    ): Int {
        val range = AutoQualityBounds.current()
        val ladder = AutoQualityLadder.targetHeight(estimateBps)
        val budget = budget(estimateBps)
        val fitting =
            streams
                .filter { stream ->
                    val h = height(stream)
                    h <= range.ceiling && (if (stream.bitrate > 0) stream.bitrate <= budget else h <= ladder)
                }.map(::height)
        val target = (fitting.maxOrNull() ?: ladder).coerceIn(minOf(range.floor, range.ceiling), range.ceiling)
        return streams.map(::height).filter { it <= target }.maxOrNull() ?: streams.minOf(::height)
    }

    private const val AUDIO_RESERVE_BPS = 128_000L

    /** What one stream may use of [estimateBps], after the audio's share. */
    internal fun budget(estimateBps: Long): Long = (estimateBps * PlayerConfig.AUTO_BANDWIDTH_FRACTION).toLong() - AUDIO_RESERVE_BPS

    /**
     * The stream at [height]: among those whose bitrate fits the budget (or is unknown), the
     * best-ranked codec, so a lean codec is chosen where the heavy one would not fit; when none
     * fits, the lightest one.
     */
    private fun pickAtHeight(
        streams: List<VideoStream>,
        height: Int,
        current: VideoStream?,
        preferredCodec: String?,
        estimateBps: Long,
    ): VideoStream? {
        val codec = current?.let { VideoCodecUtils.codecKeyFromStream(it) }
        val atHeight = streams.filter { height(it) == height }
        val limit = budget(estimateBps)
        val fitting = atHeight.filter { it.bitrate <= 0 || it.bitrate <= limit }
        if (fitting.isEmpty()) return atHeight.minByOrNull { it.bitrate }
        return fitting.minWithOrNull(
            compareBy<VideoStream> { if (codec != null && VideoCodecUtils.codecKeyFromStream(it) == codec) 0 else 1 }
                .thenBy { VideoCodecUtils.codecRankWithPreference(it, preferredCodec) }
                .thenByDescending { it.bitrate },
        )
    }

    /** One step lower after buffering, never under a floor the viewer set. */
    fun stepDown(
        current: VideoStream,
        streams: List<VideoStream>,
        preferredCodec: String?,
        range: AutoQualityRange = AutoQualityBounds.current(),
    ): VideoStream? =
        neighbour(current, streams, preferredCodec, nearestFirst = false) {
            height(it) < height(current) && height(it) >= range.userFloor
        }

    private fun neighbour(
        current: VideoStream,
        streams: List<VideoStream>,
        preferredCodec: String?,
        nearestFirst: Boolean = true,
        side: (VideoStream) -> Boolean,
    ): VideoStream? {
        val candidates = streams.filter(side).ifEmpty { return null }
        val height = if (nearestFirst) candidates.minOf(::height) else candidates.maxOf(::height)
        val codec = VideoCodecUtils.codecKeyFromStream(current)
        return candidates
            .filter { height(it) == height }
            .minWithOrNull(
                compareBy<VideoStream> { if (VideoCodecUtils.codecKeyFromStream(it) == codec) 0 else 1 }
                    .thenBy { VideoCodecUtils.codecRankWithPreference(it, preferredCodec) }
                    .thenByDescending { it.bitrate },
            )
    }

    private fun height(stream: VideoStream): Int = VideoCodecUtils.normalizeQualityHeight(VideoCodecUtils.qualityHeightFromStream(stream))
}
