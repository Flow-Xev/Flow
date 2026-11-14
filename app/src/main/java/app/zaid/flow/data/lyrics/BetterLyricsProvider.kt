// ============================================================================
// THIS IMPLEMENTATION WAS INSPIRED BY METROLIST
// ============================================================================

package app.zaid.flow.data.lyrics

/**
 * LyricsProvider implementation for the BetterLyrics service.
 * Delegates to BetterLyrics service which returns structured LyricsEntry with word timestamps.
 */
class BetterLyricsProvider : LyricsProvider {
    override val name = "BetterLyrics"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<List<LyricsEntry>> = BetterLyrics.getLyrics(title, artist, duration, album)
}
