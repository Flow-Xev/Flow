package app.zaid.flow.data.video.downloader.resolve

import app.zaid.flow.data.local.MusicAudioQuality
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.VideoCodec
import app.zaid.flow.data.video.DefaultDownloadSelection
import app.zaid.flow.data.video.DownloadStreamPolicy
import app.zaid.flow.data.video.downloader.request.DownloadRequest
import app.zaid.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import app.zaid.flow.player.stream.CaptionFormat
import app.zaid.flow.player.stream.CaptionTrackResolver
import app.zaid.flow.player.stream.InnerTubeVideoStreamExtractor
import app.zaid.flow.player.stream.InnerTubeVideoStreamExtractor.VideoExtractionResult
import app.zaid.flow.player.stream.ResolvedCaption
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** The streams one run of a download fetches. */
data class ResolvedStreams(
    val video: Format?,
    val audio: Format,
    val durationMs: Long,
    /** True when the selected video URL already contains its audio track (no remux needed). */
    val muxed: Boolean = false,
    /** The video's caption tracks as WebVTT, from the same response, for the files kept beside it. */
    val captions: List<ResolvedCaption> = emptyList(),
)

sealed interface ResolveOutcome {
    data class Resolved(
        val streams: ResolvedStreams,
    ) : ResolveOutcome

    /** Nothing downloadable: the video is gone, blocked, or offers no stream this request can use. */
    data object Unavailable : ResolveOutcome

    /** Only Opus audio exists, which the MP4 writer does not take. */
    data object NoCompatibleAudio : ResolveOutcome
}

/**
 * Turns a [DownloadRequest] into streams with fresh URLs, through the same InnerTube client ladder
 * playback uses (coalesced with it, so a download of the video on screen costs no second request).
 */
@Singleton
class DownloadStreamResolver
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) {
        suspend fun resolve(
            request: DownloadRequest,
            avoidItags: Set<Int> = emptySet(),
        ): ResolveOutcome {
            val result = InnerTubeVideoStreamExtractor.extract(request.videoId) ?: return ResolveOutcome.Unavailable
            if (result.isLive) return ResolveOutcome.Unavailable
            val outcome = select(request, result, avoidItags, defaults())
            if (outcome !is ResolveOutcome.Resolved || request.wantsAudioOnly) return outcome
            return ResolveOutcome.Resolved(outcome.streams.copy(captions = captionsOf(request, result)))
        }

        private suspend fun captionsOf(
            request: DownloadRequest,
            result: VideoExtractionResult,
        ): List<ResolvedCaption> {
            val translateTo =
                request.subtitle?.takeIf { it.translated }?.languageTag
                    ?: preferences.preferredSubtitleLanguage.first()
            return CaptionTrackResolver.resolve(result.playerResponse, format = CaptionFormat.VTT, translateTo = translateTo)
        }

        private suspend fun defaults(): SelectionDefaults =
            SelectionDefaults(
                height = preferences.defaultDownloadQuality.first().height,
                codec =
                    preferences.defaultDownloadCodec
                        .first()
                        .takeIf { it != VideoCodec.AUTO }
                        ?.codecKey,
                language = preferences.preferredAudioLanguage.first(),
                musicQuality = preferences.musicDownloadQuality.first(),
            )

        internal data class SelectionDefaults(
            val height: Int,
            val codec: String?,
            val language: String?,
            val musicQuality: MusicAudioQuality = MusicAudioQuality.HIGH,
        )

        internal companion object {
            private fun sameLanguage(
                actual: String?,
                requested: String,
            ): Boolean {
                if (actual.isNullOrBlank()) return false
                val actualLanguage = actual.substringBefore('-')
                val requestedLanguage = requested.substringBefore('-')
                return actual.equals(requested, ignoreCase = true) ||
                    actualLanguage.equals(requestedLanguage, ignoreCase = true)
            }

            /** The pure choice, separate from extraction so every rule is unit tested. */
            fun select(
                request: DownloadRequest,
                result: VideoExtractionResult,
                avoidItags: Set<Int>,
                defaults: SelectionDefaults,
            ): ResolveOutcome {
                val audioFormats =
                    DownloadStreamPolicy
                        .buildDownloadAudioFormats(result.audioFormats)
                        .filterNot { it.itag in avoidItags }
                val language = request.audioLanguage ?: defaults.language
                val durationMs = result.durationMs()
                // A song is only its audio, so it is the one download the song quality setting shapes.
                val quality = if (request.wantsAudioOnly) defaults.musicQuality else MusicAudioQuality.HIGH

                val audio =
                    request.audioItag
                        ?.let { itag -> audioFormats.firstOrNull { it.itag == itag } }
                        ?.takeIf { candidate ->
                            DownloadStreamPolicy.isAacFormat(candidate) &&
                                (request.audioTrackId == null || candidate.audioTrack?.id == request.audioTrackId) &&
                                (request.audioLanguage == null || sameLanguage(candidate.audioLanguageTag, request.audioLanguage))
                        }
                        // A stale itag must never silently select a different dub (for example Arabic).
                        // Re-resolve by the user's chosen track/language when the exact stream changed.
                        ?: request.audioTrackId?.let { id ->
                            DownloadStreamPolicy.pickAacAudio(audioFormats, language, quality, trackId = id)
                        }
                        ?: DownloadStreamPolicy.pickAacAudio(audioFormats, language, quality)
                        ?: return if (result.audioFormats.any { !it.url.isNullOrBlank() }) {
                            ResolveOutcome.NoCompatibleAudio
                        } else {
                            ResolveOutcome.Unavailable
                        }

                if (request.wantsAudioOnly) {
                    return ResolveOutcome.Resolved(ResolvedStreams(video = null, audio = audio, durationMs = durationMs))
                }

                val videoFormats =
                    DownloadStreamPolicy
                        .buildDownloadVideoFormats(result.videoFormats, separateMuxed = true)
                        .filterNot { it.itag in avoidItags }
                request.videoItag
                    ?.let { itag -> videoFormats.firstOrNull { it.itag == itag } }
                    ?.takeIf { selected -> !isMuxed(selected) || (isMp4Muxed(selected) && sameAudioTrack(selected, audio)) }
                    ?.let { selected ->
                        // Keep the dialog's requested quality/codec, but prefer a compatible
                        // single-file MP4 at that same quality when the chosen itag is video-only.
                        // This avoids a full audio/video remux without changing the selected dub.
                        val noMergeAlternative =
                            if (isMuxed(selected)) {
                                selected
                            } else {
                                videoFormats.firstOrNull { candidate ->
                                    isMp4Muxed(candidate) &&
                                        DownloadStreamPolicy.videoHeight(candidate) == DownloadStreamPolicy.videoHeight(selected) &&
                                        DownloadStreamPolicy.videoCodecKey(candidate) == DownloadStreamPolicy.videoCodecKey(selected) &&
                                        sameAudioTrack(candidate, audio)
                                } ?: selected
                            }
                        return ResolveOutcome.Resolved(
                            ResolvedStreams(noMergeAlternative, audio, durationMs, muxed = isMuxed(noMergeAlternative)),
                        )
                    }

                val height =
                    DefaultDownloadSelection.pickHeight(
                        videoFormats.map(DownloadStreamPolicy::videoHeight),
                        request.targetHeight ?: defaults.height,
                    ) ?: return ResolveOutcome.Unavailable
                val atHeight = videoFormats.filter { DownloadStreamPolicy.videoHeight(it) == height }
                val codec =
                    DefaultDownloadSelection
                        .rankCodecs(atHeight.map(DownloadStreamPolicy::videoCodecKey), request.videoCodec ?: defaults.codec)
                        .firstOrNull() ?: return ResolveOutcome.Unavailable
                val codecFormats = atHeight.filter { DownloadStreamPolicy.videoCodecKey(it) == codec }

                // Prefer a single-file MP4 when YouTube supplies one for the same audio language.
                // This avoids downloading two independent tracks and running a costly remux pass.
                val muxed = codecFormats.firstOrNull { candidate ->
                    isMp4Muxed(candidate) && sameAudioTrack(candidate, audio)
                }
                val video = muxed ?: codecFormats.firstOrNull { !isMuxed(it) } ?: codecFormats.first()
                return ResolveOutcome.Resolved(ResolvedStreams(video, audio, durationMs, muxed = isMuxed(video)))
            }


            private fun isMuxed(format: Format): Boolean =
                format.audioQuality != null || format.audioSampleRate != null || format.audioChannels != null

            private fun isMp4Muxed(format: Format): Boolean =
                isMuxed(format) && format.mimeType.substringBefore(';').equals("video/mp4", ignoreCase = true) &&
                    format.mimeType.contains("mp4a", ignoreCase = true)

            private fun sameAudioTrack(muxed: Format, selectedAudio: Format): Boolean {
                val muxedId = muxed.audioTrack?.id
                val selectedId = selectedAudio.audioTrack?.id
                if (!muxedId.isNullOrBlank() && !selectedId.isNullOrBlank()) return muxedId == selectedId
                val muxedLanguage = muxed.audioLanguageTag
                val selectedLanguage = selectedAudio.audioLanguageTag
                // A video with a single, unlabelled audio track has nothing to mismatch: any muxed copy
                // carries that same audio, so it can be saved without a merge.
                if (muxedId.isNullOrBlank() && selectedId.isNullOrBlank() && muxedLanguage.isNullOrBlank() && selectedLanguage.isNullOrBlank()) return true
                return !muxedLanguage.isNullOrBlank() && !selectedLanguage.isNullOrBlank() &&
                    sameLanguage(muxedLanguage, selectedLanguage)
            }

            private fun VideoExtractionResult.durationMs(): Long =
                (audioFormats + videoFormats).firstNotNullOfOrNull { it.approxDurationMs?.toLongOrNull() }
                    ?: playerResponse.videoDetails
                        ?.lengthSeconds
                        ?.toLongOrNull()
                        ?.times(1000L) ?: 0L
        }
    }
