package app.winters.octo.ui.playlist

import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.subsonic.PlaylistPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PlaylistFolderRowsTest {
    private fun p(id: String, name: String, serverId: String? = "s$id", upkeep: Boolean = false, notice: String? = null, songs: Int = 3, kind: String? = null) =
        PlaylistSummary(id, name, songs, 0, emptyList(), serverId = serverId, upkeep = upkeep, octoNotice = notice, upkeepKind = kind)

    private val tree = PlaylistFolderTree(
        listOf(PlaylistFolder("road", "Road trips"), PlaylistFolder("summer", "Summer", "road"), PlaylistFolder("art", "Art")),
        listOf(PlaylistPlace("s1", "road"), PlaylistPlace("s2", "summer"), PlaylistPlace("local:4", "road"), PlaylistPlace("su", "art")),
        Locale.ENGLISH,
    )

    private val all = listOf(
        p("1", "One"), p("2", "Two"), p("3", "Three"), p("4", "Four", serverId = null),
        p("u", "Review", upkeep = true, notice = "review"), p("d", "Duplicates", upkeep = true, notice = "duplicates", songs = 1),
        p("a", "🛠 Remove from library", upkeep = true, songs = 9),
    )

    @Test
    fun theTopLevelHoldsFoldersPlaylistsInNoFolderAndTheUpkeepGroup() {
        val top = folderPageRows(all, tree, null)
        assertEquals(listOf("Art", "Road trips"), top.folders.map { it.folder.name })
        assertEquals("0 playlists", top.folders[0].subtitle)
        assertEquals("2 playlists, 1 folder", top.folders[1].subtitle)
        assertEquals(listOf("3"), top.playlists.map { it.id })
        assertEquals(listOf("u", "d", "a"), top.upkeep.map { it.id })
        assertEquals("Review has 3 songs, Duplicates has 1 song", top.upkeepNote)
    }

    @Test
    fun aFolderHoldsItsFoldersAndPlaylistsAndNeverUpkeep() {
        val road = folderPageRows(all, tree, "road")
        assertEquals(listOf("Summer"), road.folders.map { it.folder.name })
        assertEquals(listOf("1", "4"), road.playlists.map { it.id })
        assertTrue(road.upkeep.isEmpty())
        assertNull(road.upkeepNote)
        assertTrue(folderPageRows(all, tree, "art").empty)
    }

    @Test
    fun withoutFoldersTheTopLevelHoldsEveryPlaylist() {
        val top = folderPageRows(all, null, null)
        assertTrue(top.folders.isEmpty())
        assertEquals(listOf("1", "2", "3", "4"), top.playlists.map { it.id })
    }

    @Test
    fun whichUpkeepListsRefuseSongsFoundOnline() {
        assertTrue(p("u", "Review", upkeep = true, notice = "review", kind = "notice").refusesFinds)
        assertTrue(p("a", "Keep", upkeep = true, kind = "action").refusesFinds)
        assertTrue("an upkeep mark without a kind counts as an action list", p("x", "Older mark", upkeep = true).refusesFinds)
        assertTrue("a family request list takes them", !p("r", "Request a copy", upkeep = true, kind = "request").refusesFinds)
        assertTrue("the name no longer decides", !p("t", "🛠 Remove from library", upkeep = true, kind = "request").refusesFinds)
        assertTrue(!p("o", "Mine").refusesFinds)
        assertTrue("upkeep is never filed", !p("u", "Review", upkeep = true).fileable)
    }

    @Test
    fun aFindsPickerHidesOnlyActionAndNoticeLists() {
        val lists = listOf(
            p("o", "Mine"),
            p("u", "Review", upkeep = true, notice = "review", kind = "notice"),
            p("a", "Keep", upkeep = true, kind = "action"),
            p("r", "Request a copy", upkeep = true, kind = "request"),
            p("x", "Older mark", upkeep = true),
        )
        fun shown(forFind: Boolean) =
            PickerGroups.build(lists, { it.id }, { it.placeKey }, { it.name }, { it.upkeep }, { it.refusesFinds }, emptyList(), null, forFind, Locale.ENGLISH)
                .flatMap { group -> group.items.map { it.id } }
        assertEquals(listOf("o", "r"), shown(forFind = true))
        assertEquals(listOf("o", "a", "x", "r", "u"), shown(forFind = false))
    }
}
