package app.zaid.flow.innertube.pages.reel

import app.zaid.flow.innertube.pages.parseYouTubeViewCount
import app.zaid.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How to ask YouTube for the page after this one: a continuation token and the endpoint that honours it. */
data class SoundContinuation(
    val token: String,
    val viaNext: Boolean = false,
)

/**
 * One page of the Shorts that use a sound.
 *
 * [total] is the count YouTube itself states for the whole sound ("1.2M Shorts"), which is usually far
 * larger than [reels]; null when the page states none.
 */
data class SoundPage(
    val reels: List<ReelLockup>,
    val next: SoundContinuation? = null,
    val total: Long? = null,
)

/** The total YouTube printed, as a number, and the text it was read from. */
data class SoundTotal(
    val count: Long,
    val text: String,
)

private val SOUND_TOTAL = Regex("""^\s*\d[\d.,'’\u00a0\u202f ]*\s?[kKmMbB]?\s+(?:short|video)s?\b.*""", RegexOption.IGNORE_CASE)

/**
 * The "<n> Shorts" / "<n> videos" count a sound page prints for the whole sound, in document order so the
 * header wins over any card further down. Null when the page prints none (or prints it in a language
 * this does not recognise); callers then fall back to what has actually been loaded.
 */
fun JsonElement.soundShortsTotal(): SoundTotal? {
    fun candidate(text: String?): SoundTotal? {
        val value = text?.trim()?.takeIf { it.length <= MAX_TOTAL_TEXT && SOUND_TOTAL.matches(it) } ?: return null
        val count = parseYouTubeViewCount(value).takeIf { it > 0L } ?: return null
        return SoundTotal(count, value)
    }

    fun walk(e: JsonElement): SoundTotal? =
        when (e) {
            is JsonObject -> candidate(e.youtubeText()) ?: e.values.firstNotNullOfOrNull(::walk)
            is JsonArray -> e.firstNotNullOfOrNull(::walk)
            is JsonPrimitive -> if (e.isString) candidate(e.content) else null
        }
    return walk(this)
}

private const val MAX_TOTAL_TEXT = 48
