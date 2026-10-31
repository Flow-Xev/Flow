package app.zaid.flow.innertube.models.body

import app.zaid.flow.innertube.models.Context
import kotlinx.serialization.Serializable

/** Body for `live_chat/send_message`; [params] is the token the chat's own send button carries. */
@Serializable
data class SendLiveChatMessageBody(
    val context: Context,
    val params: String,
    val richMessage: RichMessage,
    val clientMessageId: String,
) {
    @Serializable
    data class RichMessage(
        val textSegments: List<Segment>,
    )

    @Serializable
    data class Segment(
        val text: String,
    )
}
