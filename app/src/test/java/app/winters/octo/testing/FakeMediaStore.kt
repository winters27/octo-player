package app.winters.octo.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.AbstractCursor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.robolectric.Robolectric
import java.util.concurrent.atomic.AtomicInteger

// One audio file as the phone's media library lists it.
data class PhoneFile(
    val id: Long,
    val displayName: String,
    val relativePath: String?,
    val volume: String = "external_primary",
    val modifiedSeconds: Long = 1_700_000_000,
    val sizeBytes: Long = 30_000_000,
    val durationMs: Long = 200_000,
    val addedSeconds: Long = 1_700_000_000,
    val takenMs: Long? = null,
    val mimeType: String = "audio/flac",
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    // As the media library packs it: 2003 is disc 2, track 3.
    val track: Int? = null,
    val disc: String? = null,
    val year: Int? = null,
    val genre: String? = null,
)

// The phone's music files, `count` of them, each made when it is read.
class PhoneFiles(val count: Int, val file: (Int) -> PhoneFile) {
    companion object {
        // Files in albums of `perAlbum` in their own folders, then
        // `unknownAlbum` untagged files in one folder that the phone files
        // under no album, as a folder of downloads is.
        fun albums(files: Int, perAlbum: Int, unknownAlbum: Int = 0): PhoneFiles = PhoneFiles(files + unknownAlbum) { i ->
            if (i >= files) {
                val n = i - files
                PhoneFile(id = i + 1L, displayName = "track$n.mp3", relativePath = "Download/", mimeType = "audio/mpeg", title = "track$n")
            } else {
                val album = i / perAlbum
                val artist = album % 50
                PhoneFile(
                    id = i + 1L, displayName = "%02d Song %d.flac".format(1 + i % perAlbum, i), relativePath = "Music/Artist $artist/Album $album/",
                    title = "Song $i", artist = "Artist $artist", album = "Album $album", albumArtist = "Artist $artist",
                    track = 1 + i % perAlbum, year = 1970 + album % 50, genre = "Genre ${album % 12}",
                )
            }
        }
    }
}

// The phone's media library for Robolectric tests: answers MediaStore's
// audio query from `files`, a row at a time as the cursor moves (no list of
// rows is held), and opens no file, so tag reads find nothing and the
// library takes the media library's own tags.
class FakeMediaStore : ContentProvider() {
    @Volatile var files: PhoneFiles = PhoneFiles(0) { error("no files") }

    // How many times the files were listed.
    val queries = AtomicInteger()

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        queries.incrementAndGet()
        return FileCursor(files, (projection ?: COLUMNS).map { it }.toTypedArray())
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val COLUMNS = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.VOLUME_NAME, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATE_TAKEN,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.DISC_NUMBER, MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.IS_MUSIC,
        )

        // The fake this test put in place last.
        @Volatile var current: FakeMediaStore? = null

        // Puts the fake in place of the phone's media library for this test.
        fun install(files: PhoneFiles): FakeMediaStore =
            Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY).also {
                it.files = files
                current = it
            }
    }
}

private class FileCursor(private val files: PhoneFiles, private val columns: Array<String>) : AbstractCursor() {
    private var at = -1
    private var current: PhoneFile? = null

    private fun row(): PhoneFile {
        if (at != position) {
            current = files.file(position)
            at = position
        }
        return current!!
    }

    private fun value(column: Int): Any? {
        val f = row()
        return when (columns[column]) {
            MediaStore.Audio.Media._ID -> f.id
            MediaStore.Audio.Media.DISPLAY_NAME -> f.displayName
            MediaStore.Audio.Media.RELATIVE_PATH -> f.relativePath
            MediaStore.Audio.Media.VOLUME_NAME -> f.volume
            MediaStore.Audio.Media.DATE_MODIFIED -> f.modifiedSeconds
            MediaStore.Audio.Media.SIZE -> f.sizeBytes
            MediaStore.Audio.Media.DURATION -> f.durationMs
            MediaStore.Audio.Media.DATE_ADDED -> f.addedSeconds
            MediaStore.Audio.Media.DATE_TAKEN -> f.takenMs
            MediaStore.Audio.Media.MIME_TYPE -> f.mimeType
            MediaStore.Audio.Media.TITLE -> f.title
            MediaStore.Audio.Media.ARTIST -> f.artist
            MediaStore.Audio.Media.ALBUM -> f.album
            MediaStore.Audio.Media.TRACK -> f.track
            MediaStore.Audio.Media.YEAR -> f.year
            MediaStore.Audio.Media.ALBUM_ARTIST -> f.albumArtist
            MediaStore.Audio.Media.DISC_NUMBER -> f.disc
            MediaStore.Audio.Media.GENRE -> f.genre
            MediaStore.Audio.Media.IS_MUSIC -> 1
            else -> null
        }
    }

    override fun getCount(): Int = files.count

    override fun getColumnNames(): Array<String> = columns

    override fun getString(column: Int): String? = value(column)?.toString()

    override fun getShort(column: Int): Short = (value(column) as? Number)?.toShort() ?: 0

    override fun getInt(column: Int): Int = (value(column) as? Number)?.toInt() ?: 0

    override fun getLong(column: Int): Long = (value(column) as? Number)?.toLong() ?: 0

    override fun getFloat(column: Int): Float = (value(column) as? Number)?.toFloat() ?: 0f

    override fun getDouble(column: Int): Double = (value(column) as? Number)?.toDouble() ?: 0.0

    override fun isNull(column: Int): Boolean = value(column) == null

    override fun getType(column: Int): Int = when (value(column)) {
        null -> Cursor.FIELD_TYPE_NULL
        is Number -> Cursor.FIELD_TYPE_INTEGER
        else -> Cursor.FIELD_TYPE_STRING
    }
}
