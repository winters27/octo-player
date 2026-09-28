package app.winters.octo.playlists

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.discovery.sameArtist
import app.winters.octo.discovery.titleKeys
import app.winters.octo.discovery.versionOf
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.abs

// Playlist files (M3U and M3U8), read and written the same way on the phone
// and the desktop: parsing, writing, and finding the songs a file names in
// a library.

// The mark some editors put at the start of a text file to say it is UTF-8.
val BYTE_ORDER_MARK = Char(0xFEFF).toString()

// One song in a playlist file: where the file said it is, and what its
// #EXTINF line said about it, if it had one. `line` is the line number of
// the location, from 1, for telling the listener which lines were missed.
data class M3uEntry(
    val location: String,
    val seconds: Int? = null,
    val artist: String? = null,
    val title: String? = null,
    val line: Int = 0,
)

// Reads an M3U or M3U8 playlist: a location per line, each optionally led by
// an #EXTINF line with its length and "Artist - Title". Other # lines are
// skipped. Takes Windows and Unix line endings and a leading byte order mark.
fun parseM3u(text: String): List<M3uEntry> {
    val entries = ArrayList<M3uEntry>()
    var info: Triple<Int?, String?, String?>? = null
    text.removePrefix(BYTE_ORDER_MARK).lineSequence().forEachIndexed { index, raw ->
        val line = raw.trim().removePrefix(BYTE_ORDER_MARK)
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTINF:", ignoreCase = true) -> info = extInf(line.substring("#EXTINF:".length))
            line.startsWith("#") -> Unit
            else -> {
                val (seconds, artist, title) = info ?: Triple(null, null, null)
                entries += M3uEntry(line, seconds, artist, title, index + 1)
                info = null
            }
        }
    }
    return entries
}

// "215,Kavinsky - Nightcall", or with attributes before the comma, like
// `-1 tvg-name="x",Title`. A length of -1 or 0 means unknown.
private fun extInf(body: String): Triple<Int?, String?, String?> {
    val comma = firstCommaOutsideQuotes(body)
    val head = if (comma < 0) body else body.substring(0, comma)
    val display = if (comma < 0) "" else body.substring(comma + 1).trim()
    val seconds = head.trim().substringBefore(' ').toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
    if (display.isEmpty()) return Triple(seconds, null, null)
    val split = display.indexOf(" - ")
    return if (split > 0) {
        Triple(seconds, display.substring(0, split).trim(), display.substring(split + 3).trim())
    } else {
        Triple(seconds, null, display)
    }
}

private fun firstCommaOutsideQuotes(text: String): Int {
    var quoted = false
    text.forEachIndexed { i, c ->
        if (c == '"') quoted = !quoted
        if (c == ',' && !quoted) return i
    }
    return -1
}

// A playlist line to write: the song's length, who and what, and where.
data class M3uLine(val seconds: Int, val artist: String, val title: String, val location: String)

// Writes an M3U8 playlist: the header, then an #EXTINF line and a location
// for each song.
fun writeM3u(lines: List<M3uLine>): String = buildString {
    append("#EXTM3U\n")
    lines.forEach { song ->
        val who = song.artist.oneLine()
        val what = song.title.oneLine()
        append("#EXTINF:").append(if (song.seconds > 0) song.seconds else -1).append(',')
        append(if (who.isEmpty()) what else "$who - $what").append('\n')
        append(song.location.oneLine()).append('\n')
    }
}

private fun String.oneLine() = replace('\r', ' ').replace('\n', ' ').trim()

// What a playlist file points at when a phone song has no path known: the
// artist and title, the way some players match songs.
fun fallbackLocation(artist: String, title: String): String =
    if (artist.isBlank()) title.oneLine() else "${artist.oneLine()} - ${title.oneLine()}"

// The folders and file name a location names, whatever its slashes: a
// Windows path, a Unix path, a relative path, or a file:// or web address.
// "." and ".." and a drive letter are dropped, since only the end of the
// path is compared.
fun pathParts(location: String): List<String> {
    val path = when {
        location.contains("://") -> runCatching { URI(location.replace(" ", "%20")).rawPath }.getOrNull()
            ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
            ?: location.substringAfter("://")
        else -> location
    }
    return path.split('/', '\\')
        .filter { it.isNotEmpty() && it != "." && it != ".." && !(it.length == 2 && it[1] == ':') }
}

// A file's name without its folder or its ending, like "01 Nightcall".
fun baseName(location: String): String =
    pathParts(location).lastOrNull().orEmpty().substringBeforeLast('.').trim()

// The songs a playlist file named that the library has, in order, and the
// entries it could not find.
data class M3uMatch(val trackIds: List<String>, val missed: List<M3uEntry>)

// A library song as the matching sees it: its id, title, artist and length.
data class M3uSong(val id: String, val title: String, val artist: String, val durationMs: Long)

// Lengths further apart than this are different recordings.
private const val SAME_LENGTH_MS = 10_000L

// Leading track numbers on a file name, like "01 ", "01 - " or "1. ".
private val TrackNumber = Regex("""^\d{1,3}\s*[-.)_]?\s+""")

// Finds each playlist entry in the library, trying in turn:
// 1. the file: a song whose path ends the same way (file name first, then
//    as many folders as agree), from `paths`, song id to its path like
//    "Music/Kavinsky/Nightcall/01 Nightcall.mp3";
// 2. the #EXTINF artist and title (with the length, when both are known);
// 3. the file's own name as a title, or as "Artist - Title".
fun matchM3u(entries: List<M3uEntry>, library: List<M3uSong>, paths: Map<String, String>): M3uMatch {
    val byId = library.associateBy { it.id }
    val byFileName = HashMap<String, MutableList<Pair<String, List<String>>>>()
    paths.forEach { (id, path) ->
        if (id !in byId) return@forEach
        val parts = pathParts(path).map { it.lowercase() }
        val name = parts.lastOrNull() ?: return@forEach
        byFileName.getOrPut(name) { mutableListOf() } += id to parts
    }
    val byTitle = HashMap<String, MutableList<M3uSong>>()
    library.forEach { track -> titleKeys(track.title).forEach { byTitle.getOrPut(it) { mutableListOf() } += track } }

    val ids = ArrayList<String>()
    val missed = ArrayList<M3uEntry>()
    for (entry in entries) {
        val found = byPath(entry, byFileName)
            ?: entry.title?.let { byTitleAndArtist(it, entry.artist, (entry.seconds ?: 0) * 1000L, byTitle) }
            ?: byFileNameAsTitle(entry, byTitle)
        if (found != null) ids += found else missed += entry
    }
    return M3uMatch(ids, missed)
}

private fun byPath(entry: M3uEntry, byFileName: Map<String, List<Pair<String, List<String>>>>): String? {
    val parts = pathParts(entry.location).map { it.lowercase() }
    val candidates = byFileName[parts.lastOrNull() ?: return null] ?: return null
    // The copy sharing the most folders from the end wins; the first on a tie.
    return candidates.maxBy { (_, known) -> sharedEnding(parts, known) }.first
}

private fun sharedEnding(a: List<String>, b: List<String>): Int {
    var n = 0
    while (n < a.size && n < b.size && a[a.size - 1 - n] == b[b.size - 1 - n]) n++
    return n
}

// A library song with this title, by this artist when one is given, and of
// about this length when it is known. Without an artist, a title only
// counts when it points at one song, or one of the right length.
private fun byTitleAndArtist(title: String, artist: String?, lengthMs: Long, byTitle: Map<String, List<M3uSong>>): String? {
    val versions = versionOf(title)
    val candidates = titleKeys(title).flatMap { byTitle[it].orEmpty() }.distinctBy { it.id }
        .filter { versionOf(it.title) == versions }
        .filter { lengthMs <= 0 || it.durationMs <= 0 || abs(lengthMs - it.durationMs) <= SAME_LENGTH_MS }
    if (!artist.isNullOrBlank()) {
        return candidates.firstOrNull { sameArtist(artist, it.artist) && SongIdentity.sameTitle(title, it.title).isSame }?.id
            ?: candidates.firstOrNull { sameArtist(artist, it.artist) }?.id
    }
    return candidates.singleOrNull()?.id
}

private fun byFileNameAsTitle(entry: M3uEntry, byTitle: Map<String, List<M3uSong>>): String? {
    val name = baseName(entry.location).replace('_', ' ').replace(TrackNumber, "").trim()
    if (name.isEmpty()) return null
    val lengthMs = (entry.seconds ?: 0) * 1000L
    val split = name.indexOf(" - ")
    if (split > 0) {
        val artist = name.substring(0, split).trim()
        val title = name.substring(split + 3).trim()
        byTitleAndArtist(title, artist, lengthMs, byTitle)?.let { return it }
    }
    return byTitleAndArtist(name, entry.artist, lengthMs, byTitle)
}

// How a missed entry is listed: its #EXTINF artist and title, or its location.
fun M3uEntry.shown(): String = when {
    title != null && !artist.isNullOrBlank() -> "$artist - $title"
    title != null -> title
    else -> location
}

// How an import went: the playlist made, how many of the file's songs were
// found, and the lines that were not.
data class ImportReport(val name: String, val matched: Int, val total: Int, val missed: List<String>)

// A file name for a playlist, without characters file systems refuse. It is
// written as UTF-8, which today's players read in an .m3u file too.
fun playlistFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), " ").trim().ifEmpty { "Playlist" } + ".m3u"

// A playlist named after the file it came from, like "Road trip" for
// "Road trip.m3u8".
fun playlistNameOf(fileName: String): String =
    fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim().ifEmpty { "Imported playlist" }

// The largest playlist file read. A playlist of 10,000 songs is about 1 MB.
const val PLAYLIST_FILE_LIMIT = 8 * 1024 * 1024

// A playlist file's text: UTF-8 when it reads as UTF-8, which M3U8 always
// is, otherwise Windows' Western encoding, which older M3U files often use.
fun playlistText(bytes: ByteArray): String =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }
