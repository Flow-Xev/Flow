package app.zaid.flow.innertube.models.body

import app.zaid.flow.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class CreateCommentBody(
    val context: Context,
    val commentText: String,
    val createCommentParams: String? = null,
)
