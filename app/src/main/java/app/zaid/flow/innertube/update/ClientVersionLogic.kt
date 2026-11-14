package app.zaid.flow.innertube.update

import app.zaid.flow.innertube.models.YouTubeClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** What one source (the manifest or a live YouTube page) says about one client. */
internal data class RemoteClient(
    val version: String,
    val features: List<String> = emptyList(),
    /** Versions the manifest owner has vetoed (remote kill-switch). */
    val blocked: List<String> = emptyList(),
)

/** One planned change. [to] equals [from] when only features were announced. */
internal data class Change(
    val client: String,
    val from: String,
    val to: String,
    val newFeatures: List<String>,
) {
    val versionChanged get() = to != from
}

private val SAFE_VERSION = Regex("^[0-9]+(\\.[0-9A-Za-z]+){0,5}$")
private val SAFE_FEATURE = Regex("^[a-z0-9_.-]{1,40}$")
private val DIGITS = Regex("\\d+")

internal fun versionKey(version: String): List<Int> = DIGITS.findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()

internal fun isNewerVersion(
    candidate: String,
    current: String,
): Boolean {
    val a = versionKey(candidate)
    val b = versionKey(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Keeps only the versions strictly newer than the factory (compiled-in) one; anything else can never be the better choice. */
internal fun dropNotNewerThanFactory(
    versions: Map<String, String>,
    factory: (String) -> String?,
): Map<String, String> = versions.filter { (key, version) -> factory(key)?.let { isNewerVersion(version, it) } == true }

/**
 * Whether [to] may replace what a client runs now: strictly newer than [live] (the version in use) and not
 * quarantined. Checked again at the moment of writing, because a review can take seconds and the state may have moved.
 */
internal fun canAdopt(
    to: String,
    live: String?,
    quarantinedUntil: Long?,
    now: Long,
): Boolean = live != null && isNewerVersion(to, live) && (quarantinedUntil ?: 0L) <= now

/** Parses `{"clients": {"WEB": {"version": "...", "features": ["..."]}}}`. Anything malformed is dropped, never trusted. */
internal fun parseManifest(text: String): Map<String, RemoteClient> {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
    val clients = root["clients"] as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, RemoteClient>()
    for ((name, element) in clients) {
        val obj = element as? JsonObject ?: continue
        val version = (obj["version"] as? JsonPrimitive)?.contentOrNull ?: continue
        if (!SAFE_VERSION.matches(version)) continue
        val features =
            (obj["features"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { SAFE_FEATURE.matches(it) }
                .orEmpty()
        val blocked =
            (obj["blocked"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { SAFE_VERSION.matches(it) }
                .orEmpty()
        out[name] = RemoteClient(version, features, blocked)
    }
    return out
}

/** Newest version per client across all sources; features announced by any source are kept. */
internal fun mergeNewest(vararg sources: Map<String, RemoteClient>): Map<String, RemoteClient> {
    val out = HashMap<String, RemoteClient>()
    for (source in sources) {
        for ((name, remote) in source) {
            val cur = out[name]
            val features = ((cur?.features).orEmpty() + remote.features).distinct()
            val version = if (cur == null || isNewerVersion(remote.version, cur.version)) remote.version else cur.version
            out[name] = RemoteClient(version, features, ((cur?.blocked).orEmpty() + remote.blocked).distinct())
        }
    }
    return out
}

/**
 * Diffs [found] against what is live now. [current] returns null for a client that must not move
 * (unknown, or pinned), which drops it here — the single gate that keeps native clients untouched.
 */
internal fun planChanges(
    found: Map<String, RemoteClient>,
    current: (String) -> String?,
    knownFeatures: (String) -> Set<String>,
): List<Change> =
    found.mapNotNull { (name, remote) ->
        val cur = current(name) ?: return@mapNotNull null
        val newer = isNewerVersion(remote.version, cur)
        val added = remote.features.filter { it !in knownFeatures(name) }
        if (!newer && added.isEmpty()) null else Change(name, cur, if (newer) remote.version else cur, added)
    }

/**
 * Live client versions. Only the web-family clients are movable: their User-Agent carries no version, so a
 * new version string stays self-consistent. ANDROID / IOS / ANDROID_VR pin version, UA and build together,
 * so bumping one field alone would be a fingerprint mismatch — they stay as compiled.
 */
object ClientVersions {
    internal val updatable: Map<String, YouTubeClient> by lazy {
        mapOf(
            "WEB" to YouTubeClient.WEB,
            "MWEB" to YouTubeClient.MWEB,
            "WEB_REMIX" to YouTubeClient.WEB_REMIX,
            "TVHTML5" to YouTubeClient.TVHTML5,
        )
    }

    @Volatile
    private var overrides: Map<String, String> = emptyMap()

    internal fun setOverrides(map: Map<String, String>) {
        overrides = map
    }

    /** The factory (compiled-in) version of the movable client [key], or null when that client never moves. */
    internal fun factory(key: String): String? = updatable[key]?.clientVersion

    /** The version a request should send for the registry [key], or null when that client is not movable. */
    internal fun current(key: String): String? {
        val base = updatable[key]?.clientVersion ?: return null
        val over = overrides[key] ?: return base
        return if (isNewerVersion(over, base)) over else base
    }

    internal fun effective(client: YouTubeClient): String {
        // Only a client that still carries its compiled-in version is redirected. One built with another version
        // (the reviewer's probe, TV_TIZEN) is left alone; a copy with just a different User-Agent still follows.
        val key =
            updatable.entries
                .firstOrNull { (_, base) ->
                    base.clientName == client.clientName && base.clientId == client.clientId && base.clientVersion == client.clientVersion
                }?.key ?: return client.clientVersion
        return current(key) ?: client.clientVersion
    }
}

/** The client version to send: a newer one learned by the updater, otherwise the compiled-in one. */
val YouTubeClient.effectiveVersion: String get() = ClientVersions.effective(this)
