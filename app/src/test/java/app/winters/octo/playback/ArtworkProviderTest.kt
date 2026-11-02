package app.winters.octo.playback

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.ui.common.isOutsideLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var provider: ArtworkProvider

    @Before
    fun start() {
        provider = Robolectric.setupContentProvider(ArtworkProvider::class.java, ArtworkProvider.AUTHORITY)
    }

    @After
    fun putBack() {
        ArtworkProvider.loadCover = loader
    }

    private fun kept(): List<File> = File(context.cacheDir, "car-art-512").listFiles().orEmpty().toList()

    @Test
    fun aCoverThatFailedToLoadIsNotKeptAndTheNextAskLoadsIt() {
        var loads = 0
        var fails = true
        ArtworkProvider.loadCover = { _, _, _ ->
            loads++
            if (fails) null else Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        }
        val uri = ArtworkProvider.carUriFor(context, "online:cover-1", outside = true)
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "r") }
        assertTrue(kept().isEmpty())
        fails = false
        provider.openFile(uri, "r").close()
        assertEquals(2, loads)
        assertEquals(1, kept().size)
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
        assertFalse(adopted.mediaMetadata.artworkUri.toString().contains("/x/o"))
        val outside = look.song(find, CarNode.Liked, isOutsideLibrary(find.id, emptySet()), downloaded = false)
        assertTrue(outside.mediaMetadata.artworkUri.toString().contains("/x/o"))
    }
}
