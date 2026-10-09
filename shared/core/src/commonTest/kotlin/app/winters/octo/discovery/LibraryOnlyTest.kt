package app.winters.octo.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOnlyTest {
    @Test
    fun onlyLibrarySongsStayInTheirOrder() {
        val songs = listOf("a", "find:b", "c", "find:d")
        assertEquals(listOf("a", "c"), librarySongsOnly(songs, hide = true) { it.startsWith("find:") })
        assertSame(songs, librarySongsOnly(songs, hide = false) { it.startsWith("find:") })
    }

    @Test
    fun theWordsHaveNoDashes() {
        for (words in listOf(LIBRARY_ONLY_SETTING, LIBRARY_ONLY_HELP, ONLY_MY_SONGS)) {
            assertTrue(words.none { it.code == 0x2014 || it.code == 0x2013 })
        }
    }
}
