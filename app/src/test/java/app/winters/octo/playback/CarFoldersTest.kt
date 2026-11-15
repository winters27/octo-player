package app.winters.octo.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.subsonic.PlaylistPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

// The car's Playlists tab shows folders after the live lists and before the
// playlists in no folder; a folder holds its folders, then its playlists;
// upkeep lists never show (by the server's mark, and by the old 🛠 rule); a
// folder's id reads back as itself; a long folder splits into parts under
// the answer budget.
@RunWith(RobolectricTestRunner::class)
class CarFoldersTest {
    private fun p(id: String, name: String = id, upkeep: Boolean = false) =
        PlaylistSummary(id, name, 3, 0, emptyList(), serverId = "s$id", upkeep = upkeep)

    private val tree = PlaylistFolderTree(
        listOf(PlaylistFolder("pfA", "Road trips"), PlaylistFolder("pfB", "Summer", "pfA"), PlaylistFolder("pfC", "Art")),
        listOf(PlaylistPlace("s1", "pfA"), PlaylistPlace("s3", "pfB")),
        Locale.ENGLISH,
    )

    private val all = listOf(p("1"), p("2"), p("3"), p("up", "Review", upkeep = true), p("tool", "🛠 Remove from library"))

    private fun label(child: CarFolderChild) = when (child) {
        is CarFolderChild.Folder -> "folder:${child.folder.name}"
        is CarFolderChild.Playlist -> "playlist:${child.playlist.id}"
    }

    @Test
    fun upkeepListsNeverShowInACar() {
        assertEquals(listOf("1", "2", "3"), carPlaylists(all).map { it.id })
    }

    @Test
    fun theTabsOwnPartIsFoldersThenPlaylistsInNoFolder() {
        val top = carFolderChildren(tree, null, carPlaylists(all))
        assertEquals(listOf("folder:Art", "folder:Road trips", "playlist:2"), top.map(::label))
        assertEquals("1 playlist, 1 folder", (top[1] as CarFolderChild.Folder).subtitle)
    }

    @Test
    fun aFolderHoldsItsFoldersThenItsPlaylists() {
        assertEquals(listOf("folder:Summer", "playlist:1"), carFolderChildren(tree, "pfA", carPlaylists(all)).map(::label))
        assertEquals(listOf("playlist:3"), carFolderChildren(tree, "pfB", carPlaylists(all)).map(::label))
    }

    @Test
    fun withoutFoldersTheTabHoldsThePlaylistsAlone() {
        assertEquals(listOf("playlist:1", "playlist:2", "playlist:3"), carFolderChildren(null, null, carPlaylists(all)).map(::label))
        assertTrue(carFolderChildren(null, "pfA", carPlaylists(all)).isEmpty())
    }

    @Test
    fun aFolderIdReadsBackAsItselfAndReadsThePlaylists() {
        val node = CarNode.PlaylistFolder("pfAbc123")
        assertEquals("folder:pfAbc123", carId(node))
        assertEquals(node, parseCarId("folder:pfAbc123"))
        assertEquals("part:2:folder:pfAbc123", carId(CarNode.Part(2, node)))
        assertTrue(CarInput.Playlists in carInputsOf(node))
        assertTrue(node.isBrowsable)
    }

    @Test
    fun aLongFolderSplitsIntoPartsUnderTheBudget() {
        val node = CarNode.PlaylistFolder("pfA")
        val rows = CarSplitRows(
            letter = { heading, _, _, _ -> MediaItem.Builder().setMediaId(carId(heading)).build() },
            part = { part, _, first, _, _ -> MediaItem.Builder().setMediaId(carId(part)).setMediaMetadata(MediaMetadata.Builder().setTitle(first).build()).build() },
        )
        val items = List(60) { i -> MediaItem.Builder().setMediaId("playlist:p$i").setMediaMetadata(MediaMetadata.Builder().setTitle("Playlist ${"x".repeat(200)} $i").build()).build() }
        val parts = carFitOrParts(node, items, rows, budget = 4_000)
        assertTrue(parts.size > 1)
        assertEquals("part:0:folder:pfA", parts.first().mediaId)
        assertEquals(items.size, parts.indices.sumOf { carPartItems(it, items, budget = 4_000).size })
    }
}
