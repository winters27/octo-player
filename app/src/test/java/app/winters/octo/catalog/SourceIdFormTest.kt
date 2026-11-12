package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.device.DeviceRow
import app.winters.octo.device.buildDeviceCatalog
import app.winters.octo.server.buildServerCatalog
import app.winters.octo.testing.GenShape
import app.winters.octo.testing.LibraryGen
import app.winters.octo.testing.openTestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every source row's id is "$sourceId:" followed by the source's own id for
// it, as the only two builders make them: a server's rows (ServerCatalog)
// and the phone's (DeviceCatalogBuilder, through a scan). The find walks a
// source's rows by that id range and keeps to the source's own rows by its
// sourceId, so a source whose id begins another's ("server:host" beside
// "server:host:8443") finds only its own rows, either way round.
@RunWith(RobolectricTestRunner::class)
class SourceIdFormTest {
    private val shape = GenShape(songs = 40, albums = 6, artists = 3, phoneOnly = 0)

    @Test
    fun everyBuiltRowsIdStartsWithItsSource() {
        for (source in listOf("server:host", "server:host:8443")) {
            val rows = buildServerCatalog(source, LibraryGen.server(3, shape))
            assertTrue("$source: nothing built", rows.tracks.isNotEmpty() && rows.albums.isNotEmpty() && rows.artists.isNotEmpty())
            assertForm(source, rows.tracks.map { it.id to it.sourceId } + rows.albums.map { it.id to it.sourceId } + rows.artists.map { it.id to it.sourceId })
        }
        val phone = buildDeviceCatalog((1L..12L).map { deviceRow(it, album = "Album ${it % 3}", artist = "Artist ${it % 2}") })
        assertTrue("the phone: nothing built", phone.tracks.isNotEmpty() && phone.albums.isNotEmpty() && phone.artists.isNotEmpty())
        assertForm(DEVICE, phone.tracks.map { it.id to it.sourceId } + phone.albums.map { it.id to it.sourceId } + phone.artists.map { it.id to it.sourceId })
    }

    @Test
    fun aSourceWhoseIdBeginsAnothersFindsOnlyItsOwnRows() = guarded {
        val short = "server:host"
        val long = "server:host:8443"
        for ((mine, theirs) in listOf(short to long, long to short)) {
            val db = openTestCatalog()
            try {
                val ours = built(mine, 5)
                db.put(ours)
                db.put(built(theirs, 6))
                rebuildSettled(db, mine)
                // A copy of ours lacking every third song and with the rest
                // renamed: gone, changed and new rows of ours to find.
                db.stage(ours.copy(tracks = ours.tracks.filterIndexed { i, _ -> i % 3 != 0 }.map { it.copy(title = it.title + " (Live)") }), generation = 2)
                SaveFind(db).find(claimAndBuild(db, mine).second)
                val found = db.query("SELECT id FROM save_found", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                assertTrue("$mine: nothing found", found.any { owner(it, mine, theirs) == mine })
                assertEquals("$mine found rows of $theirs", emptyList<String>(), found.filter { owner(it, mine, theirs) == theirs })
            } finally {
                db.close()
            }
        }
    }

    // The source an id is of, of the two: the longer id it starts with.
    private fun owner(id: String, a: String, b: String): String? = listOf(a, b).filter { id.startsWith("$it:") }.maxByOrNull { it.length }

    private fun built(source: String, seed: Long): SourceCatalog =
        buildServerCatalog(source, LibraryGen.server(seed, shape)).let { SourceCatalog(source, false, it.tracks, it.albums, it.artists) }

    private fun assertForm(source: String, rows: List<Pair<String, String>>) {
        for ((id, rowSource) in rows) {
            assertEquals(id, source, rowSource)
            assertTrue("$source: $id", id.startsWith("$source:") && id.length > source.length + 1)
        }
    }

    private fun deviceRow(id: Long, album: String, artist: String) = DeviceRow(
        id = id,
        uri = "content://media/external/audio/media/$id",
        fileName = "Song $id.flac",
        folder = "Music/$album/",
        title = "Song $id",
        artist = artist,
        albumArtist = null,
        album = album,
        track = null,
        disc = null,
        year = null,
        durationMs = 180_000,
        addedAtSeconds = 1_700_000_000 + id,
        mimeType = "audio/flac",
        sizeBytes = 30_000_000,
    )
}
