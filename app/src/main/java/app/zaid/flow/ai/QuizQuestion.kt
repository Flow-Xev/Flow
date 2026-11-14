package app.zaid.flow.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One multiple-choice question from the quiz. [feedback] reacts to the answer to the question before it. */
data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    val feedback: String,
    val number: Int,
)

private const val MIN_OPTIONS = 2
private const val MAX_OPTIONS = 5
private val OPTION_PREFIX = Regex("""^\s*(?:\(?[A-Ea-e]\)|[A-Ea-e][.):])\s+""")

/**
 * Reads the model's quiz reply, a JSON object, into a question. Returns null when the reply is plain text
 * or not a usable question (so the sheet shows it as an ordinary answer instead of a broken card).
 */
internal fun parseQuiz(
    reply: String,
    number: Int,
): QuizQuestion? {
    val open = reply.indexOf('{')
    val close = reply.lastIndexOf('}')
    if (open < 0 || close <= open) return null
    val obj =
        runCatching { Json.parseToJsonElement(reply.substring(open, close + 1)).jsonObject }.getOrNull()
            ?: return null
    val question = obj.text("question").takeIf { it.isNotBlank() } ?: return null
    val options =
        runCatching {
            obj["options"]?.jsonArray?.map {
                it.jsonPrimitive.content
                    .replace(OPTION_PREFIX, "")
                    .trim()
            }
        }.getOrNull()
            ?.filter { it.isNotEmpty() }
            ?.take(MAX_OPTIONS)
            ?: return null
    if (options.size < MIN_OPTIONS) return null
    val correct = correctIndexOf(obj, options.size) ?: return null
    return QuizQuestion(
        question = question,
        options = options,
        correctIndex = correct,
        explanation = obj.text("explanation"),
        feedback = obj.text("feedback"),
        number = number,
    )
}

private fun JsonObject.text(key: String): String =
    this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        .orEmpty()

/** The right option as a number, or as a letter ("B"), whichever the model used. */
private fun correctIndexOf(
    obj: JsonObject,
    size: Int,
): Int? {
    val raw = obj.text("correct").ifEmpty { obj.text("answer") }
    val number = raw.toDoubleOrNull()?.toInt()
    val index =
        number ?: raw
            .singleOrNull()
            ?.uppercaseChar()
            ?.takeIf { it in 'A'..'E' }
            ?.minus('A')
    return index?.takeIf { it in 0 until size }
}
