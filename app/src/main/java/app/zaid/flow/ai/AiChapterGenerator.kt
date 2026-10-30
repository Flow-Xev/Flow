package app.zaid.flow.ai

import android.content.Context
import android.util.LruCache
import app.zaid.flow.data.ai.GeminiClient
import app.zaid.flow.data.ai.GeminiSettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject
import javax.inject.Singleton

/** Videos shorter than this are not worth splitting. */
private const val MIN_VIDEO_SECONDS = 180
private const val MAX_TRANSCRIPT_CHARS = 100_000

/** Low, so the same video gets the same chapters and the model sticks to what the transcript says. */
private const val CHAPTER_TEMPERATURE = 0.1

private val CHAPTER_PROMPT =
    """
    You split a video's transcript into chapters for its seek bar. The transcript is numbered lines: "[line number] mm:ss what is said" (or "h:mm:ss").

    Reply with ONLY a JSON array, no markdown and no commentary: [{"line": <line number where the chapter begins>, "title": "<chapter title>"}, ...]

    Choosing where a chapter begins:
    - A chapter begins on the line where the speaker FIRST starts the new topic: the sentence that opens it, announces it ("now let's talk about...", "next...") or turns to it. Not a line in the middle of the topic, and not the line before it.
    - Only split where the subject really changes. Never split in the middle of one explanation, story or demo, however long it is.
    - The first chapter always uses line 0.
    - Line numbers must be increasing and must exist in the transcript. Use the line numbers, never times.
    - Chapters are at least 30 seconds long. Use the number of chapters given in the request.
    - A sponsor read or a "like and subscribe" outro is its own chapter only when it is clearly that, and is then titled "Sponsor Message" or "Outro".

    Choosing the title:
    - Describe ONLY what is said from that chapter's line up to the next chapter's line. Never name a topic that is not discussed in that stretch, and never invent one.
    - Titles are ALWAYS written in English, whatever language the transcript is in. Translate the topic into English; never reply in another language or script.
    - 2 to 6 words in Title Case, specific and natural (e.g. "Setting Up the Project", "Why the Engine Overheats"). Avoid vague titles like "Introduction Part 2", "Next Topic", "Continued" or "Conclusion Thoughts", and avoid clickbait.
    - No timestamps, no line numbers, no quotes and no numbering in titles. Do not repeat a title.
    """.trimIndent()

/**
 * Makes chapters for a video that has none, from its captions, with the user's own Gemini key.
 *
 * The model is shown the captions as numbered lines and answers with line numbers, so every chapter
 * starts on a moment the captions really contain; each boundary is then checked against the wording
 * around it and nudged onto the real topic change. Finished chapters are kept on disk (see
 * [AiChapterStore]), so each video is asked about once, not once per launch. A failed call is not kept
 * and may be retried the next time the video opens.
 */
@Singleton
class AiChapterGenerator
    @Inject
    internal constructor(
        private val client: GeminiClient,
        private val settings: GeminiSettingsStore,
        private val tools: FlowAiTools,
        private val store: AiChapterStore,
        private val inspector: AiChapterInspector,
    ) {
        private val cache = LruCache<String, List<AiChapter>>(100)

        // One video at a time: opening the same video twice must not ask the model twice.
        private val generating = Mutex()

        /** Empty when the feature is off, the video is too short, it has no captions, or the reply was unusable. */
        internal suspend fun chaptersFor(
            videoId: String,
            durationSeconds: Int,
        ): List<AiChapter> {
            cache.get(videoId)?.let { return it }
            if (!settings.isEnabled() || !settings.isChaptersEnabled()) return emptyList()
            return generating.withLock {
                cache.get(videoId)?.let { return@withLock it }
                // Chapters made before cost nothing to show again, and need no key.
                store.read(videoId)?.let { return@withLock remember(videoId, it) }
                val apiKey = settings.currentApiKey()
                if (apiKey.isBlank()) return@withLock emptyList()

                val cues = tools.cuesFor(videoId)
                if (cues.isNullOrEmpty()) return@withLock remember(videoId, emptyList())
                val length = durationSeconds.takeIf { it > 0 } ?: (cues.last().endMs / 1000).toInt()
                if (length < MIN_VIDEO_SECONDS) return@withLock remember(videoId, emptyList())

                val lines = buildChapterLines(cues, MAX_TRANSCRIPT_CHARS)
                val range = chapterCountRange(length)
                val prompt =
                    "Video length: $length seconds. Write ${range.first} to ${range.last} chapters. " +
                        "Write every chapter title in English.\n\nTranscript:\n${promptTranscript(lines)}"

                var chapters = emptyList<AiChapter>()
                // One retry when the reply could not be used: an occasional malformed answer is not a verdict on the video.
                repeat(ATTEMPTS) { attempt ->
                    if (chapters.isNotEmpty()) return@repeat
                    val turn =
                        client.generate(
                            apiKey = apiKey,
                            model = settings.currentModel(),
                            systemInstruction = CHAPTER_PROMPT,
                            contents = JsonArray(listOf(GeminiClient.userContent(prompt))),
                            functionDeclarations = JsonArray(emptyList()),
                            temperature = if (attempt == 0) CHAPTER_TEMPERATURE else 0.0,
                            jsonOutput = true,
                        )
                    chapters = parseLineChapters(turn.text, lines, length, range)
                }
                // The Inspector, when it is on, reviews the draft before it is shown or kept.
                if (chapters.isNotEmpty() && inspector.isReady()) {
                    chapters = inspector.inspect(chapters, lines, length, range)
                }
                if (chapters.isNotEmpty()) store.write(videoId, chapters)
                remember(videoId, chapters)
            }
        }

        private fun remember(
            videoId: String,
            chapters: List<AiChapter>,
        ): List<AiChapter> = chapters.also { cache.put(videoId, it) }

        private companion object {
            const val ATTEMPTS = 2
        }
    }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiChapterEntryPoint {
    fun aiChapterGenerator(): AiChapterGenerator
}

/** For code that Hilt does not build, such as the player's session applier. */
internal fun aiChapterGenerator(context: Context): AiChapterGenerator =
    EntryPointAccessors
        .fromApplication(context.applicationContext, AiChapterEntryPoint::class.java)
        .aiChapterGenerator()
