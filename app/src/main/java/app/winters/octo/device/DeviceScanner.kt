package app.winters.octo.device

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Lists every music file the phone's media library knows about. The file's
// own tags are read separately; the phone's reading is kept as a fallback.
class DeviceScanner @Inject constructor(@ApplicationContext private val context: Context) {
    private val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    // The time now in seconds, which tells a real time from a broken clock
    // (fileAddedAt). Tests set their own.
    @VisibleForTesting
    internal var nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }

    // Every file at once, for checks.
    @VisibleForTesting
    suspend fun list(): List<DeviceFile> {
        val files = ArrayList<DeviceFile>()
        walk(500) { files += it }
        return files
    }

    // Reads the files in the order the media library lists them, handing
    // them to `each` `page` at a time; only one page is held at once.
    suspend fun walk(page: Int, each: suspend (List<DeviceFile>) -> Unit): Unit = withContext(Dispatchers.IO) {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.RELATIVE_PATH)
            add(MediaStore.Audio.Media.VOLUME_NAME)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_TAKEN)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.DISC_NUMBER)
                add(MediaStore.Audio.Media.GENRE)
            }
        }.toTypedArray()

        val now = nowSeconds()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { c ->
            val files = ArrayList<DeviceFile>(page)
            while (c.moveToNext()) {
                files += c.file(now) ?: continue
                if (files.size == page) {
                    each(files.toList())
                    files.clear()
                }
            }
            if (files.isNotEmpty()) each(files.toList())
        }
    }

    // The file at the cursor's row; null for a row with no id.
    private fun Cursor.file(now: Long): DeviceFile? {
        val id = long(MediaStore.Audio.Media._ID) ?: return null
        // Older phones pack the disc into the track number: 2003 is disc 2, track 3.
        val packed = int(MediaStore.Audio.Media.TRACK)
        val disc = string(MediaStore.Audio.Media.DISC_NUMBER)?.let(::positionNumber)
            ?: packed?.takeIf { it >= 1000 }?.div(1000)
        val modified = long(MediaStore.Audio.Media.DATE_MODIFIED) ?: 0
        return DeviceFile(
            id = id,
            uri = ContentUris.withAppendedId(collection, id).toString(),
            fileName = string(MediaStore.Audio.Media.DISPLAY_NAME) ?: "",
            folder = string(MediaStore.Audio.Media.RELATIVE_PATH),
            modifiedAt = modified,
            sizeBytes = long(MediaStore.Audio.Media.SIZE) ?: 0,
            durationMs = long(MediaStore.Audio.Media.DURATION) ?: 0,
            addedAtSeconds = fileAddedAt(
                modifiedSeconds = modified,
                addedSeconds = long(MediaStore.Audio.Media.DATE_ADDED) ?: 0,
                takenMs = long(MediaStore.Audio.Media.DATE_TAKEN),
                nowSeconds = now,
            ),
            mimeType = string(MediaStore.Audio.Media.MIME_TYPE),
            fallback = FileTags(
                title = string(MediaStore.Audio.Media.TITLE)?.takeIf(String::isNotBlank),
                artist = string(MediaStore.Audio.Media.ARTIST)?.takeIf { it.isNotBlank() && it != "<unknown>" },
                albumArtist = string(MediaStore.Audio.Media.ALBUM_ARTIST)?.takeIf(String::isNotBlank),
                album = string(MediaStore.Audio.Media.ALBUM)?.takeIf(String::isNotBlank),
                trackNo = packed?.rem(1000)?.takeIf { it > 0 },
                discNo = disc,
                year = int(MediaStore.Audio.Media.YEAR)?.takeIf { it > 0 },
                genres = parseGenres(listOfNotNull(string(MediaStore.Audio.Media.GENRE))),
            ),
            volume = string(MediaStore.Audio.Media.VOLUME_NAME),
        )
    }
}

// Column readers that treat a missing column or an empty cell as null.
private fun Cursor.index(name: String) = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }
private fun Cursor.string(name: String) = index(name)?.let(::getString)
private fun Cursor.long(name: String) = index(name)?.let(::getLong)
private fun Cursor.int(name: String) = index(name)?.let(::getInt)
