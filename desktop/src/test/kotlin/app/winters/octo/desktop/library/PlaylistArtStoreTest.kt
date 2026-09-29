package app.winters.octo.desktop.library

import androidx.compose.ui.graphics.asSkiaBitmap
import app.winters.octo.covers.CoverSpec
import app.winters.octo.covers.PLAYLIST_COVER_LINE
import app.winters.octo.covers.coverPaletteKey
import app.winters.octo.covers.hueDistance
import app.winters.octo.covers.seededPalette
import app.winters.octo.covers.toLch
import app.winters.octo.desktop.FakeServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Designed covers on the desktop: colours from the server's picture of a
// playlist's first albums, kept in memory and on disk, and the pictures
// drawn once for each size and kept the same way.
class PlaylistArtStoreTest {
    @get:Rule val folder = TemporaryFolder()

    // A server's four-cover square: red, teal, red, teal.
    private fun mosaic(): ByteArray {
        val surface = Surface.makeRasterN32Premul(300, 300)
        val paint = Paint()
        for (q in 0 until 4) {
            paint.color = if (q % 3 == 0) 0xFFC8283C.toInt() else 0xFF1E9C96.toInt()
            surface.canvas.drawRect(Rect.makeXYWH(150f * (q % 2), 150f * (q / 2), 150f, 150f), paint)
        }
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun order() = CoverOrder("p1", "Late night", PLAYLIST_COVER_LINE, "12 songs", listOf("pl-p1"), quarters = true, stamp = "2026-09-28")

    @Test
    fun coloursComeFromTheServersPictureAndAreKept() = runBlocking {
        FakeServer().use { server ->
            server.file("getCoverArt", mosaic())
            val client = server.client()
            val store = PlaylistArtStore(OkHttpClient()).apply { folder = this@PlaylistArtStoreTest.folder.root }
            val key = coverPaletteKey("localhost", listOf("pl-p1", "2026-09-28"))
            val palette = store.palette(client, order(), key)
            assertTrue(palette.fromMusic)
            val hues = listOf(toLch(0xFFC8283C.toInt()).h, toLch(0xFF1E9C96.toInt()).h)
            assertTrue(hues.any { hueDistance(it, palette.hue.toDouble()) < 3 })
            assertEquals(1, server.endpoints().count { it == "getCoverArt" })
            // Asked again: from memory, then (a new store) from disk.
            assertSame(palette, store.palette(client, order(), key))
            val again = PlaylistArtStore(OkHttpClient()).apply { folder = this@PlaylistArtStoreTest.folder.root }
            assertEquals(palette, again.palette(client, order(), key))
            assertEquals(1, server.endpoints().count { it == "getCoverArt" })
        }
    }

    @Test
    fun aServerThatDoesNotAnswerGivesTheListsOwnColoursButKeepsNothing() = runBlocking {
        FakeServer().use { server ->
            val client = server.client()
            val store = PlaylistArtStore(OkHttpClient()).apply { folder = this@PlaylistArtStoreTest.folder.root }
            val key = coverPaletteKey("localhost", listOf("pl-p1"))
            // The fake server answers covers with a Subsonic error, not a picture.
            assertEquals(seededPalette("p1"), store.palette(client, order(), key))
            assertEquals(null, store.knownPalette(key))
            assertFalse(File(folder.root, "palettes").exists())
        }
    }

    @Test
    fun aCoverIsDrawnOnceAtItsSizeAndKept() = runBlocking {
        val store = PlaylistArtStore(OkHttpClient()).apply { folder = this@PlaylistArtStoreTest.folder.root }
        val spec = CoverSpec("p1", "Late night", PLAYLIST_COVER_LINE, "12 songs", seededPalette("p1"))
        val first = store.art(spec, 160)
        assertEquals(160, first.width)
        assertSame(first, store.art(spec, 160))
        val saved = File(folder.root, "art").listFiles()!!.single()
        assertTrue(saved.name.endsWith(".png"))
        // Another run reads it back, the same picture.
        val read = PlaylistArtStore(OkHttpClient()).apply { folder = this@PlaylistArtStoreTest.folder.root }.art(spec, 160)
        val a = first.asSkiaBitmap()
        val b = read.asSkiaBitmap()
        for (x in listOf(5, 80, 150)) for (y in listOf(5, 80, 150)) assertEquals(a.getColor(x, y), b.getColor(x, y))
        // A new name is a new picture.
        assertNotNull(store.art(spec.copy(name = "Late nights"), 160))
        assertEquals(2, File(folder.root, "art").listFiles()!!.size)
    }

    @Test
    fun palettesReadBackAsTheyWereWritten() {
        val palette = seededPalette("p9")
        assertEquals(palette, PlaylistArtStore.readPalette(PlaylistArtStore.writePalette(palette)))
        assertEquals(null, PlaylistArtStore.readPalette("rubbish"))
    }
}
