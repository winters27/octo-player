package app.winters.octo.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumLabelTest {
    @Test
    fun anAlbumShowsItsName() {
        assertEquals("Collide With the Sky", albumLabel("Collide With the Sky", "Bulls in the Bronx", 12))
    }

    @Test
    fun aOneSongReleaseIsASingle() {
        assertNull(albumLabel("Late Night Drive", "Something Else", 1))
    }

    @Test
    fun aReleaseNamedAfterTheSongIsASingle() {
        assertNull(albumLabel("Blinding Lights", "Blinding Lights", 2))
        assertNull(albumLabel("BLINDING LIGHTS", "Blinding Lights (feat. Someone)", 3))
        assertNull(albumLabel("Blinding Lights - Single", "Blinding Lights", 2))
        assertNull(albumLabel("Blinding Lights [Remixes] - EP", "Blinding Lights", 4))
    }

    @Test
    fun punctuationAndAccentsDoNotMakeAnotherName() {
        assertNull(albumLabel("Don’t Stop Me Now", "Dont Stop Me Now", 3))
        assertNull(albumLabel("Déjà Vu - EP", "Deja Vu", 4))
        assertEquals("Déjà Vu", albumLabel("Déjà Vu", "Deja Vu Again", 4))
    }

    @Test
    fun aSingleSuffixAlwaysHides() {
        assertNull(albumLabel("Summer Songs - Single", "Other Title", 2))
    }

    @Test
    fun aTitleTrackOnARealAlbumStillMatchesAndHides() {
        // Repeating the name would still say the same thing twice.
        assertNull(albumLabel("Nothing Was the Same", "Nothing Was the Same", 13))
    }

    @Test
    fun noAlbumShowsNothing() {
        assertNull(albumLabel(null, "Song", 5))
        assertNull(albumLabel("  ", "Song", 5))
    }
}
