package app.winters.octo.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

// How long "no lyrics" is believed before the sources are asked again.
const val NONE_FOUND_FOR_MS = 24 * 60 * 60 * 1000L

// How long a "none" that may be wrong is believed: one from a server that
// looks lyrics up itself and answers "none" when its time runs out, or one
// found while nobody was looking at the lyrics.
const val SHORT_NONE_FOR_MS = 10 * 60 * 1000L

// Raised when the online lookup learns a new way to find synced lyrics, so
// songs that only got plain ones are asked again once.
const val ONLINE_LOOKUP_VERSION = 4

// The first answers saved once the server ranked a song's own lyrics among
// its sources: lyrics it sent without word timing before that are asked
// again once, as it may have word-timed ones now.
private const val SERVER_RANKS_OWN_VERSION = 4

// How long lyrics from the server without word timing are believed before it
// is asked again: it may have found word-timed ones since (it keeps looking
// after the phone stops waiting), or had its sources reordered.
const val SERVER_RECHECK_MS = 60 * 60 * 1000L

// The online lookup that could take another song's lyrics: it matched a
// search result by length alone. What it found is asked again once.
private const val LOOSE_SEARCH_VERSION = 2

// One song's saved answer: its lyrics, or none, when that was found, and
// whether the online library was asked.
@Serializable
data class CachedLyrics(
    val savedAt: Long,
    val lyrics: Lyrics? = null,
    val askedOnline: Boolean = false,
    // Which online lookup gave this answer; older ones are asked again when
    // they found no synced lyrics.
    val lookupVersion: Int = 0,
    // The listener's pick these lyrics came from (see LyricsChoices), or
    // null when the usual order found them.
    val pick: String? = null,
    // A "none" from a server whose lookup may have run out of time.
    val unsure: Boolean = false,
    // Saved while the app was in the background or the lyrics were not on
    // screen, so a "none" is asked again once they are.
    val away: Boolean = false,
) {
    // Whether this answer stands for a song whose pick is `current`: picked
    // lyrics stand while they are the ones picked; the usual answer stands
    // only while nothing is picked, and as long as `stillGood` says.
    fun standsFor(current: String?, now: Long, onlineAllowed: Boolean): Boolean =
        if (current != null) pick == current && lyrics != null else pick == null && stillGood(now, onlineAllowed)

    // Whether a "none" should be asked again now that the lyrics are back
    // on screen: it was found while they were not.
    fun recheckOnReturn(): Boolean = lyrics == null && away

    // Whether the answer still stands. "None" lasts a day, or a few minutes
    // when it may be wrong (see SHORT_NONE_FOR_MS). An answer found
    // without asking online (it was off, or only plain lyrics were found)
    // is asked again once online lookups are allowed, in case synced ones
    // are there. The server's lyrics without word timing last an hour (see
    // SERVER_RECHECK_MS).
    fun stillGood(now: Long, onlineAllowed: Boolean): Boolean {
        if (lyrics != null && lyrics.source == LyricsSource.Server && !lyrics.instrumental && lyrics.lines.none { it.words.isNotEmpty() }) {
            if (lookupVersion < SERVER_RANKS_OWN_VERSION || now - savedAt >= SERVER_RECHECK_MS) return false
        }
        if (onlineAllowed && !askedOnline && lyrics?.synced != true) return false
        if (onlineAllowed && lyrics != null && !lyrics.synced && lookupVersion < ONLINE_LOOKUP_VERSION) return false
        if (onlineAllowed && lyrics?.source == LyricsSource.Online && lookupVersion == LOOSE_SEARCH_VERSION) return false
        return lyrics != null || now - savedAt < if (unsure || away) SHORT_NONE_FOR_MS else NONE_FOUND_FOR_MS
    }
}

// Saved lyrics answers, one small JSON file per song in the app's cache
// folder, keyed by the song's library id or find id. The phone may clear
// the folder when it needs space; the lyrics are then just looked up again.
class LyricsCache(private val folder: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(songId: String): CachedLyrics? = runCatching {
        val file = fileFor(songId)
        if (!file.isFile) return null
        json.decodeFromString(CachedLyrics.serializer(), file.readText())
    }.getOrNull()

    fun write(songId: String, entry: CachedLyrics) {
        runCatching {
            folder.mkdirs()
            val file = fileFor(songId)
            // Written beside it first, so a reader never sees half a file.
            val next = File(folder, file.name + ".tmp")
            next.writeText(json.encodeToString(CachedLyrics.serializer(), entry))
            if (!next.renameTo(file)) {
                file.delete()
                next.renameTo(file)
            }
        }
    }

    // Forgets a song's answer, so its lyrics are looked up again.
    fun drop(songId: String) {
        runCatching { fileFor(songId).delete() }
    }

    private fun fileFor(songId: String): File = File(folder, "${hash(songId)}.json")

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
}
