package app.zaid.flow.data.model

import com.google.gson.annotations.SerializedName

data class SponsorBlockSegment(
    @SerializedName("category") val category: String,
    // [start, end]
    @SerializedName("segment") val segment: List<Float>,
    @SerializedName("UUID") val uuid: String,
    // "skip", "mute", etc. (usually skip)
    @SerializedName("actionType") val actionType: String,
) {
    val startTime: Float get() = if (segment.isNotEmpty()) segment[0] else 0f
    val endTime: Float get() = if (segment.size > 1) segment[1] else 0f
}
