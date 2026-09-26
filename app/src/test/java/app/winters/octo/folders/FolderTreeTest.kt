package app.winters.octo.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderTreeTest {
    // A song as the tree sees it: a title and where it sits.
    private data class Song(val title: String, val path: String)

    private fun tree(vararg songs: Song) =
        buildFolderTree(songs.toList(), { pathNames(it.path) }, compareBy { it.title })

    @Test
    fun pathNamesIgnoreSlashesAtEitherEnd() {
        assertEquals(listOf("Music", "Kavinsky", "Nightcall"), pathNames("Music/Kavinsky/Nightcall/"))
        assertEquals(listOf("Music"), pathNames("/Music//"))
        assertEquals(emptyList<String>(), pathNames(null))
        assertEquals(emptyList<String>(), pathNames(""))
    }

    @Test
    fun buildsNestedFoldersWithSongsAtEachLevel() {
        val root = tree(
            Song("b", "Music/Kavinsky/OutRun/"),
            Song("a", "Music/Kavinsky/OutRun/"),
            Song("single", "Music/Kavinsky/"),
            Song("teardrop", "Music/Massive Attack/Mezzanine/"),
        )
        val music = root.at(listOf("Music"))!!
        assertEquals(listOf("Kavinsky", "Massive Attack"), music.folders.map { it.name })
        val kavinsky = music.at(listOf("Kavinsky"))!!
        assertEquals(listOf("OutRun"), kavinsky.folders.map { it.name })
        assertEquals(listOf("single"), kavinsky.songs.map { it.title })
        // Songs follow the order given.
        assertEquals(listOf("a", "b"), kavinsky.at(listOf("OutRun"))!!.songs.map { it.title })
        assertNull(root.at(listOf("Music", "Nobody")))
    }

    @Test
    fun countsEverySongUnderAFolder() {
        val root = tree(
            Song("a", "Music/A/1/"),
            Song("b", "Music/A/1/"),
            Song("c", "Music/A/"),
            Song("d", "Music/B/"),
        )
        assertEquals(4, root.songCount)
        assertEquals(3, root.at(listOf("Music", "A"))!!.songCount)
        assertEquals(2, root.at(listOf("Music", "A", "1"))!!.songCount)
    }

    @Test
    fun allSongsFollowTheShownOrderFoldersFirst() {
        val root = tree(
            Song("own", "Music/A/"),
            Song("deep", "Music/A/Z/"),
            Song("inner", "Music/A/B/"),
        )
        // Inside folders first, in their order, then the folder's own songs.
        assertEquals(listOf("inner", "deep", "own"), root.at(listOf("Music", "A"))!!.allSongs().map { it.title })
    }

    @Test
    fun collapsesDownToWhereTheMusicIs() {
        val root = tree(Song("a", "Music/Rock/A/"), Song("b", "Music/Rock/B/"))
        val collapsed = root.collapsed()
        // "Music" and "Rock" hold only one folder each; "Rock" is where it splits.
        assertEquals(listOf("Music", "Rock"), collapsed.skipped)
        assertEquals("Rock", collapsed.name)
        assertEquals(listOf("A", "B"), collapsed.root.folders.map { it.name })
    }

    @Test
    fun collapsingStopsAtAFolderWithItsOwnSongs() {
        val root = tree(Song("a", "Music/"), Song("b", "Music/Inner/"))
        val collapsed = root.collapsed()
        assertEquals("Music", collapsed.name)
        assertEquals(listOf("a"), collapsed.root.songs.map { it.title })
    }

    @Test
    fun nothingIsSkippedWhenTheTopAlreadySplits() {
        val root = tree(Song("a", "Music/"), Song("b", "Download/"))
        val collapsed = root.collapsed()
        assertEquals(emptyList<String>(), collapsed.skipped)
        assertEquals("", collapsed.name)
        assertEquals(listOf("Download", "Music"), collapsed.root.folders.map { it.name })
    }

    @Test
    fun anEmptyTreeCollapsesToItself() {
        val collapsed = tree().collapsed()
        assertEquals(0, collapsed.root.songCount)
        assertEquals("", collapsed.name)
    }

    @Test
    fun foldersSortTheWayPeopleCount() {
        val root = tree(
            Song("a", "Disc 10/"),
            Song("b", "Disc 2/"),
            Song("c", "disc 1/"),
            Song("d", "Abba/"),
        )
        assertEquals(listOf("Abba", "disc 1", "Disc 2", "Disc 10"), root.folders.map { it.name })
    }

    @Test
    fun naturalOrderHandlesLeadingZerosAndTies() {
        val names = listOf("Track 010", "Track 9", "track 9", "Track 9b", "Track")
        assertEquals(listOf("Track", "Track 9", "track 9", "Track 9b", "Track 010"), names.sortedWith(NaturalOrder))
    }
}
