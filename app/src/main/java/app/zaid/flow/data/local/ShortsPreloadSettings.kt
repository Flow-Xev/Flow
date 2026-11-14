package app.zaid.flow.data.local

/** What Shorts preloading does when Data Saver is on or the connection is metered. */
enum class ShortsPreloadConstrainedMode {
    /** Preload exactly as on an unrestricted connection. */
    SAME,

    /** One Short ahead and no description: the cheapest preload that still helps. */
    LIGHT,

    /** No metadata preloading at all. The next Short is still prepared so swiping stays smooth. */
    OFF,
    ;

    companion object {
        fun fromStored(value: String?): ShortsPreloadConstrainedMode = entries.firstOrNull { it.name == value } ?: SAME
    }
}

/** What the user chose for Shorts preloading. Every field has the app's out-of-the-box value. */
data class ShortsPreloadSettings(
    val enabled: Boolean = true,
    /** Shorts beyond the next one that are preloaded. 0 preloads the next Short only. */
    val ahead: Int = DEFAULT_AHEAD,
    /** Stream addresses for the Shorts beyond the next one. */
    val streams: Boolean = true,
    /** Likes, channel, avatar and comment count. Never the comments themselves. */
    val details: Boolean = true,
    val description: Boolean = true,
    val dataSaverMode: ShortsPreloadConstrainedMode = ShortsPreloadConstrainedMode.SAME,
    val meteredMode: ShortsPreloadConstrainedMode = ShortsPreloadConstrainedMode.SAME,
) {
    /** What to preload right now, given the connection. The stricter of Data Saver and metered wins. */
    fun planFor(
        dataSaver: Boolean,
        metered: Boolean,
    ): ShortsPreloadPlan {
        if (!enabled) return ShortsPreloadPlan.NONE
        val mode =
            listOfNotNull(
                dataSaverMode.takeIf { dataSaver },
                meteredMode.takeIf { metered },
            ).maxByOrNull { it.ordinal } ?: ShortsPreloadConstrainedMode.SAME
        val base =
            ShortsPreloadPlan(
                ahead = ahead.coerceIn(MIN_AHEAD, MAX_AHEAD),
                streams = streams,
                details = details,
                description = description,
            )
        return when (mode) {
            ShortsPreloadConstrainedMode.SAME -> base
            ShortsPreloadConstrainedMode.LIGHT -> base.copy(ahead = minOf(base.ahead, LIGHT_AHEAD), description = false)
            ShortsPreloadConstrainedMode.OFF -> ShortsPreloadPlan.NONE
        }
    }

    companion object {
        const val MIN_AHEAD = 0
        const val MAX_AHEAD = 5
        const val DEFAULT_AHEAD = 2
        const val LIGHT_AHEAD = 1
    }
}

/** The decision [ShortsPreloadSettings.planFor] hands the pager. */
data class ShortsPreloadPlan(
    val ahead: Int,
    val streams: Boolean,
    val details: Boolean,
    val description: Boolean,
) {
    companion object {
        val NONE = ShortsPreloadPlan(ahead = 0, streams = false, details = false, description = false)
    }
}
