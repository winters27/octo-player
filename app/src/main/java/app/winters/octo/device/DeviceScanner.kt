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

// Lists every music file the phone's media library knows about. The file's
// own tags are read separately; the phone's reading is kept as a fallback.
class DeviceScanner @Inject constructor(@ApplicationContext private val context: Context) {
    private val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    suspend fun list(): List<DeviceFile> = withContext(Dispatchers.IO) {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.RELATIVE_PATH)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.DISC_NUMBER)
            }
        }.toTypedArray()

        val files = mutableListOf<DeviceFile>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.long(MediaStore.Audio.Media._ID) ?: continue
                // Older phones pack the disc into the track number: 2003 is disc 2, track 3.
                val packed = c.int(MediaStore.Audio.Media.TRACK)
                val disc = c.string(MediaStore.Audio.Media.DISC_NUMBER)?.let(::positionNumber)
                    ?: packed?.takeIf { it >= 1000 }?.div(1000)
                files += DeviceFile(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    fileName = c.string(MediaStore.Audio.Media.DISPLAY_NAME) ?: "",
                    folder = c.string(MediaStore.Audio.Media.RELATIVE_PATH),
                    modifiedAt = c.long(MediaStore.Audio.Media.DATE_MODIFIED) ?: 0,
                    sizeBytes = c.long(MediaStore.Audio.Media.SIZE) ?: 0,
                    durationMs = c.long(MediaStore.Audio.Media.DURATION) ?: 0,
                    addedAtSeconds = c.long(MediaStore.Audio.Media.DATE_ADDED) ?: 0,
                    mimeType = c.string(MediaStore.Audio.Media.MIME_TYPE),
                    fallback = FileTags(
                        title = c.string(MediaStore.Audio.Media.TITLE)?.takeIf(String::isNotBlank),
                        artist = c.string(MediaStore.Audio.Media.ARTIST)?.takeIf { it.isNotBlank() && it != "<unknown>" },
                        albumArtist = c.string(MediaStore.Audio.Media.ALBUM_ARTIST)?.takeIf(String::isNotBlank),
                        album = c.string(MediaStore.Audio.Media.ALBUM)?.takeIf(String::isNotBlank),
                        trackNo = packed?.rem(1000)?.takeIf { it > 0 },
                        discNo = disc,
                        year = c.int(MediaStore.Audio.Media.YEAR)?.takeIf { it > 0 },
                    ),
                )
            }
        }
        files
    }
}

// Column readers that treat a missing column or an empty cell as null.
private fun Cursor.index(name: String) = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }
private fun Cursor.string(name: String) = index(name)?.let(::getString)
private fun Cursor.long(name: String) = index(name)?.let(::getLong)
private fun Cursor.int(name: String) = index(name)?.let(::getInt)
