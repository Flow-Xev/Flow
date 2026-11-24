package app.zaid.flow.innertube.update

import android.content.Context
import app.zaid.flow.FlowApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** AUTO checks weekly and applies; MANUAL only on request; OFF never touches anything. */
enum class ClientUpdateMode { AUTO, MANUAL, OFF }

/** A freshly adopted version under observation; [clients] are the keys that moved. */
data class Probation(
    val since: Long,
    val clients: Set<String>,
)

data class ClientUpdateConfig(
    val mode: ClientUpdateMode = ClientUpdateMode.AUTO,
    /** New client features are enabled on arrival; when false they wait in [pending] for approval. */
    val autoFeatures: Boolean = true,
    val intervalDays: Int = 7,
    val lastCheck: Long = 0,
    val lastEmergency: Long = 0,
    val lastSummary: String = "",
    /** Learned versions per client key; only used while newer than the compiled-in one. */
    val versions: Map<String, String> = emptyMap(),
    val features: Map<String, Set<String>> = emptyMap(),
    val pending: Map<String, Set<String>> = emptyMap(),
    /** Last versions proven healthy; rollback target. Absent = the compiled-in version. */
    val stable: Map<String, String> = emptyMap(),
    /** "KEY@version" -> time until which that version may not be adopted again. */
    val quarantine: Map<String, Long> = emptyMap(),
    val probation: Probation? = null,
    /** Newest last: "epochMs|text". Capped. */
    val history: List<String> = emptyList(),
) {
    fun withLog(
        text: String,
        now: Long = System.currentTimeMillis(),
    ) = copy(history = (history + "$now|$text").takeLast(MAX_HISTORY))

    companion object {
        const val MAX_HISTORY = 30
    }
}

object ClientUpdateSettings {
    private const val PREFS = "client_updates"
    private const val KEY = "config"
    private val lock = Any()
    private val _state = MutableStateFlow(ClientUpdateConfig())
    val state: StateFlow<ClientUpdateConfig> = _state.asStateFlow()

    @Volatile
    private var loaded = false

    private fun prefs() = FlowApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Cheap and idempotent; call before the first request so learned versions apply from the start. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            _state.value = sanitize(decode(prefs().getString(KEY, null)))
            ClientVersions.setOverrides(_state.value.versions)
            loaded = true
        }
    }

    fun update(block: (ClientUpdateConfig) -> ClientUpdateConfig) {
        ensureLoaded()
        synchronized(lock) {
            // Single choke point: whoever writes, nothing at or below the factory version is ever stored.
            val next = withoutStaleVersions(block(_state.value))
            _state.value = next
            ClientVersions.setOverrides(next.versions)
            prefs().edit().putString(KEY, encode(next)).apply()
        }
    }

    /** Enables every feature that was held back, for one client or all. */
    fun approvePending(client: String? = null) =
        update { c ->
            val take = c.pending.filterKeys { client == null || it == client }
            c.copy(
                features = c.features + take.mapValues { (k, v) -> c.features[k].orEmpty() + v },
                pending = c.pending - take.keys,
            )
        }

    /**
     * Factory reset: forgets every learned version and feature, so requests use exactly the versions compiled into
     * this app. Quarantine is kept (a version that hurt before stays refused). The check timers restart, so Automatic
     * mode does not immediately learn the same versions again; "Check now" can still update on demand.
     */
    fun resetToFactory(now: Long = System.currentTimeMillis()) =
        update {
            it
                .copy(
                    versions = emptyMap(),
                    stable = emptyMap(),
                    features = emptyMap(),
                    pending = emptyMap(),
                    probation = null,
                    lastSummary = "",
                    lastCheck = now,
                    lastEmergency = now,
                ).withLog("reset to factory versions", now)
        }

    /** Drops stored versions (live and stable) that are not newer than the compiled-in one, and probation entries left without one. */
    private fun withoutStaleVersions(c: ClientUpdateConfig): ClientUpdateConfig {
        val versions = dropNotNewerThanFactory(c.versions, ClientVersions::factory)
        return c.copy(
            versions = versions,
            stable = dropNotNewerThanFactory(c.stable, ClientVersions::factory),
            probation = c.probation?.let { p -> p.copy(clients = p.clients.filter { it in versions }.toSet()) }?.takeIf { it.clients.isNotEmpty() },
        )
    }

    /** A stored version that no longer passes the format rules (corruption, tampering) is dropped, never used. */
    private fun sanitize(c: ClientUpdateConfig): ClientUpdateConfig {
        val ok = { (k, v): Map.Entry<String, String> -> k in ClientVersions.updatable && ClientReviewer.wellFormed(k, v) }
        return withoutStaleVersions(c.copy(versions = c.versions.filter(ok), stable = c.stable.filter(ok)))
    }

    fun isFeatureEnabled(
        client: String,
        feature: String,
    ): Boolean {
        ensureLoaded()
        return feature in state.value.features[client].orEmpty()
    }

    private fun encode(c: ClientUpdateConfig): String =
        buildJsonObject {
            put("mode", c.mode.name)
            put("autoFeatures", c.autoFeatures)
            put("intervalDays", c.intervalDays)
            put("lastCheck", c.lastCheck)
            put("lastEmergency", c.lastEmergency)
            put("lastSummary", c.lastSummary)
            putJsonObject("versions") { c.versions.forEach { (k, v) -> put(k, v) } }
            putJsonObject("features") { c.features.forEach { (k, v) -> putJsonArray(k) { v.forEach { add(it) } } } }
            putJsonObject("pending") { c.pending.forEach { (k, v) -> putJsonArray(k) { v.forEach { add(it) } } } }
            putJsonObject("stable") { c.stable.forEach { (k, v) -> put(k, v) } }
            putJsonObject("quarantine") { c.quarantine.forEach { (k, v) -> put(k, v) } }
            putJsonArray("history") { c.history.forEach { add(it) } }
            c.probation?.let { p ->
                putJsonObject("probation") {
                    put("since", p.since)
                    putJsonArray("clients") { p.clients.forEach { add(it) } }
                }
            }
        }.toString()

    private fun decode(text: String?): ClientUpdateConfig {
        if (text.isNullOrBlank()) return ClientUpdateConfig()
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return ClientUpdateConfig()

        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

        fun strings(k: String): Map<String, String> =
            (o[k] as? JsonObject)
                ?.mapNotNull { (a, b) -> (b as? JsonPrimitive)?.contentOrNull?.let { a to it } }
                ?.toMap()
                .orEmpty()

        fun sets(k: String): Map<String, Set<String>> =
            (o[k] as? JsonObject)
                ?.mapValues { (_, b) -> (b as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty() }
                .orEmpty()
        return ClientUpdateConfig(
            mode = runCatching { ClientUpdateMode.valueOf(str("mode").orEmpty()) }.getOrDefault(ClientUpdateMode.AUTO),
            autoFeatures = str("autoFeatures") != "false",
            intervalDays = str("intervalDays")?.toIntOrNull()?.coerceIn(1, 60) ?: 7,
            lastCheck = str("lastCheck")?.toLongOrNull() ?: 0,
            lastEmergency = str("lastEmergency")?.toLongOrNull() ?: 0,
            lastSummary = str("lastSummary").orEmpty(),
            versions = strings("versions"),
            features = sets("features"),
            pending = sets("pending"),
            stable = strings("stable"),
            quarantine = strings("quarantine").mapNotNull { (k, v) -> v.toLongOrNull()?.let { k to it } }.toMap(),
            history = (o["history"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().takeLast(ClientUpdateConfig.MAX_HISTORY),
            probation =
                (o["probation"] as? JsonObject)?.let { po ->
                    val since = (po["since"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@let null
                    val clients = (po["clients"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
                    Probation(since, clients).takeIf { clients.isNotEmpty() }
                },
        )
    }
}
