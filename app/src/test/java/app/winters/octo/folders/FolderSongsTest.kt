package app.winters.octo.folders

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSongsTest {
    // A pretend server: folder id to what it holds.
    private val folders = mapOf(
        "artist" to FolderContents(listOf("album1", "album2"), listOf("artist-loose")),
        "album1" to FolderContents(emptyList(), listOf("a1", "a2", "a3")),
        "album2" to FolderContents(listOf("bonus"), listOf("b1", "b2")),
        "bonus" to FolderContents(emptyList(), listOf("x1")),
    )
    private var opened = mutableListOf<String>()

    private suspend fun open(id: String): FolderContents<String> {
        opened += id
        return folders.getValue(id)
    }

    @Test
    fun gathersEverythingInTheShownOrder() = runTest {
        val got = gatherSongs(folders.getValue("artist"), limit = 100, maxFolders = 100, open = ::open)
        assertEquals(listOf("a1", "a2", "a3", "x1", "b1", "b2", "artist-loose"), got.songs)
        assertFalse(got.limitReached)
    }

    @Test
    fun stopsAtTheSongLimitAndSaysSo() = runTest {
        val got = gatherSongs(folders.getValue("artist"), limit = 4, maxFolders = 100, open = ::open)
        assertEquals(listOf("a1", "a2", "a3", "x1"), got.songs)
        assertTrue(got.limitReached)
        // Nothing past the limit is asked for.
        assertEquals(listOf("album1", "album2", "bonus"), opened)
    }

    @Test
    fun exactlyTheLimitWithNothingLeftIsNotCut() = runTest {
        val got = gatherSongs(folders.getValue("album1"), limit = 3, maxFolders = 100, open = ::open)
        assertEquals(3, got.songs.size)
        assertFalse(got.limitReached)
    }

    @Test
    fun stopsAfterTheFolderLimit() = runTest {
        val got = gatherSongs(folders.getValue("artist"), limit = 100, maxFolders = 1, open = ::open)
        assertEquals(listOf("a1", "a2", "a3"), got.songs)
        assertTrue(got.limitReached)
        assertEquals(listOf("album1"), opened)
    }

    @Test
    fun aFolderListedTwiceIsOpenedOnce() = runTest {
        val start = FolderContents(listOf("album1", "album1"), emptyList<String>())
        val got = gatherSongs(start, limit = 100, maxFolders = 100, open = ::open)
        assertEquals(listOf("a1", "a2", "a3"), got.songs)
        assertEquals(listOf("album1"), opened)
    }

    @Test
    fun anEmptyFolderGathersNothing() = runTest {
        val got = gatherSongs(FolderContents(emptyList(), emptyList<String>()), limit = 10, maxFolders = 10, open = ::open)
        assertTrue(got.songs.isEmpty())
        assertFalse(got.limitReached)
    }

    @Test
    fun namesTheServerPeopleWouldKnow() {
        assertEquals("Octo", serverName(isOcto = true, type = "navidrome", host = "music.example"))
        assertEquals("Navidrome", serverName(isOcto = false, type = "navidrome", host = "music.example"))
        assertEquals("music.example", serverName(isOcto = false, type = " ", host = "music.example"))
        assertEquals("music.example", serverName(isOcto = false, type = null, host = "music.example"))
    }
}
