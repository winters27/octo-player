package app.winters.octo.lyrics

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

// A lyrics file bigger than this is not a lyrics file.
private const val MAX_BYTES = 1 shl 20

// Finds an .lrc file beside a song on the phone: same folder, same name.
// The phone's media library is asked first, by folder and file name. It
// only lists files Octo may read, and with scoped storage that may leave
// out an .lrc file (it is not music), so the file's own path is tried next,
// which older phones and some storage setups allow.
class LyricsFiles @Inject constructor(@ApplicationContext private val context: Context) {
    fun beside(songUri: Uri): String? {
        val song = describe(songUri) ?: return null
        val names = lyricsFileNames(song.name)
        if (names.isEmpty()) return null
        val bytes = fromMediaLibrary(song, names) ?: fromPath(song, names) ?: return null
        return decodeText(bytes)
    }

    private class SongFile(val volume: String?, val folder: String?, val name: String, val path: String?)

    @Suppress("DEPRECATION")
    private fun describe(uri: Uri): SongFile? = runCatching {
        val columns = arrayOf(
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
        )
        context.contentResolver.query(uri, columns, null, null, null)?.use { row ->
            if (!row.moveToFirst()) return@use null
            val name = row.getString(2) ?: return@use null
            SongFile(row.getString(0), row.getString(1), name, row.getString(3))
        }
    }.getOrNull()

    private fun fromMediaLibrary(song: SongFile, names: List<String>): ByteArray? = runCatching {
        val folder = song.folder ?: return null
        val files = MediaStore.Files.getContentUri(song.volume ?: MediaStore.VOLUME_EXTERNAL)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} IN (${names.joinToString { "?" }})"
        val id = context.contentResolver.query(
            files,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf(folder) + names,
            null,
        )?.use { row -> if (row.moveToFirst()) row.getLong(0) else null } ?: return null
        context.contentResolver.openInputStream(ContentUris.withAppendedId(files, id))?.use(::readCapped)
    }.getOrNull()

    private fun fromPath(song: SongFile, names: List<String>): ByteArray? = runCatching {
        val folder = song.path?.let { File(it).parentFile } ?: return null
        names.firstNotNullOfOrNull { name ->
            File(folder, name).takeIf { it.isFile && it.canRead() && it.length() <= MAX_BYTES }?.readBytes()
        }
    }.getOrNull()
}

// The names a song's lyrics file can have: the song's name with .lrc, in
// either case.
fun lyricsFileNames(songFileName: String): List<String> {
    val base = songFileName.substringBeforeLast('.', "")
    if (base.isEmpty()) return emptyList()
    return listOf("$base.lrc", "$base.LRC")
}

// The whole stream, or null when it runs past the size limit.
private fun readCapped(input: InputStream): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        if (out.size() > MAX_BYTES) return null
    }
    return out.toByteArray()
}

// A lyrics file's text. Most are UTF-8; older ones are often Windows
// Latin-1, so a file that is not valid UTF-8 is read that way.
fun decodeText(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    String(bytes, Charset.forName("windows-1252"))
}
