package app.winters.octo.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Lyrics files beside a song, and the saved answers on the phone.
class LyricsStorageTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun lyricsFilesShareTheSongsName() {
        assertEquals(listOf("01 Song.lrc", "01 Song.LRC"), lyricsFileNames("01 Song.flac"))
        assertEquals(listOf("a.b.lrc", "a.b.LRC"), lyricsFileNames("a.b.mp3"))
        assertTrue(lyricsFileNames("noextension").isEmpty())
    }

    @Test
    fun filesThatAreNotUtf8AreReadAsLatin() {
        assertEquals("Café", decodeText("Café".toByteArray(Charsets.UTF_8)))
        assertEquals("Café", decodeText(byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte())))
    }

    @Test
    fun cachedAnswersKeepAndExpire() {
        val cache = LyricsCache(temp.newFolder("lyrics"))
        val lyrics = Lyrics(synced = true, lines = listOf(LyricLine(1_000, text = "Café 日本")), source = LyricsSource.SongFile)
        cache.write("device:1", CachedLyrics(savedAt = 0, lyrics = lyrics))
        assertEquals(lyrics, cache.read("device:1")?.lyrics)
        assertNull(cache.read("find:other"))

        val found = CachedLyrics(savedAt = 0, lyrics = lyrics)
        assertTrue(found.stillGood(now = 365L * NONE_FOUND_FOR_MS, onlineAllowed = true))

        val none = CachedLyrics(savedAt = 0, lyrics = null, askedOnline = true)
        assertTrue(none.stillGood(now = NONE_FOUND_FOR_MS - 1, onlineAllowed = true))
        assertFalse(none.stillGood(now = NONE_FOUND_FOR_MS, onlineAllowed = true))

        // Found without asking online, and online is now allowed: asked again
        // unless what was found is already synced.
        val offlineNone = CachedLyrics(savedAt = 0, lyrics = null, askedOnline = false)
        assertTrue(offlineNone.stillGood(now = 1, onlineAllowed = false))
        assertFalse(offlineNone.stillGood(now = 1, onlineAllowed = true))
        val offlinePlain = CachedLyrics(savedAt = 0, lyrics = lyrics.copy(synced = false), askedOnline = false)
        assertFalse(offlinePlain.stillGood(now = 1, onlineAllowed = true))

        // Plain lyrics from an older online lookup are asked again once, in
        // case the newer lookup finds synced ones; from the current one, kept.
        val oldPlain = CachedLyrics(savedAt = 0, lyrics = lyrics.copy(synced = false), askedOnline = true)
        assertFalse(oldPlain.stillGood(now = 1, onlineAllowed = true))
        assertTrue(oldPlain.copy(lookupVersion = ONLINE_LOOKUP_VERSION).stillGood(now = 1, onlineAllowed = true))

        // Online lyrics from the lookup that matched by length alone are asked
        // again once, even when timed, since they may be another song's.
        val looseOnline = CachedLyrics(savedAt = 0, lyrics = lyrics.copy(source = LyricsSource.Online), askedOnline = true, lookupVersion = 2)
        assertFalse(looseOnline.stillGood(now = 1, onlineAllowed = true))
        assertTrue(looseOnline.copy(lookupVersion = ONLINE_LOOKUP_VERSION).stillGood(now = 1, onlineAllowed = true))
        // Lyrics from the song file are never asked again for this.
        assertTrue(looseOnline.copy(lyrics = lyrics).stillGood(now = 1, onlineAllowed = true))
    }
}
