package app.winters.octo.playback

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.ui.common.isOutsideLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
class ArtworkProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val loader = ArtworkProvider.loadCover
    private val glyphs = ArtworkProvider.drawGlyph
    private val drawn = mutableListOf<Int>()
    private lateinit var provider: ArtworkProvider

    @Before
    fun start() {
        provider = Robolectric.setupContentProvider(ArtworkProvider::class.java, ArtworkProvider.AUTHORITY)
        // The app's drawables are not here; the glyph drawn is noted instead.
        ArtworkProvider.drawGlyph = { _, id, _, _ -> drawn += id }
    }

    @After
    fun putBack() {
        ArtworkProvider.loadCover = loader
        ArtworkProvider.drawGlyph = glyphs
    }

    private fun kept(): List<File> = File(context.cacheDir, "car-art-512").listFiles().orEmpty().toList()

    @Test
    fun aCoverThatFailedToLoadShowsItsKindsTileIsNotKeptAndTheNextAskLoadsIt() {
        var loads = 0
        var fails = true
        ArtworkProvider.loadCover = { _, _, _ ->
            loads++
            if (fails) null else Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        }
        val uri = ArtworkProvider.carUriFor(context, "online:cover-1", outside = true)
        provider.openFile(uri, "r").use { assertTrue(it.statSize > 0) }
        assertEquals(listOf(CarGlyph.Album.drawable), drawn)
        assertTrue(kept().isEmpty())
        fails = false
        provider.openFile(uri, "r").close()
        assertEquals(2, loads)
        assertEquals(1, kept().size)
    }

    @Test
    fun anArtistWithNoPictureGetsTheArtistTileAndTheMark() {
        ArtworkProvider.loadCover = { _, _, _ -> error("nothing is loaded for no cover") }
        val uri = ArtworkProvider.carMarkOnlyUri(round = true)
        assertEquals(listOf("512", "x", "mr", ArtworkProvider.NO_COVER), uri.pathSegments)
        provider.openFile(uri, "r").use { assertTrue(it.statSize > 0) }
        assertEquals(listOf(CarGlyph.Artist.drawable), drawn)
    }

    @Test
    fun aGlyphTileIsServedOnlyForAGlyphOfItsOwn() {
        assertEquals(listOf("512", "x", "g", "more"), ArtworkProvider.carGlyphUri(CarGlyph.More).pathSegments)
        provider.openFile(ArtworkProvider.carGlyphUri(CarGlyph.More), "r").use { assertTrue(it.statSize > 0) }
        assertEquals(listOf(CarGlyph.More.drawable), drawn)
        val unknown = ArtworkProvider.carGlyphUri(CarGlyph.More).buildUpon().path("/512/x/g/sym_lock").build()
        assertThrows(FileNotFoundException::class.java) { provider.openFile(unknown, "r") }
    }

    @Test
    fun aDrawnPlaylistCoverIsDrawnForEachAskAndNeverKept() {
        var draws = 0
        ArtworkProvider.loadCover = { _, _, _ -> draws++; Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }
        val uri = ArtworkProvider.carUriFor(context, PlaylistArtRef(PlaylistCoverStyle.Mosaic, "pl1", "Road Trip", null, null, null, listOf("a", "b")).encode())
        provider.openFile(uri, "r").close()
        provider.openFile(uri, "r").close()
        assertEquals(2, draws)
        assertTrue(kept().isEmpty())
    }

    @Test
    fun aMosaicMissingACoverIsNeverDrawnWithAHole() {
        val one = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertNull(mosaicBitmap(listOf("a", "b", "c", "d"), 64) { ref, _ -> if (ref == "c") null else one })
        assertNull(mosaicBitmap(listOf("a"), 64) { _, _ -> null })
        assertEquals(64, mosaicBitmap(listOf("a", "b", "c", "d"), 64) { _, _ -> one }?.width)
    }

    @Test
    fun anAddressMarkedTheOldWayNoLongerCarriesTheMark() {
        ArtworkProvider.loadCover = { _, _, _ -> Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }
        val old = ArtworkProvider.uriFor("x").buildUpon().path("/512/x/o/online:cover-2").build()
        provider.openFile(old, "r").close()
        assertEquals(listOf("${carSha1("online:cover-2")}.jpg"), kept().map { it.name })
    }

    @Test
    fun theMarkOnSomethingWithNoCoverSitsOnThePlainTile() {
        ArtworkProvider.loadCover = { _, _, _ -> error("nothing is loaded for no cover") }
        provider.openFile(ArtworkProvider.carMarkOnlyUri(), "r").use { assertTrue(it.statSize > 0) }
    }

    @Test
    fun aReferenceThatGrowsWhenWrittenOutGoesByItsHandle() {
        val ref = "s:server:" + "東".repeat(60)
        assertTrue(ref.length < INLINE_REF_MAX)
        val uri = ArtworkProvider.carUriFor(context, ref)
        assertEquals(listOf("512", "x", "h"), uri.pathSegments.take(3))
        assertEquals(ref, ArtworkProvider.refs(context).refFor(uri.pathSegments[3]))
    }

    @Test
    fun aFindDownloadedIntoTheLibraryHasNoMark() {
        val look = CarLook(context)
        val find = track("find:9")
        val adopted = look.song(find, CarNode.Liked, isOutsideLibrary(find.id, setOf(find.id)), downloaded = true)
        assertFalse(adopted.mediaMetadata.artworkUri.toString().contains("/x/m"))
        val outside = look.song(find, CarNode.Liked, isOutsideLibrary(find.id, emptySet()), downloaded = false)
        assertTrue(outside.mediaMetadata.artworkUri.toString().contains("/x/m"))
    }
}
