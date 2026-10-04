package app.winters.octo.desktop.search

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.songJson
import app.winters.octo.subsonic.Acquisition
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.SearchResult
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val server = FakeServer()

    @After fun stop() = server.close()

    private val library = LibraryIndex(
        songs = listOf(Song("lib1", "Karma Police", artist = "Radiohead", duration = 264)),
        albums = listOf(Album("alb1", "OK Computer")),
        artists = listOf(Artist("art1", "Radiohead")),
    )

    private val sent = SearchResult(
        artist = listOf(Artist("art1", "Radiohead"), Artist("ext-art", "Radiohead Tribute")),
        album = listOf(Album("alb1", "OK Computer"), Album("ext-alb", "Kid A Mnesia")),
        song = listOf(Song("lib1", "Karma Police", artist = "Radiohead"), Song("ext1", "Lift", artist = "Radiohead", duration = 250)),
    )

    // Brandon, 2026-10-04: some of his songs in search had checkmarks, some
    // did not, though all were his. Octo had sent an online copy of a song
    // he owns; it went among his songs, marked, so that one row had a "+"
    // and every other row a check. The library's songs are now only his.
    @Test
    fun anOnlineCopyOfALibrarySongIsTheLibrarysOwn() {
        val copy = Song("ext-kp", "Karma Police", artist = "Radiohead", duration = 264, isExternal = true)
        val withCopy = sent.copy(song = listOf(copy, Song("lib1", "Karma Police", artist = "Radiohead", duration = 264), Song("ext1", "Lift", artist = "Radiohead", duration = 250, isExternal = true)))
        val found = splitResults(withCopy, "radiohead", SearchFilter.All, emptyList(), library, outsideOn = true)
        assertEquals(listOf("lib1"), found.library.songs.map { it.id })
        assertFalse(found.library.songs.any { it.isExternal })
        assertEquals(listOf("ext1"), found.outside.songs.map { it.id })
        // Alone, it still stands for the library's song.
        val alone = splitResults(sent.copy(song = listOf(copy)), "radiohead", SearchFilter.All, emptyList(), library, outsideOn = true)
        assertEquals(listOf("lib1"), alone.library.songs.map { it.id })
    }

    @Test
    fun anOctoServerSplitsOutWhatIsNotInTheLibrary() {
        val found = splitResults(sent, "radiohead", SearchFilter.All, emptyList(), library, outsideOn = true)
        assertEquals(listOf("lib1"), found.library.songs.map { it.id })
        assertEquals(listOf("alb1"), found.library.albums.map { it.id })
        assertEquals(listOf("art1"), found.library.artists.map { it.id })
        assertEquals(listOf("ext1"), found.outside.songs.map { it.id })
        assertEquals(listOf("ext-alb"), found.outside.albums.map { it.id })
        assertEquals(listOf("ext-art"), found.outside.artists.map { it.id })
    }

    @Test
    fun anyOtherServerShowsEverythingAsTheLibrary() {
        val found = splitResults(sent, "radiohead", SearchFilter.All, emptyList(), library, outsideOn = false)
        assertEquals(2, found.library.songs.size)
        assertTrue(found.outside.isEmpty)
    }

    @Test
    fun beforeTheLibraryIsReadNothingIsCalledOutside() {
        val found = splitResults(sent, "radiohead", SearchFilter.All, emptyList(), index = null, outsideOn = true)
        assertTrue(found.outside.isEmpty)
        assertEquals(2, found.library.albums.size)
    }

    @Test
    fun aFilterShowsOnlyItsKind() {
        val found = splitResults(sent, "radiohead", SearchFilter.Albums, emptyList(), library, outsideOn = true)
        assertTrue(found.library.songs.isEmpty())
        assertTrue(found.outside.songs.isEmpty())
        assertEquals(listOf("ext-alb"), found.outside.albums.map { it.id })
    }

    @Test
    fun playlistsMatchByNameAccentsAndCaseIgnored() {
        val lists = listOf(Playlist("p1", "Café Radio"), Playlist("p2", "Gym"))
        val found = splitResults(SearchResult(), "cafe", SearchFilter.All, lists, library, outsideOn = false)
        assertEquals(listOf("p1"), found.library.playlists.map { it.id })
    }

    @Test
    fun moreThanFitsOffersSeeAll() {
        val many = SearchResult(album = (1..21).map { Album("a$it", "Album $it") })
        val found = splitResults(many, "album", SearchFilter.All, emptyList(), null, outsideOn = false)
        assertEquals(20, found.library.albums.size)
        assertTrue(found.library.moreAlbums)
        assertFalse(found.library.moreSongs)
    }

    @Test
    fun theSameCapsAsThePhone() {
        assertEquals(SearchCaps(10, 20, 50, 10), searchCaps(SearchFilter.All))
        assertEquals(1_000, searchCaps(SearchFilter.Songs).songs)
    }

    @Test
    fun typingWaitsForAPauseAndAsksOnce() = runBlocking {
        server.answer("search3", """"searchResult3":{"song":[${songJson("s1", "Lift")}]}""")
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val model = SearchModel(server.connection(), { null }, { emptyList() }, scope)
        model.type("l")
        assertEquals(SearchState.Idle, model.state)
        model.type("li")
        model.type("lif")
        assertEquals(SearchState.Looking, model.state)
        withTimeout(5_000) { while (model.state !is SearchState.Done) delay(20) }
        val done = model.state as SearchState.Done
        assertEquals(listOf("s1"), done.found.library.songs.map { it.id })
        assertEquals(listOf("search3"), server.endpoints())
        val asked = server.calls.single().url
        assertEquals("lif", asked.queryParameter("query"))
        assertTrue("always more than Octo holds back for", asked.queryParameter("songCount")!!.toInt() > 12)
        scope.cancel()
    }
}

class FetchesTest {
    private val server = FakeServer()

    @After fun stop() = server.close()

    @Test
    fun theServersWordsBecomeTheButtonsPhases() {
        assertEquals(FetchPhase.Queued, phaseOf(Acquisition(state = "searching")))
        assertEquals(FetchPhase.Downloading(0.5f), phaseOf(Acquisition(state = "downloading", progress = 0.5f)))
        assertEquals(FetchPhase.Adding, phaseOf(Acquisition(state = "importing")))
        assertEquals(FetchPhase.Done, phaseOf(Acquisition(state = "done")))
        assertEquals(FetchPhase.Failed("no source"), phaseOf(Acquisition(state = "failed", error = "no source")))
        assertEquals(FetchPhase.Queued, phaseOf(Acquisition(state = "something new")))
    }

    @Test
    fun askingStarsTheSongAndFollowsItToTheLibrary() = runBlocking {
        server.answer("star")
        server.answer("getAcquisitions", """"acquisitions":{"acquisition":[{"id":"ext1","state":"downloading","progress":0.4,"startedAt":"2026-09-27T10:00:00Z"}]}""")
        var arrived = 0
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val fetches = Fetches(server.client(), scope, onArrived = { arrived++ }, pollMs = 50)
        fetches.request("ext1")
        assertEquals(FetchPhase.Queued, fetches.phase("ext1"))
        withTimeout(5_000) { fetches.phases.first { it["ext1"] is FetchPhase.Downloading } }
        assertEquals(FetchPhase.Downloading(0.4f), fetches.phase("ext1"))
        assertEquals("ext1", server.calls.first { it.url.pathSegments.last() == "star" }.url.queryParameter("id"))
        server.answer("getAcquisitions", """"acquisitions":{"acquisition":[{"id":"ext1","state":"done","startedAt":"2026-09-27T10:00:00Z"}]}""")
        withTimeout(5_000) { fetches.phases.first { it["ext1"] == FetchPhase.Done } }
        assertEquals(1, arrived)
        scope.cancel()
    }
}
