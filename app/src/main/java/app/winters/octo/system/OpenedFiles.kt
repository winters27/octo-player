package app.winters.octo.system

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import app.winters.octo.catalog.SourceDao
import app.winters.octo.device.DEVICE
import app.winters.octo.playback.canRead
import app.winters.octo.playback.openedFileId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

// How far apart two lengths may be and still be the same recording, since
// the phone's index and the file's own tags round differently.
private const val SAME_LENGTH_MS = 1_500L

// A song the phone's media index knows by the same file name.
data class NamedCandidate(val id: Long, val durationMs: Long?, val sizeBytes: Long?)

// The phone's own id for a song, read from an address that carries it: the
// media index's own addresses, and the Files app's "audio:" documents.
fun mediaStoreIdOf(uri: String): Long? {
    MEDIA_URI.matchEntire(uri)?.let { return it.groupValues[1].toLongOrNull() }
    DOCUMENT_URI.matchEntire(uri)?.let { return it.groupValues[1].toLongOrNull() }
    return null
}

private val MEDIA_URI = Regex("""content://media/[^/]+/audio/media/(\d+)""")
private val DOCUMENT_URI = Regex("""content://com\.android\.providers\.media\.documents/document/audio(?::|%3A)(\d+)""")

// Which of the songs with the same file name is this one: the same length,
// or with no length to go on, the same size. Null when none fits.
fun pickSameFile(candidates: List<NamedCandidate>, durationMs: Long?, sizeBytes: Long?): Long? {
    val sameSize = { c: NamedCandidate -> sizeBytes != null && c.sizeBytes == sizeBytes }
    val fits = candidates.filter { c ->
        if (durationMs != null && c.durationMs != null) abs(c.durationMs - durationMs) <= SAME_LENGTH_MS else sameSize(c)
    }
    return (fits.firstOrNull(sameSize) ?: fits.firstOrNull())?.id
}

// The name a file goes by, without its type: "Song.mp3" is "Song".
fun titleFromName(name: String?): String? = name?.substringBeforeLast('.')?.trim()?.ifEmpty { null }

// Keeps the right to read a file handed over by another app, when that app
// offers it for good. Otherwise the file plays while the handover lasts.
fun keepAccess(context: Context, intent: Intent, uri: Uri) {
    if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: SecurityException) {
        Log.w("Octo", "could not keep access to an opened file")
    }
}

// Turns an audio file opened from another app into something to play: the
// library song it is, when it is one, or else the file itself.
class OpenedFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: SourceDao,
) {
    // The id to play, or null when the file cannot be read at all.
    suspend fun trackIdFor(uri: Uri): String? = withContext(Dispatchers.IO) {
        mediaStoreIdOf(uri.toString())?.let { id -> libraryId(id)?.let { return@withContext it } }
        val about = describe(uri)
        sameNamedSong(about)?.let { id -> libraryId(id)?.let { return@withContext it } }
        if (!canRead(context, uri)) return@withContext null
        openedFileId(uri.toString(), about.title ?: titleFromName(about.name).orEmpty(), about.artist.orEmpty())
    }

    private suspend fun libraryId(mediaId: Long): String? =
        sources.libraryLinks(DEVICE, listOf(mediaId.toString())).firstOrNull()?.trackId

    // What the file says about itself: its name and size from the app that
    // shared it, and its title, artist and length from its tags.
    private data class About(val name: String?, val sizeBytes: Long?, val title: String?, val artist: String?, val durationMs: Long?)

    private fun describe(uri: Uri): About {
        var name: String? = null
        var size: Long? = null
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.let { file ->
                name = file.name
                size = file.length().takeIf { it > 0 }
            }
        } else {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0)
                        size = if (cursor.isNull(1)) null else cursor.getLong(1)
                    }
                }
            }
        }
        val tags = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                Triple(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                )
            }
        }.getOrNull()
        return About(name, size, tags?.first?.trim()?.ifEmpty { null }, tags?.second?.trim()?.ifEmpty { null }, tags?.third)
    }

    // The phone's own id for a song filed under the same name and length.
    private fun sameNamedSong(about: About): Long? {
        val name = about.name ?: return null
        val candidates = ArrayList<NamedCandidate>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE),
                "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                arrayOf(name),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    candidates += NamedCandidate(
                        id = cursor.getLong(0),
                        durationMs = if (cursor.isNull(1)) null else cursor.getLong(1),
                        sizeBytes = if (cursor.isNull(2)) null else cursor.getLong(2),
                    )
                }
            }
        } catch (e: SecurityException) {
            // Without access to the phone's music there is nothing to match.
            return null
        }
        return pickSameFile(candidates, about.durationMs, about.sizeBytes)
    }
}
