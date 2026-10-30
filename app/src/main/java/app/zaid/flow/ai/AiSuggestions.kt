package app.zaid.flow.ai

import androidx.annotation.StringRes
import app.zaid.flow.R
import app.zaid.flow.data.model.Video

/** One shortcut pill in the assistant sheet. Its label is also the question that gets asked. */
data class AiPill(
    val id: String,
    @StringRes val label: Int,
)

/** What a video is mostly about, as far as its title, tags and length say. Decides which pills fit. */
enum class VideoKind { EDUCATION, MUSIC, GAMING, NEWS, PODCAST, TECH, COOKING, FITNESS, GENERAL }

private fun pill(
    id: String,
    @StringRes label: Int,
) = AiPill(id, label)

/** The pill that starts a quiz instead of asking a question. */
const val QUIZ_PILL_ID = "quiz"

/** The pill that ends a quiz with its score. */
const val QUIZ_FINISH_PILL_ID = "quiz_finish"

/** What the pill row offers while a quiz is running. */
fun quizPills(): List<AiPill> =
    listOf(
        pill("quiz_next", R.string.flow_ai_pill_quiz_next),
        pill("quiz_easier", R.string.flow_ai_pill_quiz_easier),
        pill("quiz_harder", R.string.flow_ai_pill_quiz_harder),
        pill(QUIZ_FINISH_PILL_ID, R.string.flow_ai_pill_quiz_finish),
    )

private val SUMMARISE = pill("summarise", R.string.flow_ai_pill_summarise)
private val KEY_POINTS = pill("key_points", R.string.flow_ai_pill_key_points)
private val CHAPTERS = pill("chapters", R.string.flow_ai_pill_chapters)
private val TLDR = pill("tldr", R.string.flow_ai_pill_tldr)
private val RELATED = pill("related", R.string.flow_ai_pill_related)

private val EDUCATION_WORDS =
    listOf(
        "lecture",
        "tutorial",
        "course",
        "lesson",
        "explained",
        "explain",
        "how to",
        "how does",
        "why does",
        "learn",
        "crash course",
        "for beginners",
        "beginner",
        "introduction to",
        "intro to",
        "masterclass",
        "class ",
        "exam",
        "physics",
        "chemistry",
        "biology",
        "mathematics",
        "maths",
        "math ",
        "calculus",
        "algebra",
        "geometry",
        "history of",
        "science",
        "programming",
        "python",
        "kotlin",
        "javascript",
        "algorithm",
        "khan academy",
        "ted-ed",
        "documentary",
        "study",
        "syllabus",
        "chapter ",
    )
private val MUSIC_WORDS =
    listOf("official video", "official audio", "lyrics", "lyric video", "remix", "music video", "(audio)", "feat.", "ft.")
private val GAMING_WORDS =
    listOf("gameplay", "walkthrough", "let's play", "speedrun", "minecraft", "fortnite", "gta ", "valorant", "boss fight", "playthrough")
private val NEWS_WORDS = listOf("breaking news", "news", "headlines", "election", "live updates", "press conference", "debate")
private val PODCAST_WORDS = listOf("podcast", "interview", "conversation with", " ep.", "episode", "full episode", "in conversation")
private val TECH_WORDS = listOf("review", "unboxing", "vs ", " vs.", "iphone", "android", "hands-on", "first look", "benchmark")
private val COOKING_WORDS = listOf("recipe", "cooking", "how to make", "baking", "kitchen")
private val FITNESS_WORDS = listOf("workout", "exercise", "yoga", "fitness", "stretching", "home workout", "gym")

/** Classifies a video from what is known about it without asking the model. */
fun videoKindOf(video: Video?): VideoKind {
    if (video == null) return VideoKind.GENERAL
    if (video.isMusic) return VideoKind.MUSIC
    val text = (video.title + " " + video.tags.joinToString(" ") + " " + video.description.take(300)).lowercase()
    val title = video.title.lowercase()

    fun hits(words: List<String>) = words.count { it in title } * 2 + words.count { it in text }
    val scores =
        mapOf(
            VideoKind.MUSIC to hits(MUSIC_WORDS),
            VideoKind.GAMING to hits(GAMING_WORDS),
            VideoKind.NEWS to hits(NEWS_WORDS),
            VideoKind.PODCAST to hits(PODCAST_WORDS) + if (video.duration >= 40 * 60) 1 else 0,
            VideoKind.TECH to hits(TECH_WORDS),
            VideoKind.COOKING to hits(COOKING_WORDS),
            VideoKind.FITNESS to hits(FITNESS_WORDS),
            VideoKind.EDUCATION to hits(EDUCATION_WORDS),
        )
    val best = scores.maxByOrNull { it.value }
    return if (best == null || best.value < 2) VideoKind.GENERAL else best.key
}

/** The pills for the video that is open; they change when the video does. No video gives the general set. */
fun pillsFor(video: Video?): List<AiPill> {
    if (video == null) {
        return listOf(
            pill("recent", R.string.flow_ai_pill_recent),
            pill("searches", R.string.flow_ai_pill_searches),
            pill("find", R.string.flow_ai_pill_find),
        )
    }
    val specific =
        when (videoKindOf(video)) {
            VideoKind.EDUCATION -> {
                listOf(
                    pill(QUIZ_PILL_ID, R.string.flow_ai_pill_quiz),
                    pill("explain_simple", R.string.flow_ai_pill_explain_simple),
                    pill("study_notes", R.string.flow_ai_pill_study_notes),
                    pill("flashcards", R.string.flow_ai_pill_flashcards),
                    pill("examples", R.string.flow_ai_pill_examples),
                )
            }

            VideoKind.MUSIC -> {
                listOf(
                    pill("song_about", R.string.flow_ai_pill_song_about),
                    pill("similar_songs", R.string.flow_ai_pill_similar_songs),
                    pill("about_artist", R.string.flow_ai_pill_about_artist),
                )
            }

            VideoKind.GAMING -> {
                listOf(
                    pill("game_tips", R.string.flow_ai_pill_game_tips),
                    pill("best_moments", R.string.flow_ai_pill_best_moments),
                    pill("game_guides", R.string.flow_ai_pill_game_guides),
                )
            }

            VideoKind.NEWS -> {
                listOf(
                    pill("key_facts", R.string.flow_ai_pill_key_facts),
                    pill("claims", R.string.flow_ai_pill_claims),
                    pill("perspectives", R.string.flow_ai_pill_perspectives),
                )
            }

            VideoKind.PODCAST -> {
                listOf(
                    pill("topics", R.string.flow_ai_pill_topics),
                    pill("best_moments", R.string.flow_ai_pill_best_moments),
                    pill("guest", R.string.flow_ai_pill_guest),
                )
            }

            VideoKind.TECH -> {
                listOf(
                    pill("pros_cons", R.string.flow_ai_pill_pros_cons),
                    pill("verdict", R.string.flow_ai_pill_verdict),
                    pill("alternatives", R.string.flow_ai_pill_alternatives),
                )
            }

            VideoKind.COOKING -> {
                listOf(
                    pill("ingredients", R.string.flow_ai_pill_ingredients),
                    pill("steps", R.string.flow_ai_pill_steps),
                    pill("substitutes", R.string.flow_ai_pill_substitutes),
                )
            }

            VideoKind.FITNESS -> {
                listOf(
                    pill("routine", R.string.flow_ai_pill_routine),
                    pill("mistakes", R.string.flow_ai_pill_mistakes),
                    KEY_POINTS,
                )
            }

            VideoKind.GENERAL -> {
                listOf(TLDR, RELATED)
            }
        }
    val long = video.duration >= 10 * 60
    return buildList {
        add(SUMMARISE)
        addAll(specific)
        if (VideoKind.GENERAL == videoKindOf(video) || none { it.id == "key_points" }) add(KEY_POINTS)
        if (long) add(CHAPTERS)
    }.distinctBy { it.id }
}
