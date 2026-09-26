package app.winters.octo.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFilesTest {
    private val source = "server:music.example"

    @Test
    fun aNameReadsAsArtistAndTitle() {
        val name = downloadFileName("Drake", "Hotline Bling", source, "tr-1", "audio/flac")
        assertTrue(name, name.matches(Regex("Drake - Hotline Bling \\[[0-9a-f]{8}]\\.flac")))
    }

    @Test
    fun charactersNoFileSystemTakesAreLeftOut() {
        val name = downloadFileName("AC/DC", "What? <Live>: \"Take\" 1|2*\\", source, "tr-2", "audio/mpeg")
        val stem = name.substringBefore(" [")
        assertFalse(name, stem.any { it in "\\/:*?\"<>|" })
        assertEquals("AC DC - What Live Take 1 2", stem.replace(Regex("\\s+"), " "))
        assertTrue(name.endsWith(".mp3"))
    }

    @Test
    fun controlCharactersAndDotsAtTheEndsGo() {
        val name = downloadFileName("  ..Band\u0000\n", "Song...", source, "tr-3", null)
        assertTrue(name, name.startsWith("Band - Song ["))
        assertTrue(name.endsWith(".audio"))
    }

    @Test
    fun songsWithTheSameNameNeverShareAFile() {
        val one = downloadFileName("Artist", "Intro", source, "tr-1", "audio/flac")
        val two = downloadFileName("Artist", "Intro", source, "tr-2", "audio/flac")
        val other = downloadFileName("Artist", "Intro", "server:other.example", "tr-1", "audio/flac")
        assertNotEquals(one, two)
        assertNotEquals(one, other)
        // And the same song always has the same name.
        assertEquals(one, downloadFileName("Artist", "Intro", source, "tr-1", "audio/flac"))
    }

    @Test
    fun longNamesAreCutAndEmptyOnesStillName() {
        val long = downloadFileName("A".repeat(200), "B".repeat(200), source, "tr-9", "audio/ogg")
        assertTrue(long.length <= 80 + " [12345678].ogg".length)
        assertTrue(downloadFileName("", "", source, "tr-0", "audio/opus").startsWith("Song ["))
    }

    @Test
    fun theEndingFollowsTheType() {
        assertEquals("mp3", extensionFor("audio/mpeg"))
        assertEquals("flac", extensionFor("audio/x-flac"))
        assertEquals("m4a", extensionFor("audio/mp4"))
        assertEquals("opus", extensionFor("audio/opus"))
        assertEquals("ogg", extensionFor("AUDIO/OGG; codecs=vorbis"))
        assertEquals("audio", extensionFor("application/octet-stream"))
    }
}
