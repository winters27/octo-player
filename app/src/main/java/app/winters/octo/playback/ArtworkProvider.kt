package app.winters.octo.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.winters.octo.BuildConfig
import app.winters.octo.design.OctoIcons
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.roundToInt

// Serves cover pictures to other apps that are allowed to see them, like a
// car's screen, which cannot read the app's own artwork references. Each
// cover is made once as a small JPEG and kept in the cache; a drawn playlist
// cover is drawn for each ask (the playlist covers keep their own drawings).
// Nothing is readable without a grant for its address (see `grantArtwork`).
//
//   /512/<ref>               a cover, its reference written out
//   /512/x/<flags>/<value>   flags: m the "Not in your library" mark,
//                            r an artist's picture: the mark placed for a
//                              round picture, the artist glyph for no cover,
//                            h a handle in place of the reference,
//                            g a glyph tile, the value naming its glyph
//                              and the size it is drawn at
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Not ready")
        val segments = uri.pathSegments
        if (segments.size == 4 && segments[1] == "x" && segments[2] == GLYPH_FLAG.toString()) {
            val glyph = CarGlyph.ofAddress(segments[3]) ?: throw FileNotFoundException("No such glyph")
            return ParcelFileDescriptor.open(glyphFile(ctx, glyph), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val asked = coverOf(ctx, segments) ?: throw FileNotFoundException("No artwork")
        return ParcelFileDescriptor.open(coverFile(ctx, asked), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // The cover's file. A drawn playlist cover is drawn again for each ask,
    // over its last drawing, so a cover drawn without one of its pictures is
    // never what the next ask gets. A cover that cannot be had is answered
    // with its kind's glyph tile and nothing is kept for it, so the next ask
    // tries it again.
    private fun coverFile(ctx: Context, asked: AskedCover): File {
        val drawn = playlistArtOf(asked.ref) != null
        val dir = File(ctx.cacheDir, if (drawn) "car-drawn-$ART_SIZE" else "car-art-$ART_SIZE").apply { mkdirs() }
        val file = File(dir, asked.fileName)
        if (!drawn && isWhole(file)) return file
        val noCover = asked.ref == NO_COVER
        val loaded = if (noCover) null else loadCover(ctx, asked.ref, ART_SIZE)
        val bitmap = when {
            loaded != null && asked.marked -> marked(shrink(loaded), asked.artist)
            loaded != null -> shrink(loaded)
            // The mark for something with no cover sits on its kind's tile,
            // its glyph drawn smaller to stay clear of the mark.
            noCover && asked.marked -> marked(glyphTile(ctx, asked.glyph, MARKED_GLYPH_SHARE), asked.artist)
            else -> return glyphFile(ctx, asked.glyph)
        }
        if (!isWhole(file)) file.delete()
        writeWhole(file, jpeg(bitmap))
        if (!file.isFile) throw FileNotFoundException("No artwork")
        return file
    }

    private fun shrink(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= ART_SIZE) return bitmap
        val scale = ART_SIZE.toFloat() / longest
        return bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
    }

    private fun marked(bitmap: Bitmap, round: Boolean): Bitmap {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(copy.width * copy.height)
        copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        drawOutsideMark(pixels, copy.width, copy.height, round)
        copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        return copy
    }

    // A glyph tile's file, made once.
    private fun glyphFile(ctx: Context, glyph: CarGlyph): File {
        val file = File(File(ctx.cacheDir, "car-glyph-$ART_SIZE").apply { mkdirs() }, "${glyph.address}.jpg")
        if (!isWhole(file)) {
            file.delete()
            writeWhole(file, jpeg(glyphTile(ctx, glyph)))
        }
        if (!file.isFile) throw FileNotFoundException("No artwork")
        return file
    }

    // A kept picture that can be served: an empty one, as a sudden stop can
    // leave, is made again.
    private fun isWhole(file: File): Boolean = file.length() > 0

    // A tile lighter than a car's list behind it, with a glyph in its middle,
    // `share` of the tile across.
    private fun glyphTile(ctx: Context, glyph: CarGlyph, share: Float = glyph.share): Bitmap {
        val tile = createBitmap(ART_SIZE, ART_SIZE).apply { eraseColor(TILE_COLOR) }
        val side = (ART_SIZE * share).toInt()
        val inset = (ART_SIZE - side) / 2
        drawGlyph(ctx, glyph.drawable, Canvas(tile), Rect(inset, inset, inset + side, inset + side))
        return tile
    }

    private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        out.toByteArray()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    // A cover asked for: its reference, whether it carries the mark, whether
    // it is an artist's, and the cache file it is kept in. A plain cover
    // keeps the file name it always had.
    private class AskedCover(val ref: String, val marked: Boolean, val artist: Boolean) {
        val fileName: String
            get() = if (!marked) carSha1(ref) + ".jpg" else carSha1((if (artist) "mr|" else "m|") + ref) + ".jpg"

        // The glyph standing in when there is no picture.
        val glyph: CarGlyph
            get() = when {
                artist -> CarGlyph.Artist
                playlistArtOf(ref) != null -> CarGlyph.Playlist
                else -> CarGlyph.Album
            }
    }

    private fun coverOf(ctx: Context, segments: List<String>): AskedCover? {
        if (segments.size == 2) return AskedCover(segments[1], marked = false, artist = false)
        if (segments.size != 4 || segments[1] != "x") return null
        val flags = segments[2]
        val value = segments[3]
        val ref = if (HANDLE_FLAG in flags) refs(ctx).refFor(value) ?: return null else value
        return AskedCover(ref, marked = MARK_FLAG in flags, artist = ARTIST_FLAG in flags)
    }

    companion object {
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".art"
        private const val ART_SIZE = 512
        private const val ARTIST_FLAG = 'r'
        private const val GLYPH_FLAG = 'g'

        // A glyph tile: lighter than a car's list (#242529), its glyph white
        // at 60%. Under the mark, which an artist's picture places in from
        // the corner for a round picture, the glyph is 30% of the tile, so
        // the two never touch.
        private const val TILE_COLOR = 0xFF34363B.toInt()
        private const val GLYPH_COLOR = 0x99FFFFFF.toInt()
        private const val MARKED_GLYPH_SHARE = 0.3f

        @Volatile private var kept: CarArtRefs? = null

        // Loads a cover's picture at a size; tests put in their own.
        @Volatile internal var loadCover: (Context, String, Int) -> Bitmap? = { ctx, ref, size -> artworkBitmap(ctx, ref, size) }

        // Draws one of the app's drawables in the glyph color within bounds;
        // tests put in their own.
        @Volatile internal var drawGlyph: (Context, Int, Canvas, Rect) -> Unit = { ctx, id, canvas, bounds ->
            ContextCompat.getDrawable(ctx, id)?.mutate()?.run {
                setTint(GLYPH_COLOR)
                setBounds(bounds)
                draw(canvas)
            }
        }

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
                if (outside && round) append(ARTIST_FLAG)
                if (long) append(HANDLE_FLAG)
            }
            val value = if (long) refs(context).handleFor(ref) else ref
            return marked(flags, value)
        }

        // The address of the mark alone on its kind's tile, for something
        // outside the library with no cover; `round` for an artist.
        fun carMarkOnlyUri(round: Boolean = false): Uri =
            marked(if (round) "$MARK_FLAG$ARTIST_FLAG" else MARK_FLAG.toString(), NO_COVER)

        // The address of a glyph tile.
        fun carGlyphUri(glyph: CarGlyph): Uri = marked(GLYPH_FLAG.toString(), glyph.address)

        private fun marked(flags: String, value: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(ART_SIZE.toString())
                .appendPath("x").appendPath(flags).appendPath(value).build()

        // Stands for "no cover" in a marked address, with the size its glyph
        // is drawn at, so a car asks again when that changes.
        val NO_COVER = "-" + (MARKED_GLYPH_SHARE * 100).roundToInt()
    }
}

// The glyphs a car's tiles carry: "See all", and what stands in for an
// album's, an artist's, a list's or a station's missing picture; each `share`
// of its tile across. The dots of "See all" are drawn larger, as their ink
// is a thin line across the glyph.
enum class CarGlyph(val key: String, @DrawableRes val drawable: Int, val share: Float = 0.4f) {
    More("more", OctoIcons.More, share = 0.56f),
    Album("album", OctoIcons.Album),
    Artist("artist", OctoIcons.Artist),
    Playlist("playlist", OctoIcons.Playlists),
    Radio("radio", OctoIcons.Radio),
    ;

    // Its name in an address, with the size it is drawn at: a car keeps
    // pictures by address, so it asks again when the size changes.
    val address: String get() = "$key-${(share * 100).roundToInt()}"

    companion object {
        fun ofAddress(address: String): CarGlyph? = entries.firstOrNull { it.address == address }
    }
}
