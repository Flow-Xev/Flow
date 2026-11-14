package app.zaid.flow.data.shorts

import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.player.quality.AdaptiveLadder
import app.zaid.flow.player.quality.AutoQualityBounds
import app.zaid.flow.player.resolver.ManifestGenerator
import app.zaid.flow.player.stream.InnerTubeStreamBridge
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Wraps a resolved Shorts stream URL in a single-representation DASH manifest.
 *
 * This is what stops YouTube throttling the stream. A plain progressive GET against a googlevideo
 * URL is capped at roughly 50-100 KB/s once the opening burst is spent — enough for VP9 or AV1 at
 * Shorts resolutions, but not for H.264, which needs about twice the bitrate for the same picture.
 * That is why a reel plays for a few seconds and then buffers forever on H.264 while the same reel
 * is fine on AV1 (#917). A manifest carrying the format's init/index byte ranges makes ExoPlayer
 * fetch the stream in ranged chunks instead, which is not throttled.
 *
 * The main player has always done this ([ManifestGenerator], via `VideoPlaybackResolver`); the
 * Shorts pool went straight to `ProgressiveMediaSource` and so never got the benefit.
 *
 * Returns null when a manifest cannot be built — no byte ranges, no duration, an OTF stream. The
 * caller then falls back to a progressive source, which is exactly what Shorts did before.
 */
internal object ShortsDashManifest {
    fun forVideo(
        format: PlayerResponse.StreamingData.Format,
        resolvedUrl: String,
        durationMs: Long?,
    ): String? =
        InnerTubeStreamBridge
            .convertVideoFormats(listOf(format.copy(url = resolvedUrl)))
            .firstOrNull()
            ?.let { forStream(it, durationMs) }

    fun forAudio(
        format: PlayerResponse.StreamingData.Format,
        resolvedUrl: String,
        durationMs: Long?,
    ): String? =
        InnerTubeStreamBridge
            .convertAudioFormats(listOf(format.copy(url = resolvedUrl)))
            .firstOrNull()
            ?.let { forStream(it, durationMs) }

    /**
     * The NewPipe leg already hands us streams carrying an [org.schabi.newpipe.extractor.services.youtube.ItagItem],
     * so they need no conversion.
     */
    fun forStream(
        stream: Stream,
        durationMs: Long?,
    ): String? {
        val itag = stream.itagItem ?: return null
        val durationSeconds = (durationMs ?: return null) / 1000L
        if (durationSeconds <= 0L) return null
        if (stream.content.isNullOrBlank()) return null
        if (itag.initEnd <= 0 || itag.indexStart < 0 || itag.indexEnd <= 0) return null
        return ManifestGenerator.generateProgressiveManifest(stream, itag, durationSeconds)
    }

    /**
     * One multi-quality DASH manifest for Shorts Auto, so Media3's adaptive selection switches
     * between the rungs from the live bandwidth estimate, as YouTube's own Auto does. The ladder
     * holds one stream per height in [anchor]'s codec. Null when fewer than two rungs qualify.
     */
    fun forLadder(
        formats: List<PlayerResponse.StreamingData.Format>,
        anchor: PlayerResponse.StreamingData.Format,
        durationMs: Long?,
    ): String? {
        val durationSeconds = (durationMs ?: return null) / 1000.0
        if (durationSeconds <= 0.0) return null
        val streams = InnerTubeStreamBridge.convertVideoFormats(formats)
        val anchorStream = InnerTubeStreamBridge.convertVideoFormats(listOf(anchor)).firstOrNull() ?: return null
        val rungs = AdaptiveLadder.rungs(anchorStream, streams, AutoQualityBounds.currentShorts())
        if (rungs.size < 2) return null
        val mime = anchorStream.format?.mimeType ?: return null
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" minBufferTime=\"PT1.5S\" ")
            append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" mediaPresentationDuration=\"PT${durationSeconds}S\">")
            append("<Period><AdaptationSet mimeType=\"$mime\" subsegmentAlignment=\"true\">")
            rungs.forEach { append(representation(it)) }
            append("</AdaptationSet></Period></MPD>")
        }
    }

    private fun representation(stream: VideoStream): String {
        val itag = stream.itagItem!!
        val url = stream.content.replace("&", "&amp;").replace("<", "&lt;")
        return "<Representation id=\"${itag.id}\" bandwidth=\"${itag.bitrate}\" codecs=\"${itag.codec}\" " +
            "width=\"${itag.width}\" height=\"${itag.height}\"" +
            (if (itag.fps > 0) " frameRate=\"${itag.fps}\"" else "") + ">" +
            "<BaseURL>$url</BaseURL>" +
            "<SegmentBase indexRange=\"${itag.indexStart}-${itag.indexEnd}\">" +
            "<Initialization range=\"${itag.initStart}-${itag.initEnd}\"/></SegmentBase></Representation>"
    }
}
