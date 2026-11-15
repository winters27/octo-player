package app.winters.octo.ui.playlist

import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.PlaylistRowView
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SongIds
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.PlaylistSongs
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.common.playlistPickIds
import app.winters.octo.ui.menu.SongAction
import app.winters.octo.ui.menu.albumAddSpec
import app.winters.octo.ui.menu.songActions
import app.winters.octo.ui.paging.ListLoaders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A song found online goes in a playlist only from a server listing
// octoPlaylistSongs: from its menu (and the quick add), a selection, an
// album's add holding finds, a playlist's rows, ids, and the store's own
// pages, never otherwise.
@RunWith(RobolectricTestRunner::class)
class FindAddGatesTest {
    private fun track(id: String) = TrackEntity(
        id = id, sourceId = "server:h", nativeId = id, title = "T $id", searchKey = "t", sortKey = "t",
        artist = "A", artistId = "a", album = "B", albumId = "b", trackNo = null, discNo = null, year = null,
        durationMs = 1_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun find(id: String) = OnlineSongEntity(
        id = "find:$id", sourceId = "server:h", nativeId = id, title = "F $id", artist = "A", album = "", albumId = null, artistId = null,
        durationMs = 1_000, coverId = null, mimeType = null, bitrate = null, seenAt = 1, explicit = null,
    )

    @Test
    fun aFindsMenuOffersAddToPlaylistOnlyWithTheExtension() {
        val open = songActions(find = true, radio = false, lastPlaylist = true, playlistAdds = true)
        assertTrue(SongAction.AddToPlaylist in open)
        assertTrue(SongAction.AddToLastPlaylist in open)
        val closed = songActions(find = true, radio = false, lastPlaylist = true)
        assertFalse(SongAction.AddToPlaylist in closed)
        assertFalse(SongAction.AddToLastPlaylist in closed)
    }

    @Test
    fun aSelectionAndAnAlbumBringTheirFindsOnlyWithTheExtension() {
        assertEquals(listOf("t1", "find:x"), playlistPickIds(listOf("t1", "find:x"), finds = true))
        assertEquals(listOf("t1"), playlistPickIds(listOf("t1", "find:x"), finds = false))
        assertEquals(ListSpec.Album("al", withFinds = true), albumAddSpec("al", finds = true))
        assertEquals(ListSpec.Album("al", withFinds = false), albumAddSpec("al", finds = false))
    }

    @Test
    fun aListsRowsBringFindsOnlyWithTheExtension() {
        val library = track("t1")
        val found = track("find:x")
        val notInLibrary = PlaylistRowView(ord = 0, itemKey = 1, itemId = 1, inLibrary = false, track = found)
        assertEquals(found, addableRow(notInLibrary, finds = true))
        assertNull(addableRow(notInLibrary, finds = false))
        assertNull(addableRow(found, finds = false))
        assertEquals(found, addableRow(found, finds = true))
        assertEquals(library, addableRow(library, finds = false))
    }

    @Test
    fun idsBringTheFindsWhoseRowsAreWrittenOnlyWithTheExtension() = runBlocking {
        val db = openTestCatalog()
        db.dao().insertTracks(listOf(track("t1")))
        db.onlineDao().insert(listOf(find("x")))
        val sources = AddSources(db, dagger.Lazy<ListLoaders> { error("no list here") }, WorkOwners(), PlaylistSets(db))
        val open = ArrayList<String>()
        sources.pages(AddSource.Ids(listOf("t1", "find:x", "find:gone")), emit = { page -> open += page.map { it.trackId } }, finds = true)
        assertEquals(listOf("t1", "find:x"), open)
        val closed = ArrayList<String>()
        sources.pages(AddSource.Ids(listOf("t1", "find:x")), emit = { page -> closed += page.map { it.trackId } })
        assertEquals(listOf("t1"), closed)
        db.close()
    }

    @Test
    fun theStoresPagesKeepKeptFindsOnlyWithTheExtension() = runBlocking {
        val db = openTestCatalog()
        db.dao().insertTracks(listOf(track("t1")))
        val songs = PlaylistSongs(db.dao(), SongIds(db, db.testMerge("server:h")), keptFinds = { ids -> ids.filter { it == "find:x" }.toSet() })
        val open = ArrayList<String>()
        songs.pages(listOf("t1", "find:x", "find:y"), { page -> open += page.map { it.trackId } }, finds = true)
        assertEquals(listOf("t1", "find:x"), open)
        val closed = ArrayList<String>()
        songs.pages(listOf("t1", "find:x"), { page -> closed += page.map { it.trackId } })
        assertEquals(listOf("t1"), closed)
        db.close()
    }
}
