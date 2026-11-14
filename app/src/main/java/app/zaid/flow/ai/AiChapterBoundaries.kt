package app.zaid.flow.ai

import app.zaid.flow.data.transcript.TranscriptCue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One numbered line of the transcript the model reads. The model answers with line numbers, never with
 * seconds: a number can only point at a place that exists, so a chapter always starts on the exact moment
 * somebody begins a sentence there, instead of a time the model made up.
 */
internal data class TranscriptLine(
    val index: Int,
    val startSeconds: Int,
    val text: String,
)

private const val FIRST_LINE_WINDOW_SECONDS = 12
private const val LAST_LINE_WINDOW_SECONDS = 96
private const val LINE_OVERHEAD_CHARS = 16
private const val MIN_LINE_TEXT_CHARS = 40
private const val MAX_LINE_TEXT_CHARS = 260

/**
 * Groups the captions into numbered lines of about 12 seconds, widening the lines for a very long video
 * and finally shortening what each line says, so the whole video always fits in [maxChars] and no part of
 * the end is cut off.
 */
internal fun buildChapterLines(
    cues: List<TranscriptCue>,
    maxChars: Int,
): List<TranscriptLine> {
    var window = FIRST_LINE_WINDOW_SECONDS
    var lines = groupCues(cues, window)
    while (promptChars(lines) > maxChars && window < LAST_LINE_WINDOW_SECONDS) {
        window *= 2
        lines = groupCues(cues, window)
    }
    if (promptChars(lines) > maxChars && lines.isNotEmpty()) {
        val perLine = max(MIN_LINE_TEXT_CHARS, maxChars / lines.size - LINE_OVERHEAD_CHARS)
        lines = lines.map { it.copy(text = it.text.take(perLine)) }
    }
    return lines
}

private fun promptChars(lines: List<TranscriptLine>): Int = lines.sumOf { it.text.length + LINE_OVERHEAD_CHARS }

private fun groupCues(
    cues: List<TranscriptCue>,
    windowSeconds: Int,
): List<TranscriptLine> {
    val lines = ArrayList<TranscriptLine>()
    val text = StringBuilder()
    var start = -1L

    fun flush() {
        val body = text.toString().trim()
        if (body.isNotEmpty()) lines += TranscriptLine(lines.size, (start / 1000L).toInt(), body.take(MAX_LINE_TEXT_CHARS))
        text.clear()
    }
    for (cue in cues) {
        val spoken = cue.text.replace('\n', ' ').trim()
        if (spoken.isEmpty()) continue
        if (start < 0 || cue.startMs - start >= windowSeconds * 1000L) {
            flush()
            start = cue.startMs
        }
        text.append(spoken).append(' ')
    }
    flush()
    return lines
}

/** The lines as the model reads them: `[12] 03:25 what is said there`. */
internal fun promptTranscript(lines: List<TranscriptLine>): String =
    lines.joinToString("\n") { "[${it.index}] ${timestamp(it.startSeconds * 1000L)} ${it.text}" }

/** How many chapters suit a video of this length: roughly one per four minutes, never fewer than three. */
internal fun chapterCountRange(durationSeconds: Int): IntRange {
    val ideal = durationSeconds / 240.0
    val low = max(3, floor(ideal * 0.6).toInt())
    val high = min(20, max(low + 1, ceil(ideal * 1.4).toInt()))
    return low..high
}

private const val MIN_CHAPTER_SECONDS = 25
private const val MAX_TITLE_WORDS = 8
private const val MAX_TITLE_LENGTH = 60

/**
 * Reads the model's `[{"line": n, "title": "..."}]` reply. Each chapter starts at the start of line `n`, so
 * the time is always one the captions really contain. Chapters come out in order, at least
 * [MIN_CHAPTER_SECONDS] apart, with clean titles, the first at 0:00; fewer than three (YouTube's own
 * minimum) or an unreadable reply gives none.
 */
internal fun parseLineChapters(
    reply: String,
    lines: List<TranscriptLine>,
    durationSeconds: Int,
    allowed: IntRange = chapterCountRange(durationSeconds),
): List<AiChapter> {
    if (lines.isEmpty()) return emptyList()
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
                    val line = obj["line"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: return@runCatching null
                    val title = cleanChapterTitle(obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    if (title.isEmpty() || line !in lines.indices) null else line to title
                }.getOrNull()
            }.sortedBy { it.first }
            .distinctBy { it.first }
    if (proposed.size < MIN_CHAPTER_COUNT) return emptyList()

    val refined = refineBoundaries(lines, proposed.map { it.first })
    var chapters = proposed.mapIndexed { i, (_, title) -> AiChapter(title, lines[refined[i]].startSeconds) }
    // The intro belongs to the first chapter even when its first words come a little after 0:00.
    chapters = chapters.toMutableList().also { it[0] = it[0].copy(startSeconds = 0) }
    chapters = dropCrowded(chapters, durationSeconds)
    chapters = dropRepeatedTitles(chapters)
    chapters = thin(chapters, allowed.last)
    return if (chapters.size < MIN_CHAPTER_COUNT) emptyList() else chapters
}

private const val MIN_CHAPTER_COUNT = 3

private fun dropCrowded(
    chapters: List<AiChapter>,
    durationSeconds: Int,
): List<AiChapter> {
    val kept = ArrayList<AiChapter>()
    for (chapter in chapters) {
        val last = kept.lastOrNull()
        if (last == null || chapter.startSeconds - last.startSeconds >= MIN_CHAPTER_SECONDS) kept += chapter
    }
    // A last chapter squeezed against the end of the video is not a chapter.
    val end = kept.lastOrNull()
    if (durationSeconds > 0 && kept.size > 1 && end != null &&
        durationSeconds - end.startSeconds < MIN_CHAPTER_SECONDS
    ) {
        kept.removeAt(kept.lastIndex)
    }
    return kept
}

/** Two chapters in a row with the same title say nothing the seek bar needs twice. */
private fun dropRepeatedTitles(chapters: List<AiChapter>): List<AiChapter> {
    val kept = ArrayList<AiChapter>()
    for (chapter in chapters) {
        if (kept.lastOrNull()?.title.equals(chapter.title, ignoreCase = true)) continue
        kept += chapter
    }
    return kept
}

/** More chapters than the length warrants: the two closest together lose their later one, until it fits. */
private fun thin(
    chapters: List<AiChapter>,
    limit: Int,
): List<AiChapter> {
    val kept = chapters.toMutableList()
    while (kept.size > limit && kept.size > 1) {
        var closest = 1
        for (i in 2 until kept.size) {
            if (kept[i].startSeconds - kept[i - 1].startSeconds < kept[closest].startSeconds - kept[closest - 1].startSeconds) closest = i
        }
        kept.removeAt(closest)
    }
    return kept
}

private val LEADING_NOISE =
    Regex("""^\s*(?:chapter\s*)?(?:\[?\(?\d{1,2}(?::\d{2}){1,2}\)?\]?|\d+[.):])\s*[-–—:]?\s*""", RegexOption.IGNORE_CASE)

/** A title as the seek bar should show it: no numbering, timestamp or quotes, a few words, no full stop. */
internal fun cleanChapterTitle(raw: String): String {
    var title = raw.trim().replace(Regex("\\s+"), " ").trim(*TITLE_WRAPPERS)
    // Numbering often sits inside the quotes ("1. Setup"), so quotes come off before and after it.
    title =
        title
            .replace(LEADING_NOISE, "")
            .trim()
            .trim(*TITLE_WRAPPERS)
            .trim()
    title = title.trimEnd('.', ',', ';', ':', '!', '-', '–', '—').trim()
    val words = title.split(' ').filter { it.isNotEmpty() }
    if (words.size > MAX_TITLE_WORDS) title = words.take(MAX_TITLE_WORDS).joinToString(" ")
    return title.take(MAX_TITLE_LENGTH).trim()
}

private val TITLE_WRAPPERS = charArrayOf('"', '\'', '“', '”', '‘', '’', '*', '#', '`', ' ')

private const val BLOCK_LINES = 4
private const val SEARCH_RADIUS = 2
private const val DISTANCE_PENALTY = 0.04
private const val MIN_GAIN_TO_MOVE = 0.08

/**
 * Nudges each chosen boundary onto the line where the wording really changes. For every candidate line
 * within [SEARCH_RADIUS] lines of the model's pick, it compares the vocabulary of the few lines before
 * with the few lines after; the sharpest change wins, but only if it beats the model's own pick clearly,
 * so a good pick is never disturbed. Boundaries stay in order and never cross.
 */
internal fun refineBoundaries(
    lines: List<TranscriptLine>,
    chosen: List<Int>,
): List<Int> {
    if (chosen.size < 2 || lines.size < BLOCK_LINES * 2) return chosen
    val bags = lines.map { wordBag(it.text) }
    val refined = chosen.toMutableList()
    for (i in 1 until chosen.size) {
        val original = chosen[i]
        val lower = max(refined[i - 1] + 1, original - SEARCH_RADIUS)
        val upper = min(if (i + 1 < chosen.size) chosen[i + 1] - 1 else lines.lastIndex, original + SEARCH_RADIUS)
        if (lower > upper) continue
        val baseline = shift(bags, original)
        var best = original
        var bestScore = baseline
        for (candidate in lower..upper) {
            val score = shift(bags, candidate) - DISTANCE_PENALTY * abs(candidate - original)
            if (score > bestScore + 1e-9) {
                bestScore = score
                best = candidate
            }
        }
        if (best != original && bestScore - baseline >= MIN_GAIN_TO_MOVE) refined[i] = best
    }
    return refined
}

/** How different the lines just before [boundary] are from the lines just after it, 0 (same) to 1 (nothing shared). */
private fun shift(
    bags: List<Map<String, Int>>,
    boundary: Int,
): Double {
    if (boundary <= 0 || boundary >= bags.size) return 0.0
    val before = merge(bags, max(0, boundary - BLOCK_LINES) until boundary)
    val after = merge(bags, boundary until min(bags.size, boundary + BLOCK_LINES))
    return 1.0 - cosine(before, after)
}

private fun merge(
    bags: List<Map<String, Int>>,
    range: IntRange,
): Map<String, Int> {
    val total = HashMap<String, Int>()
    for (i in range) for ((word, count) in bags[i]) total[word] = (total[word] ?: 0) + count
    return total
}

private fun cosine(
    a: Map<String, Int>,
    b: Map<String, Int>,
): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    var dot = 0.0
    for ((word, count) in a) dot += count * (b[word] ?: 0).toDouble()
    val normA = sqrt(a.values.sumOf { it.toDouble() * it })
    val normB = sqrt(b.values.sumOf { it.toDouble() * it })
    return if (normA == 0.0 || normB == 0.0) 0.0 else dot / (normA * normB)
}

private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

private val STOP_WORDS =
    setOf(
        "the",
        "and",
        "that",
        "this",
        "with",
        "for",
        "you",
        "are",
        "was",
        "were",
        "have",
        "has",
        "had",
        "but",
        "not",
        "they",
        "them",
        "then",
        "than",
        "there",
        "their",
        "what",
        "when",
        "which",
        "who",
        "will",
        "would",
        "could",
        "should",
        "from",
        "your",
        "about",
        "just",
        "like",
        "got",
        "get",
        "going",
        "gonna",
        "want",
        "know",
        "okay",
        "yeah",
        "right",
        "really",
        "very",
        "can",
        "its",
        "it's",
        "i'm",
        "don't",
        "one",
        "all",
        "out",
        "how",
        "why",
        "some",
        "into",
        "also",
        "only",
        "because",
        "here",
        "now",
        "going",
        "let",
        "say",
        "said",
        "thing",
        "things",
    )

private fun wordBag(text: String): Map<String, Int> {
    val bag = HashMap<String, Int>()
    for (word in text.lowercase().split(WORD_SPLIT)) {
        if (word.length < 3 || word in STOP_WORDS) continue
        bag[word] = (bag[word] ?: 0) + 1
    }
    return bag
}
