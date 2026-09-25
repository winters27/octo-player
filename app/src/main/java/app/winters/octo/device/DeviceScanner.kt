package app.winters.octo.device

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Reads every music file the phone's media library knows about.
class DeviceScanner @Inject constructor(@ApplicationContext private val context: Context) {
    private val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    suspend fun read(): List<DeviceRow> = withContext(Dispatchers.IO) {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.DISC_NUMBER)
            }
        }.toTypedArray()

        val rows = mutableListOf<DeviceRow>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.long(MediaStore.Audio.Media._ID) ?: continue
                rows += DeviceRow(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = c.string(MediaStore.Audio.Media.TITLE),
                    artist = c.string(MediaStore.Audio.Media.ARTIST),
                    albumArtist = c.string(MediaStore.Audio.Media.ALBUM_ARTIST),
                    album = c.string(MediaStore.Audio.Media.ALBUM),
                    albumId = c.long(MediaStore.Audio.Media.ALBUM_ID) ?: 0,
                    track = c.int(MediaStore.Audio.Media.TRACK),
                    // Stored as text, sometimes "1/2"; the part before the slash is the disc.
                    disc = c.string(MediaStore.Audio.Media.DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull(),
                    year = c.int(MediaStore.Audio.Media.YEAR),
                    durationMs = c.long(MediaStore.Audio.Media.DURATION) ?: 0,
                    addedAtSeconds = c.long(MediaStore.Audio.Media.DATE_ADDED) ?: 0,
                    mimeType = c.string(MediaStore.Audio.Media.MIME_TYPE),
                    sizeBytes = c.long(MediaStore.Audio.Media.SIZE),
                )
            }
        }
        rows
    }
}

// Column readers that treat a missing column or an empty cell as null.
private fun Cursor.index(name: String) = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }
private fun Cursor.string(name: String) = index(name)?.let(::getString)
private fun Cursor.long(name: String) = index(name)?.let(::getLong)
private fun Cursor.int(name: String) = index(name)?.let(::getInt)
