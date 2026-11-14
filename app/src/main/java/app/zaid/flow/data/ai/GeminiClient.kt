package app.zaid.flow.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

        private fun errorMessage(
            body: String,
            code: Int,
        ): String {
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
            return apiMessage?.takeIf { it.isNotBlank() } ?: "Gemini request failed (HTTP $code)"
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
