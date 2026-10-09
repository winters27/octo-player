package app.winters.octo.desktop.system

import app.winters.octo.desktop.nav.Page
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.parseFamilyLink
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Paths

// Audio files opened with Octo ("Open with", a double click, a drop on the
// window) play as one-off songs, the way the phone plays a file opened from
// another app. The id carries everything needed to play the file, in the
// phone app's own form, so nothing about it is stored anywhere else:
//
//   file:<url-encoded address>:<url-encoded title>:<url-encoded artist>
//
// The player plays the address (a file:// URI) instead of asking the
// server: openedFileOf(song.id)?.path is the file.
const val OPENED_FILE_PREFIX = "file:"

// The kinds of audio Octo can play from a file.
val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "mp4", "aac", "alac", "ogg", "oga", "opus", "wav", "wave", "aif", "aiff")

// What an opened file's id holds.
data class OpenedFile(val uri: String, val title: String, val artist: String) {
    // The file itself, or null when the address is not a local file.
    val path: File? get() = runCatching { Paths.get(URI(uri)).toFile() }.getOrNull()
}

fun isOpenedFile(id: String) = id.startsWith(OPENED_FILE_PREFIX)

fun openedFileId(uri: String, title: String = "", artist: String = ""): String =
    OPENED_FILE_PREFIX + encode(uri.trim()) + ":" + encode(title.trim()) + ":" + encode(artist.trim())

fun openedFileOf(id: String): OpenedFile? {
    if (!isOpenedFile(id)) return null
    val parts = id.removePrefix(OPENED_FILE_PREFIX).split(':')
    val uri = parts.getOrNull(0)?.let(::decode)?.takeIf(String::isNotBlank) ?: return null
    return OpenedFile(uri, parts.getOrNull(1)?.let(::decode).orEmpty(), parts.getOrNull(2)?.let(::decode).orEmpty())
}

// The name a file goes by, without its type: "Song.mp3" is "Song".
fun titleFromFileName(file: File?): String = file?.name?.substringBeforeLast('.')?.trim().orEmpty().ifEmpty { "Audio file" }

fun isAudioFile(file: File): Boolean = file.extension.lowercase() in AUDIO_EXTENSIONS

// A file as a song to put in the queue. Its length is not known until it
// plays, so like the phone's opened files it never counts as a play.
fun openedFileSong(file: File): Song {
    val absolute = file.absoluteFile
    val title = titleFromFileName(absolute)
    return Song(
        id = openedFileId(absolute.toPath().toUri().toString(), title),
        title = title,
        suffix = absolute.extension.lowercase().ifEmpty { null },
        size = absolute.length().takeIf { it > 0 },
    )
}

// What Octo was asked to do when it started, or when it was started again.
sealed interface LaunchRequest {
    // Audio files to play, in the order given.
    data class OpenFiles(val files: List<File>) : LaunchRequest

    // An octo:// link.
    data class OpenLink(val link: String) : LaunchRequest
}

// Reads the command line: file paths, file:// addresses and octo:// links.
// Options (anything starting with "-", like macOS's "-psn_..."), files that
// are not there and files that are not audio are left out.
fun parseLaunchArgs(args: List<String>, isFile: (File) -> Boolean = File::isFile): List<LaunchRequest> {
    val files = ArrayList<File>()
    val links = ArrayList<LaunchRequest>()
    for (raw in args) {
        val arg = raw.trim()
        if (arg.isEmpty() || arg.startsWith("-")) continue
        when {
            arg.startsWith("octo:", ignoreCase = true) -> links += LaunchRequest.OpenLink(arg)
            // A family link in its https form, handed over like an octo:// one.
            parseFamilyLink(arg) != null -> links += LaunchRequest.OpenLink(arg)
            arg.startsWith("file:", ignoreCase = true) -> runCatching { Paths.get(URI(arg)).toFile() }.getOrNull()?.let { files += it }
            else -> files += File(arg).absoluteFile
        }
    }
    val playable = files.filter { isAudioFile(it) && isFile(it) }
    return (if (playable.isEmpty()) emptyList() else listOf(LaunchRequest.OpenFiles(playable))) + links
}

// A launch's command line made ready to hand to the running Octo, which
// started in another folder: file paths are made whole from `folder`, where
// this launch started. Options, links and addresses stay as typed.
fun absoluteLaunchArgs(args: List<String>, folder: File? = null): List<String> = args.map { raw ->
    val arg = raw.trim()
    when {
        arg.isEmpty() || arg.startsWith("-") || ADDRESS_START.containsMatchIn(arg) -> raw
        folder == null -> File(arg).absolutePath
        else -> File(arg).takeIf { it.isAbsolute }?.path ?: File(folder, arg).absolutePath
    }
}

// The start of an address like "octo:", "file:" or "https:". Two letters at
// least, so a Windows drive like "C:" still reads as a file.
private val ADDRESS_START = Regex("^[A-Za-z][A-Za-z0-9+.-]+:")

// The page an octo:// link names: octo://album/<id>, octo://artist/<id>,
// octo://playlist/<id>, or octo://search. Null for anything else.
fun pageForLink(link: String): Page? {
    val rest = link.substringAfter("://", "").trim('/')
    if (rest.isEmpty()) return null
    val kind = rest.substringBefore('/').substringBefore('?').lowercase()
    val id = rest.substringAfter('/', "").substringBefore('?').let(::decode).trim()
    return when (kind) {
        "album" -> id.takeIf(String::isNotEmpty)?.let { Page.Album(it) }
        "artist" -> id.takeIf(String::isNotEmpty)?.let { Page.Artist(it) }
        "playlist" -> id.takeIf(String::isNotEmpty)?.let { Page.Playlist(it) }
        "search" -> Page.Search
        "home" -> Page.Home
        else -> null
    }
}

private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

private fun decode(text: String): String = runCatching { URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault("")
