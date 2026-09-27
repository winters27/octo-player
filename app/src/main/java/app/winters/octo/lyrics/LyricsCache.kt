package app.winters.octo.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

// How long "no lyrics" is believed before the sources are asked again.
const val NONE_FOUND_FOR_MS = 24 * 60 * 60 * 1000L

// Raised when the online lookup learns a new way to find synced lyrics, so
// songs that only got plain ones are asked again once.
const val ONLINE_LOOKUP_VERSION = 3

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
) {
    // Whether the answer still stands. "None" lasts a day. An answer found
    // without asking online (it was off, or only plain lyrics were found)
    // is asked again once online lookups are allowed, in case synced ones
    // are there.
    fun stillGood(now: Long, onlineAllowed: Boolean): Boolean {
        if (onlineAllowed && !askedOnline && lyrics?.synced != true) return false
        if (onlineAllowed && lyrics != null && !lyrics.synced && lookupVersion < ONLINE_LOOKUP_VERSION) return false
        if (onlineAllowed && lyrics?.source == LyricsSource.Online && lookupVersion == LOOSE_SEARCH_VERSION) return false
        return lyrics != null || now - savedAt < NONE_FOUND_FOR_MS
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

    private fun fileFor(songId: String): File = File(folder, "${hash(songId)}.json")

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
}
