package app.winters.octo.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.createBitmap
import app.winters.octo.covers.CoverGlyph
import app.winters.octo.covers.CoverSpec
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.covers.coverPaletteKey
import app.winters.octo.covers.coverSide
import app.winters.octo.playlists.PlaylistArtEntry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder
import java.net.URLEncoder

const val PLAYLIST_ART_PREFIX = "playlist-art:"

// A playlist's picture as the phone draws it, as one artwork reference: the
// cover style the listener chose (part of it, so another style is another
// address a car asks for anew), the list's id, name, the line and foot of a
// designed cover, the glyph of one of Octo's own lists, and the covers of
// its first albums.
//
//   playlist-art:<style>|<id>|<name>|<line>|<footer>|<glyph>|<cover>|<cover>...
//
// Each part is url-encoded, so none holds a "|".
data class PlaylistArtRef(
    val style: PlaylistCoverStyle,
    val id: String,
    val name: String,
    val line: String?,
    val footer: String?,
    val glyph: CoverGlyph?,
    val covers: List<String>,
) {
    fun encode(): String =
        PLAYLIST_ART_PREFIX + (listOf(style.name, id, name, line.orEmpty(), footer.orEmpty(), glyph?.name.orEmpty()) + covers).joinToString("|") { encode(it) }
}

fun playlistArtOf(ref: String?): PlaylistArtRef? {
    if (ref == null || !ref.startsWith(PLAYLIST_ART_PREFIX)) return null
    val parts = ref.removePrefix(PLAYLIST_ART_PREFIX).split('|').map(::decode)
    if (parts.size < 6) return null
    val style = PlaylistCoverStyle.entries.firstOrNull { it.name == parts[0] } ?: return null
    return PlaylistArtRef(
        style, parts[1], parts[2], parts[3].ifEmpty { null }, parts[4].ifEmpty { null },
        CoverGlyph.entries.firstOrNull { it.name == parts[5] }, parts.drop(6).filter(String::isNotEmpty),
    )
}

// The picture, about `px` across: the covers in a square for Mosaic (the
// first cover alone when there are fewer than four), otherwise the designed
// cover on colors from the covers, as the phone's Playlists page draws it.
// A mosaic missing any of its covers is drawn designed instead, never with a
// hole. Blocks, so never on the main thread.
fun playlistArtBitmap(context: Context, art: PlaylistArtRef, px: Int): Bitmap? {
    if (art.style == PlaylistCoverStyle.Mosaic && art.covers.isNotEmpty()) {
        mosaicBitmap(art.covers, px) { cover, side -> artworkBitmap(context, cover, side) }?.let { return it }
    }
    return runCatching {
        val playlistArt = EntryPointAccessors.fromApplication(context.applicationContext, PlaylistArtEntry::class.java).playlistArt()
        runBlocking {
            val palette = playlistArt.palette(art.id, art.covers, coverPaletteKey("phone", art.covers))
            playlistArt.art(CoverSpec(art.id, art.name, art.line, art.footer, palette, glyph = art.glyph), coverSide(px))
        }.asAndroidBitmap()
    }.getOrNull()
}

// The covers in a square `px` across (the first alone when there are fewer
// than four), or null when any of them cannot be had.
internal fun mosaicBitmap(covers: List<String>, px: Int, load: (String, Int) -> Bitmap?): Bitmap? {
    if (covers.size < 4) return load(covers.first(), px)
    val half = px / 2
    val pictures = covers.take(4).map { load(it, half) ?: return null }
    val square = createBitmap(px, px)
    val canvas = Canvas(square)
    pictures.forEachIndexed { i, picture ->
        val left = (i % 2) * half
        val top = (i / 2) * half
        canvas.drawBitmap(picture, null, Rect(left, top, left + half, top + half), null)
    }
    return square
}

private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

private fun decode(text: String): String = runCatching { URLDecoder.decode(text, Charsets.UTF_8.name()) }.getOrDefault("")
