package app.zaid.flow.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps finished AI chapters on disk, so a video is only ever sent to the model once, not once per app
 * launch. The store is bounded like every other cache in the app: at most [MAX_ENTRIES] videos, none older
 * than [MAX_AGE_DAYS] days, oldest dropped first. [FORMAT_VERSION] is part of the file name, so when the way
 * chapters are made changes, chapters made the old way are simply never read again and age out.
 */
@Singleton
internal class AiChapterStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val directory: File get() = File(context.cacheDir, DIRECTORY_NAME)

        private var writesSincePrune = 0

        /** The chapters kept for [videoId], or null when there are none or they have expired. */
        fun read(videoId: String): List<AiChapter>? =
            runCatching {
                val file = fileFor(videoId)
                if (!file.isFile) return@runCatching null
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                val savedAt = root["t"]?.jsonPrimitive?.longOrNull ?: return@runCatching null
                if (System.currentTimeMillis() - savedAt > TimeUnit.DAYS.toMillis(MAX_AGE_DAYS)) {
                    file.delete()
                    return@runCatching null
                }
                val chapters =
                    root["c"]?.jsonArray?.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        val start = obj["s"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val title = obj["n"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        AiChapter(title, start)
                    }
                chapters?.takeIf { it.size >= MIN_STORED }
            }.onFailure { Log.w(TAG, "Could not read stored chapters", it) }.getOrNull()

        fun write(
            videoId: String,
            chapters: List<AiChapter>,
        ) {
            if (chapters.size < MIN_STORED) return
            runCatching {
                val dir = directory.apply { mkdirs() }
                val body =
                    buildJsonObject {
                        put("t", System.currentTimeMillis())
                        put(
                            "c",
                            buildJsonArray {
                                chapters.forEach { chapter ->
                                    add(
                                        buildJsonObject {
                                            put("s", chapter.startSeconds)
                                            put("n", chapter.title)
                                        },
                                    )
                                }
                            },
                        )
                    }
                // Written beside the target and renamed over it, so a crash never leaves half a file to misread.
                val target = File(dir, fileName(videoId))
                val temp = File(dir, "${fileName(videoId)}.tmp")
                temp.writeText(body.toString())
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
                if (++writesSincePrune >= PRUNE_EVERY_WRITES) prune()
            }.onFailure { Log.w(TAG, "Could not store chapters", it) }
        }

        /** Drops expired entries and, beyond [MAX_ENTRIES], the oldest ones. */
        fun prune() {
            writesSincePrune = 0
            runCatching {
                val files = directory.listFiles()?.filter { it.isFile } ?: return@runCatching
                val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_AGE_DAYS)
                val (expired, alive) = files.partition { it.lastModified() < cutoff || it.name.endsWith(".tmp") }
                expired.forEach { it.delete() }
                alive.sortedByDescending { it.lastModified() }.drop(MAX_ENTRIES).forEach { it.delete() }
            }.onFailure { Log.w(TAG, "Could not prune stored chapters", it) }
        }

        private fun fileFor(videoId: String) = File(directory, fileName(videoId))

        private fun fileName(videoId: String) = "${videoId.replace(UNSAFE, "_")}.v$FORMAT_VERSION.json"

        private companion object {
            const val TAG = "AiChapterStore"
            const val DIRECTORY_NAME = "ai_chapters"
            const val FORMAT_VERSION = 2
            const val MAX_ENTRIES = 400
            const val MAX_AGE_DAYS = 30L
            const val PRUNE_EVERY_WRITES = 20
            const val MIN_STORED = 3
            val UNSAFE = Regex("[^A-Za-z0-9_-]")
        }
    }
