package app.zaid.flow.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
        ): GeminiTurn {
            val config = settings.currentConfig()
            return if (config.provider == AiProvider.GEMINI) {
                generateGemini(apiKey, model, systemInstruction, contents, functionDeclarations, temperature, jsonOutput)
            } else {
                generateOpenAi(config, apiKey, model, systemInstruction, contents, functionDeclarations, temperature, jsonOutput)
            }
        }

        private suspend fun generateGemini(
            apiKey: String,
            model: String,
            systemInstruction: String,
            contents: JsonArray,
            functionDeclarations: JsonArray,
            temperature: Double,
            jsonOutput: Boolean,
        ): GeminiTurn =
            withContext(Dispatchers.IO) {
                val body =
                    buildJsonObject {
                        putJsonObject("systemInstruction") {
                            putJsonArray("parts") { add(buildJsonObject { put("text", systemInstruction) }) }
                        }
                        put("contents", contents)
                        if (functionDeclarations.isNotEmpty()) {
                            putJsonArray("tools") {
                                add(buildJsonObject { put("functionDeclarations", functionDeclarations) })
                            }
                        }
                        putJsonObject("generationConfig") {
                            put("temperature", temperature)
                            // Valid only without function declarations; the caller asks for it on plain answers.
                            if (jsonOutput && functionDeclarations.isEmpty()) put("responseMimeType", "application/json")
                        }
                    }
                val request =
                    Request
                        .Builder()
                        .url("$ENDPOINT/${model.trim()}:generateContent")
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
                        throw GeminiException(e.message ?: "Network error", 0)
                    }
                parse(raw)
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
        ): GeminiTurn =
            withContext(Dispatchers.IO) {
                val label = config.provider.label
                val root = config.endpointRoot
                if (root.isBlank()) throw GeminiException("Set the base URL of your custom provider in Settings > Integrations.")
                if (model.isBlank()) throw GeminiException("Set a model name for $label in Settings > Integrations.")
                val openRouter = config.provider == AiProvider.OPENROUTER
                val fallbacks = if (openRouter) config.fallbacks.split(',').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
                val primary = model.trim().let { if (openRouter && config.webSearch && !it.endsWith(":online")) "$it:online" else it }
                val hasTools = functionDeclarations.isNotEmpty()
                val body =
                    buildJsonObject {
                        if (fallbacks.isEmpty()) {
                            put("model", primary)
                        } else {
                            putJsonArray("models") { (listOf(primary) + fallbacks).forEach { add(JsonPrimitive(it)) } }
                        }
                        put("temperature", temperature)
                        put("messages", toOpenAiMessages(systemInstruction, contents, cacheSystem = openRouter))
                        if (hasTools) {
                            putJsonArray("tools") {
                                functionDeclarations.forEach { decl ->
                                    add(
                                        buildJsonObject {
                                            put("type", "function")
                                            put("function", decl)
                                        },
                                    )
                                }
                            }
                            put("tool_choice", "auto")
                        } else if (jsonOutput && config.provider != AiProvider.CUSTOM) {
                            putJsonObject("response_format") { put("type", "json_object") }
                        }
                        if (openRouter) {
                            // Only route to providers that really support tools/JSON, optionally to ones that keep no data.
                            putJsonObject("provider") {
                                put("require_parameters", true)
                                if (config.noDataStorage) put("data_collection", "deny")
                            }
                            // Long transcripts are compressed to fit the model's window instead of failing.
                            putJsonArray("transforms") { add(JsonPrimitive("middle-out")) }
                        }
                    }
                val request =
                    Request
                        .Builder()
                        .url("$root/chat/completions")
                        .apply {
                            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                            if (openRouter) header("X-Title", "Flow")
                        }.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                val raw =
                    try {
                        client.newCall(request).execute().use { response ->
                            val text = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                // httpCode 0 keeps the controller from blaming Gemini; the text names the provider.
                                throw GeminiException("$label: ${apiError(text) ?: "request failed (HTTP ${response.code})"}", 0)
                            }
                            text
                        }
                    } catch (e: GeminiException) {
                        throw e
                    } catch (e: IOException) {
                        throw GeminiException("$label: ${e.message ?: "Network error"}", 0)
                    }
                parseOpenAi(raw, label)
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
                                            val id = "call_${index}_$i"
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
                                    put("tool_call_id", pendingIds.removeFirstOrNull() ?: "call_${index}_x")
                                    put("content", (r["response"] ?: JsonObject(emptyMap())).toString())
                                },
                            )
                        }
                        val text = parts.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
                        if (text.isNotEmpty()) {
                            add(buildJsonObject { put("role", "user"); put("content", text) })
                        }
                    }
                }
            }

        private fun parseOpenAi(
            raw: String,
            label: String,
        ): GeminiTurn {
            val root = json.parseToJsonElement(raw).jsonObject
            val message =
                root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
                    ?: throw GeminiException("$label: ${apiError(raw) ?: "no answer returned"}")
            val text = (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
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
            return GeminiTurn(replay, text, calls)
        }

        private fun errorMessage(
            body: String,
            code: Int,
        ): String = apiError(body) ?: "Gemini request failed (HTTP $code)"

        private fun apiError(body: String): String? {
            val apiMessage =
                runCatching {
                    json
                        .parseToJsonElement(body)
                        .jsonObject["error"]
                        ?.jsonObject
                        ?.get("message")
                        ?.jsonPrimitive
                        ?.contentOrNull
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
            return GeminiTurn(replay, text.trim(), calls)
        }

        companion object {
            private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
            private const val TIMEOUT_SECONDS = 90L
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
