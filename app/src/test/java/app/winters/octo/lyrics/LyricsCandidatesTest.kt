package app.winters.octo.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsCandidatesTest {
    private fun timed(source: LyricsSource, vararg words: String, id: Long? = null) = Lyrics(
        synced = true,
        lines = words.mapIndexed { i, text -> LyricLine(startMs = i * 1_000L, text = text) },
        source = source,
        onlineId = id,
    )

    private val server = LyricsCandidate(LyricsPick.Own(LyricsSource.Server), CandidateOrigin.Server, timed(LyricsSource.Server, "server"))
    private val songFile = LyricsCandidate(LyricsPick.Own(LyricsSource.SongFile), CandidateOrigin.SongFile, timed(LyricsSource.SongFile, "tag"))
    private val lrcFile = LyricsCandidate(LyricsPick.Own(LyricsSource.LyricsFile), CandidateOrigin.LyricsFile, timed(LyricsSource.LyricsFile, "lrc"))

    private fun online(id: Long, origin: CandidateOrigin, vararg words: String) =
        LyricsCandidate(LyricsPick.Online(id), origin, timed(LyricsSource.Online, *words, id = id), title = "Song", artist = "Artist")

    private val match = online(10, CandidateOrigin.OnlineMatch, "match")
    private val searchA = online(11, CandidateOrigin.OnlineSearch, "search a")
    private val searchB = online(12, CandidateOrigin.OnlineSearch, "search b")

    private fun CandidateChoices.picks() = items.map { it.pick }

    @Test
    fun theSourcesComeInOrderWhateverOrderTheyArrive() {
        val list = candidateList(listOf(searchA, match, searchB, lrcFile, songFile, server), current = null, showing = null)
        assertEquals(listOf(server, songFile, lrcFile, match, searchA, searchB).map { it.pick }, list.picks())
        assertEquals(-1, list.showing)
    }

    @Test
    fun theChosenOneComesFirst() {
        val list = candidateList(listOf(server, songFile, lrcFile, match, searchA, searchB), current = LyricsPick.Online(12), showing = searchB.lyrics)
        assertEquals(listOf(searchB, server, songFile, lrcFile, match, searchA).map { it.pick }, list.picks())
        assertEquals(0, list.showing)
    }

    @Test
    fun withoutAKnownPickTheShowingLyricsAreFoundByTheirWords() {
        // Online lyrics saved before the library's number was kept.
        val showing = timed(LyricsSource.Online, "search a")
        val list = candidateList(listOf(server, match, searchA), current = null, showing = showing)
        assertEquals(listOf(searchA, server, match).map { it.pick }, list.picks())
        assertEquals(0, list.showing)
    }

    @Test
    fun aCopyFoundTwiceShowsOnce() {
        // The library's match is also among its search results.
        val again = online(10, CandidateOrigin.OnlineSearch, "match")
        // The .lrc file holds the same words and timing as the server's.
        val sameAsServer = lrcFile.copy(lyrics = timed(LyricsSource.LyricsFile, "server"))
        // Another record in the library with the very same lyrics.
        val duplicateRecord = online(13, CandidateOrigin.OnlineSearch, "match")
        val list = candidateList(listOf(again, server, sameAsServer, match, duplicateRecord, searchA), current = null, showing = null)
        assertEquals(listOf(server, match, searchA).map { it.pick }, list.picks())
        // The one kept is the one higher in the order.
        assertEquals(CandidateOrigin.OnlineMatch, list.items[1].origin)
    }

    @Test
    fun theShowingLyricsStayInTheListWhenNoSourceOffersThem() {
        // Picked from the library before, which cannot be reached now.
        val showing = timed(LyricsSource.Online, "picked before", id = 99)
        val list = candidateList(listOf(server, songFile), current = LyricsPick.Online(99), showing = showing)
        assertEquals(listOf(LyricsPick.Online(99), server.pick, songFile.pick), list.picks())
        assertEquals(CandidateOrigin.OnlineMatch, list.items.first().origin)
        assertEquals(0, list.showing)
    }

    @Test
    fun lyricsWithNoWordsAreLeftOut() {
        val empty = songFile.copy(lyrics = Lyrics(false, listOf(LyricLine(text = " ")), LyricsSource.SongFile))
        assertEquals(listOf(server.pick), candidateList(listOf(empty, server), current = null, showing = null).picks())
    }

    @Test
    fun thePreviewIsTheFirstTwoLinesWithWords() {
        val lyrics = Lyrics(
            synced = true,
            lines = listOf(LyricLine(startMs = 0, text = ""), LyricLine(startMs = 1, text = " One "), LyricLine(startMs = 2, text = "Two"), LyricLine(startMs = 3, text = "Three")),
            source = LyricsSource.Server,
        )
        assertEquals(listOf("One", "Two"), server.copy(lyrics = lyrics).preview)
    }
}
