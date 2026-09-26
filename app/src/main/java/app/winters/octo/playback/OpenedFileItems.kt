package app.winters.octo.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

// An audio file opened with Octo from another app that is not a song in the
// library. Like a radio station, its id carries everything needed to play
// it, so nothing about it is stored anywhere else:
//
//   file:<url-encoded address>:<url-encoded title>:<url-encoded artist>
//
// Encoding turns every ':' into %3A, so the parts split cleanly. It has no
// length on record, so it never counts as a play.
const val OPENED_FILE_PREFIX = "file:"

fun isOpenedFile(id: String) = id.startsWith(OPENED_FILE_PREFIX)

// What an opened file's id holds.
data class OpenedFile(val uri: String, val title: String, val artist: String)

fun openedFileId(uri: String, title: String = "", artist: String = ""): String =
    OPENED_FILE_PREFIX + encode(uri.trim()) + ":" + encode(title.trim()) + ":" + encode(artist.trim())

// The file's address, title and artist, or null when the id is not one.
fun openedFileOf(id: String): OpenedFile? {
    if (!isOpenedFile(id)) return null
    val parts = id.removePrefix(OPENED_FILE_PREFIX).split(':')
    val uri = parts.getOrNull(0)?.let(::decode)?.takeIf(String::isNotBlank) ?: return null
    return OpenedFile(uri, parts.getOrNull(1)?.let(::decode).orEmpty(), parts.getOrNull(2)?.let(::decode).orEmpty())
}

// The file, ready to play, or null once Octo can no longer read it: the app
// that shared it took its permission back, or the file is gone. That also
// drops it quietly from a queue brought back later.
internal suspend fun openedFileItem(context: Context, id: String): MediaItem? {
    val file = openedFileOf(id) ?: return null
    val uri = file.uri.toUri()
    if (!canRead(context, uri)) return null
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(file.title.ifEmpty { uri.lastPathSegment ?: "Audio file" })
                .setArtist(file.artist.ifEmpty { null })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()
}

// Whether the file can be opened now, trying it rather than guessing.
internal suspend fun canRead(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    when (uri.scheme) {
        "file" -> uri.path?.let { File(it).canRead() } == true
        "content" -> try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
        } catch (e: Exception) {
            // Refused or gone: either way it cannot play.
            false
        }
        else -> false
    }
}

private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

private fun decode(text: String): String = runCatching { URLDecoder.decode(text, Charsets.UTF_8.name()) }.getOrDefault("")
