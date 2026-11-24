package app.zaid.flow.data.gif

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * How a chosen GIF is added to the create-comment request. YouTube has not published this, so the default
 * (an empty template) is a best guess. Once the real request is known, paste its extra fields as a JSON
 * object in Settings; `{url}` is replaced by the GIF's address, and the object is merged into the body.
 */
object GifSendFormat {
    @Volatile
    var template: String = ""

    /** Whether [text] is a JSON object this format can use. */
    fun isValid(text: String): Boolean = text.isBlank() || parse(text.replace("{url}", "https://example.com/x.gif")) != null

    /** The fields to merge into the request for [url], or null to use the built-in guess. */
    fun extraFor(url: String): JsonObject? {
        if (template.isBlank()) return null
        val safeUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
        return parse(template.replace("{url}", safeUrl))
    }

    private val tenorViewId = Regex("""tenor\.com/(?:[a-z]{2}(?:-[A-Z]{2})?/)?view/[^?#\s]*?-(\d{6,})(?:[?#/]|$)""")
    private val bareId = Regex("""^\d{6,}$""")

    /**
     * The Tenor id inside [url] (a tenor.com/view/...-gif-123 page or a bare id), or null for any other
     * host such as GIPHY. YouTube only accepts Tenor ids for a GIF attachment.
     */
    fun tenorIdOf(url: String): String? {
        val trimmed = url.trim()
        if (bareId.matches(trimmed)) return trimmed
        return tenorViewId.find(trimmed)?.groupValues?.get(1)
    }

    private fun parse(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
