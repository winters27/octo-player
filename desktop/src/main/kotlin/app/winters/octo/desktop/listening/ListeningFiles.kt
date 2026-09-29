package app.winters.octo.desktop.listening

import app.winters.octo.desktop.system.appendWhole
import app.winters.octo.desktop.system.readWhole
import app.winters.octo.desktop.system.writeWhole
import app.winters.octo.listening.PendingPlay
import app.winters.octo.listening.decodePending
import app.winters.octo.listening.encodePending
import app.winters.octo.listening.plusPlay
import app.winters.octo.livelists.accountKey
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.key
import app.winters.octo.subsonic.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

// Each server account keeps its listening in its own folder, named by the
// server's id: a hash of the name and address from when it was first kept,
// so neither shows in the file name, and the folder stays when the address
// is edited.
fun listeningFolder(root: File, server: SavedServer): File = File(File(root, "listening"), server.key)

// The folder of an account by its name and address, as it was first kept.
fun listeningFolder(root: File, username: String, address: String): File =
    File(File(root, "listening"), accountKey(username, address))

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
// The file only grows by a line at a time; once it holds a quarter more
// than `max` plays it is cut back to the newest `max`, so it stays a few
// megabytes. Keep one for each file, so its count of lines holds.
class PlayLog(private val file: File, private val max: Int = MAX_LOGGED_PLAYS) {
    // Lines in the file, counted when the first play is added.
    private var lines = -1

    @Synchronized
    fun add(play: LoggedPlay) {
        if (lines < 0) lines = linesOf(file).count(String::isNotBlank)
        appendWhole(file, json.encodeToString(LoggedPlay.serializer(), play) + "\n")
        lines++
        // The play is in; a log that cannot be cut back now is cut next time.
        if (lines > max + max / 4) runCatching { trim() }
    }

    // Newest first. Lines that cannot be read are skipped.
    @Synchronized
    fun read(): List<LoggedPlay> =
        linesOf(file).mapNotNull { line ->
            line.takeIf(String::isNotBlank)?.let { runCatching { json.decodeFromString(LoggedPlay.serializer(), it) }.getOrNull() }
        }.asReversed()

    private fun trim() {
        val kept = linesOf(file).filter(String::isNotBlank).takeLast(max)
        writeWhole(file, kept.joinToString("\n", postfix = "\n"))
        lines = kept.size
    }
}

// Plays still to reach the server, oldest first, kept on disk so none is
// lost when the server cannot be reached or Octo quits.
class PendingPlays(private val file: File) {
    @Synchronized
    fun all(): List<PendingPlay> = decodePending(linesOf(file).filter(String::isNotBlank).toSet())

    // Both throw when the file could not be written, rather than return as
    // if the play were added or taken off.
    @Synchronized
    fun add(play: PendingPlay) = write(all().plusPlay(play))

    @Synchronized
    fun remove(play: PendingPlay) = write(all() - play)

    private fun write(plays: List<PendingPlay>) =
        writeWhole(file, plays.takeIf { it.isNotEmpty() }?.let { encodePending(it).sorted().joinToString("\n", postfix = "\n") })
}

// A file's lines, or none when it is not there.
private fun linesOf(file: File): List<String> = readWhole(file)?.lines().orEmpty()

// The most plays the log keeps.
const val MAX_LOGGED_PLAYS = 20_000
