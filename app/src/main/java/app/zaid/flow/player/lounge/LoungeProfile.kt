package app.zaid.flow.player.lounge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The identity Flow presents to the TV's YouTube app on the Lounge channel. */
internal data class LoungeProfile(
    val app: String,
    val mdx: String,
    val ver: String,
) {
    companion object {
        /** Compiled-in profiles, best first. A TV that rejects one is tried with the next. */
        val FACTORY =
            listOf(
                LoungeProfile("youtube-desktop", "3", "8"),
                LoungeProfile("web", "3", "8"),
                LoungeProfile("youtube-desktop", "2", "8"),
            )

        private val SAFE = Regex("^[A-Za-z0-9._-]{1,32}$")

        /**
         * Reads `{"lounge": {"profiles": [{"app": "...", "mdx": "3", "ver": "8"}]}}` from the client manifest.
         * Anything malformed is dropped, never trusted.
         */
        fun parseManifest(text: String): List<LoungeProfile> =
            runCatching {
                val array = Json.parseToJsonElement(text).jsonObject.getValue("lounge").jsonObject.getValue("profiles").jsonArray
                array.take(6).mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    fun field(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    LoungeProfile(field("app"), field("mdx"), field("ver")).takeIf { SAFE.matches(it.app) && SAFE.matches(it.mdx) && SAFE.matches(it.ver) }
                }
            }.getOrDefault(emptyList())

        /** Learned profiles first, then the factory ones, no duplicates. */
        fun ladder(learned: List<LoungeProfile>): List<LoungeProfile> = (learned + FACTORY).distinct()
    }
}
