package app.zaid.flow.player.quality

import app.zaid.flow.data.local.DataSaver

/**
 * The heights Auto may play at, as it applies right now: [floor] to [ceiling], both inclusive.
 *
 * [floor] is where Auto never plays below even on a very slow connection (480p unless the viewer
 * set another height, higher or lower). [ceiling] is where it never plays above ([Int.MAX_VALUE] when nothing limits it).
 * [floor] never exceeds [ceiling]. [userFloor] is the floor only when the viewer chose one, else 0,
 * so rules that were free to drop below 480p before the setting existed (a buffering step down)
 * stay free to.
 */
data class AutoQualityRange(
    val floor: Int,
    val ceiling: Int,
    val userFloor: Int,
) {
    fun clamp(height: Int): Int = height.coerceIn(floor, ceiling)

    operator fun contains(height: Int): Boolean = height in floor..ceiling

    /**
     * The members of [items] Auto may choose between.
     *
     * Those inside the range, or when the video has none: the best it has under the ceiling, and the
     * lowest it has when every one is above it. When the viewer set a floor and the video falls short
     * of it, only its single best quality under the floor stays, so Auto does not wander further
     * down than it has to. Without a floor, everything under the range stays, as Auto always did.
     */
    fun <T> restrict(
        items: List<T>,
        height: (T) -> Int,
    ): List<T> {
        items.filter { height(it) in this }.ifEmpty { null }?.let { return it }
        val underFloor = items.filter { height(it) < floor }
        if (underFloor.isNotEmpty()) {
            if (userFloor == 0) return underFloor
            val best = underFloor.maxOf(height)
            return underFloor.filter { height(it) == best }
        }
        return items.minOfOrNull(height)?.let { lowest -> items.filter { height(it) == lowest } }.orEmpty()
    }

    /**
     * The range a ladder Media3 adapts over is held to: the ceiling, and only a floor the viewer set.
     * The implicit 480p is Auto's own pick rule, not a limit on what the ladder may hold.
     */
    fun forLadder(): AutoQualityRange = copy(floor = userFloor)

    /** Part of the cache key of anything resolved from this range, so a changed setting is not served stale. */
    val cacheKey: String get() = "$floor-$ceiling"

    companion object {
        /** The range with no setting involved: 480p and up, no ceiling. */
        val DEFAULT = AutoQualityRange(AutoQualityBounds.DEFAULT_FLOOR, Int.MAX_VALUE, 0)
    }
}

/**
 * The viewer's Auto quality floor and ceiling (Settings, Quality). They shape only Auto; a quality
 * the viewer picks by hand is played as picked. Data Saver's 720p limit stays in force on top of the
 * ceiling, and a floor above the limit gives way to it.
 *
 * The values are mirrors of the saved settings, kept current by the app and the setters, for code
 * that cannot suspend.
 */
object AutoQualityBounds {
    /** Stored when no floor or ceiling is set. */
    const val NONE = 0

    /** The lowest height Auto ever picks on its own, unless a ceiling is below it. */
    const val DEFAULT_FLOOR = 480

    @Volatile
    var floor: Int = NONE

    @Volatile
    var ceiling: Int = NONE

    /** Shorts have their own floor and ceiling, so limiting Shorts never changes regular videos. */
    @Volatile
    var shortsFloor: Int = NONE

    @Volatile
    var shortsCeiling: Int = NONE

    /** Tallest height worth fetching for the player window as it is now; 0 until measured. */
    @Volatile
    var windowCap: Int = NONE

    /** The range for regular videos. */
    fun current(): AutoQualityRange = resolve(floor, ceiling, DataSaver.enabled, windowCap)

    /** The range for Shorts. */
    fun currentShorts(): AutoQualityRange = resolve(shortsFloor, shortsCeiling, DataSaver.enabled)

    fun resolve(
        userFloor: Int,
        userCeiling: Int,
        dataSaver: Boolean,
        windowCap: Int = NONE,
    ): AutoQualityRange {
        var top = if (userCeiling > NONE) userCeiling else Int.MAX_VALUE
        if (dataSaver) top = minOf(top, DataSaver.MAX_VIDEO_HEIGHT)
        if (windowCap > NONE) top = minOf(top, windowCap)
        val chosen = if (userFloor > NONE) minOf(userFloor, top) else NONE
        return AutoQualityRange(
            // A floor the viewer chose is used as chosen, below 480p too; without one Auto stops at 480p.
            floor = if (chosen > NONE) chosen else minOf(DEFAULT_FLOOR, top),
            ceiling = top,
            userFloor = chosen,
        )
    }
}
