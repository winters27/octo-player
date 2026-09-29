package app.winters.octo.covers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCoversTest {
    @Test
    fun aCoverIsDrawnAtTheNextSizeUpInFineSteps() {
        assertEquals(32, coverSide(32))
        assertEquals(36, coverSide(33))
        assertEquals(128, coverSide(128))
        assertEquals(144, coverSide(129))
        assertEquals(576, coverSide(513))
        for (px in 16..2048) {
            val side = coverSide(px)
            assertTrue(side >= px)
            // Never more than an eighth bigger than asked, past the smallest.
            if (px >= 32) assertTrue("$px drawn at $side", side <= px * 1.125 + 1)
        }
    }

    @Test
    fun theFootLineSaysWhoseItIsOrHowBig() {
        assertEquals("12 songs", playlistCoverFooter(12, "winters", "winters"))
        assertEquals("1 song", playlistCoverFooter(1, null, "winters"))
        assertEquals("2,500 songs", playlistCoverFooter(2500, "Winters", "winters"))
        assertEquals("By sam", playlistCoverFooter(12, "sam", "winters"))
        assertNull(playlistCoverFooter(0, "winters", "winters"))
    }

    @Test
    fun coloursComeFromOneCoverPerAlbum() {
        val songs = listOf("a1" to "c1", "a1" to "c1", "a2" to null, "a3" to "c3", "a4" to "c4", "a5" to "c5", "a6" to "c6")
        assertEquals(listOf("c1", "c3", "c4", "c5"), coverSources(songs))
    }

    @Test
    fun paletteKeysFollowTheirCovers() {
        val key = coverPaletteKey("music.example", listOf("c1", "c2"))
        assertNotEquals(key, coverPaletteKey("music.example", listOf("c2", "c1")))
        assertNotEquals(key, coverPaletteKey("other.example", listOf("c1", "c2")))
        assertTrue(key.contains("v$COVER_PALETTE_VERSION"))
    }

    @Test
    fun fileNamesAreShortAndSafe() {
        val name = coverFileName("playlist-art:v1:1.2:pl/ü:1.2.3.4m:160:x", "png")
        assertTrue(name.matches(Regex("[0-9a-f]{32}\\.png")))
        assertEquals(name, coverFileName("playlist-art:v1:1.2:pl/ü:1.2.3.4m:160:x", "png"))
    }

    @Test
    fun theSettingSaysWhenEachIsForAndHasNoDashes() {
        for (style in PlaylistCoverStyle.entries) {
            val words = playlistCoverStyleName(style) + playlistCoverStyleHelp(style)
            assertTrue(words.none { it == '—' || it == '–' })
        }
        assertEquals("Designed", playlistCoverStyleName(PlaylistCoverStyle.Designed))
        assertEquals("Album mosaic", playlistCoverStyleName(PlaylistCoverStyle.Mosaic))
    }
}
