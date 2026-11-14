package app.zaid.flow.innertube.pages.reel

import app.zaid.flow.innertube.pages.objectOrNull
import app.zaid.flow.innertube.pages.renderer.largestImageUrl
import app.zaid.flow.innertube.pages.stringOrNull
import app.zaid.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue

/** The browse id YouTube uses for every sound page; the sound itself rides in the params. */
const val SOUND_BROWSE_ID = "FEsfv_audio_pivot"

/**
 * What a reel's sound (pivot) button tells us, read in one pass over the response.
 *
 * A reel that reuses someone else's audio is keyed by that source video, so [sourceIds] can name videos
 * other than the reel itself. [panelTokens] belong to the in-player audio panel, used only as a last resort.
 */
data class SoundAnchor(
    val browseId: String? = null,
    val params: String? = null,
    val sourceIds: List<String> = emptyList(),
    val panelTokens: List<String> = emptyList(),
    val title: String? = null,
    val thumbnailUrl: String? = null,
) {
    val hasEndpoint: Boolean get() = !browseId.isNullOrBlank()
}

private val SOURCE_LINK = Regex("""/source/([A-Za-z0-9_-]{11})""")

private class AnchorScan {
    /** Every sound button in the response, in document order. A response can carry several (header, rail, panel). */
    val pivots = ArrayList<JsonObject>()

    /** The first audio-pivot browse endpoint found anywhere, for responses whose pivot button has no link of its own. */
    var looseSoundEndpoint: JsonObject? = null
    val sourceIds = LinkedHashSet<String>()
    val tokens = LinkedHashSet<String>()

    fun walk(
        element: JsonElement,
        inAudioPanel: Boolean,
    ) {
        when (element) {
            is JsonObject -> {
                (element["pivotButtonRenderer"] ?: element["pivotButtonViewModel"]).objectOrNull()?.let(pivots::add)
                if (looseSoundEndpoint == null) {
                    element["browseEndpoint"]
                        .objectOrNull()
                        ?.takeIf { it["browseId"].stringOrNull() == SOUND_BROWSE_ID && !it["params"].stringOrNull().isNullOrBlank() }
                        ?.let { looseSoundEndpoint = it }
                }
                val panelId = element["panelIdentifier"].stringOrNull() ?: element["targetId"].stringOrNull()
                val audio = inAudioPanel || panelId?.contains("audio-pivot") == true
                if (audio) {
                    element["continuationCommand"]
                        .objectOrNull()
                        ?.get("token")
                        .stringOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(tokens::add)
                }
                element.values.forEach { walk(it, audio) }
            }

            is JsonArray -> {
                element.forEach { walk(it, inAudioPanel) }
            }

            is JsonPrimitive -> {
                if (element.isString && element.content.contains("/source/")) {
                    SOURCE_LINK.findAll(element.content).forEach { sourceIds.add(it.groupValues[1]) }
                }
            }
        }
    }
}

private fun JsonElement.firstBrowseEndpoint(): JsonObject? =
    when (this) {
        is JsonObject -> this["browseEndpoint"].objectOrNull() ?: values.firstNotNullOfOrNull { it.firstBrowseEndpoint() }
        is JsonArray -> firstNotNullOfOrNull { it.firstBrowseEndpoint() }
        else -> null
    }

/**
 * Reads the sound button of a `reel_item_watch` response or a Shorts page's data. [selfId] is the reel's own
 * id; it is kept out of [SoundAnchor.sourceIds] because a link back to the reel itself says nothing new.
 */
fun JsonElement.soundAnchor(selfId: String = ""): SoundAnchor {
    val scan = AnchorScan()
    scan.walk(this, inAudioPanel = false)
    // The pivot that links to the audio page wins; otherwise any pivot with a link; otherwise the first one,
    // which still names the sound and its cover even when it cannot be opened.
    val pivot =
        scan.pivots.firstOrNull { it.firstBrowseEndpoint()?.get("browseId").stringOrNull() == SOUND_BROWSE_ID }
            ?: scan.pivots.firstOrNull { it.firstBrowseEndpoint() != null }
            ?: scan.pivots.firstOrNull()
    val endpoint = pivot?.firstBrowseEndpoint() ?: scan.looseSoundEndpoint
    // `soundAttributionTitle` is plain text, runs or a view-model `content` depending on the response era.
    val title =
        pivot?.get("soundAttributionTitle").youtubeText()
            ?: pivot?.get("title").youtubeText()
            ?: pivot?.get("contentDescription").youtubeText()
    return SoundAnchor(
        browseId = endpoint?.get("browseId").stringOrNull()?.takeIf { it.isNotBlank() },
        params = endpoint?.get("params").stringOrNull()?.takeIf { it.isNotBlank() },
        sourceIds = scan.sourceIds.filter { it != selfId },
        panelTokens = scan.tokens.toList(),
        title = title?.trim()?.takeIf { it.isNotEmpty() },
        thumbnailUrl = pivot?.get("thumbnail").largestImageUrl() ?: pivot?.get("image").largestImageUrl(),
    )
}

// ---- audio pivot params ----------------------------------------------------------------------

private fun varint(value: Int): ByteArray {
    var n = value
    val out = ArrayList<Byte>(2)
    while (true) {
        val low = n and 0x7F
        n = n ushr 7
        if (n != 0) {
            out.add((low or 0x80).toByte())
        } else {
            out.add(low.toByte())
            break
        }
    }
    return out.toByteArray()
}

private fun lengthDelimited(
    tag: Int,
    body: ByteArray,
): ByteArray = byteArrayOf(tag.toByte()) + varint(body.size) + body

/**
 * The `bp` value of youtube.com/feed/sfv_audio_pivot, built the way yt-dlp builds it: field 94 holds a message
 * whose field 2 holds the video id three times (tags 1, 2 and 3). Lengths are real varints, so the encoding
 * stays valid for any id length. For `2GtVksBMYFM` this is `8gUrCikSJwoLMkd0VmtzQk1ZRk0SCzJHdFZrc0JNWUZNGgsyR3RWa3NCTVlGTQ==`.
 */
fun audioPivotParams(videoId: String): String {
    val id = videoId.toByteArray(Charsets.UTF_8)
    val triple = lengthDelimited(0x0A, id) + lengthDelimited(0x12, id) + lengthDelimited(0x1A, id)
    val message = lengthDelimited(0x0A, lengthDelimited(0x12, triple))
    val bytes = byteArrayOf(0xF2.toByte(), 0x05) + varint(message.size) + message
    return Base64.getEncoder().encodeToString(bytes)
}

// ---- page data -------------------------------------------------------------------------------

private val INITIAL_DATA_ASSIGN = Regex("""ytInitialData["']?\]?\s*=\s*""")

/** Index of the brace that closes the object opening at [start], skipping braces inside strings; -1 when unbalanced. */
internal fun balancedObjectEnd(
    text: String,
    start: Int,
): Int {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                inString = false
            }
        } else if (c == '"') {
            inString = true
        } else if (c == '{') {
            depth++
        } else if (c == '}') {
            depth--
            if (depth == 0) return i
        }
    }
    return -1
}

/** Decodes the `\xNN`, `\uNNNN` and `\\` escapes of a single-quoted JS string literal body. */
internal fun decodeJsStringBody(body: String): String {
    val out = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '\\' || i + 1 >= body.length) {
            out.append(c)
            i++
            continue
        }
        when (val next = body[i + 1]) {
            'x' -> {
                val code = body.substring(i + 2, minOf(i + 4, body.length)).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 4
                } else {
                    out.append(next)
                    i += 2
                }
            }

            'u' -> {
                val code = body.substring(i + 2, minOf(i + 6, body.length)).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 6
                } else {
                    out.append(next)
                    i += 2
                }
            }

            'n' -> {
                out.append('\n')
                i += 2
            }

            't' -> {
                out.append('\t')
                i += 2
            }

            else -> {
                out.append(next)
                i += 2
            }
        }
    }
    return out.toString()
}

/**
 * The `ytInitialData` object embedded in a youtube.com page, whichever way the page spells it: a plain
 * object literal (`var ytInitialData = {...};`) or a quoted, hex-escaped string (`ytInitialData = '\x7b...'`).
 * Null when the page carries none or it cannot be read; callers treat that as "this source has nothing".
 */
fun extractYtInitialData(html: String): JsonObject? {
    for (match in INITIAL_DATA_ASSIGN.findAll(html)) {
        val at = match.range.last + 1
        if (at >= html.length) continue
        val parsed =
            runCatching {
                when (html[at]) {
                    '{' -> {
                        val end = balancedObjectEnd(html, at)
                        if (end <= at) return@runCatching null
                        Json.parseToJsonElement(html.substring(at, end + 1))
                    }

                    '\'' -> {
                        val close = findClosingQuote(html, at + 1)
                        if (close < 0) return@runCatching null
                        Json.parseToJsonElement(decodeJsStringBody(html.substring(at + 1, close)))
                    }

                    else -> {
                        null
                    }
                }
            }.getOrNull()
        if (parsed is JsonObject) return parsed
    }
    return null
}

private fun findClosingQuote(
    text: String,
    from: Int,
): Int {
    var i = from
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            '\'' -> return i
            else -> i++
        }
    }
    return -1
}

// ---- diagnostics -----------------------------------------------------------------------------

/**
 * One load's log, safe to write from several racing sources at once. It lives and dies with the request that
 * made it, so nothing global grows while the person scrolls.
 */
class SoundTrace {
    private val lines = ConcurrentLinkedQueue<String>()

    fun note(line: String) {
        lines.add(line)
        while (lines.size > MAX_LINES) lines.poll()
    }

    override fun toString(): String = lines.joinToString("\n")

    private companion object {
        const val MAX_LINES = 60
    }
}

// ---- reels and paging --------------------------------------------------------------------------

/** Below this many strict lockups a page counts as thin and the loose scan is allowed to add reels to it. */
private const val THIN_PAGE_REELS = 8

private val SHORTS_LINK = Regex("""/shorts/([A-Za-z0-9_-]{11})""")

/**
 * Reels found by any `reelWatchEndpoint`, for feeds that use a renderer the strict parser skips. With
 * [includeLinks] it also reads `/shorts/<id>` links, which is looser still and so only for a page that has nothing else.
 */
internal fun JsonElement.looseReelLockups(includeLinks: Boolean): List<ReelLockup> {
    val ids = LinkedHashSet<String>()

    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> {
                e["reelWatchEndpoint"]
                    .objectOrNull()
                    ?.get("videoId")
                    .stringOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(ids::add)
                e.values.forEach(::walk)
            }

            is JsonArray -> {
                e.forEach(::walk)
            }

            is JsonPrimitive -> {
                if (includeLinks && e.isString) {
                    SHORTS_LINK.find(e.content)?.groupValues?.get(1)?.let(ids::add)
                }
            }
        }
    }
    walk(this)
    return ids.map { id -> ReelLockup(id = id, title = "", viewCount = 0, thumbnailUrl = reelPosterUrl(id)) }
}

/**
 * Every reel a sound response lists. Strict lockups come first because they carry titles and counts. A thin
 * answer (a few lockups with more reels hidden in a renderer the strict parser skips) is topped up from the
 * loose scan; an empty one falls all the way back to links. A full page is never mixed with loose finds, which
 * could belong to a "more from this creator" shelf rather than to the sound.
 */
internal fun JsonObject.soundReels(): List<ReelLockup> {
    val strict = toReelLockups()
    if (strict.size >= THIN_PAGE_REELS) return strict
    val known = strict.mapTo(HashSet()) { it.id }
    return strict + looseReelLockups(includeLinks = strict.isEmpty()).filter { it.id !in known }
}

/** The first `continuationCommand` token inside [this], however many `commandExecutorCommand` layers wrap it. */
private fun JsonElement.firstContinuationToken(): String? =
    when (this) {
        is JsonObject -> {
            this["continuationCommand"]
                .objectOrNull()
                ?.get("token")
                .stringOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: values.firstNotNullOfOrNull { it.firstContinuationToken() }
        }

        is JsonArray -> {
            firstNotNullOfOrNull { it.firstContinuationToken() }
        }

        else -> {
            null
        }
    }

/**
 * The token for the page after this one: the one on a `continuationItemRenderer`, whether its endpoint holds
 * the command directly or inside a `commandExecutorCommand`. Null when the response has no further page.
 */
internal fun JsonElement.nextPageToken(): String? =
    when (this) {
        is JsonObject -> {
            this["continuationItemRenderer"].objectOrNull()?.get("continuationEndpoint")?.firstContinuationToken()
                ?: values.firstNotNullOfOrNull { it.nextPageToken() }
        }

        is JsonArray -> {
            firstNotNullOfOrNull { it.nextPageToken() }
        }

        else -> {
            null
        }
    }
