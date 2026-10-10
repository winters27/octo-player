package app.winters.octo.desktop.library

import app.winters.octo.desktop.ui.SongAction
import app.winters.octo.desktop.ui.SongPlace
import app.winters.octo.desktop.ui.songMenuActions
import app.winters.octo.subsonic.Song
import app.winters.octo.ui.family.offersRemoveOn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Remove from my library goes on the songs the server marks as the
// member's own, or on every library song when the server marks none.
class PersonalSongsTest {
    private fun offered(songs: List<Song>, index: LibraryIndex): Boolean =
        songMenuActions(songs.size, SongPlace.Library, canRemoveFromMine = songs.all { offersRemoveOn(it.octoPersonal, index.marksPersonal) })
            .flatten().contains(SongAction.RemoveFromMyLibrary)

    @Test
    fun aServerThatMarksGetsItOnlyOnTheMembersSongs() {
        val mine = Song("tr-1", "Mine", octoPersonal = true)
        val shared = Song("tr-2", "Shared")
        val index = LibraryIndex(listOf(mine, shared), emptyList(), emptyList())
        assertTrue(index.marksPersonal)
        assertTrue(offered(listOf(mine), index))
        assertFalse(offered(listOf(shared), index))
        // Picked together, a shared song keeps it off.
        assertFalse(offered(listOf(mine, shared), index))
    }

    @Test
    fun aServerThatMarksNoneGetsItOnEveryLibrarySong() {
        val index = LibraryIndex(listOf(Song("tr-1", "One"), Song("tr-2", "Two")), emptyList(), emptyList())
        assertFalse(index.marksPersonal)
        assertTrue(offered(index.songs, index))
    }
}
