package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Several servers are kept, one in use: only the phone's music and the
// server in use make the library, so two servers' copies of a song never
// become one row.
class LibrarySourcesTest {
    private fun track(source: String, native: String = "1") = SourceTrackEntity(
        id = "$source:$native", sourceId = source, nativeId = native, title = "Hotline Bling", searchKey = "hotline bling",
        sortKey = "hotline bling", artist = "Drake", artistId = "$source:artist", album = "Views", albumId = "$source:album",
        trackNo = 1, discNo = null, year = null, durationMs = 200_000, addedAt = 0, mimeType = null, sizeBytes = null,
        artwork = null, uri = null, albumOrder = 0, relinkKey = "", genre = "",
    )

    private fun source(id: String) = SourceCatalog(
        id,
        onPhone = id == "device",
        listOf(track(id)),
        listOf(SourceAlbumEntity("$id:album", id, "album", "Views", "views", "views", "Drake", "$id:artist", null, 1, 0, 0, null)),
        listOf(SourceArtistEntity("$id:artist", id, "Drake", "drake", "drake", 1, 1, null)),
    )

    @Test
    fun onlyTheServerInUseJoinsThePhonesMusic() {
        assertTrue(inLibrary("device", "server:home.test"))
        assertTrue(inLibrary("server:home.test", "server:home.test"))
        assertFalse(inLibrary("server:work.test", "server:home.test"))
        assertTrue(inLibrary("device", null))
        assertFalse(inLibrary("server:home.test", null))
    }

    @Test
    fun twoServersCopiesOfASongAreNeverOneRow() {
        val all = listOf("device", "server:home.test", "server:work.test").map(::source)
        val merged = mergeCatalogs(all.filter { inLibrary(it.sourceId, "server:work.test") })
        val song = merged.tracks.single()
        assertEquals("the phone's copy keeps its id", "device:1", song.id)
        assertEquals("device:1", merged.mergedIds["server:work.test:1"])
        assertFalse("the server not in use is no copy of it", "server:home.test:1" in merged.mergedIds)
    }

    @Test
    fun withNoServerInUseTheLibraryIsThePhonesOwn() {
        val all = listOf("server:home.test", "server:work.test").map(::source)
        val merged = mergeCatalogs(all.filter { inLibrary(it.sourceId, null) })
        assertTrue(merged.tracks.isEmpty())
    }
}
