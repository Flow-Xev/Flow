package app.zaid.flow.ai

import app.zaid.flow.data.local.SearchHistoryRepository
import app.zaid.flow.data.ai.GeminiSettingsStore
import app.zaid.flow.data.localmedia.LocalMediaRepository
import app.zaid.flow.data.local.ViewHistory
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.local.entity.DownloadItemStatus
import app.zaid.flow.data.repository.YouTubeRepository
import app.zaid.flow.data.video.VideoDownloadManager
import app.zaid.flow.data.video.downloader.work.DownloadController
import app.zaid.flow.data.transcript.TranscriptCue
import app.zaid.flow.data.transcript.TranscriptRepository
import app.zaid.flow.player.EnhancedPlayerManager
import app.zaid.flow.player.GlobalPlayerState
import app.zaid.flow.player.state.SubtitleOption
import app.zaid.flow.player.state.SubtitleOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What one tool call produced: the JSON the model reads, and the videos the chat should show as cards. */
internal class ToolResult(
    val response: JsonObject,
    val videos: List<Video> = emptyList(),
    val openVideo: Video? = null,
    /** A screen route the app shell should open, from the open_screen tool. */
    val openScreen: String? = null,
)

/**
 * The things the assistant can do inside Flow: look at the video playing now, read any video's
 * transcript, search YouTube, look through watch and search history, and open or seek a video.
 *
 * Every tool answers with a small JSON object. A failure is an `error` string the model can relay,
 * never an exception, so one bad call does not end the conversation.
 */
@Singleton
internal class FlowAiTools
    @Inject
    constructor(
        private val youtube: YouTubeRepository,
        private val viewHistory: ViewHistory,
        private val searchHistory: SearchHistoryRepository,
        private val transcripts: TranscriptRepository,
        private val localMedia: LocalMediaRepository,
        private val aiSettings: GeminiSettingsStore,
        private val downloadManager: VideoDownloadManager,
        private val downloadController: DownloadController,
    ) {
        /** Videos met in this chat, so opening one needs no second lookup. */
        private val known = LinkedHashMap<String, Video>()

        fun forget() = known.clear()

        val declarations: JsonArray =
            buildJsonArray {
                add(
                    declaration(
                        "get_current_video",
                        "Details of the video that is open in the player right now: id, title, channel, length, how far in " +
                            "the viewer is, and whether captions exist. Call this when the user says \"this video\".",
                    ),
                )
                add(
                    declaration(
                        "get_transcript",
                        "The spoken text of a video with [mm:ss] timestamps, from its captions. Use it to summarise, answer " +
                            "questions about, or find a moment in a video. Omit video_id for the video playing now. For a long " +
                            "video the text may be cut off: call again with start_seconds/end_seconds to read the rest.",
                        "video_id" to ("string" to "YouTube video id. Omit for the current video."),
                        "start_seconds" to ("integer" to "Only lines from this second on."),
                        "end_seconds" to ("integer" to "Only lines before this second."),
                    ),
                )
                add(
                    declaration(
                        "search_youtube",
                        "Search YouTube for videos. Results are shown to the user as tappable cards.",
                        "query" to ("string" to "What to search for."),
                        "limit" to ("integer" to "How many results, 1-10. Default 6."),
                        required = listOf("query"),
                    ),
                )
                add(
                    declaration(
                        "search_watch_history",
                        "Search the videos the user has watched in Flow. With no query it lists the most recent ones. " +
                            "Results are shown as tappable cards.",
                        "query" to ("string" to "Words to look for in the title or channel name. Optional."),
                        "days_back" to ("integer" to "Only videos watched within this many days. Optional."),
                        "limit" to ("integer" to "How many results, 1-20. Default 10."),
                    ),
                )
                add(
                    declaration(
                        "get_search_history",
                        "The user's recent search queries in Flow, newest first. Only use when the user asks about their searches.",
                        "limit" to ("integer" to "How many, 1-50. Default 20."),
                    ),
                )
                add(
                    declaration(
                        "get_music_library",
                        "Read-only overview of songs available in the user's device music library. Use only when asked about their local music, songs, artists, or albums. Does not upload or modify files.",
                        "query" to ("string" to "Optional words to match in song title, artist, or album."),
                        "limit" to ("integer" to "How many matches, 1-30. Default 15."),
                    ),
                )
                add(
                    declaration(
                        "open_video",
                        "Open a video in the player for the user. Use when they ask to play or open something.",
                        "video_id" to ("string" to "YouTube video id, from an earlier search or history result."),
                        required = listOf("video_id"),
                    ),
                )
                add(
                    declaration(
                        "open_screen",
                        "Open a screen of the Flow app for the user. Screens: " + SCREEN_ROUTES.keys.joinToString(", ") + ". " +
                            "Use when they ask to go to, open or show a part of the app (music library, downloads, history...).",
                        "screen" to ("string" to "One of: " + SCREEN_ROUTES.keys.joinToString(", ")),
                        required = listOf("screen"),
                    ),
                )
                add(
                    declaration(
                        "list_downloads",
                        "The user's downloads (finished and in progress) with status and progress. Read-only.",
                        "limit" to ("integer" to "How many, 1-30. Default 15."),
                    ),
                )
                add(
                    declaration(
                        "manage_download",
                        "Pause, resume or delete one download. Deleting removes the file and needs the user's permission.",
                        "video_id" to ("string" to "Video id from list_downloads."),
                        "action" to ("string" to "pause, resume or delete."),
                        required = listOf("video_id", "action"),
                    ),
                )
                add(
                    declaration(
                        "seek_current_video",
                        "Jump the video that is playing now to a time.",
                        "seconds" to ("integer" to "Where to jump to, in seconds from the start."),
                        required = listOf("seconds"),
                    ),
                )
            }

        suspend fun run(
            name: String,
            args: JsonObject,
        ): ToolResult =
            try {
                when (name) {
                    "get_current_video" -> currentVideo()
                    "get_transcript" -> transcript(args)
                    "search_youtube" -> searchYoutube(args)
                    "search_watch_history" -> searchWatchHistory(args)
                    "get_search_history" -> searchQueries(args)
                    "get_music_library" -> musicLibrary(args)
                    "open_video" -> openVideo(args)
                    "open_screen" -> openScreen(args)
                    "list_downloads" -> listDownloads(args)
                    "manage_download" -> manageDownload(args)
                    "seek_current_video" -> seek(args)
                    else -> failure("Unknown tool $name")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(e.message ?: e::class.simpleName ?: "Tool failed")
            }

        // ── current video ───────────────────────────────────────────────────────

        private suspend fun currentVideo(): ToolResult {
            val video = playingVideo() ?: return failure("No video is open in the player.")
            val (positionMs, tracks) = playerSnapshot(video.id)
            return ToolResult(
                buildJsonObject {
                    put("video_id", video.id)
                    put("title", video.title)
                    put("channel", video.channelName)
                    put("duration_seconds", video.duration)
                    put("position_seconds", positionMs / 1000)
                    put("is_live", video.isLive)
                    put("has_captions", tracks.isNotEmpty())
                    if (video.description.isNotBlank()) put("description", video.description.take(DESCRIPTION_CHARS))
                },
            )
        }

        private fun playingVideo(): Video? {
            GlobalPlayerState.currentVideo.value?.let { return it }
            val id =
                EnhancedPlayerManager
                    .getInstance()
                    .playerState.value.currentVideoId ?: return null
            return known[id]
                ?: Video(
                    id = id,
                    title = "",
                    channelName = "",
                    channelId = "",
                    thumbnailUrl = "",
                    duration = 0,
                    viewCount = 0L,
                    uploadDate = "",
                )
        }

        /** The player's position and online caption tracks, read on the thread the player lives on. */
        private suspend fun playerSnapshot(videoId: String): Pair<Long, List<SubtitleOption>> =
            withContext(Dispatchers.Main) {
                val player = EnhancedPlayerManager.getInstance()
                val state = player.playerState.value
                if (state.currentVideoId != videoId) {
                    0L to emptyList()
                } else {
                    player.getCurrentPosition() to state.availableSubtitles.filter { it.origin == SubtitleOrigin.ONLINE }
                }
            }

        // ── transcript ──────────────────────────────────────────────────────────

        /** The caption track to read for [videoId]: the player's own when it is playing, otherwise the extractor's. */
        private suspend fun trackUrlFor(videoId: String): String? =
            playerSnapshot(videoId).second.let { pickTrack(it) }?.url
                ?: youtube
                    .getVideoStreamInfo(videoId)
                    ?.subtitles
                    .orEmpty()
                    .filter { it.content.isNotBlank() }
                    .let { streams ->
                        val device = Locale.getDefault().language
                        (
                            streams.firstOrNull { it.languageTag.startsWith(device) && !it.isAutoGenerated }
                                ?: streams.firstOrNull { it.languageTag.startsWith(device) }
                                ?: streams.firstOrNull { !it.isAutoGenerated }
                                ?: streams.firstOrNull()
                        )?.content
                    }

        /** The captions of [videoId] as timed lines, or null when it has none the app can read. */
        internal suspend fun cuesFor(videoId: String): List<TranscriptCue>? {
            val trackUrl = trackUrlFor(videoId) ?: return null
            return transcripts.cues(trackUrl).takeIf { it.isNotEmpty() }
        }

        private suspend fun transcript(args: JsonObject): ToolResult {
            val requested = args.string("video_id")
            val videoId = requested ?: playingVideo()?.id ?: return failure("No video is open, and no video_id was given.")
            val from = args.int("start_seconds")?.let { it * 1000L } ?: 0L
            val until = args.int("end_seconds")?.let { it * 1000L } ?: Long.MAX_VALUE

            val trackUrl =
                trackUrlFor(videoId) ?: return failure("This video has no captions, so there is no transcript to read.")

            val cues = transcripts.cues(trackUrl)
            if (cues.isEmpty()) return failure("The captions could not be read.")
            return ToolResult(transcriptJson(videoId, cues, from, until))
        }

        private fun pickTrack(tracks: List<SubtitleOption>): SubtitleOption? {
            if (tracks.isEmpty()) return null
            val device = Locale.getDefault().language
            val original = tracks.filter { !it.isTranslated }
            return original.firstOrNull { it.language.startsWith(device) && !it.isAutoGenerated }
                ?: original.firstOrNull { it.language.startsWith(device) }
                ?: original.firstOrNull { !it.isAutoGenerated }
                ?: original.firstOrNull()
                ?: tracks.first()
        }

        private fun transcriptJson(
            videoId: String,
            cues: List<TranscriptCue>,
            from: Long,
            until: Long,
        ): JsonObject {
            val body = StringBuilder()
            var lastIncluded = from
            var truncated = false
            for (cue in cues) {
                if (cue.startMs < from || cue.startMs >= until) continue
                val line = "[${timestamp(cue.startMs)}] ${cue.text}\n"
                if (body.length + line.length > MAX_TRANSCRIPT_CHARS) {
                    truncated = true
                    break
                }
                body.append(line)
                lastIncluded = cue.startMs
            }
            return buildJsonObject {
                put("video_id", videoId)
                put("transcript", body.toString())
                put("total_length_seconds", (cues.last().endMs / 1000))
                if (truncated) {
                    put("truncated", true)
                    put("continue_from_seconds", lastIncluded / 1000 + 1)
                }
            }
        }

        // ── search & history ────────────────────────────────────────────────────

        private suspend fun searchYoutube(args: JsonObject): ToolResult {
            val query = args.string("query") ?: return failure("query is required")
            val limit = (args.int("limit") ?: DEFAULT_SEARCH_LIMIT).coerceIn(1, MAX_SEARCH_LIMIT)
            val videos = youtube.searchVideos(query).first.take(limit)
            if (videos.isEmpty()) return failure("No results for \"$query\".")
            videos.forEach(::remember)
            return ToolResult(
                buildJsonObject { put("results", videoArray(videos)) },
                videos = videos,
            )
        }

        private suspend fun searchWatchHistory(args: JsonObject): ToolResult {
            val words =
                args
                    .string("query")
                    ?.lowercase()
                    ?.split(Regex("\\s+"))
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            val limit = (args.int("limit") ?: DEFAULT_HISTORY_LIMIT).coerceIn(1, MAX_HISTORY_LIMIT)
            val cutoff = args.int("days_back")?.let { System.currentTimeMillis() - it * DAY_MS } ?: Long.MIN_VALUE

            val rows =
                viewHistory
                    .readAccountHistory()
                    .asSequence()
                    .filter { it.timestamp >= cutoff }
                    .filter { row ->
                        val haystack = "${row.title} ${row.channelName}".lowercase()
                        words.all { haystack.contains(it) }
                    }.sortedByDescending { it.timestamp }
                    .take(limit)
                    .toList()
            if (rows.isEmpty()) return failure("Nothing in the watch history matches.")
            val videos = rows.map { it.toVideo() }
            videos.forEach(::remember)
            return ToolResult(
                buildJsonObject {
                    putJsonArray("results") {
                        rows.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("video_id", row.videoId)
                                    put("title", row.title)
                                    put("channel", row.channelName)
                                    put(
                                        "watched_at",
                                        java.time.Instant
                                            .ofEpochMilli(row.timestamp)
                                            .toString(),
                                    )
                                    if (row.duration > 0) {
                                        put("percent_watched", ((row.position * 100) / row.duration).coerceIn(0, 100))
                                    }
                                },
                            )
                        }
                    }
                },
                videos = videos,
            )
        }

        private suspend fun searchQueries(args: JsonObject): ToolResult {
            val limit = (args.int("limit") ?: DEFAULT_QUERY_LIMIT).coerceIn(1, MAX_QUERY_LIMIT)
            val items = searchHistory.getSearchHistoryFlow().first().take(limit)
            if (items.isEmpty()) return failure("The search history is empty (or switched off).")
            return ToolResult(
                buildJsonObject {
                    putJsonArray("queries") {
                        items.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("query", item.query)
                                    put(
                                        "searched_at",
                                        java.time.Instant
                                            .ofEpochMilli(item.timestamp)
                                            .toString(),
                                    )
                                },
                            )
                        }
                    }
                },
            )
        }


        // ── device music library (read-only) ────────────────────────────────────

        private suspend fun musicLibrary(args: JsonObject): ToolResult {
            if (!aiSettings.canReadLocalMusic()) {
                return failure("Access to the device music library is off. The user can enable read-only access in Settings > Integrations > Flow AI.")
            }
            val library = localMedia.library.first()
            if (library.failed) {
                return failure("Flow could not read the device music library. Check Android media permission in system settings; no files were changed.")
            }
            val words = args.string("query")?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
            val limit = (args.int("limit") ?: 15).coerceIn(1, 30)
            val tracks = library.music.asSequence()
                .filter { item -> words.all { item.searchText.contains(it) } }
                .take(limit)
                .toList()
            return ToolResult(
                buildJsonObject {
                    put("total_music_files", library.music.size)
                    put("returned", tracks.size)
                    putJsonArray("tracks") {
                        tracks.forEach { item ->
                            add(buildJsonObject {
                                put("title", item.title)
                                put("artist", item.artist)
                                put("album", item.album)
                                put("duration_seconds", item.durationMs / 1_000L)
                                put("file_name", item.fileName)
                            })
                        }
                    }
                    if (tracks.isEmpty()) put("message", if (words.isEmpty()) "The device music library is empty." else "No local songs matched the query.")
                },
            )
        }

        // ── acting ──────────────────────────────────────────────────────────────

        private suspend fun openVideo(args: JsonObject): ToolResult {
            val id = args.string("video_id") ?: return failure("video_id is required")
            val video = known[id] ?: youtube.getVideo(id) ?: return failure("That video could not be found.")
            remember(video)
            return ToolResult(
                buildJsonObject {
                    put("status", "opened")
                    put("title", video.title)
                },
                openVideo = video,
            )
        }

        private fun openScreen(args: JsonObject): ToolResult {
            val name = args.string("screen")?.trim()?.lowercase()?.replace(' ', '_') ?: return failure("screen is required")
            val route = SCREEN_ROUTES[name] ?: return failure("Unknown screen. Choose one of: " + SCREEN_ROUTES.keys.joinToString(", "))
            return ToolResult(buildJsonObject { put("status", "opened $name") }, openScreen = route)
        }

        private suspend fun listDownloads(args: JsonObject): ToolResult {
            val limit = (args.int("limit") ?: 15).coerceIn(1, 30)
            val all = downloadManager.allDownloads.first()
            return ToolResult(
                buildJsonObject {
                    put("total", all.size)
                    putJsonArray("downloads") {
                        all.take(limit).forEach { d ->
                            add(
                                buildJsonObject {
                                    put("video_id", d.download.videoId)
                                    put("title", d.download.title)
                                    put("status", d.overallStatus.name.lowercase())
                                    put("percent", (d.progress * 100).toInt())
                                },
                            )
                        }
                    }
                },
            )
        }

        private suspend fun manageDownload(args: JsonObject): ToolResult {
            val id = args.string("video_id") ?: return failure("video_id is required")
            val row = downloadManager.allDownloads.first().firstOrNull { it.download.videoId == id } ?: return failure("No such download.")
            return when (args.string("action")?.lowercase()) {
                "pause" -> {
                    downloadController.pause(id)
                    ToolResult(buildJsonObject { put("status", "paused") })
                }

                "resume" -> {
                    downloadController.resume(id)
                    ToolResult(buildJsonObject { put("status", "resumed") })
                }

                "delete" -> {
                    if (row.overallStatus == DownloadItemStatus.COMPLETED) downloadManager.deleteDownload(id) else downloadController.cancel(id).join()
                    ToolResult(buildJsonObject { put("status", "deleted") })
                }

                else -> failure("action must be pause, resume or delete.")
            }
        }

        private suspend fun seek(args: JsonObject): ToolResult {
            val seconds = args.int("seconds") ?: return failure("seconds is required")
            if (playingVideo() == null) return failure("No video is open in the player.")
            withContext(Dispatchers.Main) { EnhancedPlayerManager.getInstance().seekTo(seconds.coerceAtLeast(0) * 1000L) }
            return ToolResult(buildJsonObject { put("status", "jumped to ${timestamp(seconds * 1000L)}") })
        }

        // ── helpers ─────────────────────────────────────────────────────────────

        private fun remember(video: Video) {
            known[video.id] = video
            while (known.size > KNOWN_LIMIT) known.remove(known.keys.first())
        }

        private fun videoArray(videos: List<Video>): JsonArray =
            buildJsonArray {
                videos.forEach { video ->
                    add(
                        buildJsonObject {
                            put("video_id", video.id)
                            put("title", video.title)
                            put("channel", video.channelName)
                            if (video.duration > 0) put("duration_seconds", video.duration)
                            if (video.viewCount > 0) put("views", video.viewCount)
                            if (video.uploadDate.isNotBlank()) put("published", video.uploadDate)
                            if (video.isLive) put("is_live", true)
                            if (video.snippet.isNotBlank()) put("matched_text", video.snippet)
                        },
                    )
                }
            }

        private fun failure(message: String) = ToolResult(buildJsonObject { put("error", message) })

        private fun JsonObject.string(key: String): String? =
            this[key]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        private fun JsonObject.int(key: String): Int? {
            val primitive = this[key] as? JsonPrimitive ?: return null
            return primitive.intOrNull ?: primitive.doubleOrNull?.toInt()
        }

        private fun declaration(
            name: String,
            description: String,
            vararg parameters: Pair<String, Pair<String, String>>,
            required: List<String> = emptyList(),
        ): JsonObject =
            buildJsonObject {
                put("name", name)
                put("description", description)
                if (parameters.isNotEmpty()) {
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            parameters.forEach { (key, spec) ->
                                putJsonObject(key) {
                                    put("type", spec.first)
                                    put("description", spec.second)
                                }
                            }
                        }
                        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }

        private companion object {
            /** Screens the assistant may open, by the name it uses, mapped to the nav route. */
            val SCREEN_ROUTES =
                linkedMapOf(
                    "home" to "home",
                    "shorts" to "shorts",
                    "music" to "music",
                    "subscriptions" to "subscriptions",
                    "library" to "library",
                    "search" to "search",
                    "explore" to "categories",
                    "history" to "history",
                    "downloads" to "downloads",
                    "local_music" to "localMedia",
                    "saved_shorts" to "savedShorts",
                    "notes" to "notes",
                    "notifications" to "notifications",
                    "equalizer" to "equalizer",
                )
            const val MAX_TRANSCRIPT_CHARS = 60_000
            const val DESCRIPTION_CHARS = 1_000
            const val DEFAULT_SEARCH_LIMIT = 6
            const val MAX_SEARCH_LIMIT = 10
            const val DEFAULT_HISTORY_LIMIT = 10
            const val MAX_HISTORY_LIMIT = 20
            const val DEFAULT_QUERY_LIMIT = 20
            const val MAX_QUERY_LIMIT = 50
            const val KNOWN_LIMIT = 200
            const val DAY_MS = 24L * 60L * 60L * 1000L
        }
    }

internal fun timestamp(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
