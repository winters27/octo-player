package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class KeysTest {
    @Test
    fun accentsAndCaseAreIgnored() {
        assertEquals("beyonce", searchKey("Beyoncé"))
        assertEquals("motley crue", searchKey(" Mötley Crüe "))
    }

    @Test
    fun leadingArticleIsDroppedForSorting() {
        assertEquals("beatles", sortKey("The Beatles"))
        assertEquals("tribe called quest", sortKey("A Tribe Called Quest"))
        // A name that is only an article, or merely starts with the letters, stays whole.
        assertEquals("the", sortKey("The"))
        assertEquals("theory", sortKey("Theory"))
    }
}
