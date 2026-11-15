package app.winters.octo.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadNamingTest {
    // A lossy file at or under the cap comes as it is, whatever format
    // smaller copies are made as; lossless or above the cap is made smaller.
    @Test
    fun onlyWhatIsBiggerThanTheCapIsMadeSmaller() {
        assertNull(smallerKbps("audio/mpeg", 128_000, 128))
        assertNull(smallerKbps("audio/mp4", 128_000, 256))
        assertNull(smallerKbps("audio/flac", 900_000, null))
        assertEquals(128, smallerKbps("audio/flac", 900_000, 128))
        assertEquals(128, smallerKbps("audio/mpeg", 320_000, 128))
        // A lossy file of unknown bitrate is made smaller, since it may be bigger.
        assertEquals(128, smallerKbps("audio/mpeg", null, 128))
    }

    @Test
    fun theMarkSitsInsideTheLastBracket() {
        assertEquals("Drake - Hotline Bling [a1b2c3d4~s]", madeSmallerStem("Drake - Hotline Bling [a1b2c3d4]"))
        assertEquals("X [a1b2c3d4~s]", madeSmallerStem("X [a1b2c3d4~s]"))
        assertTrue(isMadeSmallerName("/music/Octo/Drake - Hotline Bling [a1b2c3d4~s].opus"))
        assertFalse(isMadeSmallerName("/music/Octo/Drake - Hotline Bling [a1b2c3d4].mp3"))
    }

    @Test
    fun aTitleCannotFakeTheMark() {
        // A title that ends in "~s]" still sits before the name's own bracket.
        assertFalse(isMadeSmallerName("Band - Song [b~s] [a1b2c3d4].flac"))
        assertFalse(isMadeSmallerName("Band - Song ~s].flac"))
    }
}
