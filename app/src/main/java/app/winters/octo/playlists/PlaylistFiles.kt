package app.winters.octo.playlists

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.UserDao
import app.winters.octo.device.DEVICE
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.playback.PlaylistStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

// The largest playlist file read. A playlist of 10,000 songs is about 1 MB.
private const val MAX_FILE_BYTES = 8 * 1024 * 1024

// How an import went: the playlist made, how many of the file's songs were
// found, and the lines that were not.
data class ImportReport(val name: String, val matched: Int, val total: Int, val missed: List<String>)

// Reads playlist files the listener picks into new playlists, and writes
// playlists out as files other players can open.
@Singleton
class PlaylistFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CatalogDao,
    private val sources: SourceDao,
    private val userDao: UserDao,
    private val device: DeviceLibrary,
    private val store: PlaylistStore,
) {
    // Makes a playlist from a picked M3U or M3U8 file, named after the file,
    // with the songs the library has. Null when the file cannot be read.
    suspend fun import(uri: Uri): ImportReport? = withContext(Dispatchers.IO) {
        val text = runCatching { readText(uri) }.getOrNull() ?: return@withContext null
        val entries = parseM3u(text)
        val name = displayName(uri).substringBeforeLast('.').trim().ifEmpty { "Imported playlist" }
        if (entries.isEmpty()) return@withContext ImportReport(name, 0, 0, emptyList())
        val match = matchM3u(entries, catalog.tracks().first(), phonePaths())
        if (match.trackIds.isNotEmpty()) store.create(name, match.trackIds)
        ImportReport(name, match.trackIds.size, entries.size, match.missed.map { it.shown() })
    }

    // Writes a playlist to a picked file: each song's length, artist and
    // title, and where it is on the phone, or "Artist - Title" for a song
    // with no phone path known. False when it could not be written.
    suspend fun export(playlistId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val tracks = userDao.playlistTracks(playlistId).first().map { it.track }
        val paths = phonePaths()
        val text = writeM3u(
            tracks.map { track ->
                M3uLine(
                    seconds = (track.durationMs / 1000).toInt(),
                    artist = track.artist,
                    title = track.title,
                    location = paths[track.id] ?: fallbackLocation(track.artist, track.title),
                )
            },
        )
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
    }

    // Library song id to where its phone copy sits, for songs on the phone
    // whose path the last scan saw.
    private suspend fun phonePaths(): Map<String, String> {
        val files = device.files.value ?: return emptyMap()
        val paths = HashMap<String, String>()
        sources.tracks(DEVICE).forEach { copy ->
            val mergedId = copy.mergedId.takeIf(String::isNotEmpty) ?: return@forEach
            val path = copy.nativeId.toLongOrNull()?.let(files::get) ?: return@forEach
            paths.putIfAbsent(mergedId, path)
        }
        return paths
    }

    // The file as text: UTF-8 when it reads as UTF-8, which M3U8 always is,
    // otherwise Windows' Western encoding, which older M3U files often use.
    private fun readText(uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readUpTo(MAX_FILE_BYTES) } ?: return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
}

// A file name for a playlist, without characters file systems refuse. It is
// written as UTF-8, which today's players read in an .m3u file too.
fun playlistFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), " ").trim().ifEmpty { "Playlist" } + ".m3u"

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
