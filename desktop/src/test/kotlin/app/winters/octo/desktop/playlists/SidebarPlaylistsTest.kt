package app.winters.octo.desktop.playlists

import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.subsonic.PlaylistPlace
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SidebarPlaylistsTest {
    private fun playlist(id: String) = Playlist(id, id, owner = "winters")

    private val liked = Playlist("og1234567890123456789A", "Liked Songs", owner = "winters", readonly = true)

    private val tree = PlaylistFolderTree(
        listOf(PlaylistFolder("road", "Road trips"), PlaylistFolder("summer", "Summer", "road"), PlaylistFolder("art", "Art")),
        listOf(PlaylistPlace("p2", "road"), PlaylistPlace("p3", "summer"), PlaylistPlace("p4", "art")),
        Locale.ENGLISH,
    )

    private val all = listOf(playlist("p1"), playlist("p2"), playlist("p3"), playlist("p4"), playlist("p5"), liked)

    private fun keys(rows: List<SidebarPlaylistRow>) = rows.map { it.key }

    @Test
    fun pinnedFirstThenOctosOwnThenFoldersThenTheRest() {
        val rows = sidebarPlaylistRows(all, pinned = listOf("p3", "p1"), tree = tree, open = setOf("road", "summer"), rail = false)
        assertEquals(
            listOf("pin:p3", "pin:p1", "pl:${liked.id}", "pf:art", "pf:road", "pf:summer", "pl:p3", "pl:p2", "pl:p5"),
            keys(rows),
        )
        val road = rows.single { it.key == "pf:road" } as SidebarPlaylistRow.Folder
        assertEquals(1, road.depth)
        assertEquals(1, road.playlists)
        assertEquals(1, road.folders)
        val summer = rows.single { it.key == "pf:summer" } as SidebarPlaylistRow.Folder
        assertEquals(2, summer.depth)
        val inSummer = rows.single { it.key == "pl:p3" } as SidebarPlaylistRow.Item
        assertEquals("a playlist in a folder sits one level further in", 2, inSummer.depth)
        assertTrue("a pinned playlist in a folder shows there too, marked", inSummer.pinned)
        assertFalse((rows.single { it.key == "pf:art" } as SidebarPlaylistRow.Folder).open)
    }

    @Test
    fun theRailShowsTheTopLevelOnlyAndNoFoldersKeepsTheServersOrder() {
        val rail = sidebarPlaylistRows(all, emptyList(), tree, open = setOf("road"), rail = true)
        assertEquals(listOf("pl:${liked.id}", "pf:art", "pf:road", "pl:p1", "pl:p5"), keys(rail))
        assertFalse((rail.single { it.key == "pf:road" } as SidebarPlaylistRow.Folder).open)
        val flat = sidebarPlaylistRows(all, listOf("p4"), null, emptySet(), rail = false)
        assertEquals(listOf("pin:p4", "pl:${liked.id}", "pl:p1", "pl:p2", "pl:p3", "pl:p5"), keys(flat))
    }

    @Test
    fun ctrlAndShiftClicksPickSeveralAndADragCarriesThePicks() {
        val picks = SidebarPicks()
        val order = listOf("a", "b", "c", "d")
        picks.click("b", toggle = false, range = false, order = order)
        assertEquals(setOf("b"), picks.picked)
        picks.click("d", toggle = false, range = true, order = order)
        assertEquals(setOf("b", "c", "d"), picks.picked)
        picks.click("c", toggle = true, range = false, order = order)
        assertEquals(setOf("b", "d"), picks.picked)
        assertEquals(listOf("b", "d"), picks.group("d", order))
        assertEquals("a row not picked carries itself", listOf("a"), picks.group("a", order))
        picks.clear()
        assertTrue(picks.picked.isEmpty())
    }

    @Test
    fun songsFoundOnlineGoOnlyWhereTheyCanBeKept() {
        val library = Song("s1", "One")
        val found = Song("ext-deezer-song-1", "Two", isExternal = true)
        val mine = playlist("p1")
        val review = Playlist("r", "Review", octoUpkeep = true, octoUpkeepKind = UPKEEP_NOTICE)
        val action = Playlist("a", "Remove from library", octoUpkeep = true)
        val request = Playlist("q", "Request a copy", octoUpkeep = true, octoUpkeepKind = UPKEEP_REQUEST)
        assertEquals(listOf(library, found), songsFor(mine, listOf(library, found), outsideAllowed = true))
        assertEquals("an older server drops them", listOf(library), songsFor(mine, listOf(library, found), outsideAllowed = false))
        assertEquals(listOf(library), songsFor(review, listOf(library, found), outsideAllowed = true))
        assertTrue("a missing kind is an action list", refusesFinds(action))
        assertFalse(refusesFinds(request))
        assertEquals(listOf(library, found), songsFor(request, listOf(library, found), outsideAllowed = true))
        assertFalse(refusesFinds(mine))
    }

    @Test
    fun aMoveIsMadeAgainOverAChangedListOrRefusedWhenASongIsGone() {
        val move = songMoveOf(listOf("s1", "s2", "s3"), listOf(2), 0)
        assertEquals(SongMove(listOf("s3"), "s1"), move)
        assertEquals(listOf(2, 0, 1, 3), reapplied(move, listOf("s1", "s2", "s3", "s4")))
        assertNull(reapplied(move, listOf("s1", "s2")))
        assertEquals("a gone anchor puts them at the end", listOf(1, 0), reapplied(move, listOf("s3", "s2")))
        assertEquals(listOf(1, 0), reapplied(SongMove(listOf("s1"), null), listOf("s1", "s2")))
    }
}
