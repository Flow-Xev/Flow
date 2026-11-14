package app.zaid.flow.ai

import app.zaid.flow.data.transcript.TranscriptCue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.stream.StreamSegment

/** One chapter the model proposed. */
internal data class AiChapter(
    val title: String,
    val startSeconds: Int,
)

/**
 * A chapter made by the model rather than written by the uploader. It is a [StreamSegment] like any other so
 * every chapter surface draws it unchanged; the type is how the chapter sheet knows to say so.
 */
internal class AiStreamSegment(
    title: String,
    startTimeSeconds: Int,
) : StreamSegment(title, startTimeSeconds)

private const val MIN_CHAPTERS = 3
private const val MAX_CHAPTERS = 20
private const val MIN_GAP_SECONDS = 15
private const val MAX_TITLE_CHARS = 60

/**
 * Reads the model's reply, a JSON array of `{"start": seconds, "title": "..."}`, into chapters that are safe
 * to draw: in order, inside the video, at least [MIN_GAP_SECONDS] apart, the first at 0:00. Fewer than
 * [MIN_CHAPTERS] (YouTube's own minimum) or an unreadable reply gives none.
 */
internal fun parseAiChapters(
    reply: String,
    durationSeconds: Int,
): List<AiChapter> {
    val open = reply.indexOf('[')
    val close = reply.lastIndexOf(']')
    if (open < 0 || close <= open) return emptyList()
    val items =
        runCatching { Json.parseToJsonElement(reply.substring(open, close + 1)).jsonArray }.getOrNull()
            ?: return emptyList()
    val proposed =
        items
            .mapNotNull { element ->
                runCatching {
                    val obj = element as JsonObject
                    val start = obj["start"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: return@runCatching null
                    val title =
                        obj["title"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.trim()
                            ?.take(MAX_TITLE_CHARS)
                    if (title.isNullOrBlank() || start < 0 || (durationSeconds > 0 && start >= durationSeconds)) {
                        null
                    } else {
                        AiChapter(title, start)
                    }
                }.getOrNull()
            }.sortedBy { it.startSeconds }
    val kept = mutableListOf<AiChapter>()
    for (chapter in proposed) {
        val last = kept.lastOrNull()
        if (last == null || chapter.startSeconds - last.startSeconds >= MIN_GAP_SECONDS) kept += chapter
    }
    if (kept.size < MIN_CHAPTERS) return emptyList()
    if (kept[0].startSeconds != 0) kept[0] = kept[0].copy(startSeconds = 0)
    return kept.take(MAX_CHAPTERS)
}

/**
 * The captions as `[mm:ss] text` lines of about [START_WINDOW_SECONDS] seconds each, widened until the whole
 * transcript fits in [maxChars], so a long video is summarised across its full length instead of cut off.
 */
internal fun condenseTranscript(
    cues: List<TranscriptCue>,
    maxChars: Int,
): String {
    var window = START_WINDOW_SECONDS
    while (true) {
        val text = transcriptLines(cues, window)
        if (text.length <= maxChars || window >= MAX_WINDOW_SECONDS) return text.take(maxChars)
        window *= 2
    }
}

private const val START_WINDOW_SECONDS = 20
private const val MAX_WINDOW_SECONDS = 640

private fun transcriptLines(
    cues: List<TranscriptCue>,
    windowSeconds: Int,
): String {
    val out = StringBuilder()
    val line = StringBuilder()
    var lineStart = -1L

    fun flush() {
        if (line.isNotBlank()) {
            out
                .append('[')
                .append(timestamp(lineStart))
                .append("] ")
                .append(line.toString().trim())
                .append('\n')
        }
        line.clear()
    }
    for (cue in cues) {
        if (lineStart < 0 || cue.startMs - lineStart >= windowSeconds * 1000L) {
            flush()
            lineStart = cue.startMs
        }
        line.append(cue.text.replace('\n', ' ')).append(' ')
    }
    flush()
    return out.toString()
}
