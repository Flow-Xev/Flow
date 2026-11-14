package app.zaid.flow.player.stream

import android.util.Log
import app.zaid.flow.innertube.models.ResponseContext
import app.zaid.flow.innertube.models.Thumbnail
import app.zaid.flow.innertube.models.Thumbnails
import app.zaid.flow.innertube.models.YouTubeClient
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.player.error.PlayerDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * The very last resort: asks the NewPipe extractor for a video's streams once every InnerTube
 * client in [InnerTubeVideoStreamExtractor]'s ladder has failed.
 *
 * It answers in the same shape as the InnerTube ladder ([InnerTubeVideoStreamExtractor.VideoExtractionResult]),
 * so nothing downstream has to know which stack produced the streams. Only direct progressive
 * streams are taken; manifest-delivered streams are skipped because the rest of the pipeline builds
 * its own DASH manifests from itags and byte ranges.
 */
internal object NewPipeStreamFallback {
    private const val TAG = "NewPipeStreamFallback"
    private const val TIMEOUT_MS = 20_000L

    suspend fun resolve(videoId: String): InnerTubeVideoStreamExtractor.VideoExtractionResult? =
        withContext(Dispatchers.IO) {
            try {
                val info =
                    withTimeoutOrNull(TIMEOUT_MS) {
                        StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
                    } ?: return@withContext fail(videoId, "timed out")
                toResult(videoId, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(videoId, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

    private fun toResult(
        videoId: String,
        info: StreamInfo,
    ): InnerTubeVideoStreamExtractor.VideoExtractionResult? {
        val isLive = info.streamType == StreamType.LIVE_STREAM
        val hls = info.hlsUrl?.takeIf { it.isNotBlank() }
        val dash = info.dashMpdUrl?.takeIf { it.isNotBlank() }
        val video = info.videoOnlyStreams.orEmpty().mapNotNull { it.toFormat() }
        val audio = info.audioStreams.orEmpty().mapNotNull { it.toFormat() }

        if (isLive && (hls != null || dash != null)) {
            Log.w(TAG, "NewPipe live manifest for $videoId (hls=${hls != null}, dash=${dash != null})")
            PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via NewPipe (live manifest) — last resort")
            return InnerTubeVideoStreamExtractor.VideoExtractionResult(
                videoFormats = emptyList(),
                audioFormats = emptyList(),
                playerResponse = stubResponse(videoId, info, emptyList()),
                usedClient = YouTubeClient.WEB,
                sabrInfo = null,
                isLive = true,
                liveHlsUrl = hls,
                liveDashUrl = dash,
            )
        }
        if (video.isEmpty() || audio.isEmpty()) return fail(videoId, "no direct streams (video=${video.size}, audio=${audio.size})")

        Log.w(TAG, "NewPipe streams for $videoId: ${video.size} video, ${audio.size} audio")
        PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via NewPipe (${video.size}v/${audio.size}a) — last resort")
        return InnerTubeVideoStreamExtractor.VideoExtractionResult(
            videoFormats = video,
            audioFormats = audio,
            playerResponse = stubResponse(videoId, info, video + audio),
            usedClient = YouTubeClient.WEB,
            sabrInfo = null,
        )
    }

    private fun VideoStream.toFormat(): PlayerResponse.StreamingData.Format? {
        if (!isUrl || deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return null
        val url = content?.takeIf { it.isNotBlank() } ?: return null
        val mime = format?.mimeType ?: return null
        val h = height.takeIf { it > 0 } ?: return null
        val w = width.takeIf { it > 0 } ?: return null
        val item = itagItem
        return PlayerResponse.StreamingData.Format(
            itag = item?.id ?: itag,
            url = url,
            mimeType = withCodec(mime, codec),
            bitrate = bitrate.coerceAtLeast(0),
            width = w,
            height = h,
            contentLength = item?.contentLength?.takeIf { it > 0 },
            quality = resolution ?: "${h}p",
            fps = fps.takeIf { it > 0 },
            qualityLabel = resolution ?: "${h}p",
            averageBitrate = bitrate.takeIf { it > 0 },
            audioQuality = null,
            approxDurationMs = item?.approxDurationMs?.takeIf { it > 0 }?.toString(),
            audioSampleRate = null,
            audioChannels = null,
            loudnessDb = null,
            lastModified = item?.lastModified?.takeIf { it > 0 },
            signatureCipher = null,
            initRange = item?.rangeOrNull(init = true),
            indexRange = item?.rangeOrNull(init = false),
        )
    }

    private fun AudioStream.toFormat(): PlayerResponse.StreamingData.Format? {
        if (!isUrl || deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return null
        val url = content?.takeIf { it.isNotBlank() } ?: return null
        val mime = format?.mimeType ?: return null
        val item = itagItem
        val avg = averageBitrate.takeIf { it > 0 } ?: bitrate.takeIf { it > 0 }
        val track =
            if (audioTrackId != null || audioTrackName != null) {
                PlayerResponse.StreamingData.Format.AudioTrack(
                    displayName = audioTrackName,
                    id = audioTrackId,
                    isAutoDubbed = null,
                    audioIsDefault = audioTrackType == AudioTrackType.ORIGINAL,
                )
            } else {
                null
            }
        return PlayerResponse.StreamingData.Format(
            itag = item?.id ?: itag,
            url = url,
            mimeType = withCodec(mime, codec),
            bitrate = (avg ?: 0).coerceAtLeast(0),
            width = null,
            height = null,
            contentLength = item?.contentLength?.takeIf { it > 0 },
            quality = "tiny",
            fps = null,
            qualityLabel = null,
            averageBitrate = avg,
            audioQuality = null,
            approxDurationMs = item?.approxDurationMs?.takeIf { it > 0 }?.toString(),
            audioSampleRate = item?.sampleRate?.takeIf { it > 0 },
            audioChannels = item?.audioChannels?.takeIf { it > 0 },
            loudnessDb = null,
            lastModified = item?.lastModified?.takeIf { it > 0 },
            signatureCipher = null,
            audioTrack = track,
            initRange = item?.rangeOrNull(init = true),
            indexRange = item?.rangeOrNull(init = false),
        )
    }

    private fun org.schabi.newpipe.extractor.services.youtube.ItagItem.rangeOrNull(
        init: Boolean,
    ): PlayerResponse.StreamingData.Format.Range? {
        val start = if (init) initStart else indexStart
        val end = if (init) initEnd else indexEnd
        if (end <= 0) return null
        return PlayerResponse.StreamingData.Format.Range(start = start.toString(), end = end.toString())
    }

    private fun withCodec(
        mime: String,
        codec: String?,
    ): String = if (codec.isNullOrBlank() || mime.contains("codecs=")) mime else "$mime; codecs=\"$codec\""

    private fun stubResponse(
        videoId: String,
        info: StreamInfo,
        formats: List<PlayerResponse.StreamingData.Format>,
    ) = PlayerResponse(
        responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
        playabilityStatus = PlayerResponse.PlayabilityStatus(status = "OK", reason = null),
        playerConfig = null,
        streamingData = PlayerResponse.StreamingData(adaptiveFormats = formats),
        videoDetails =
            PlayerResponse.VideoDetails(
                videoId = videoId,
                title = info.name,
                author = info.uploaderName,
                channelId =
                    info.uploaderUrl
                        ?.substringAfter("/channel/", "")
                        ?.substringBefore('/')
                        .orEmpty(),
                shortDescription = info.description?.content,
                lengthSeconds = info.duration.coerceAtLeast(0).toString(),
                viewCount = info.viewCount.takeIf { it >= 0 }?.toString(),
                thumbnail =
                    Thumbnails(
                        info.thumbnails.orEmpty().map {
                            Thumbnail(
                                url = it.url,
                                width = it.width.takeIf { w -> w > 0 },
                                height =
                                    it.height.takeIf { h ->
                                        h >
                                            0
                                    },
                            )
                        },
                    ),
                isLive = info.streamType == StreamType.LIVE_STREAM,
                isLiveContent = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.POST_LIVE_STREAM,
            ),
        playbackTracking = null,
    )

    private fun fail(
        videoId: String,
        why: String,
    ): InnerTubeVideoStreamExtractor.VideoExtractionResult? {
        Log.w(TAG, "NewPipe last resort failed for $videoId: $why")
        PlayerDiagnostics.logWarning(TAG, "NewPipe last resort failed for $videoId: $why")
        return null
    }
}
