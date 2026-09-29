package app.winters.octo.playlists

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.covers.CoverBackground
import app.winters.octo.covers.CoverBackgrounds
import app.winters.octo.covers.CoverPalette
import app.winters.octo.covers.CoverSpec
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.covers.coverArtKey
import app.winters.octo.covers.coverFileName
import app.winters.octo.covers.coverPalette
import app.winters.octo.covers.coverSwatches
import app.winters.octo.design.CoverFontFamily
import app.winters.octo.design.coverMeasurer
import app.winters.octo.design.designCover
import app.winters.octo.playback.artworkBitmap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playlistArtPrefs by preferencesDataStore("playlist_art")
private val STYLE = stringPreferencesKey("playlist_covers")

// Whether playlists show designed covers or their album mosaics, kept in
// its own small store. Designed unless the listener picked the mosaics.
class PlaylistArtSettings internal constructor(private val data: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.playlistArtPrefs)

    val style: Flow<PlaylistCoverStyle> = data.data
        .map { stored -> PlaylistCoverStyle.entries.firstOrNull { it.name == stored[STYLE] } ?: PlaylistCoverStyle.Designed }
        .distinctUntilChanged()

    suspend fun setStyle(style: PlaylistCoverStyle) {
        data.edit { it[STYLE] = style.name }
    }
}

// Designed playlist covers on the phone: their colours, from the covers of
// a list's first albums, and the pictures, drawn at the size shown. Both
// are kept in memory, and the pictures in the app's cache folder too.
@Singleton
class PlaylistArt @Inject constructor(
    @ApplicationContext private val context: Context,
    val settings: PlaylistArtSettings,
) {
    private val palettes = LruCache<String, CoverPalette>(256)
    private val pictures = object : LruCache<String, ImageBitmap>(MemoryKb) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }
    private val folder get() = File(context.cacheDir, "playlist-art")

    // A few at a time, so a long list does not take every core.
    private val drawing = Semaphore(2)
    private val fonts by lazy { createFontFamilyResolver(context) }
    private var written = 0

    fun knownPalette(key: String): CoverPalette? = palettes.get(key)

    fun known(key: String): ImageBitmap? = pictures.get(key)

    // A list's colours, from its covers (artwork references); the list's own
    // when it has none, or none could be read.
    suspend fun palette(id: String, covers: List<String>, key: String): CoverPalette {
        knownPalette(key)?.let { return it }
        if (covers.isEmpty()) return coverPalette(emptyList(), id).also { palettes.put(key, it) }
        val pixels = withContext(Dispatchers.IO) { covers.map { ref -> artworkBitmap(context, ref, SampleSize)?.let(::pixelsOf) } }
        if (pixels.any { it == null }) return coverPalette(emptyList(), id)
        val palette = withContext(Dispatchers.Default) { coverPalette(pixels.map { coverSwatches(it!!) }, id) }
        palettes.put(key, palette)
        return palette
    }

    // The cover drawn `side` pixels square: kept, or drawn now off the main thread.
    suspend fun art(spec: CoverSpec, side: Int): ImageBitmap {
        val key = coverArtKey(spec, side)
        known(key)?.let { return it }
        val file = File(folder, coverFileName(key, "png"))
        val stored = withContext(Dispatchers.IO) {
            if (file.isFile) runCatching { android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() else null
        }
        if (stored != null) return stored.also { pictures.put(key, it) }
        val drawn = drawing.withPermit {
            withContext(Dispatchers.Default) { designCover(spec, side, coverMeasurer(fonts), CoverFontFamily, ::backgroundPixels, ::pictureOf) }
        }
        withContext(Dispatchers.IO) { save(file, drawn) }
        pictures.put(key, drawn)
        return drawn
    }

    // The library's backgrounds as pixels (WebP, which Android reads), the
    // last two kept: a list of playlists often shares one.
    private val backgrounds = object : LinkedHashMap<String, Pair<IntArray, Int>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<IntArray, Int>>) = size > 2
    }

    private fun backgroundPixels(background: CoverBackground): Pair<IntArray, Int> = synchronized(backgrounds) {
        backgrounds.getOrPut(background.file) {
            val bytes = CoverBackgrounds.bytes(background.file)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("${background.file} could not be read")
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
            pixels to bitmap.width
        }
    }

    private fun pictureOf(pixels: IntArray, side: Int): ImageBitmap =
        Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()

    private fun save(file: File, image: ImageBitmap) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temp.renameTo(file)) temp.delete()
        }
        if (++written % TrimEvery == 0) trim()
    }

    // Keeps the folder under its budget, the oldest pictures going first.
    private fun trim() {
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DiskBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val small = if (bitmap.width > SampleSize || bitmap.height > SampleSize) Bitmap.createScaledBitmap(bitmap, SampleSize, SampleSize, true) else bitmap
        val out = IntArray(small.width * small.height)
        small.getPixels(out, 0, small.width, 0, 0, small.width, small.height)
        return out
    }

    private companion object {
        const val MemoryKb = 24 * 1024
        const val DiskBytes = 48L * 1024 * 1024
        const val TrimEvery = 40
        const val SampleSize = 64
    }
}

// For pictures drawn in lists, which have no view model of their own.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaylistArtEntry {
    fun playlistArt(): PlaylistArt
}
