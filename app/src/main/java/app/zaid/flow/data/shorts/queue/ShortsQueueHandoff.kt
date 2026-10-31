package app.zaid.flow.data.shorts.queue

import app.zaid.flow.data.model.ShortVideo
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.model.toShortVideo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShortsQueueHandoff
    @Inject
    constructor() {
        private val entries = LinkedHashMap<String, List<ShortVideo>>()
        private val continuations = HashMap<String, SnapshotContinuation>()
        private var nextToken = 0L

        /**
         * Hands [items] to the Shorts player. A source that is still paging (a sound with more Shorts than
         * are loaded) passes [more] so the player keeps pulling from it instead of running dry at the end
         * of what happened to be loaded.
         */
        @Synchronized
        fun offer(
            items: List<ShortVideo>,
            more: SnapshotContinuation? = null,
        ): String {
            val token = "h${nextToken++}"
            entries[token] = items
            if (more != null) continuations[token] = more
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
                continuations.remove(oldest)
            }
            return token
        }

        @Synchronized
        fun peek(token: String): List<ShortVideo>? = entries[token]

        @Synchronized
        fun continuationFor(token: String): SnapshotContinuation? = continuations[token]

        @Synchronized
        fun clear() {
            entries.clear()
            continuations.clear()
        }

        fun sourceForShelf(
            shelf: List<Video>,
            tapped: Video,
        ): ShortsQueueSource {
            val reels = shelf.filter { it.isShort }
            if (reels.none { it.id == tapped.id }) return ShortsQueueSource.SeededFeed(tapped.id)
            return ShortsQueueSource.Snapshot(
                token = offer(reels.map { it.toShortVideo() }),
                startVideoId = tapped.id,
            )
        }

        private companion object {
            const val MAX_ENTRIES = 4
        }
    }
