package app.winters.octo.playlists

import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.subsonic.PlaylistPlace
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistFolderTreeTest {
    private fun folder(id: String, name: String, parent: String? = null) = PlaylistFolder(id, name, parent)

    private val tree = PlaylistFolderTree(
        listOf(
            folder("c", "cherry"), folder("a", "Apple"), folder("e", "Éclair"), folder("b", "banana"),
            folder("a1", "Road trips", "a"), folder("a2", "Summer", "a1"), folder("a3", "Night", "a2"), folder("a4", "Late", "a3"),
            folder("lost", "Lost", "gone"),
        ),
        listOf(PlaylistPlace("pl1", "a2"), PlaylistPlace("pl2", "gone"), PlaylistPlace("local:r1", "b")),
        Locale.ENGLISH,
    )

    @Test
    fun eachLevelGoesAToZInTheListenersLanguage() {
        assertEquals(listOf("Apple", "banana", "cherry", "Éclair", "Lost"), tree.children(null).map { it.name })
    }

    @Test
    fun aFolderWithAMissingParentOrALoopSitsAtTheTop() {
        assertNull(tree.parentOf("lost"))
        val looped = PlaylistFolderTree(listOf(folder("x", "X", "y"), folder("y", "Y", "x")), emptyList(), Locale.ENGLISH)
        assertEquals(listOf("X", "Y"), looped.children(null).map { it.name })
        assertEquals(1, looped.depth("x"))
    }

    @Test
    fun pathsDepthsAndPlaces() {
        assertEquals("Apple / Road trips / Summer", tree.pathName("a2"))
        assertEquals(3, tree.depth("a2"))
        assertEquals(5, tree.height("a"))
        assertEquals("a2", tree.folderOf("pl1"))
        assertNull(tree.folderOf("pl2"), "a placement in a missing folder counts as none")
        assertEquals(listOf("local:r1"), tree.playlistsIn("b", listOf("pl1", "pl2", "local:r1")))
        assertEquals(listOf("pl2"), tree.playlistsIn(null, listOf("pl1", "pl2", "local:r1")))
        assertEquals(setOf("a", "a1", "a2", "a3", "a4"), tree.subtree("a"))
    }

    @Test
    fun aFolderNeverGoesInsideItselfOrPastFourDeep() {
        assertEquals(FolderMoveRefusal.IntoItself, tree.moveRefusal("a1", "a3"))
        assertEquals(FolderMoveRefusal.IntoItself, tree.moveRefusal("a1", "a1"))
        assertEquals(FolderMoveRefusal.TooDeep, tree.moveRefusal("a1", "b"))
        assertNull(tree.moveRefusal("a3", "b"))
        assertNull(tree.moveRefusal("a3", null))
        assertFalse(tree.canHoldFolder("a3"))
        assertTrue(tree.canHoldFolder("a2"))
        assertTrue(tree.canHoldFolder(null))
        assertEquals(PlaylistFolderWords.INTO_ITSELF, FolderMoveRefusal.IntoItself.words)
        assertEquals(PlaylistFolderWords.TOO_DEEP, FolderMoveRefusal.TooDeep.words)
    }

    @Test
    fun treeOrderWalksEachLevelAToZ() {
        assertEquals(
            listOf("Apple" to 1, "Road trips" to 2, "Summer" to 3, "Night" to 4, "Late" to 5, "banana" to 1, "cherry" to 1, "Éclair" to 1, "Lost" to 1),
            tree.inTreeOrder().map { (f, d) -> f.name to d },
        )
    }

    @Test
    fun idsNamesAndWhatCanBeFiled() {
        val id = PlaylistFolderTree.newId(Random(7))
        assertEquals(22, id.length)
        assertTrue(PlaylistFolderTree.isId(id))
        assertFalse(PlaylistFolderTree.isId("pf-not-an-id"))
        assertEquals("Night drives", PlaylistFolderTree.cleanName("  Night drives "))
        assertNull(PlaylistFolderTree.cleanName("   "))
        assertNull(PlaylistFolderTree.cleanName("x".repeat(101)))
        assertEquals("😀".repeat(100), PlaylistFolderTree.cleanName("😀".repeat(100)))
        assertFalse(PlaylistFolderTree.fileable("or1234567890123456789A"))
        assertFalse(PlaylistFolderTree.fileable("og1234567890123456789A"))
        assertTrue(PlaylistFolderTree.fileable("oi1234567890123456789A"))
        assertTrue(PlaylistFolderTree.fileable("of1234567890123456789A"))
        assertTrue(PlaylistFolderTree.fileable("local:abc"))
        assertEquals("local:abc", PlaylistFolderTree.localKey("abc"))
    }

    @Test
    fun theWords() {
        assertEquals("1 playlist", PlaylistFolderWords.folderSubtitle(1, 0))
        assertEquals("1,234 playlists, 2 folders", PlaylistFolderWords.folderSubtitle(1234, 2))
        assertEquals("0 playlists, 1 folder", PlaylistFolderWords.folderSubtitle(0, 1))
        assertEquals("Review has 3 songs, Duplicates has 1 song", PlaylistFolderWords.upkeepNote(listOf("Review" to 3, "Empty" to 0, "Duplicates" to 1)))
        assertNull(PlaylistFolderWords.upkeepNote(listOf("Review" to 0)))
        assertEquals("Its playlists move to the top level. No playlist is deleted.", PlaylistFolderWords.deleteBody(null))
        assertEquals("Its playlists move to Apple. No playlist is deleted.", PlaylistFolderWords.deleteBody("Apple"))
        assertEquals("Delete \"Apple\"?", PlaylistFolderWords.deleteTitle("Apple"))
        assertEquals("Move here (3)", PlaylistFolderWords.moveHere(3))
        assertEquals(
            "This playlist changed on your server and on this phone. Your phone's version was saved as \"Road (from this phone)\".",
            PlaylistFolderWords.bothChanged("Road"),
        )
    }
}
