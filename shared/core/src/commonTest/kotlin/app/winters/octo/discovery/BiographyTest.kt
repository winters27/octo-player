package app.winters.octo.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiographyTest {
    @Test
    fun theReadMoreLinkAndTagsGo() {
        val bio = "Vincent Belorgey, known as <b>Kavinsky</b>, is a French musician. " +
            "<a target='_blank' href=\"https://www.last.fm/music/Kavinsky\" rel=\"nofollow\">Read more on Last.fm</a>"
        assertEquals("Vincent Belorgey, known as Kavinsky, is a French musician.", cleanBiography(bio))
    }

    @Test
    fun aLinkInsideTheTextKeepsItsWords() {
        val bio = "Signed to <a href=\"https://example.test/label\">Record Makers</a> in 2006."
        assertEquals("Signed to Record Makers in 2006.", cleanBiography(bio))
    }

    @Test
    fun charactersAreWrittenOut() {
        assertEquals(
            "Daft Punk & Justice \"live\" at the café's <best> night",
            cleanBiography("Daft Punk &amp; Justice &quot;live&quot; at the caf&#233;&#x27;s &lt;best&gt;&nbsp;night"),
        )
        // "&amp;lt;" is the text "&lt;", not a "<".
        assertEquals("a &lt; b", cleanBiography("a &amp;lt; b"))
    }

    @Test
    fun spacingIsTidied() {
        assertEquals(
            "First paragraph.\n\nSecond one,\nwith a break.",
            cleanBiography("  First   paragraph.<br/><br />\n  Second one,<br>with a break.  "),
        )
    }

    @Test
    fun nothingLeftIsNoBiography() {
        assertNull(cleanBiography(""))
        assertNull(cleanBiography("   "))
        assertNull(cleanBiography("<a href=\"https://www.last.fm/music/X\">Read more on Last.fm</a>"))
    }
}
