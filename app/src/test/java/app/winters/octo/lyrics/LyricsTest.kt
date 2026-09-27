package app.winters.octo.lyrics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val lines = listOf(
        LyricLine(startMs = 1_000, text = "one"),
        LyricLine(startMs = 4_000, text = "two"),
        LyricLine(startMs = 4_000, text = "two again"),
        LyricLine(startMs = 9_000, text = "three"),
    )

    @Test
    fun theLineBeingSungIsTheLastOneStarted() {
        assertEquals(-1, lines.lineAt(0))
        assertEquals(-1, lines.lineAt(999))
        assertEquals(0, lines.lineAt(1_000))
        assertEquals(0, lines.lineAt(3_999))
        // Two lines at one time: the later of them.
        assertEquals(2, lines.lineAt(4_000))
        assertEquals(3, lines.lineAt(9_000))
        assertEquals(3, lines.lineAt(600_000))
        assertEquals(-1, emptyList<LyricLine>().lineAt(5_000))
    }

    @Test
    fun longWaitsGetAGapLine() {
        val lyrics = Lyrics(
            synced = true,
            lines = listOf(
                LyricLine(startMs = 8_000, endMs = 10_000, text = "late start"),
                LyricLine(startMs = 20_000, text = "after a wait"),
            ),
            source = LyricsSource.Server,
        )
        val shown = lyrics.shownLines()
        assertEquals(listOf(0L, 8_000L, 10_000L, 20_000L), shown.map { it.startMs })
        assertTrue(shown[0].isGap)
        assertTrue(shown[2].isGap)
        assertEquals(20_000L, shown.endOf(2))
    }

    @Test
    fun songFileTagsPreferTimedText() {
        val tags = mapOf(
            "LYRICS" to arrayOf("Plain words"),
            "UNSYNCEDLYRICS" to arrayOf("[00:01.00]Timed words"),
        )
        assertEquals("[00:01.00]Timed words", songFileLyrics(tags))
        assertEquals("Described", songFileLyrics(mapOf("lyrics:eng" to arrayOf("Described"))))
        assertNull(songFileLyrics(mapOf("TITLE" to arrayOf("Not lyrics"), "LYRICS" to arrayOf(" "))))
    }

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
    fun theOnlineLookupSendsOnlyWhatItNeeds() {
        val url = lookupUrl("https://lyrics.example".toHttpUrl(), "Song", "Artist", "", 201_600)
        assertEquals("/api/get", url.encodedPath)
        assertEquals("Song", url.queryParameter("track_name"))
        assertEquals("Artist", url.queryParameter("artist_name"))
        assertEquals("202", url.queryParameter("duration"))
        assertNull(url.queryParameter("album_name"))
        assertEquals(3, url.querySize)
    }

    @Test
    fun theOnlineAnswerPrefersSyncedLyrics() {
        val both = libraryLyrics("""{"id":1,"instrumental":false,"plainLyrics":"Hello","syncedLyrics":"[00:01.00] Hello"}""")
        assertTrue(both!!.synced)
        assertEquals(LyricsSource.Online, both.source)
        val plainOnly = libraryLyrics("""{"plainLyrics":"Hello","syncedLyrics":null}""")
        assertFalse(plainOnly!!.synced)
        assertTrue(libraryLyrics("""{"instrumental":true}""")!!.instrumental)
        assertNull(libraryLyrics("""{"plainLyrics":null,"syncedLyrics":null}"""))
        assertNull(libraryLyrics("not json"))
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

    @Test
    fun searchFindsATimedCopyOfTheSameRecording() {
        val body = """[
            {"trackName":"Song","artistName":"Artist","albumName":"Other","duration":201.0,"plainLyrics":"Hi","syncedLyrics":null},
            {"trackName":"Song","artistName":"Artist","albumName":"Live","duration":260.0,"syncedLyrics":"[00:01.00] Live take"},
            {"trackName":"Song","artistName":"Artist","albumName":"Other","duration":202.5,"syncedLyrics":"[00:01.00] Close"},
            {"trackName":"song!","artistName":"ARTIST","albumName":"Album","duration":203.9,"syncedLyrics":"[00:01.00] Same album"}
        ]"""
        // Same album wins among copies of the right length; the live one is too long.
        assertEquals("Same album", bestSearchMatch(body, "Song", "Artist", "Album", 201_600)!!.lines.first().text)
        assertEquals("Close", bestSearchMatch(body, "Song", "Artist", "Nothing like it", 201_600)!!.lines.first().text)
        assertNull(bestSearchMatch("""[{"trackName":"Song","artistName":"Artist","duration":201.0,"plainLyrics":"Hi"}]""", "Song", "Artist", "", 201_000))
        assertNull(bestSearchMatch("not json", "Song", "Artist", "", 0))
    }

    @Test
    fun searchNeverTakesAnotherSongOfTheSameLength() {
        // What the library answered for "$UICIDE": other songs by the same
        // artist, one of them within a second of its length.
        val body = """[
            {"trackName":"Ultimate ${'$'}uicide","artistName":"${'$'}uicideboy${'$'}","albumName":"High Tide","duration":170.0,"syncedLyrics":"[00:01.00] Wrong song"},
            {"trackName":"Black ${'$'}uicide","artistName":"${'$'}uicideboy${'$'}","albumName":"Black ${'$'}uicide","duration":170.0,"syncedLyrics":"[00:01.00] Also wrong"}
        ]"""
        assertNull(bestSearchMatch(body, "${'$'}UICIDE", "${'$'}uicideboy${'$'}", "Sing Me a Lullaby", 169_000))
        // Another artist's song of the same name is not it either.
        val other = """[{"trackName":"Song","artistName":"Someone Else","duration":200.0,"syncedLyrics":"[00:01.00] Theirs"}]"""
        assertNull(bestSearchMatch(other, "Song", "Artist", "", 200_000))
        // Nor a remix of it.
        val remix = """[{"trackName":"Song (Club Remix)","artistName":"Artist","duration":200.0,"syncedLyrics":"[00:01.00] Remix"}]"""
        assertNull(bestSearchMatch(remix, "Song", "Artist", "", 200_000))
    }

    @Test
    fun searchAsksByTitleAndArtistOnly() {
        val url = searchUrl("https://lyrics.example".toHttpUrl(), "Song", "Artist")
        assertEquals("/api/search", url.encodedPath)
        assertEquals("Song", url.queryParameter("track_name"))
        assertEquals("Artist", url.queryParameter("artist_name"))
        assertNull(url.queryParameter("duration"))
    }
}
