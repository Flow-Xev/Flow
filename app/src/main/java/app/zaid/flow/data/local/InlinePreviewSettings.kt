package app.zaid.flow.data.local

/** When a feed card may play a short preview of the video under it. */
enum class InlinePreviewMode {
    /** Cards never play a preview. */
    OFF,

    /** Previews only on an unmetered Wi-Fi connection, so a preview never spends mobile data. */
    WIFI_ONLY,

    /** Previews on any connection, including mobile data. Data Saver and Battery Saver do not stop them. */
    ALWAYS,
    ;

    companion object {
        val Default = WIFI_ONLY

        fun fromStored(value: String?): InlinePreviewMode = entries.firstOrNull { it.name == value } ?: Default
    }
}

/** What the device is doing right now that decides whether a preview should start. */
data class InlinePreviewConditions(
    val online: Boolean,
    val onWifi: Boolean,
    val metered: Boolean,
    val dataSaver: Boolean,
    val powerSave: Boolean,
)

/**
 * Whether a preview may start under [mode] given [conditions].
 *
 * Only being offline or the user's own setting blocks a preview. Data Saver and Battery Saver are
 * deliberately ignored: the Video previews setting is the user's choice, and the system saver modes
 * no longer override it. [InlinePreviewConditions.dataSaver] and [InlinePreviewConditions.powerSave]
 * are still reported so the skip log shows the full device state.
 */
fun inlinePreviewAllowed(
    mode: InlinePreviewMode,
    conditions: InlinePreviewConditions,
): Boolean =
    when {
        mode == InlinePreviewMode.OFF -> false
        !conditions.online -> false
        mode == InlinePreviewMode.WIFI_ONLY -> conditions.onWifi && !conditions.metered
        else -> true
    }
