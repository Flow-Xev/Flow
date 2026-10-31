// ==================================================================================================
// ==================================================================================================

package app.zaid.flow.data.lyrics

import app.zaid.flow.data.lyrics.paxsenix.Paxsenix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PaxsenixLyricsProvider : LyricsProvider {
    override val name = "Paxsenix"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<List<LyricsEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val context = app.zaid.flow.FlowApplication.appContext
                Paxsenix.init(context)
                val lrc = Paxsenix.getLyrics(title, artist, duration, album).getOrThrow()
                LyricsUtils.parseLyrics(lrc)
            }
        }
}
