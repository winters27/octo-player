package app.winters.octo.playlists

import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.subsonic.Playlist
import app.winters.octo.ui.menu.CollectionAction
import app.winters.octo.ui.menu.playlistActions
import app.winters.octo.ui.playlist.PickerGroups
import app.winters.octo.ui.playlist.deletes
import app.winters.octo.ui.playlist.fileable
import app.winters.octo.ui.playlist.placeKey
import app.winters.octo.ui.playlist.renames
import app.winters.octo.ui.playlist.takesSongs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// An imported playlist's copy comes to the phone as a copy that only
// follows the server; it can be filed, is never offered to add songs to,
// and offers no Rename or Delete. The listener's own playlist on a server
// listing octoPlaylistSongs is pushed with the family rule when both sides
// changed, and a push names the server's own ids for songs found online.
class ImportRowsTest {
    private val importId = "oi1234567890123456789A"

    @Test
    fun anImportedCopyComesToThePhoneAndOnlyFollowsTheServer() {
        val copy = Playlist(importId, "Imported", owner = "winters", readonly = true, octoImport = true)
        val station = Playlist("or1234567890123456789A", "Station", owner = "winters", readonly = true)
        assertEquals(listOf(importId), importable(listOf(copy, station), emptySet(), "winters").map { it.id })
        assertTrue("never a new playlist's id", newestNamedCandidates(listOf(copy), "Imported", "winters", emptySet()).isEmpty())
        val row = PlaylistEntity("p", "Imported", 1, 5, serverId = importId, sourceId = "server:h", syncedAt = 3, syncedName = "Imported", serverStamp = "a|1", octoImport = true)
        val linked = row.linked(ownRules = true)!!
        assertTrue(linked.pullOnly)
        assertFalse(linked.ownRules)
        assertEquals(PlaylistStep.Pull("p", importId, "b|1", keepName = false), planLinked(ServerPlaylist(importId, "Imported", "b|1"), linked))
    }

    @Test
    fun anImportedCopyCanBeFiledButTakesNoSongsAndIsNeverRenamedOrDeleted() {
        val copy = PlaylistSummary("p", "Imported", 3, 0, emptyList(), serverId = importId, octoImport = true)
        val own = PlaylistSummary("q", "Mine", 3, 0, emptyList(), serverId = "srvQ")
        assertTrue(copy.fileable)
        assertEquals(importId, copy.placeKey)
        assertFalse(copy.takesSongs)
        assertFalse(copy.renames)
        assertFalse(copy.deletes)
        val actions = playlistActions(empty = false, renames = copy.renames, deletes = copy.deletes, moves = copy.fileable)
        assertFalse(CollectionAction.Rename in actions || CollectionAction.Delete in actions)
        assertTrue(CollectionAction.MoveToFolder in actions)
        val groups = PickerGroups.build(listOf(copy, own).filter { it.takesSongs }, { it.id }, { it.placeKey }, { it.name }, { it.upkeep }, { false }, emptyList(), null, false)
        assertEquals("not in the picker", listOf("q"), groups.flatMap { g -> g.items.map { it.id } })
    }

    @Test
    fun theListenersOwnPlaylistChangedOnBothSidesIsPushedWithTheFamilyRule() {
        val linked = LinkedPlaylist("p", "srv", "Road", edited = true, syncedName = "Road", syncedStamp = "a|1", ownRules = true)
        assertEquals(PlaylistStep.Push("p", "srv"), planLinked(ServerPlaylist("srv", "Road", "b|1"), linked))
        val plain = linked.copy(ownRules = false)
        assertEquals(PlaylistStep.Pull("p", "srv", "b|1", keepName = false), planLinked(ServerPlaylist("srv", "Road", "b|1"), plain))
        val row = PlaylistEntity("p", "Road", 1, 5, serverId = "srv", sourceId = "server:h", syncedAt = 3)
        assertTrue(row.linked(ownRules = true)!!.ownRules)
        assertFalse("a family playlist keeps its own rule", row.copy(family = true).linked(ownRules = true)!!.ownRules)
    }

    @Test
    fun theOutsideRuleNamesTheServersOwnIdAndTheStampsChangedPart() {
        // A library song matched by title to x1: the server's own id goes.
        assertEquals("x1", outsideSongFor(Row("t2", "x1"), copies = listOf("s2"), libraryIds = emptySet()))
        // A library row from the server: its own copy.
        assertEquals("s1", outsideSongFor(Row("t1", "s1"), copies = listOf("s1"), libraryIds = setOf("s1")))
        // A song of the phone's own added here: its copy on the server.
        assertEquals("s4", outsideSongFor(Row("t4", null), copies = listOf("s4"), libraryIds = emptySet()))
        // A row whose old id the library still links to another song: today's rule (the song's copy).
        assertEquals("s5", outsideSongFor(Row("t5", "old"), copies = listOf("s5"), libraryIds = setOf("old")))
        // A find added here.
        assertEquals("f0", outsideSongFor(Row("find:f0", null), copies = emptyList(), libraryIds = emptySet()))
        assertEquals("2026-11-14T10:00:00.123Z", changedOf("2026-11-14T10:00:00.123Z|3"))
        assertEquals(null, changedOf("|3"))
        assertEquals(null, changedOf(null))
    }
}
