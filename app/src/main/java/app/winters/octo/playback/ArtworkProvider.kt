package app.winters.octo.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import app.winters.octo.BuildConfig
import app.winters.octo.design.OctoColors
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

// Serves cover pictures to other apps that are allowed to see them, like a
// car's screen, which cannot read the app's own artwork references. Each
// cover is made once as a small JPEG and kept in the cache. Nothing is
// readable without a grant for its address (see `grantArtwork`).
//
//   /512/<ref>               a cover, its reference written out
//   /512/x/<flags>/<value>   flags: o the "Not in your library" mark,
//                            r the mark placed for a round picture,
//                            h a handle in place of the reference
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Not ready")
        val asked = coverOf(ctx, uri.pathSegments) ?: throw FileNotFoundException("No artwork")
        val file = cached(ctx, asked) ?: throw FileNotFoundException("No artwork")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun cached(ctx: Context, asked: AskedCover): File? {
        val dir = File(ctx.cacheDir, "car-art-$ART_SIZE").apply { mkdirs() }
        val file = File(dir, asked.fileName)
        if (file.exists()) return file
        val noCover = asked.ref == NO_COVER
        val loaded = if (noCover) null else loadCover(ctx, asked.ref, ART_SIZE)
        val bitmap = when {
            loaded != null && asked.marked -> marked(shrink(loaded), asked.round)
            loaded != null -> shrink(loaded)
            // The mark for something with no cover sits on the phone's plain
            // tile. A cover that could not be loaded is not kept, so the next
            // ask tries it again.
            noCover && asked.marked -> marked(plainTile(), asked.round)
            else -> return null
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
        writeWhole(file, bytes)
        return file.takeIf { it.isFile }
    }

    private fun shrink(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= ART_SIZE) return bitmap
        val scale = ART_SIZE.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    private fun marked(bitmap: Bitmap, round: Boolean): Bitmap {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(copy.width * copy.height)
        copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        drawOutsideMark(pixels, copy.width, copy.height, round)
        copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        return copy
    }

    private fun plainTile(): Bitmap =
        createBitmap(ART_SIZE, ART_SIZE).apply { eraseColor(OctoColors.BackgroundTertiary.toArgb()) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    // A cover asked for: its reference, whether it carries the mark and
    // where, and the cache file it is kept in. A plain cover keeps the file
    // name it always had.
    private class AskedCover(val ref: String, val marked: Boolean, val round: Boolean) {
        val fileName: String
            get() = if (!marked) carSha1(ref) + ".jpg" else carSha1((if (round) "or|" else "o|") + ref) + ".jpg"
    }

    private fun coverOf(ctx: Context, segments: List<String>): AskedCover? {
        if (segments.size == 2) return AskedCover(segments[1], marked = false, round = false)
        if (segments.size != 4 || segments[1] != "x") return null
        val flags = segments[2]
        val value = segments[3]
        val ref = if (HANDLE_FLAG in flags) refs(ctx).refFor(value) ?: return null else value
        return AskedCover(ref, marked = MARK_FLAG in flags, round = ROUND_FLAG in flags)
    }

    companion object {
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".art"
        private const val ART_SIZE = 512
        private const val ROUND_FLAG = 'r'

        @Volatile private var kept: CarArtRefs? = null

        // Loads a cover's picture at a size; tests put in their own.
        @Volatile internal var loadCover: (Context, String, Int) -> Bitmap? = { ctx, ref, size -> artworkBitmap(ctx, ref, size) }

        // Where long references are kept for their handles: out of the cache,
        // which Android may clear, and out of backups.
        fun refs(context: Context): CarArtRefs =
            kept ?: synchronized(this) {
                kept ?: CarArtRefs(File(context.noBackupFilesDir, "car-art-refs")).also { kept = it }
            }

        // The address of a cover for other apps. The size is part of it, so a
        // car that keeps pictures by address asks again when the size changes.
        fun uriFor(ref: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(ART_SIZE.toString()).appendPath(ref).build()

        // The address of a cover a car is sent: a short reference written
        // out, a long one by its handle, and either with the "Not in your
        // library" mark when `outside`, placed for a round picture when
        // `round`.
        fun carUriFor(context: Context, ref: String, outside: Boolean = false, round: Boolean = false): Uri {
            val long = Uri.encode(ref).length > INLINE_REF_MAX
            if (!long && !outside) return uriFor(ref)
            val flags = buildString {
                if (outside) append(MARK_FLAG)
                if (outside && round) append(ROUND_FLAG)
                if (long) append(HANDLE_FLAG)
            }
            val value = if (long) refs(context).handleFor(ref) else ref
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(ART_SIZE.toString())
                .appendPath("x").appendPath(flags).appendPath(value).build()
        }

        // The address of the mark alone on the plain tile, for something
        // outside the library with no cover.
        fun carMarkOnlyUri(round: Boolean = false): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(ART_SIZE.toString())
                .appendPath("x").appendPath(if (round) "or" else "o").appendPath(NO_COVER).build()

        // Stands for "no cover" in a marked address.
        const val NO_COVER = "-"
    }
}
