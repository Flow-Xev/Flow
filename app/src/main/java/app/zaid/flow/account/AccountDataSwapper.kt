package app.zaid.flow.account

import android.content.Context
import android.util.Log
import app.zaid.flow.data.local.AppDatabase
import app.zaid.flow.data.local.SubscriptionRepository
import app.zaid.flow.data.local.ViewHistory
import app.zaid.flow.data.local.entity.WatchHistoryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps each signed-in account's watch history and subscriptions apart.
 *
 * The app has one history table and one subscription store. The account in use owns what is in
 * them; when another account takes over, the outgoing account's data is written to a file under
 * `account_data/<id>/` and the incoming account's file (if it has one) is loaded in its place.
 * Music history and plays of local files are not tied to an account and are never touched.
 *
 * Callers must not run two swaps at once; [AccountManager] serialises them.
 */
@Singleton
class AccountDataSwapper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        @Serializable
        private data class SavedSubscriptions(
            val entries: Map<String, String>,
        )

        private val json = Json { ignoreUnknownKeys = true }
        private val viewHistory by lazy { ViewHistory.getInstance(context) }
        private val subscriptions by lazy { SubscriptionRepository.getInstance(context) }

        private fun dirFor(accountId: String) = File(context.filesDir, "account_data/$accountId")

        private fun historyFile(accountId: String) = File(dirFor(accountId), "history.jsonl")

        private fun subscriptionsFile(accountId: String) = File(dirFor(accountId), "subscriptions.json")

        /**
         * Sets the live data aside under [outgoingId] (or throws it away when [keepOutgoing] is
         * false, because that account is gone) and loads [incomingId]'s saved data.
         */
        suspend fun swap(
            outgoingId: String?,
            keepOutgoing: Boolean,
            incomingId: String,
        ) {
            if (outgoingId == incomingId) return
            if (outgoingId != null && keepOutgoing) saveLive(outgoingId)
            // Channels the outgoing account followed must not linger in the cached subscription feed.
            val outgoingChannels = subscriptions.getAllSubscriptionIds()
            viewHistory.clearAccountHistory()
            subscriptions.replaceForAccountSwap(emptyMap())
            loadSaved(incomingId)
            val incomingChannels = subscriptions.getAllSubscriptionIds()
            val dropped = (outgoingChannels - incomingChannels).toList()
            if (dropped.isNotEmpty()) {
                runCatching {
                    dropped.chunked(500).forEach {
                        AppDatabase.getDatabase(context).cacheDao().deleteSubscriptionFeedForChannels(it)
                    }
                }
            }
        }

        /** Forgets everything saved for an account that was removed. */
        fun discard(accountId: String) {
            runCatching { dirFor(accountId).deleteRecursively() }
        }

        fun discardAll() {
            runCatching { File(context.filesDir, "account_data").deleteRecursively() }
        }

        private suspend fun saveLive(accountId: String) {
            val dir = dirFor(accountId).apply { mkdirs() }
            // Written beside the real file and renamed, so a crash mid-write cannot corrupt the last good copy.
            val historyTmp = File(dir, "history.jsonl.tmp")
            historyTmp.bufferedWriter().use { out ->
                viewHistory.readAccountHistory().forEach { row ->
                    out.write(json.encodeToString(SavedHistoryRow.from(row)))
                    out.newLine()
                }
            }
            historyTmp.renameTo(historyFile(accountId)).also { ok ->
                if (!ok) Log.w(TAG, "Could not save history for $accountId")
            }
            val subsTmp = File(dir, "subscriptions.json.tmp")
            subsTmp.writeText(json.encodeToString(SavedSubscriptions(subscriptions.snapshotForAccountSwap())))
            subsTmp.renameTo(subscriptionsFile(accountId))
        }

        private suspend fun loadSaved(accountId: String) {
            val history = historyFile(accountId)
            if (history.exists()) {
                val batch = ArrayList<WatchHistoryEntity>(500)
                history.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val row = runCatching { json.decodeFromString<SavedHistoryRow>(line) }.getOrNull() ?: return@forEach
                        batch += row.toEntity()
                    }
                }
                viewHistory.restoreAccountHistory(batch)
            }
            val subs = subscriptionsFile(accountId)
            if (subs.exists()) {
                runCatching { json.decodeFromString<SavedSubscriptions>(subs.readText()) }
                    .getOrNull()
                    ?.let { subscriptions.replaceForAccountSwap(it.entries) }
            }
        }

        @Serializable
        private data class SavedHistoryRow(
            val videoId: String,
            val position: Long,
            val duration: Long,
            val timestamp: Long,
            val title: String,
            val thumbnailUrl: String,
            val channelName: String,
            val channelId: String,
            val isShort: Boolean,
        ) {
            fun toEntity() =
                WatchHistoryEntity(
                    videoId = videoId,
                    position = position,
                    duration = duration,
                    timestamp = timestamp,
                    title = title,
                    thumbnailUrl = thumbnailUrl,
                    channelName = channelName,
                    channelId = channelId,
                    isMusic = false,
                    isShort = isShort,
                    isLocal = false,
                )

            companion object {
                fun from(row: WatchHistoryEntity) =
                    SavedHistoryRow(
                        videoId = row.videoId,
                        position = row.position,
                        duration = row.duration,
                        timestamp = row.timestamp,
                        title = row.title,
                        thumbnailUrl = row.thumbnailUrl,
                        channelName = row.channelName,
                        channelId = row.channelId,
                        isShort = row.isShort,
                    )
            }
        }

        private companion object {
            const val TAG = "AccountDataSwapper"
        }
    }
