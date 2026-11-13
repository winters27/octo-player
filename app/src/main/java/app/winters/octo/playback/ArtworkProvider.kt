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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.roundToInt

// Serves cover pictures to other apps that are allowed to see them, like a
// car's screen, which cannot read the app's own artwork references. Each
// cover is made once as a small JPEG and kept in the cache; a drawn playlist
// cover is drawn for each ask (the playlist covers keep their own drawings).
// Nothing is readable without a grant for its address (see `grantArtwork`).
//
// No ask ever waits here: the app's binder threads also carry the system's
// answers about ducking, so a car loading a grid of covers must never tie
// them up. A cover kept whole is opened at once; anything else is answered
// with a pipe whose picture is fetched, drawn and encoded on the provider's
// own threads and then written in whole.
//
//   /512/<ref>               a cover, its reference written out
//   /512/x/<flags>/<value>   flags: m the "Not in your library" mark,
//                            r an artist's picture: the mark placed for a
//                              round picture, the artist glyph for no cover,
//                            h a handle in place of the reference,
//                            g a glyph tile, the value naming its glyph
//                              and the size it is drawn at
class ArtworkProvider : ContentProvider() {
    // The kinds' glyph tiles are made once, off the binder threads: the
    // provider can run in a process the player never started.
    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        executor.execute {
            for (glyph in CarGlyph.entries) runCatching { glyphFile(ctx, glyph) }
        }
        return true
    }

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Not ready")
        val segments = uri.pathSegments
        if (segments.size == 4 && segments[1] == "x" && segments[2] == GLYPH_FLAG.toString()) {
            val glyph = CarGlyph.ofAddress(segments[3]) ?: throw FileNotFoundException("No such glyph")
            val file = glyphPath(ctx, glyph)
            if (isWhole(file)) return open(file)
            return piped { glyphFile(ctx, glyph).readBytes() }
        }
        val asked = askedOf(segments) ?: throw FileNotFoundException("No artwork")
        if (asked.handle == null && playlistArtOf(asked.ref) == null) {
            val file = keptFile(ctx, AskedCover(asked.ref, asked.marked, asked.artist))
            if (isWhole(file)) return open(file)
        }
        return piped {
            // The handle's reference is a disk read, so it is looked up here.
            val ref = asked.handle?.let { refs(ctx).refFor(it) } ?: asked.ref
            if (ref.isEmpty()) null else coverBytes(ctx, AskedCover(ref, asked.marked, asked.artist))
        }
    }

    private fun open(file: File): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    // A pipe whose picture `make` gives on the provider's threads; with no
    // picture the reader sees an error.
    private fun piped(make: () -> ByteArray?): ParcelFileDescriptor {
        val pipe = pipes.open()
        executor.execute {
            val bytes = try {
                make()
            } catch (e: Exception) {
                null
            }
            if (bytes == null || bytes.isEmpty()) pipe.fail("No artwork") else feeder().fill(pipe, bytes)
        }
        return pipe.reader
    }

    private fun keptFile(ctx: Context, asked: AskedCover): File =
        File(File(ctx.cacheDir, "car-art-$ART_SIZE").apply { mkdirs() }, asked.fileName)

    // The cover's picture. A drawn playlist cover is drawn again for each
    // ask and never kept, so a cover drawn without one of its pictures is
    // never what the next ask gets. A cover that cannot be had is answered
    // with its kind's glyph tile and nothing is kept for it, so the next ask
    // tries it again.
    private fun coverBytes(ctx: Context, asked: AskedCover): ByteArray {
        val drawn = playlistArtOf(asked.ref) != null
        val file = keptFile(ctx, asked)
        if (!drawn && isWhole(file)) return file.readBytes()
        val noCover = asked.ref == NO_COVER
        val loaded = if (noCover) null else loadCover(ctx, asked.ref, ART_SIZE)
        val bitmap = when {
            loaded != null && asked.marked -> marked(shrink(loaded), asked.artist)
            loaded != null -> shrink(loaded)
            // The mark for something with no cover sits on its kind's tile,
            // its glyph drawn smaller to stay clear of the mark.
            noCover && asked.marked -> marked(glyphTile(ctx, asked.glyph, MARKED_GLYPH_SHARE), asked.artist)
            else -> return glyphFile(ctx, asked.glyph).readBytes()
        }
        val bytes = jpeg(bitmap)
        if (!drawn) {
            if (!isWhole(file)) file.delete()
            writeWhole(file, bytes)
        }
        return bytes
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

    private fun glyphPath(ctx: Context, glyph: CarGlyph): File =
        File(File(ctx.cacheDir, "car-glyph-$ART_SIZE").apply { mkdirs() }, "${glyph.address}.jpg")

    // A glyph tile's file, made once.
    private fun glyphFile(ctx: Context, glyph: CarGlyph): File {
        val file = glyphPath(ctx, glyph)
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

    // An address read without touching the disk: a handle is looked up
    // later, off the binder thread.
    private class Asked(val ref: String, val handle: String?, val marked: Boolean, val artist: Boolean)

    private fun askedOf(segments: List<String>): Asked? {
        if (segments.size == 2) return Asked(segments[1], null, marked = false, artist = false)
        if (segments.size != 4 || segments[1] != "x") return null
        val flags = segments[2]
        val value = segments[3]
        val handle = if (HANDLE_FLAG in flags) value else null
        return Asked(if (handle != null) "" else value, handle, marked = MARK_FLAG in flags, artist = ARTIST_FLAG in flags)
    }

    companion object {
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".art"
        private const val ART_SIZE = 512
        private const val ARTIST_FLAG = 'r'
        private const val GLYPH_FLAG = 'g'

        // Above OkHttp's five requests per host, so a grid loads at least
        // as fast as the binder threads loaded it.
        private const val COVER_THREADS = 8

        // The provider's threads; tests put in their own.
        @Volatile internal var executor: Executor = Executors.newFixedThreadPool(COVER_THREADS) { r ->
            Thread(r, "octo-covers").apply { isDaemon = true }
        }

        // The pipes covers go through; tests put in their own.
        @Volatile internal var pipes: CoverPipes = SystemCoverPipes()

        // A picture no one reads in this long fails its pipe.
        @Volatile internal var pipeDeadlineMs = 10_000L

        @Volatile private var feeding: Pair<CoverPipes, CoverFeeder>? = null

        internal fun feeder(): CoverFeeder {
            val p = pipes
            feeding?.takeIf { it.first === p }?.let { return it.second }
            return synchronized(this) {
                feeding?.takeIf { it.first === p }?.second ?: CoverFeeder(p, { pipeDeadlineMs }).also { feeding = p to it }
            }
        }

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
