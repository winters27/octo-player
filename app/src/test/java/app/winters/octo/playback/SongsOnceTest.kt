package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.addSongs
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.search.SearchFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Each list says whether it holds a song at most once, and every one that
// says so does: a walk of it never meets a song twice. A playlist does
// not, and a selection on a playlist's page of two rows of one song plays
// both rows.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class SongsOnceTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Suppress("UNCHECKED_CAST")
    private fun walk(spec: ListSpec): Pair<Boolean, List<String>> = rig.run {
        val loader = rig.loaders.forSpec(spec, rig.owners.walk()) as ListLoader<Any>
        val ids = ArrayList<String>()
        var from: app.winters.octo.ui.paging.RowKey? = null
        while (true) {
            val page = loader.after(from, 37)
            page.forEach { ids += songOf(loader, it.row)!! }
            if (page.size < 37) break
            from = page.last().key
        }
        loader.songsOnce to ids
    }

    @Test
    fun everyListThatSaysSoHoldsEachSongOnce() {
        rig.db.songs(300, album = "a1")
        rig.db.songs(200, album = "a2", prefix = "t")
        fun album(id: String, year: Int) = app.winters.octo.catalog.AlbumEntity(id, "device", id, id, id, id, "ar", "ar", year, 1, 1, 0, null)
        rig.run { rig.db.dao().insertAlbums(listOf(album("a1", 2001), album("a2", 2002))) }
        val specs = listOf(
            ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)),
            ListSpec.Songs(SongScope.All, SortOrder(SongSort.Album, true)),
            ListSpec.Album("a1", withFinds = false),
            ListSpec.Artist("ar", ArtistOrder.Page),
            ListSpec.Artist("ar", ArtistOrder.Albums),
            ListSpec.Search("s", SearchFilter.Songs),
        )
        for (spec in specs) {
            val (once, ids) = walk(spec)
            assertTrue("$spec says it holds songs once", once)
            assertTrue("$spec is not empty", ids.isNotEmpty())
            assertEquals("$spec met a song twice", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun aSelectionOfTwoRowsOfOneSongPlaysBoth() {
        val songs = rig.db.songs(10)
        rig.run {
            rig.db.userDao().insertPlaylist(PlaylistEntity("p", "P", 1, 1))
            rig.db.addSongs("p", songs + songs.take(1), now = 1)
        }
        val rows = rig.run { rig.db.userDao().playlistItems("p") }
        assertFalse(walk(ListSpec.Playlist("p")).first)
        val twice = rows.filter { it.trackId == "s0" }
        assertEquals(2, twice.size)
        val selection = ListSpec.Selection(ListSpec.Playlist("p"), twice.map { it.itemKey.toString() })
        assertFalse(walk(selection).first)
        rig.play(PlayRequest(selection, null))
        rig.settle()
        assertEquals(listOf("s0", "s0"), rig.held())
        assertEquals(listOf("s0", "s0"), rig.songs())
    }
}
