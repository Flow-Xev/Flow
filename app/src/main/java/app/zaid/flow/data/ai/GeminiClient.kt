package app.zaid.flow.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A call Gemini wants made before it can answer. */
data class GeminiFunctionCall(
    val name: String,
    val args: JsonObject,
)

/**
 * One model turn. [content] is the model's content object exactly as received: it goes back into the
 * history untouched, because Gemini attaches signatures to its parts that a later turn must echo.
 */
data class GeminiTurn(
    val content: JsonObject,
    val text: String,
    val calls: List<GeminiFunctionCall>,
)

class GeminiException(
    message: String,
    val httpCode: Int = 0,
) : IOException(message)

/**
 * A minimal client for the Gemini `generateContent` REST endpoint.
 *
 * The key travels in the `x-goog-api-key` header, never in the URL, so it does not end up in logs.
 * The app-wide client is reused for its proxy settings, minus its response cache and with the longer
 * read timeout a model answer needs.
 */
@Singleton
class GeminiClient
    @Inject
    constructor(
        baseClient: OkHttpClient,
        private val settings: GeminiSettingsStore,
    ) {
        private val client: OkHttpClient =
            baseClient
                .newBuilder()
                .cache(null)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        private val json = Json { ignoreUnknownKeys = true }

        suspend fun generate(
            apiKey: String,
            model: String,
            systemInstruction: String,
            contents: JsonArray,
            functionDeclarations: JsonArray,
            temperature: Double = DEFAULT_TEMPERATURE,
            jsonOutput: Boolean = false,
            allowWebSearch: Boolean = false,
        ): GeminiTurn {
            val config = settings.currentConfig()
            // Web search only for chat turns that ask for it, and only when the user switched it on.
            val webSearch = allowWebSearch && config.webSearch && !jsonOutput
            return if (config.provider == AiProvider.GEMINI) {
                generateGemini(apiKey, model, systemInstruction, contents, functionDeclarations, temperature, jsonOutput, webSearch)
            } else {
                generateOpenAi(config, apiKey, model, systemInstruction, contents, functionDeclarations, temperature, jsonOutput, webSearch)
            }
        }

        /**
         * Always calls Google's Gemini API, whatever provider the assistant is set to. For features that run on
         * a Gemini key of their own (the chapter Inspector): that key must never be sent to another provider.
         */
        suspend fun generateWithGemini(
            apiKey: String,
            model: String,
            systemInstruction: String,
            contents: JsonArray,
            temperature: Double = DEFAULT_TEMPERATURE,
            jsonOutput: Boolean = false,
        ): GeminiTurn =
            generateGemini(apiKey, model, systemInstruction, contents, JsonArray(emptyList()), temperature, jsonOutput, webSearch = false)

        private suspend fun generateGemini(
            apiKey: String,
            model: String,
            systemInstruction: String,
            contents: JsonArray,
            functionDeclarations: JsonArray,
            temperature: Double,
            jsonOutput: Boolean,
            webSearch: Boolean,
        ): GeminiTurn =
            withContext(Dispatchers.IO) {
                val modelId = model.trim()
                val search = webSearch && canGroundWithSearch(modelId, functionDeclarations)
                try {
                    requestGemini(apiKey, modelId, systemInstruction, contents, functionDeclarations, temperature, jsonOutput, search)
                } catch (e: GeminiException) {
                    // Search with function calling is a preview feature: a model that refuses it answers without.
                    if (search && e.httpCode == 400) {
                        searchUnsupported += modelId
                        requestGemini(apiKey, modelId, systemInstruction, contents, functionDeclarations, temperature, jsonOutput, false)
                    } else {
                        throw e
                    }
                }
            }

        /**
         * Google Search grounding runs alone on any current Gemini model, but next to function declarations only
         * on Gemini 3 and newer (tool combination); older models keep function calling and go without search.
         */
        private fun canGroundWithSearch(
            model: String,
            functionDeclarations: JsonArray,
        ): Boolean {
            if (model in searchUnsupported) return false
            if (functionDeclarations.isEmpty()) return true
            val version = Regex("""gemini-(\d+)""").find(model)?.groupValues?.get(1)?.toIntOrNull() ?: return false
            return version >= 3
        }

        private val searchUnsupported: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        private fun requestGemini(
            apiKey: String,
            model: String,
            systemInstruction: String,
            contents: JsonArray,
            functionDeclarations: JsonArray,
            temperature: Double,
            jsonOutput: Boolean,
            search: Boolean,
        ): GeminiTurn {
            val combine = search && functionDeclarations.isNotEmpty()
            val history = if (combine) withFunctionResponseIds(contents) else withoutServerToolParts(contents)
            val body =
                buildJsonObject {
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", systemInstruction) }) }
                    }
                    put("contents", history)
                    if (functionDeclarations.isNotEmpty() || search) {
                        putJsonArray("tools") {
                            if (functionDeclarations.isNotEmpty()) {
                                add(buildJsonObject { put("functionDeclarations", functionDeclarations) })
                            }
                            if (search) add(buildJsonObject { putJsonObject("googleSearch") {} })
                        }
                    }
                    // Needed for built-in tools next to function calls: the model then returns its search steps
                    // as toolCall/toolResponse parts, which go back to it untouched in the next turn.
                    if (combine) putJsonObject("toolConfig") { put("includeServerSideToolInvocations", true) }
                    putJsonObject("generationConfig") {
                        put("temperature", temperature)
                        // Valid only without tools; the caller asks for it on plain answers.
                        if (jsonOutput && functionDeclarations.isEmpty() && !search) put("responseMimeType", "application/json")
                    }
                }
            val request =
                Request
                    .Builder()
                    .url("$ENDPOINT/$model:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            val raw =
                try {
                    client.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw GeminiException(errorMessage(text, response.code), response.code)
                        text
                    }
                } catch (e: GeminiException) {
                    throw e
                } catch (e: IOException) {
                    throw GeminiException("Gemini could not be reached: ${e.message ?: "network error"}", 0)
                }
            return parse(raw)
        }

        /**
         * With tool combination every functionResponse must carry the id of the functionCall it answers. The app
         * stores answers by name and order, so the ids are copied over from the model turn just before them.
         */
        private fun withFunctionResponseIds(contents: JsonArray): JsonArray {
            var callIds = ArrayDeque<String?>()
            return JsonArray(
                contents.map { element ->
                    val content = element as? JsonObject ?: return@map element
                    val parts = (content["parts"] as? JsonArray).orEmpty()
                    if ((content["role"] as? JsonPrimitive)?.contentOrNull == "model") {
                        callIds =
                            ArrayDeque<String?>(
                                parts
                                    .mapNotNull { (it as? JsonObject)?.get("functionCall") as? JsonObject }
                                    .map { (it["id"] as? JsonPrimitive)?.contentOrNull },
                            )
                        return@map content
                    }
                    if (parts.none { (it as? JsonObject)?.containsKey("functionResponse") == true }) return@map content
                    val patched =
                        parts.map { part ->
                            val obj = part as? JsonObject ?: return@map part
                            val answer = obj["functionResponse"] as? JsonObject ?: return@map part
                            val id = callIds.removeFirstOrNull()
                            if (id == null || answer.containsKey("id")) {
                                part
                            } else {
                                JsonObject(obj + ("functionResponse" to JsonObject(answer + ("id" to JsonPrimitive(id)))))
                            }
                        }
                    JsonObject(content + ("parts" to JsonArray(patched)))
                },
            )
        }

        /** Search steps from an earlier grounded turn are only valid while search is on; without it they are dropped. */
        private fun withoutServerToolParts(contents: JsonArray): JsonArray {
            val hasServerParts =
                contents.any { element ->
                    ((element as? JsonObject)?.get("parts") as? JsonArray).orEmpty().any { part ->
                        (part as? JsonObject)?.let { it.containsKey("toolCall") || it.containsKey("toolResponse") } == true
                    }
                }
            if (!hasServerParts) return contents
            return JsonArray(
                contents.map { element ->
                    val content = element as? JsonObject ?: return@map element
                    val parts = (content["parts"] as? JsonArray) ?: return@map content
                    val kept =
                        parts.filterNot { part ->
                            (part as? JsonObject)?.let { it.containsKey("toolCall") || it.containsKey("toolResponse") } == true
                        }
                    val safe = kept.ifEmpty { listOf(buildJsonObject { put("text", "") }) }
                    JsonObject(content + ("parts" to JsonArray(safe)))
                },
            )
        }

        // ── OpenAI-compatible providers (OpenRouter, OpenAI, Groq, DeepSeek, custom) ─────────────
        // The app keeps one history format (Gemini's). It is translated to chat-completions messages
        // here and the answer is translated back, so nothing else in the app knows the provider.

        private suspend fun generateOpenAi(
            config: AiProviderConfig,
            apiKey: String,
            model: String,
            systemInstruction: String,
            contents: JsonArray,
            functionDeclarations: JsonArray,
            temperature: Double,
            jsonOutput: Boolean,
            webSearch: Boolean,
        ): GeminiTurn =
            withContext(Dispatchers.IO) {
                val label = config.provider.label
                val root = config.endpointRoot
                if (root.isBlank()) throw GeminiException("Set the base URL of your custom provider in Settings > Integrations.")
                if (model.isBlank()) throw GeminiException("Set a model name for $label in Settings > Integrations.")
                val hasTools = functionDeclarations.isNotEmpty()
                // JSON mode is a hint, not a requirement: many models and providers reject response_format, so
                // a request that fails because of it is sent once more without it and the reply is parsed leniently.
                val wantJsonMode = jsonOutput && !hasTools && config.provider != AiProvider.CUSTOM
                val web = webSearch && config.provider == AiProvider.OPENROUTER
                try {
                    requestOpenAi(config, apiKey, model, systemInstruction, contents, functionDeclarations, temperature, wantJsonMode, web)
                } catch (e: GeminiException) {
                    if (wantJsonMode && isUnsupportedParameter(e)) {
                        requestOpenAi(config, apiKey, model, systemInstruction, contents, functionDeclarations, temperature, false, web)
                    } else {
                        throw e
                    }
                }
            }

        private fun requestOpenAi(
            config: AiProviderConfig,
            apiKey: String,
            model: String,
            systemInstruction: String,
            contents: JsonArray,
            functionDeclarations: JsonArray,
            temperature: Double,
            jsonMode: Boolean,
            webSearch: Boolean,
        ): GeminiTurn {
            val label = config.provider.label
            val root = config.endpointRoot
            val openRouter = config.provider == AiProvider.OPENROUTER
            val fallbacks = if (openRouter) config.fallbacks.split(',').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
            val primary = model.trim()
            val hasTools = functionDeclarations.isNotEmpty()
            // Prompt caching breakpoints are an Anthropic feature; other providers may reject the array form.
            val cacheSystem = openRouter && primary.startsWith("anthropic/")
            val body =
                buildJsonObject {
                    if (fallbacks.isEmpty()) {
                        put("model", primary)
                    } else {
                        putJsonArray("models") { (listOf(primary) + fallbacks).forEach { add(JsonPrimitive(it)) } }
                    }
                    put("temperature", temperature)
                    put("messages", toOpenAiMessages(systemInstruction, contents, cacheSystem = cacheSystem))
                    if (hasTools) {
                        putJsonArray("tools") {
                            functionDeclarations.forEach { decl ->
                                add(
                                    buildJsonObject {
                                        put("type", "function")
                                        // Strict OpenAI-style APIs (Mistral) answer 422 when a function has no parameters object.
                                        put(
                                            "function",
                                            if (decl.containsKey("parameters")) {
                                                decl
                                            } else {
                                                JsonObject(
                                                    decl +
                                                        ("parameters" to
                                                            buildJsonObject {
                                                                put("type", "object")
                                                                putJsonObject("properties") {}
                                                            }),
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        put("tool_choice", "auto")
                    } else if (jsonMode) {
                        putJsonObject("response_format") { put("type", "json_object") }
                    }
                    if (openRouter) {
                        // require_parameters only for tool calls: a chat that needs tools must land on a provider that
                        // runs them. For plain or JSON answers it would rule out most providers ("no endpoints").
                        if (hasTools || config.noDataStorage) {
                            putJsonObject("provider") {
                                if (hasTools) put("require_parameters", true)
                                if (config.noDataStorage) put("data_collection", "deny")
                            }
                        }
                        // Long transcripts are compressed to fit the model's window instead of failing.
                        putJsonArray("transforms") { add(JsonPrimitive("middle-out")) }
                        // OpenRouter's web plugin works with every model (native search where the model has it,
                        // Exa otherwise) and, unlike a ":online" suffix, also applies to the fallback models.
                        if (webSearch && !primary.endsWith(":online")) {
                            putJsonArray("plugins") {
                                add(
                                    buildJsonObject {
                                        put("id", "web")
                                        put("max_results", WEB_RESULTS)
                                    },
                                )
                            }
                        }
                    }
                }
            val request =
                Request
                    .Builder()
                    .url("$root/chat/completions")
                    .apply {
                        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                        if (openRouter) {
                            header("X-Title", "Flow")
                            header("HTTP-Referer", "https://github.com/Flow-Xev/Flow")
                        }
                    }.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            val raw =
                try {
                    client.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw GeminiException("$label: ${apiError(text) ?: "request failed (HTTP ${response.code})"}", response.code)
                        }
                        text
                    }
                } catch (e: GeminiException) {
                    throw e
                } catch (e: IOException) {
                    throw GeminiException("$label could not be reached: ${e.message ?: "network error"}", 0)
                }
            return parseOpenAi(raw, label)
        }

        /** True when a failure looks like the provider or model refusing a request option, not a bad key or quota. */
        private fun isUnsupportedParameter(e: GeminiException): Boolean {
            if (e.httpCode == 400 || e.httpCode == 404 || e.httpCode == 422) return true
            val text = e.message.orEmpty().lowercase()
            return "response_format" in text || "json" in text || "unsupported" in text || "not supported" in text ||
                "no endpoints" in text
        }

        private fun toOpenAiMessages(
            system: String,
            contents: JsonArray,
            cacheSystem: Boolean,
        ): JsonArray =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "system")
                        if (cacheSystem) {
                            // OpenRouter prompt caching: honoured by models that support it, ignored by the rest.
                            putJsonArray("content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", system)
                                        putJsonObject("cache_control") { put("type", "ephemeral") }
                                    },
                                )
                            }
                        } else {
                            put("content", system)
                        }
                    },
                )
                var pendingIds = ArrayDeque<String>()
                contents.forEachIndexed { index, element ->
                    val content = element.jsonObject
                    val parts = content["parts"]?.jsonArray.orEmpty().map { it.jsonObject }
                    if (content["role"]?.jsonPrimitive?.contentOrNull == "model") {
                        val text =
                            parts
                                .filter { it["thought"]?.jsonPrimitive?.contentOrNull != "true" }
                                .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                                .joinToString("")
                        val calls = parts.mapNotNull { it["functionCall"]?.jsonObject }
                        pendingIds = ArrayDeque()
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                if (text.isNotEmpty() || calls.isEmpty()) put("content", text) else put("content", JsonNull)
                                if (calls.isNotEmpty()) {
                                    putJsonArray("tool_calls") {
                                        calls.forEachIndexed { i, call ->
                                            // Mistral only accepts tool call ids of exactly 9 letters or digits.
                                            val id = "c%04d%04d".format(index, i)
                                            pendingIds.addLast(id)
                                            add(
                                                buildJsonObject {
                                                    put("id", id)
                                                    put("type", "function")
                                                    putJsonObject("function") {
                                                        put("name", call["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
                                                        put("arguments", (call["args"] ?: JsonObject(emptyMap())).toString())
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    } else {
                        val responses = parts.mapNotNull { it["functionResponse"]?.jsonObject }
                        responses.forEach { r ->
                            add(
                                buildJsonObject {
                                    put("role", "tool")
                                    put("tool_call_id", pendingIds.removeFirstOrNull() ?: "c%04d9999".format(index))
                                    put("content", (r["response"] ?: JsonObject(emptyMap())).toString())
                                },
                            )
                        }
                        val text = parts.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
                        if (text.isNotEmpty()) {
                            add(
                                buildJsonObject {
                                    put("role", "user")
                                    put("content", text)
                                },
                            )
                        }
                    }
                }
            }

        private fun parseOpenAi(
            raw: String,
            label: String,
        ): GeminiTurn {
            val root =
                runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: throw GeminiException("$label: the answer could not be read")
            // OpenRouter can answer 200 with an error object when the upstream provider failed mid-request.
            apiError(raw)?.let { throw GeminiException("$label: $it", errorCode(root)) }
            val message =
                root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
                    ?: throw GeminiException("$label: no answer returned")
            val text = contentText(message["content"]).trim()
            val calls =
                message["tool_calls"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val fn = element.jsonObject["function"]?.jsonObject ?: return@mapNotNull null
                    val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val args =
                        runCatching { json.parseToJsonElement(fn["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "{}" }) }
                            .getOrNull() as? JsonObject ?: JsonObject(emptyMap())
                    GeminiFunctionCall(name, args)
                }
            val replay =
                buildJsonObject {
                    put("role", "model")
                    putJsonArray("parts") {
                        if (text.isNotEmpty()) add(buildJsonObject { put("text", text) })
                        calls.forEach { c ->
                            add(
                                buildJsonObject {
                                    putJsonObject("functionCall") {
                                        put("name", c.name)
                                        put("args", c.args)
                                    }
                                },
                            )
                        }
                        if (text.isEmpty() && calls.isEmpty()) add(buildJsonObject { put("text", "") })
                    }
                }
            val sources =
                (message["annotations"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val citation = (element as? JsonObject)?.get("url_citation") as? JsonObject ?: return@mapNotNull null
                    val url = (citation["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    url to (citation["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
            return GeminiTurn(replay, withSources(text, sources), calls)
        }

        /** Message content is a string for most providers, but some send an array of typed parts. */
        private fun contentText(content: JsonElement?): String =
            when (content) {
                is JsonPrimitive -> content.contentOrNull.orEmpty()
                is JsonArray ->
                    content.joinToString("") { part ->
                        when (part) {
                            is JsonPrimitive -> part.contentOrNull.orEmpty()
                            is JsonObject -> (part["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            else -> ""
                        }
                    }
                else -> ""
            }

        private fun errorCode(root: JsonObject): Int =
            ((root["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

        private fun errorMessage(
            body: String,
            code: Int,
        ): String = apiError(body) ?: "Gemini request failed (HTTP $code)"

        private fun apiError(body: String): String? {
            val apiMessage =
                runCatching {
                    val error = json.parseToJsonElement(body).jsonObject["error"]
                    when (error) {
                        is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
                        is JsonPrimitive -> error.contentOrNull
                        else -> null
                    }
                }.getOrNull()
            return apiMessage?.takeIf { it.isNotBlank() }
        }

        private fun parse(raw: String): GeminiTurn {
            val root = json.parseToJsonElement(raw).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            val content = candidate?.get("content")?.jsonObject
            if (content == null) {
                val blocked =
                    root["promptFeedback"]
                        ?.jsonObject
                        ?.get("blockReason")
                        ?.jsonPrimitive
                        ?.contentOrNull
                throw GeminiException(if (blocked != null) "The request was blocked ($blocked)" else "Gemini returned no answer")
            }
            val parts = content["parts"]?.jsonArray.orEmpty().map { it.jsonObject }
            val text =
                parts
                    .filter { it["thought"]?.jsonPrimitive?.contentOrNull != "true" }
                    .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("")
            val calls =
                parts.mapNotNull { part ->
                    val call = part["functionCall"]?.jsonObject ?: return@mapNotNull null
                    val name = call["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    GeminiFunctionCall(name, call["args"] as? JsonObject ?: JsonObject(emptyMap()))
                }
            // A turn with neither text nor calls still needs a role to be replayed as history.
            val replay =
                if (content["role"] != null) {
                    content
                } else {
                    buildJsonObject {
                        put("role", "model")
                        put("parts", JsonArray(parts))
                    }
                }
            val sources =
                ((candidate?.get("groundingMetadata") as? JsonObject)?.get("groundingChunks") as? JsonArray)
                    .orEmpty()
                    .mapNotNull { chunk ->
                        val web = (chunk as? JsonObject)?.get("web") as? JsonObject ?: return@mapNotNull null
                        val uri = (web["uri"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        uri to (web["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    }
            return GeminiTurn(replay, withSources(text.trim(), sources), calls)
        }

        /**
         * Adds the web pages a grounded answer used as a short markdown "Sources" list, skipping any the answer
         * already links. Only shown to the user; the model's own history keeps the answer as it gave it.
         */
        private fun withSources(
            text: String,
            sources: List<Pair<String, String>>,
        ): String {
            if (text.isBlank()) return text
            val fresh =
                sources
                    .filter { (url, _) -> url.startsWith("http") && url !in text }
                    .distinctBy { it.first }
                    .take(WEB_RESULTS)
            if (fresh.isEmpty()) return text
            return buildString {
                append(text.trimEnd())
                append("\n\nSources:")
                fresh.forEach { (url, title) ->
                    val name = title.ifBlank { url.substringAfter("://").substringBefore('/') }.replace("[", "").replace("]", "")
                    append("\n- [").append(name).append("](").append(url).append(')')
                }
            }
        }

        companion object {
            private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
            private const val TIMEOUT_SECONDS = 90L
            private const val WEB_RESULTS = 5
            const val DEFAULT_TEMPERATURE = 0.5
            private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

            fun userContent(text: String): JsonObject =
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                }

            fun modelContent(text: String): JsonObject =
                buildJsonObject {
                    put("role", "model")
                    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                }

            /** The answers to a model turn's calls, in the order they were asked. */
            fun functionResponses(results: List<Pair<String, JsonObject>>): JsonObject =
                buildJsonObject {
                    put("role", "user")
                    put(
                        "parts",
                        buildJsonArray {
                            results.forEach { (name, result) ->
                                add(
                                    buildJsonObject {
                                        putJsonObject("functionResponse") {
                                            put("name", name)
                                            put("response", result)
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
        }
    }
