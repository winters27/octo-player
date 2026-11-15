package app.winters.octo.ui.album

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.findId
import app.winters.octo.data.Drains
import app.winters.octo.discovery.outsideAlbumSongs
import app.winters.octo.discovery.today.wholeAlbum
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.paging.AlbumListing
import app.winters.octo.ui.paging.albumCopyLoader
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import kotlin.random.Random

// A labeled pin: a huge album's page, streamed from the server's listing
// into its copy and walked a page at a time, is the held album's whole
// album (discovery/today's wholeAlbum, as c2208eaa had it, over
// outsideAlbumSongs as the held page makes it now): the server's order, a
// song listed twice shown once where it first comes, finds the server marks
// as outside the library in their places, a find since taken into the
// library as the song it became, a song the server lists unmarked and the
// library lacks in its place as the server's own, and the library's songs
// the listing leaves out after it in album order.
@RunWith(RobolectricTestRunner::class)
class HugeAlbumParityTest {
    private val db = openTestCatalog()
    private val server = AlbumServer()
    private val drainDir = Files.createTempDirectory("drain").toFile()

    @After
    fun close() {
        server.close()
        db.close()
        drainDir.deleteRecursively()
    }

    private fun find(id: String) = TrackEntity(
        id = findId(id), sourceId = server.sourceId, nativeId = id, title = id, searchKey = id, sortKey = id, artist = "Band", artistId = "",
        album = "Big", albumId = "", trackNo = null, discNo = null, year = null, durationMs = 200_000, addedAt = 0, mimeType = null,
        sizeBytes = null, artwork = null, uri = null,
    )

    @Test
    fun aHugeAlbumIsTodaysWholeAlbum() = timed(90) {
        val n = 3_000
        db.albumOnServer(n, server.sourceId) { it / 1_000 + 1 }
        // A song only on the phone, and a find's song taken in since.
        db.dao().insertTracks(listOf(albumTrack(5_000, 3, id = "phone-only", title = "Phone"), albumTrack(5_001, 1, id = "t-taken", title = "Taken")))
        val random = Random(8)
        val order = (0 until n).shuffled(random).sortedBy { it / 500 }
        val listing = ArrayList<app.winters.octo.subsonic.Song>()
        order.forEachIndexed { i, s ->
            listing += listed(s, disc = s / 1_000 + 1)
            if (i % 100 == 0) listing += found(i, disc = s / 1_000 + 1)
            if (i == 700) listing += listed(order[3])
            if (i == 1_500) listing += app.winters.octo.subsonic.Song(id = "u1", title = "Unmarked", artist = "Band", albumId = "srv")
        }
        server.listing = listing
        val adoptions = mapOf(findId("f500") to "t-taken")

        val copy = AlbumListing(db, db.testMerge(server.sourceId), Drains(drainDir), { Long.MAX_VALUE }, { adoptions })
        assertTrue("the listing holds finds", copy.build(WorkOwners.marked("vm:1"), "al", server.client, runsOcto = true))
        val shown = albumCopyLoader(db.listsDao(), WorkOwners.marked("vm:1")).walkedIds(page = 97)

        val library = db.dao().albumTracks("al").first()
        val byServerId = (0 until n).associate { "s$it" to "t%05d".format(it) }
        val byId = library.associateBy { it.id }
        val resolved = listing.map { song -> if (!song.isExternal) byServerId[song.id]?.let(byId::getValue) ?: find(song.id) else find(song.id) }
        val today = wholeAlbum(library, outsideAlbumSongs(listing, resolved), adoptions).map { it.id }

        assertEquals(today, shown)
        assertEquals(today.size, albumCopyLoader(db.listsDao(), WorkOwners.marked("vm:1")).count())
        assertEquals("phone-only", shown.last())
        assertTrue("t-taken" in shown && findId("f500") !in shown && findId("u1") in shown)
    }
}
