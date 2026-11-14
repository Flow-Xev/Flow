package app.zaid.flow.innertube.models.body

import app.zaid.flow.innertube.models.Context
import kotlinx.serialization.Serializable

/** A request that carries nothing but the client context: account lists, history pause/resume/clear. */
@Serializable
data class AccountContextBody(
    val context: Context,
)
