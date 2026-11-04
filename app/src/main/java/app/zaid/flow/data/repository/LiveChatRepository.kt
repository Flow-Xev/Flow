package app.zaid.flow.data.repository

import android.util.Log
import app.zaid.flow.data.model.LiveChatMessage
import app.zaid.flow.data.model.LiveChatMessageType
import app.zaid.flow.data.model.LiveChatSegment
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.models.response.GetLiveChatResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches YouTube live chat via the native InnerTube stack (no NewPipeExtractor). Ported from PipePipe's
 * YoutubeBulletCommentsExtractor
 */
@Singleton
class LiveChatRepository
    @Inject
    constructor() {
        data class LiveChatPage(
            val messages: List<LiveChatMessage>,
            val nextContinuation: String?,
            val timeoutMs: Long,
            /** Ids a moderator removed since the last page; the transcript drops them. */
            val removedIds: Set<String> = emptySet(),
        )

        /** Where a video's chat starts, and whether it is the recording of a finished stream. */
        data class Seed(
            val continuation: String,
            val isReplay: Boolean,
        )

        suspend fun seed(videoId: String): Seed? =
            withContext(Dispatchers.IO) {
                try {
                    val (token, replay) = YouTube.liveChatSeed(videoId).getOrNull() ?: return@withContext null
                    token?.let { Seed(it, replay) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to seed live chat for $videoId: ${e.message}")
                    null
                }
            }

        suspend fun initialContinuation(videoId: String): String? =
            withContext(Dispatchers.IO) {
                try {
                    YouTube.liveChatContinuation(videoId).getOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to seed live chat continuation for $videoId: ${e.message}")
                    null
                }
            }

        /**
         * One page of chat. A replay page ([replayOffsetMs] set) is the recording around that video
         * position, fetched from `get_live_chat_replay`; otherwise it is the live chat.
         */
        suspend fun poll(
            continuation: String,
            replayOffsetMs: Long? = null,
        ): LiveChatPage? =
            withContext(Dispatchers.IO) {
                val response =
                    try {
                        if (replayOffsetMs != null) {
                            YouTube.liveChatReplay(continuation, replayOffsetMs).getOrNull()
                        } else {
                            YouTube.liveChat(continuation).getOrNull()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Live chat poll failed: ${e.message}")
                        null
                    } ?: return@withContext null

                val cont = response.continuationContents?.liveChatContinuation ?: return@withContext null
                // The send token rides along with every poll, so the chat box costs no request of its own.
                cont.actionPanel?.sendParams()?.let(LiveChatSession::offerSendParams)
                val continuationObj = cont.continuations.firstOrNull()
                val next = continuationObj?.token()
                val timeout =
                    (continuationObj?.timeoutMs() ?: DEFAULT_TIMEOUT_MS)
                        .coerceIn(MIN_TIMEOUT_MS, if (replayOffsetMs != null) MAX_REPLAY_TIMEOUT_MS else MAX_TIMEOUT_MS)

                val messages = ArrayList<LiveChatMessage>()
                val removed = HashSet<String>()

                fun read(
                    action: GetLiveChatResponse.Action,
                    offsetMs: Long?,
                ) {
                    action.addChatItemAction
                        ?.item
                        ?.toMessage()
                        ?.let { messages += it.copy(offsetMs = offsetMs) }
                    action.removeChatItemAction?.targetItemId?.let(removed::add)
                    action.markChatItemAsDeletedAction?.targetItemId?.let(removed::add)
                }
                cont.actions.forEach { action ->
                    val replay = action.replayChatItemAction
                    if (replay != null) {
                        val at = replay.videoOffsetTimeMsec?.toLongOrNull()
                        replay.actions.forEach { read(it, at) }
                    } else {
                        read(action, null)
                    }
                }
                LiveChatPage(messages = messages, nextContinuation = next, timeoutMs = timeout, removedIds = removed)
            }

        private fun GetLiveChatResponse.Action.Item.toMessage(): LiveChatMessage? {
            liveChatTextMessageRenderer?.let { return it.toMessage(LiveChatMessageType.TEXT) }
            liveChatPaidMessageRenderer?.let { return it.toMessage(LiveChatMessageType.SUPER_CHAT) }
            liveChatMembershipItemRenderer?.let { return it.toMessage(LiveChatMessageType.MEMBERSHIP) }
            liveChatPaidStickerRenderer?.let { return it.toMessage(LiveChatMessageType.STICKER) }
            return null
        }

        private fun GetLiveChatResponse.ChatMessageRenderer.toMessage(type: LiveChatMessageType): LiveChatMessage? {
            val msgId = id ?: return null
            val bodyMessage =
                when (type) {
                    LiveChatMessageType.MEMBERSHIP -> {
                        message?.takeIf { it.plainText().isNotBlank() } ?: headerSubtext
                    }

                    else -> {
                        message
                    }
                }
            val text = bodyMessage?.plainText().orEmpty()
            val segments = bodyMessage?.toSegments().orEmpty()

            var isOwner = false
            var isModerator = false
            var isVerified = false
            var isMember = false
            var memberBadgeUrl: String? = null
            authorBadges.forEach { badge ->
                val renderer = badge.liveChatAuthorBadgeRenderer ?: return@forEach
                when (renderer.icon?.iconType?.uppercase()) {
                    "OWNER" -> isOwner = true
                    "MODERATOR" -> isModerator = true
                    "VERIFIED" -> isVerified = true
                }
                renderer.customThumbnail?.bestUrl()?.let {
                    isMember = true
                    memberBadgeUrl = it
                }
            }

            return LiveChatMessage(
                id = msgId,
                author = authorName?.value() ?: "",
                authorPhotoUrl = authorPhoto?.bestUrl(),
                message = text,
                segments = segments,
                timestamp = timestampText?.value(),
                type = type,
                isOwner = isOwner,
                isModerator = isModerator,
                isVerified = isVerified,
                isMember = isMember,
                memberBadgeUrl = memberBadgeUrl,
                superChatAmount = purchaseAmountText?.value(),
                superChatArgb = (bodyBackgroundColor ?: moneyChipBackgroundColor)?.toInt(),
                superChatHeaderArgb = headerBackgroundColor?.toInt(),
                stickerUrl = sticker?.bestUrl()?.let { if (it.startsWith("//")) "https:$it" else it },
            )
        }

        private fun GetLiveChatResponse.Message.toSegments(): List<LiveChatSegment> =
            runs.mapNotNull { run ->
                val emoji = run.emoji
                when {
                    run.text != null -> {
                        LiveChatSegment(text = run.text)
                    }

                    emoji != null -> {
                        val isCustom = emoji.isCustomEmoji == true
                        val unicode = emoji.emojiId?.takeIf { !isCustom && it.isNotBlank() }
                        if (unicode != null) {
                            LiveChatSegment(text = unicode)
                        } else {
                            LiveChatSegment(
                                text = emoji.shortcuts.firstOrNull() ?: emoji.emojiId ?: "",
                                emojiImageUrl = emoji.image?.bestUrl(),
                            )
                        }
                    }

                    else -> {
                        null
                    }
                }
            }

        companion object {
            private const val TAG = "LiveChatRepository"
            private const val DEFAULT_TIMEOUT_MS = 1000L
            private const val MIN_TIMEOUT_MS = 1000L
            private const val MAX_TIMEOUT_MS = 1500L
            private const val MAX_REPLAY_TIMEOUT_MS = 5000L
        }
    }

/**
 * The chat box of the stream being watched: whether this account may post, and posting.
 * A process-wide object so the chat sheet, side panel and fullscreen panel share one state without plumbing.
 */
object LiveChatSession {
    private val params = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** True once YouTube has offered this signed-in session a chat box for the current stream. */
    val canSend: kotlinx.coroutines.flow.Flow<Boolean> =
        params.let { flow -> kotlinx.coroutines.flow.flow { flow.collect { emit(it != null && YouTube.innerTube.isSignedIn) } } }

    internal fun offerSendParams(value: String) {
        params.value = value
    }

    fun reset() {
        params.value = null
    }

    /** Sends [text] to the current stream's chat as the signed-in account. */
    suspend fun send(text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = params.value ?: error("Chat isn't available for this account")
                val response =
                    YouTube.innerTube.accountSendLiveChatMessage(
                        params = token,
                        text = text,
                        clientMessageId =
                            java.util.UUID
                                .randomUUID()
                                .toString(),
                    )
                check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
                check(!response.bodyAsText().contains("\"errors\"")) { "YouTube rejected the message" }
            }
        }
}
