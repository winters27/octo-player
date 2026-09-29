package app.winters.octo.desktop.listening

import app.winters.octo.listening.PendingPlay
import app.winters.octo.listening.decodePending
import app.winters.octo.listening.encodePending
import app.winters.octo.listening.plusPlay
import app.winters.octo.livelists.accountKey
import app.winters.octo.subsonic.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

// Each server account keeps its listening in its own folder, named by a
// hash of the name and address so neither shows in the file name.
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
        // The play is in; a log that cannot be cut back now is cut next time.
        if (written > max / 4) runCatching { trim() }
    }

    // Newest first. Lines that cannot be read are skipped.
    @Synchronized
    fun read(): List<LoggedPlay> =
        linesOf(file).mapNotNull { line ->
            line.takeIf(String::isNotBlank)?.let { runCatching { json.decodeFromString(LoggedPlay.serializer(), it) }.getOrNull() }
        }.asReversed()

    private fun trim() {
        written = 0
        val lines = linesOf(file).filter(String::isNotBlank)
        if (lines.size <= max) return
        writeWhole(file, lines.takeLast(max).joinToString("\n", postfix = "\n"))
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

// How long a file that another program has open is waited for.
private const val BUSY_FILE_WAIT_MS = 2_000L

// Runs `step` on a file, trying again for a while when Windows refuses it:
// it will not move over a file something else has open (a virus scanner, a
// backup, any reader), nor remove one read through java.io, and may refuse
// to open one that is being removed. Throws if it never goes through.
private fun <T> patiently(step: () -> T): T {
    val giveUpAt = System.nanoTime() + BUSY_FILE_WAIT_MS * 1_000_000
    while (true) {
        try {
            return step()
        } catch (e: IOException) {
            if (e is NoSuchFileException || System.nanoTime() > giveUpAt) throw e
            Thread.sleep(10)
        }
    }
}

// A file's lines, or none when it is not there. Read so that the file can
// still be removed while it is open, which on Windows a file read through
// java.io cannot be until it is closed.
private fun linesOf(file: File): List<String> =
    patiently {
        try {
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8).lines()
        } catch (e: NoSuchFileException) {
            emptyList()
        }
    }

// Puts `text` in `file` whole, or removes the file when `text` is null.
// The text goes in a file beside it that is then moved over it in one step,
// so the file holds the old text or the new, even if Octo stops part way.
// If something keeps the file open all the while, the text is written into
// it where it is instead (empty for none), which a reader through java.io
// allows. Throws if that fails too, rather than pass as written.
private fun writeWhole(file: File, text: String?) {
    val target = file.toPath()
    val next = File(file.parentFile, file.name + ".new").toPath()
    try {
        if (text == null) {
            patiently { Files.deleteIfExists(target) }
        } else {
            file.parentFile?.mkdirs()
            Files.writeString(next, text)
            patiently { Files.move(next, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        }
    } catch (e: IOException) {
        Files.writeString(target, text.orEmpty())
        Files.deleteIfExists(next)
    }
}

// The most plays the log keeps.
const val MAX_LOGGED_PLAYS = 20_000
