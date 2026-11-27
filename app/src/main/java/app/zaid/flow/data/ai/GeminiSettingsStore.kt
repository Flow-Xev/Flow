package app.zaid.flow.data.ai

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.zaid.flow.data.local.KeystoreSecretBox
import app.zaid.flow.data.local.safePreferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiDataStore by safePreferencesDataStore(name = "ai_assistant")

/** Where Flow AI sends its requests. Everything except [GEMINI] speaks the OpenAI chat-completions protocol. */
enum class AiProvider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
) {
    GEMINI("Gemini", "", "gemini-2.5-flash"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-2.5-flash"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    GROQ("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),

    /** Any OpenAI-compatible server (Ollama, LM Studio, vLLM, Together, Mistral, xAI, Azure, ...). */
    CUSTOM("Custom", "", ""),
}

/**
 * The chosen provider plus its options. [baseUrl] only matters for [AiProvider.CUSTOM]; the rest are
 * OpenRouter-only: [webSearch] (online models), [noDataStorage] (only providers that do not store or
 * train on prompts) and [fallbacks] (comma-separated model ids tried when the main model fails).
 */
data class AiProviderConfig(
    val provider: AiProvider = AiProvider.GEMINI,
    val baseUrl: String = "",
    val webSearch: Boolean = false,
    val noDataStorage: Boolean = false,
    val fallbacks: String = "",
) {
    /** The chat-completions root for OpenAI-style providers, without a trailing slash. */
    val endpointRoot: String
        get() = (if (provider == AiProvider.CUSTOM) baseUrl else provider.baseUrl)
            .trim().trimEnd('/').removeSuffix("/chat/completions")
}

/**
 * The user's own Gemini API key and model. The key is sealed with the Android Keystore before it is
 * written, the same way the proxy password and scrobbling secrets are.
 */
@Singleton
class GeminiSettingsStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val providerKey = stringPreferencesKey("ai_provider")
        private val baseUrlKey = stringPreferencesKey("ai_base_url")
        private val webSearchKey = booleanPreferencesKey("ai_or_web_search")
        private val noStorageKey = booleanPreferencesKey("ai_or_no_storage")
        private val fallbacksKey = stringPreferencesKey("ai_or_fallbacks")

        // Gemini keeps its original names so existing installs keep their key and model.
        private fun apiKeyKeyFor(p: AiProvider) =
            stringPreferencesKey(if (p == AiProvider.GEMINI) "gemini_api_key" else "api_key_${p.name.lowercase()}")

        private fun modelKeyFor(p: AiProvider) =
            stringPreferencesKey(if (p == AiProvider.GEMINI) "gemini_model" else "model_${p.name.lowercase()}")

        private fun providerOf(p: Preferences): AiProvider =
            AiProvider.entries.firstOrNull { it.name == p[providerKey] } ?: AiProvider.GEMINI

        private fun configOf(p: Preferences) =
            AiProviderConfig(
                providerOf(p),
                p[baseUrlKey].orEmpty(),
                p[webSearchKey] ?: false,
                p[noStorageKey] ?: false,
                p[fallbacksKey].orEmpty(),
            )
        private val enabledKey = booleanPreferencesKey("ai_enabled")
        private val chaptersKey = booleanPreferencesKey("ai_chapters_enabled")
        private val inspectorEnabledKey = booleanPreferencesKey("ai_inspector_enabled")
        private val inspectorKeyKey = stringPreferencesKey("ai_inspector_api_key")
        private val localMusicAccessKey = booleanPreferencesKey("ai_local_music_access")

        /** Off hides every Flow AI button and sheet and stops the assistant answering. */
        val enabled: Flow<Boolean> =
            context.aiDataStore.data
                .map { it[enabledKey] ?: true }
                .distinctUntilChanged()

        suspend fun isEnabled(): Boolean = enabled.first()

        /**
         * Whether videos that come without chapters get AI-made ones on the seek bar. Each one sends that
         * video's captions to Gemini, so it has its own switch next to the assistant's.
         */
        val chaptersEnabled: Flow<Boolean> =
            context.aiDataStore.data
                .map { it[chaptersKey] ?: true }
                .distinctUntilChanged()

        suspend fun isChaptersEnabled(): Boolean = chaptersEnabled.first()

        suspend fun setChaptersEnabled(value: Boolean) {
            context.aiDataStore.edit { it[chaptersKey] = value }
        }

        suspend fun setEnabled(value: Boolean) {
            context.aiDataStore.edit { it[enabledKey] = value }
        }

        /** Persistent opt-in for read-only access to indexed device songs. Off by default. */
        val localMusicAccessEnabled: Flow<Boolean> =
            context.aiDataStore.data.map { it[localMusicAccessKey] ?: false }.distinctUntilChanged()

        suspend fun canReadLocalMusic(): Boolean = localMusicAccessEnabled.first()

        suspend fun setLocalMusicAccessEnabled(value: Boolean) {
            context.aiDataStore.edit { it[localMusicAccessKey] = value }
        }

        val config: Flow<AiProviderConfig> =
            context.aiDataStore.data.map { configOf(it) }.distinctUntilChanged()

        suspend fun currentConfig(): AiProviderConfig = config.first()

        /** The key of the selected provider; each provider keeps its own. */
        val apiKey: Flow<String> =
            context.aiDataStore.data
                .map { KeystoreSecretBox.open(it[apiKeyKeyFor(providerOf(it))]) }
                .flowOn(Dispatchers.IO)
                .distinctUntilChanged()

        /** The model of the selected provider; each provider keeps its own. */
        val model: Flow<String> =
            context.aiDataStore.data
                .map { prefs ->
                    val provider = providerOf(prefs)
                    prefs[modelKeyFor(provider)]?.takeIf(String::isNotBlank) ?: provider.defaultModel
                }
                .distinctUntilChanged()

        suspend fun currentApiKey(): String = apiKey.first()

        suspend fun currentModel(): String = model.first()

        suspend fun setApiKey(value: String) {
            val trimmed = value.trim()
            val sealed = if (trimmed.isEmpty()) "" else withContext(Dispatchers.IO) { KeystoreSecretBox.seal(trimmed) }
            context.aiDataStore.edit { preferences ->
                val key = apiKeyKeyFor(providerOf(preferences))
                if (sealed.isEmpty()) preferences.remove(key) else preferences[key] = sealed
            }
        }

        suspend fun setModel(value: String) {
            val trimmed = value.trim()
            context.aiDataStore.edit { preferences ->
                val key = modelKeyFor(providerOf(preferences))
                if (trimmed.isEmpty()) preferences.remove(key) else preferences[key] = trimmed
            }
        }

        /** Saves the provider, its options and that provider's model in one write. */
        suspend fun setProvider(
            value: AiProviderConfig,
            model: String,
        ) {
            context.aiDataStore.edit { preferences ->
                preferences[providerKey] = value.provider.name
                preferences[baseUrlKey] = value.baseUrl.trim()
                preferences[webSearchKey] = value.webSearch
                preferences[noStorageKey] = value.noDataStorage
                preferences[fallbacksKey] = value.fallbacks.trim()
                val key = modelKeyFor(value.provider)
                if (model.isBlank()) preferences.remove(key) else preferences[key] = model.trim()
            }
        }

        /**
         * The AI Inspector double-checks what the first model made (chapters, titles). It runs on its own
         * Gemini key, ideally from a different Google account, so the two never share a rate limit.
         */
        val inspectorEnabled: Flow<Boolean> =
            context.aiDataStore.data
                .map { it[inspectorEnabledKey] ?: false }
                .distinctUntilChanged()

        suspend fun isInspectorEnabled(): Boolean = inspectorEnabled.first()

        suspend fun setInspectorEnabled(value: Boolean) {
            context.aiDataStore.edit { it[inspectorEnabledKey] = value }
        }

        val inspectorApiKey: Flow<String> =
            context.aiDataStore.data
                .map { KeystoreSecretBox.open(it[inspectorKeyKey]) }
                .flowOn(Dispatchers.IO)
                .distinctUntilChanged()

        suspend fun currentInspectorApiKey(): String = inspectorApiKey.first()

        suspend fun setInspectorApiKey(value: String) {
            val trimmed = value.trim()
            val sealed = if (trimmed.isEmpty()) "" else withContext(Dispatchers.IO) { KeystoreSecretBox.seal(trimmed) }
            context.aiDataStore.edit { preferences ->
                if (sealed.isEmpty()) preferences.remove(inspectorKeyKey) else preferences[inspectorKeyKey] = sealed
            }
        }

        companion object {
            const val DEFAULT_MODEL = "gemini-2.5-flash"
        }
    }
