package app.winters.octo.discovery

import app.winters.octo.listening.timed
import app.winters.octo.ui.album.AlbumServer
import app.winters.octo.ui.album.found
import app.winters.octo.ui.album.listed
import app.winters.octo.ui.paging.WHOLE_ALBUM_MAX
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

// The album page of an album it holds whole reads the server's listing
// whole only when the listing itself names at most WHOLE_ALBUM_MAX songs,
// whatever the library holds of it: a longer listing answers no songs, and
// the page shows the library's.
@RunWith(RobolectricTestRunner::class)
class ListingUpToTest {
    private val server = AlbumServer()
    private val drains = Files.createTempDirectory("drain").toFile()

    @After
    fun close() {
        server.close()
        drains.deleteRecursively()
    }

    private fun file() = File(drains, "listing.json")

    @Test
    fun aListingPastTheLimitIsNeverHeld() = timed(60) {
        // A few library songs, and a listing far longer, of finds.
        server.listing = (0 until 5).map { listed(it) } + (0 until WHOLE_ALBUM_MAX + 100).map { found(it) }
        val (album, songs) = server.client.listingUpTo("srv", file(), WHOLE_ALBUM_MAX)
        assertEquals("Big", album.name)
        assertNull(songs)
        // At the limit, the listing is held whole, in its order.
        server.listing = (0 until WHOLE_ALBUM_MAX).map { listed(it) }
        val (_, whole) = server.client.listingUpTo("srv", file(), WHOLE_ALBUM_MAX)
        assertEquals(server.listing.map { it.id }, whole!!.map { it.id })
        assertTrue(file().exists())
    }
}
