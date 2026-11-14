package app.zaid.flow.data.video

import app.zaid.flow.data.local.MusicAudioQuality
import app.zaid.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import app.zaid.flow.player.stream.VideoCodecUtils
import app.zaid.flow.player.stream.preferNonDrc
import java.util.Locale
import kotlin.math.abs

/**
 * Which formats a download offers and which audio track it pairs with a video track. Pure policy:
 * no Compose, no resources, no Android UI, so every rule here is unit-testable.
 */
object DownloadStreamPolicy {
    /**
     * Codec order within one resolution for a *download*, which is deliberately not
     * [VideoCodecUtils.playbackCodecRank]: playback ranks h264 first because it is the codec every
     * decoder handles, while a download ranks vp9 first because it is the smallest file the app can
     * mux reliably, and av1 above the legacy vp8/hevc ladder.
     */
    val DOWNLOAD_CODEC_PRIORITY = mapOf("vp9" to 0, "h264" to 1, "av1" to 2, "vp8" to 3, "hevc" to 4)

    private const val UNRANKED_CODEC = 99
    private const val AUDIO_MP4 = "audio/mp4"
    private const val AUDIO_WEBM = "audio/webm"
    private const val MEDIUM_BITRATE = 128_000
    private val VIDEO_CONTAINERS = setOf("video/mp4", "video/webm", "video/3gpp")

    /** Codecs the MP4 muxer takes only with AAC; the Matroska writer takes any audio. */

    fun videoHeight(format: Format): Int = VideoCodecUtils.qualityHeightFromFormat(format.qualityLabel, format.height ?: 0)

    fun videoCodecKey(format: Format): String = VideoCodecUtils.codecKeyFromMimeType(format.mimeType)

    fun isHdr(format: Format): Boolean = format.colorInfo?.isHdr == true

    /** "VP9 1080p", "VP9 1080p60", or with [hdrLabel] appended for an HDR format. */
    fun videoQualityLabel(
        format: Format,
        hdrLabel: String,
    ): String {
        val base =
            "${VideoCodecUtils.codecLabelFromKey(videoCodecKey(format))} " +
                VideoCodecUtils.qualityLabelWithFrameRate(videoHeight(format), format.fps ?: 0)
        return if (isHdr(format)) "$base $hdrLabel" else base
    }

    /**
     * The video ladder both download dialogs show: one entry per (resolution, codec, frame rate,
     * HDR), highest resolution first, [DOWNLOAD_CODEC_PRIORITY] within a resolution, then SDR before
     * HDR and the higher frame rate first. Formats with no URL are dropped: selecting one can only
     * fail.
     */
    fun buildDownloadVideoFormats(
        formats: List<Format>,
        separateMuxed: Boolean = false,
    ): List<Format> =
        formats
            .filter { !it.url.isNullOrBlank() && it.height != null && containerOf(it.mimeType) in VIDEO_CONTAINERS }
            // Keep muxed and video-only variants separate: the resolver may prefer a single-file
            // stream to avoid a second transfer and a remux pass.
            .distinctBy {
                listOf(
                    videoHeight(it),
                    videoCodecKey(it),
                    it.fps ?: 0,
                    isHdr(it),
                    separateMuxed && (it.audioQuality != null || it.audioSampleRate != null || it.audioChannels != null),
                )
            }
            .sortedWith(
                compareByDescending<Format> { videoHeight(it) }
                    .thenBy { DOWNLOAD_CODEC_PRIORITY[videoCodecKey(it)] ?: UNRANKED_CODEC }
                    .thenBy { isHdr(it) }
                    .thenByDescending { it.fps ?: 0 },
            )

    private fun audioBitrate(format: Format): Int = format.averageBitrate ?: format.bitrate

    fun audioBitrateKbps(format: Format): Int {
        val raw = format.averageBitrate?.takeIf { it > 0 } ?: format.bitrate
        return if (raw > 1000) raw / 1000 else raw.coerceAtLeast(0)
    }

    fun audioFormatLabel(
        format: Format,
        unknownLabel: String = "",
    ): String =
        when (containerOf(format.mimeType)) {
            AUDIO_WEBM -> if ("opus" in format.mimeType.lowercase()) "OPUS" else "WEBM"
            AUDIO_MP4 -> "M4A"
            else -> unknownLabel
        }

    fun audioLanguageLabel(format: Format): String? =
        format.audioTrack?.displayName?.takeIf { it.isNotBlank() }
            ?: audioLocale(format)?.displayLanguage?.takeIf { it.isNotBlank() }
            ?: format.audioTrack?.id?.takeIf { it.isNotBlank() }

    fun audioTrackTypeLabel(
        format: Format,
        originalLabel: String,
        dubbedLabel: String,
    ): String = if (format.isOriginal) originalLabel else dubbedLabel

    /**
     * The audio formats a download can use: AAC only, since every download is an MP4 or M4A and
     * Media3 cuts the start of Opus in MP4. DRC twins and URL-less formats are dropped, one entry
     * per (bitrate, track, language, role), highest bitrate first.
     */
    fun buildDownloadAudioFormats(formats: List<Format>): List<Format> =
        formats
            .preferNonDrc()
            .filter { !it.url.isNullOrBlank() && isAacFormat(it) }
            .distinctBy { format ->
                listOf(
                    audioBitrateKbps(format).toString(),
                    format.audioTrack?.id.orEmpty(),
                    audioLocale(format)?.toLanguageTag().orEmpty(),
                    format.isOriginal.toString(),
                ).joinToString("|")
            }.sortedWith(
                compareByDescending<Format> { audioBitrateKbps(it) }
                    .thenBy { audioLocale(it)?.displayLanguage.orEmpty() },
            )

    /**
     * The AAC audio of a download, which is always an MP4 or M4A: the chosen language's AAC when it
     * has one, otherwise any AAC, otherwise none. Opus is never taken, because Opus in MP4 loses its
     * first 300 ms in Media3 (androidx/media#3431). The best one wins, unless [quality] asks for
     * Medium (nearest 128 kbps) or Low (the smallest).
     */
    fun pickAacAudio(
        allAudio: List<Format>,
        preferredLang: String?,
        quality: MusicAudioQuality = MusicAudioQuality.HIGH,
        trackId: String? = null,
    ): Format? {
        val aac = allAudio.filter(::isAacFormat)
        if (aac.isEmpty()) return null
        val chosenTrack = trackId?.let { id -> aac.filter { it.audioTrack?.id == id } }.orEmpty()
        val inLanguage =
            if (!preferredLang.isNullOrEmpty() && preferredLang != "original") {
                aac.filter {
                    val locale = audioLocale(it)
                    locale?.language.equals(preferredLang, ignoreCase = true) ||
                        locale?.toLanguageTag().equals(preferredLang, ignoreCase = true)
                }
            } else {
                emptyList()
            }
        // Never fall back to "the loudest of every language": that is how a dubbed (e.g. Arabic) track
        // used to win. The original track, then the track marked default, then a track-less stream.
        val candidates =
            chosenTrack.ifEmpty {
                inLanguage.ifEmpty {
                    aac.filter { it.isOriginal }.ifEmpty {
                        aac.filter { it.audioTrack?.audioIsDefault == true }.ifEmpty {
                            aac.filter { it.audioTrack == null }.ifEmpty { aac }
                        }
                    }
                }
            }
        return when (quality) {
            MusicAudioQuality.MEDIUM -> candidates.minByOrNull { abs(audioBitrate(it) - MEDIUM_BITRATE) }
            MusicAudioQuality.LOW -> candidates.minByOrNull(::audioBitrate)
            MusicAudioQuality.HIGH, MusicAudioQuality.AUTO -> candidates.maxByOrNull(::audioBitrate)
        }
    }

    /** One selectable audio language of a video: its track id, language tag and a readable label. */
    data class AudioTrackChoice(
        val trackId: String?,
        val languageTag: String?,
        val label: String,
        val isOriginal: Boolean,
    )

    /** The distinct audio tracks a download can pick between, the original first. Empty or one entry when the video has no dubs. */
    fun audioTrackChoices(formats: List<Format>): List<AudioTrackChoice> =
        formats
            .filter(::isAacFormat)
            .distinctBy { format ->
                // Some InnerTube clients omit track ids. Keep separate language variants
                // instead of collapsing every id-less dub into one (often Arabic).
                format.audioTrack?.id?.takeIf { it.isNotBlank() }
                    ?: audioLocale(format)?.toLanguageTag().orEmpty()
            }
            .map {
                AudioTrackChoice(
                    trackId = it.audioTrack?.id,
                    languageTag = it.audioLanguageTag,
                    label = audioLanguageLabel(it).orEmpty(),
                    isOriginal = it.isOriginal,
                )
            }.sortedByDescending { it.isOriginal }

    fun isAacFormat(format: Format): Boolean = containerOf(format.mimeType) == AUDIO_MP4

    private fun containerOf(mimeType: String): String = mimeType.substringBefore(';').trim().lowercase()

    private fun audioLocale(format: Format): Locale? =
        format.audioLanguageTag
            ?.let { tag -> runCatching { Locale.forLanguageTag(tag) }.getOrNull() }
            ?.takeIf { it.language.isNotBlank() }
}
