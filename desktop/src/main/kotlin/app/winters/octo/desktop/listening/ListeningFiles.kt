package app.winters.octo.desktop.listening

import app.winters.octo.listening.PendingPlay
import app.winters.octo.listening.decodePending
import app.winters.octo.listening.encodePending
import app.winters.octo.listening.plusPlay
import app.winters.octo.subsonic.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

// Each server account keeps its listening in its own folder, named by a
// hash of the name and address so neither shows in the file name.
fun listeningFolder(root: File, username: String, address: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest("$username@$address".toByteArray())
    val name = digest.take(8).joinToString("") { "%02x".format(it) }
    return File(File(root, "listening"), name)
}

// A song as the play log keeps it: enough to show it and open its album
// and artist, even when it has left the library.
@Serializable
data class LoggedSong(
    val id: String,
    val title: String,
    val artist: String? = null,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
) {
    fun toSong() = Song(id, title, artist = artist, artistId = artistId, album = album, albumId = albumId, coverArt = coverArt, duration = duration)
}

fun Song.logged() = LoggedSong(id, title, displayArtist ?: artist, artistId, album, albumId, coverArt, duration)

// One play in the log: when it began, how long it was heard, and the song.
@Serializable
data class LoggedPlay(val at: Long, val heardMs: Long, val song: LoggedSong)

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

// Every play counted on this computer, one JSON line each, oldest first.
// The file only grows by a line at a time; past `max` plays it is cut back
// to the newest, so it stays a few megabytes.
class PlayLog(private val file: File, private val max: Int = MAX_LOGGED_PLAYS) {
    // Lines added since the file was last cut back.
    private var written = 0

    @Synchronized
    fun add(play: LoggedPlay) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(LoggedPlay.serializer(), play) + "\n")
        written++
        if (written > max / 4) trim()
    }

    // Newest first. Lines that cannot be read are skipped.
    @Synchronized
    fun read(): List<LoggedPlay> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            line.takeIf(String::isNotBlank)?.let { runCatching { json.decodeFromString(LoggedPlay.serializer(), it) }.getOrNull() }
        }.asReversed()
    }

    private fun trim() {
        written = 0
        val lines = file.readLines().filter(String::isNotBlank)
        if (lines.size <= max) return
        val kept = File(file.parentFile, file.name + ".new")
        kept.writeText(lines.takeLast(max).joinToString("\n", postfix = "\n"))
        if (!kept.renameTo(file)) {
            file.delete()
            kept.renameTo(file)
        }
    }
}

// Plays still to reach the server, oldest first, kept on disk so none is
// lost when the server cannot be reached or Octo quits.
class PendingPlays(private val file: File) {
    @Synchronized
    fun all(): List<PendingPlay> =
        if (file.exists()) decodePending(file.readLines().filter(String::isNotBlank).toSet()) else emptyList()

    @Synchronized
    fun add(play: PendingPlay) = write(all().plusPlay(play))

    @Synchronized
    fun remove(play: PendingPlay) = write(all() - play)

    private fun write(plays: List<PendingPlay>) {
        file.parentFile?.mkdirs()
        if (plays.isEmpty()) {
            file.delete()
            return
        }
        val next = File(file.parentFile, file.name + ".new")
        next.writeText(encodePending(plays).sorted().joinToString("\n", postfix = "\n"))
        if (!next.renameTo(file)) {
            file.delete()
            next.renameTo(file)
        }
    }
}

// The most plays the log keeps.
const val MAX_LOGGED_PLAYS = 20_000
