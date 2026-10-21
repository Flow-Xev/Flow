package app.zaid.flow.data.model

data class LiveChatSegment(
    val text: String,
    val emojiImageUrl: String? = null,
)

data class LiveChatMessage(
    val id: String,
    val author: String,
    val authorPhotoUrl: String?,
    val message: String,
    val segments: List<LiveChatSegment> = emptyList(),
    val timestamp: String?,
    val type: LiveChatMessageType = LiveChatMessageType.TEXT,
    val isOwner: Boolean = false,
    val isModerator: Boolean = false,
    val isVerified: Boolean = false,
    val isMember: Boolean = false,
    val memberBadgeUrl: String? = null,
    // Super-chat only. ARGB colors are packed ints suitable for Compose Color(argb).
    val superChatAmount: String? = null,
    val superChatArgb: Int? = null,
    val superChatHeaderArgb: Int? = null,
    // Super Sticker only: the artwork. The amount rides in [superChatAmount].
    val stickerUrl: String? = null,
    // Replay only: where in the video this message was sent, so it can follow the playhead.
    val offsetMs: Long? = null,
)

enum class LiveChatMessageType { TEXT, SUPER_CHAT, MEMBERSHIP, STICKER }
