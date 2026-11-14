package app.zaid.flow.player

/** Finished downloads or an eligible in-progress local preview, so playback uses local bytes when available. */
fun interface LocalCopySource {
    /** Path of a finished download or, on an explicit tap / when offline, a partial preview of an unfinished download, or null. */
    suspend fun localCopyPath(videoId: String): String?
}
