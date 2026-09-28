package app.winters.octo.desktop.library

import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Which songs are in the library and which were found online, and how an
// album that has only some of them says so.
class OwnershipTest {
    private val mine = Song("Ab3dE5fG7hJ9kL1mN3pQ5r", "Buy Myself a Chance", artist = "Randy Rogers Band", duration = 231)
    private val index = LibraryIndex(listOf(mine), emptyList(), emptyList())

    // Octo's online songs have ids that look like the library's, so only
    // the mark tells them apart.
    private val marked = Song("mXFKjv7oqx1HTOzoJP1nk3", "One Woman (Album Version)", artist = "Randy Rogers Band", isExternal = true)

    @Test
    fun aSongOctoMarksIsOutsideTheLibrary() {
        assertTrue(isOutsideSong(marked, index, canFetch = true))
        // Even before the library has been read, and on a server that
        // cannot fetch it.
        assertTrue(isOutsideSong(marked, null, canFetch = false))
    }

    @Test
    fun aLibrarySongIsNot() {
        assertFalse(isOutsideSong(mine, index, canFetch = true))
        assertFalse(isOutsideSong(mine.copy(isExternal = false), null, canFetch = true))
    }

    @Test
    fun aSongWithNoMarkTheLibraryLacksIsOutsideOnlyWhereTheServerCanFetch() {
        val find = Song("q2W4e6R8t0Y2u4I6o8P0a2", "Too Late for Goodbyes", artist = "Randy Rogers Band")
        assertTrue(isOutsideSong(find, index, canFetch = true))
        assertFalse(isOutsideSong(find, index, canFetch = false))
        // Not known until the library has been read.
        assertFalse(isOutsideSong(find, null, canFetch = true))
    }

    @Test
    fun aFileOpenedFromThisComputerIsNeverOutside() {
        assertFalse(isOutsideSong(Song("file:abc", "Local"), index, canFetch = true))
    }

    @Test
    fun theOutsideSongsOfAListAreFoundByTheirIds() {
        assertEquals(setOf(marked.id), outsideIds(listOf(mine, marked), index, canFetch = true))
    }

    @Test
    fun aListTheServerMarkedIsTakenAtItsWord() {
        // Added to the library after it was last read: the index lacks it,
        // but the server, marking the song beside it, says it is in.
        val justAdded = Song("Zz9yX8wV7uT6sR5qP4oN3m", "Pyramid Song", artist = "Radiohead")
        assertEquals(setOf(marked.id), outsideIds(listOf(mine, justAdded, marked), index, canFetch = true))
    }

    @Test
    fun aListWithNoMarksFallsBackOnTheLibrary() {
        val find = Song("q2W4e6R8t0Y2u4I6o8P0a2", "Too Late for Goodbyes", artist = "Randy Rogers Band")
        assertEquals(setOf(find.id), outsideIds(listOf(mine, find), index, canFetch = true))
        assertEquals(emptySet<String>(), outsideIds(listOf(mine, find), index, canFetch = false))
    }

    @Test
    fun aWholeAlbumSaysNothing() {
        assertNull(libraryShare(total = 12, owned = 12))
        assertNull(libraryShare(total = 0, owned = 0))
    }

    @Test
    fun aPartlyOwnedAlbumSaysHowMuchIsInAndOffersTheRest() {
        assertEquals(LibraryShare("1 of 12 in your library", "Add the 11 missing songs"), libraryShare(total = 12, owned = 1))
        assertEquals(LibraryShare("11 of 12 in your library", "Add the missing song"), libraryShare(total = 12, owned = 11))
    }

    @Test
    fun anAlbumWithNoneInTheLibraryOffersThemAll() {
        assertEquals(LibraryShare("Not in your library", "Add all 12"), libraryShare(total = 12, owned = 0))
    }

    @Test
    fun songsOnTheirWayAreCountedAndNotOfferedAgain() {
        assertEquals(LibraryShare("1 of 12 in your library, 3 on the way", "Add the 8 missing songs"), libraryShare(total = 12, owned = 1, coming = 3))
        assertEquals(LibraryShare("1 of 12 in your library, 11 on the way", null), libraryShare(total = 12, owned = 1, coming = 11))
        assertEquals(LibraryShare("Not in your library, 2 on the way", "Add the 10 missing songs"), libraryShare(total = 12, owned = 0, coming = 2))
    }
}
