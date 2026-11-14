package app.zaid.flow.ai

/** One run of inline text with the styling the assistant asked for. */
internal data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    /** A `mm:ss` or `h:mm:ss` moment; the sheet turns it into a jump in the player. */
    val timestampMs: Long? = null,
    /** An http(s) link target. */
    val link: String? = null,
)

internal sealed interface MdBlock {
    data class Paragraph(
        val spans: List<MdSpan>,
    ) : MdBlock

    data class Heading(
        val level: Int,
        val spans: List<MdSpan>,
    ) : MdBlock

    data class Bullet(
        val indent: Int,
        val spans: List<MdSpan>,
    ) : MdBlock

    data class Numbered(
        val number: Int,
        val indent: Int,
        val spans: List<MdSpan>,
    ) : MdBlock

    data class Code(
        val text: String,
    ) : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*\u2022]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val PLAIN_TOKEN = Regex("(https?://[^\\s)]+)|(\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b)")
private const val TRAILING_PUNCTUATION = ".,;:!?"

/**
 * The small markdown subset Flow AI is told to use: headings, bullets, numbered lists, code, bold,
 * italic, inline code, links and timestamps. Anything else is shown as the plain text it arrived as,
 * so an unexpected reply degrades to readable text rather than breaking.
 */
internal fun parseAiMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val code = StringBuilder()
    var inCode = false
    for (line in text.replace("\r\n", "\n").split('\n')) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += MdBlock.Code(code.toString().trimEnd('\n'))
                code.clear()
            }
            inCode = !inCode
            continue
        }
        if (inCode) {
            code.append(line).append('\n')
            continue
        }
        if (line.isBlank()) continue
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        blocks +=
            when {
                heading != null -> {
                    MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2]))
                }

                bullet != null -> {
                    MdBlock.Bullet(bullet.groupValues[1].length / 2, parseInline(bullet.groupValues[2]))
                }

                numbered != null -> {
                    MdBlock.Numbered(
                        number = numbered.groupValues[2].toIntOrNull() ?: 1,
                        indent = numbered.groupValues[1].length / 2,
                        spans = parseInline(numbered.groupValues[3]),
                    )
                }

                else -> {
                    MdBlock.Paragraph(parseInline(line.trim()))
                }
            }
    }
    // A reply cut off inside a code fence still shows what arrived.
    if (inCode && code.isNotEmpty()) blocks += MdBlock.Code(code.toString().trimEnd('\n'))
    return blocks
}

internal fun parseInline(src: String): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    val plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) {
            out += splitPlain(plain.toString())
            plain.clear()
        }
    }
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (c == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > i + 1) {
                flush()
                out += MdSpan(src.substring(i + 1, end), code = true)
                i = end + 1
                continue
            }
        }
        if (src.startsWith("**", i)) {
            val end = src.indexOf("**", i + 2)
            if (end > i + 2) {
                flush()
                parseInline(src.substring(i + 2, end)).forEach { out += it.copy(bold = true) }
                i = end + 2
                continue
            }
        }
        if (c == '*' && i + 1 < src.length && !src[i + 1].isWhitespace()) {
            val end = src.indexOf('*', i + 1)
            if (end > i + 1 && !src[end - 1].isWhitespace()) {
                flush()
                parseInline(src.substring(i + 1, end)).forEach { out += it.copy(italic = true) }
                i = end + 1
                continue
            }
        }
        if (c == '[') {
            val mid = src.indexOf("](", i + 1)
            val end = if (mid > i + 1) src.indexOf(')', mid + 2) else -1
            if (end > mid + 2) {
                val url = src.substring(mid + 2, end).trim()
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    flush()
                    out += MdSpan(src.substring(i + 1, mid), link = url)
                    i = end + 1
                    continue
                }
            }
        }
        plain.append(c)
        i++
    }
    flush()
    return out
}

/** Plain text with bare links and timestamps picked out. */
private fun splitPlain(text: String): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    var last = 0
    for (match in PLAIN_TOKEN.findAll(text)) {
        if (match.range.first > last) out += MdSpan(text.substring(last, match.range.first))
        val url = match.groups[1]?.value
        if (url != null) {
            val trimmed = url.trimEnd { it in TRAILING_PUNCTUATION }
            out += MdSpan(trimmed, link = trimmed)
            if (trimmed.length < url.length) out += MdSpan(url.substring(trimmed.length))
        } else {
            val ms = timestampToMs(match.value)
            out += if (ms != null) MdSpan(match.value, timestampMs = ms) else MdSpan(match.value)
        }
        last = match.range.last + 1
    }
    if (last < text.length) out += MdSpan(text.substring(last))
    return out
}

internal fun timestampToMs(text: String): Long? {
    val parts = text.split(':').map { it.toIntOrNull() ?: return null }
    val seconds =
        when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> return null
        }
    if (parts.drop(1).any { it !in 0..59 }) return null
    return seconds * 1000L
}
