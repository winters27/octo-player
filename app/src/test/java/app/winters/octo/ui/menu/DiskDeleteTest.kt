package app.winters.octo.ui.menu

import app.winters.octo.health.deleteBody
import app.winters.octo.health.deleteTitle
import org.junit.Assert.assertEquals
import org.junit.Test

// The question before songs leave the server's disk, in the desktop's words.
class DiskDeleteTest {
    @Test
    fun oneSongIsNamedAndMoreAreCounted() {
        assertEquals("Teardrop", deleteNames(listOf("Teardrop")))
        assertEquals("3 songs", deleteNames(listOf("One", "Two", "Three")))
    }

    @Test
    fun theQuestionSaysWhatGoesAndForHowLongTheServerKeepsIt() {
        assertEquals("Delete this song from disk?", deleteTitle(1))
        assertEquals("Delete 3 songs from disk?", deleteTitle(3))
        assertEquals(
            "This takes Teardrop off the server's disk and out of your library. The server keeps the file in its trash for 30 days, " +
                "so you can put it back from Library health. Octo will not download it again by itself.",
            deleteBody(deleteNames(listOf("Teardrop")), 30),
        )
        assertEquals(
            "This takes 2 songs off the server's disk and out of your library. The server keeps the file in its trash until someone clears it, " +
                "so you can put it back from Library health. Octo will not download it again by itself.",
            deleteBody(deleteNames(listOf("One", "Two")), 0),
        )
    }
}
