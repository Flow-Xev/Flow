package app.zaid.flow.ai

import android.util.Log
import app.zaid.flow.data.ai.GeminiClient
import app.zaid.flow.data.ai.GeminiSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val INSPECTOR_TEMPERATURE = 0.0

private val INSPECTOR_PROMPT =
    """
    You are the Inspector: a strict second reviewer of chapters that another AI made for a video's seek bar. The transcript is numbered lines: "[line number] mm:ss what is said". The draft chapters are given as [{"line": n, "title": "..."}].

    Check every chapter against the transcript, then reply with ONLY the final, corrected JSON array in the same shape, no markdown and no commentary: [{"line": <line number>, "title": "<title>"}, ...]

    Fix these problems, and change nothing that is already good:
    - Boundary: a chapter must begin on the line where the speaker FIRST opens the new topic. Move it if it sits mid-topic or a line too early or late. Line numbers must exist, be increasing, and the first chapter uses line 0.
    - Split or merge: merge two chapters about one continuous topic; add a chapter when one chapter clearly holds two separate topics. Keep chapters at least 30 seconds long.
    - Title accuracy: a title may describe ONLY what is said from its line up to the next chapter's line. Remove anything invented, exaggerated or clickbait.
    - Title style: ALWAYS English (translate if the transcript is not English), 2 to 6 words, Title Case, specific and natural. No timestamps, numbering, quotes or trailing punctuation. No duplicate or vague titles ("Introduction Part 2", "Next Topic", "Continued").
    - Sponsor reads and subscribe outros are their own chapter only when clearly that, titled "Sponsor Message" or "Outro".
    - Make the titles better: concrete and informative, so a viewer knows what they will get before they tap.

    If the draft is already excellent, return it unchanged.
    """.trimIndent()

/**
 * A second pair of eyes for AI chapters. It runs on its own Gemini key (set in Settings, normally from
 * another Google account), reads the same numbered transcript the first model saw and returns improved
 * chapters. It never makes things worse: an unusable, much shorter or failed review leaves the original
 * chapters as they were.
 */
@Singleton
class AiChapterInspector
    @Inject
    internal constructor(
        private val client: GeminiClient,
        private val settings: GeminiSettingsStore,
    ) {
        /** True when the Inspector is switched on and has a key of its own. */
        internal suspend fun isReady(): Boolean = settings.isInspectorEnabled() && settings.currentInspectorApiKey().isNotBlank()

        internal suspend fun inspect(
            draft: List<AiChapter>,
            lines: List<TranscriptLine>,
            durationSeconds: Int,
            allowed: IntRange,
        ): List<AiChapter> {
            if (draft.size < MIN_DRAFT || lines.isEmpty()) return draft
            val apiKey = settings.currentInspectorApiKey()
            if (!settings.isInspectorEnabled() || apiKey.isBlank()) return draft
            return try {
                val prompt =
                    "Video length: $durationSeconds seconds. Keep between ${allowed.first} and ${allowed.last} chapters.\n\n" +
                        "Draft chapters:\n${draftJson(draft, lines)}\n\nTranscript:\n${promptTranscript(lines)}"
                val turn =
                    client.generate(
                        apiKey = apiKey,
                        model = settings.currentModel(),
                        systemInstruction = INSPECTOR_PROMPT,
                        contents = JsonArray(listOf(GeminiClient.userContent(prompt))),
                        functionDeclarations = JsonArray(emptyList()),
                        temperature = INSPECTOR_TEMPERATURE,
                        jsonOutput = true,
                    )
                val reviewed = parseLineChapters(turn.text, lines, durationSeconds, allowed)
                if (isAcceptable(draft, reviewed)) reviewed else draft
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Inspector could not review the chapters", e)
                draft
            }
        }

        private companion object {
            const val TAG = "AiChapterInspector"
            const val MIN_DRAFT = 3
        }
    }

/** The draft as the model reads it: each chapter on the transcript line it begins at. */
internal fun draftJson(
    draft: List<AiChapter>,
    lines: List<TranscriptLine>,
): String =
    buildJsonArray {
        draft.forEach { chapter ->
            add(
                buildJsonObject {
                    put("line", lineIndexAt(lines, chapter.startSeconds))
                    put("title", chapter.title)
                },
            )
        }
    }.toString()

/** The last line that starts at or before [seconds]; 0 when the chapter starts before the first line. */
internal fun lineIndexAt(
    lines: List<TranscriptLine>,
    seconds: Int,
): Int {
    var found = 0
    for (line in lines) {
        if (line.startSeconds <= seconds) found = line.index else break
    }
    return found
}

/**
 * A review is only used when it is still a real chapter list: at least three chapters and not far fewer
 * than the draft, so an over-eager merge or a half-answer never replaces good chapters.
 */
internal fun isAcceptable(
    draft: List<AiChapter>,
    reviewed: List<AiChapter>,
): Boolean = reviewed.size >= 3 && reviewed.size * 2 >= draft.size
