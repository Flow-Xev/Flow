package app.zaid.flow.innertube.models.body

import app.zaid.flow.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class ResolveUrlBody(
    val context: Context,
    val url: String,
)
