package app.zaid.flow.innertube.models.body

import app.zaid.flow.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class CreateCommentBody(
    val context: Context,
    val commentText: String,
    val createCommentParams: String? = null,
    /**
     * The Tenor id of a GIF to attach. YouTube's GIF comments only accept Tenor ids; the old made-up
     * `commentGif` object was rejected with HTTP 400 INVALID_ARGUMENT.
     */
    val tenorId: String? = null,
)
