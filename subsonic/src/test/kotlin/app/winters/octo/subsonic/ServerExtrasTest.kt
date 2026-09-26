package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Loudness, ratings, artist information and top songs.
class ServerExtrasTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(name: String) {
        val body = requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText(Charsets.UTF_8)
        server.enqueue(MockResponse.Builder().body(body).build())
    }

    @Test
    fun songsCarryTheirLoudnessAndRating() = runTest {
        answer("getTopSongs")
        val songs = client().topSongs("Kavinsky")
        assertEquals(3, songs.size)

        val gain = songs[0].replayGain!!
        assertEquals(-8.43f, gain.trackGain!!, 0.001f)
        assertEquals(-7.9f, gain.albumGain!!, 0.001f)
        assertEquals(0.988251f, gain.trackPeak!!, 0.000001f)
        assertEquals(1f, gain.albumPeak!!, 0f)
        assertEquals(0f, gain.baseGain!!, 0f)
        assertEquals(-6.5f, gain.fallbackGain!!, 0.001f)
        assertEquals(4, songs[0].userRating)

        // An empty object is a song with no loudness known.
        assertEquals(SongReplayGain(), songs[1].replayGain)
        assertEquals(0, songs[1].userRating)
        // And a server that sends neither still parses.
        assertNull(songs[2].replayGain)
        assertNull(songs[2].userRating)
    }

    @Test
    fun topSongsAskByNameAndByIdOnlyWhenGiven() = runTest {
        answer("getTopSongs")
        answer("getTopSongs")
        client().topSongs("Kavinsky", count = 10)
        client().topSongs("Kavinsky", count = 5, artistId = "1mNJ8hAlbt4jJc2dFaf38y")

        val byName = server.takeRequest().url
        assertTrue(byName.encodedPath.endsWith("/rest/getTopSongs"))
        assertEquals("Kavinsky", byName.queryParameter("artist"))
        assertEquals("10", byName.queryParameter("count"))
        assertNull(byName.queryParameter("id"))

        val byId = server.takeRequest().url
        assertEquals("Kavinsky", byId.queryParameter("artist"))
        assertEquals("5", byId.queryParameter("count"))
        assertEquals("1mNJ8hAlbt4jJc2dFaf38y", byId.queryParameter("id"))
    }

    @Test
    fun readsArtistInfo() = runTest {
        answer("getArtistInfo2")
        val info = client().artistInfo("1mNJ8hAlbt4jJc2dFaf38y")

        val url = server.takeRequest().url
        assertTrue(url.encodedPath.endsWith("/rest/getArtistInfo2"))
        assertEquals("1mNJ8hAlbt4jJc2dFaf38y", url.queryParameter("id"))

        assertTrue(info.biography!!.startsWith("Vincent Belorgey"))
        assertEquals("1d2a3a6f-6a3c-4b53-9e3d-41f3a1bdfb4a", info.musicBrainzId)
        assertEquals("https://www.last.fm/music/Kavinsky", info.lastFmUrl)
        assertEquals("https://example.test/large.jpg", info.largeImageUrl)
        assertEquals(listOf("College", "Lazerhawk"), info.similarArtist.map { it.name })
        assertEquals("3kL9pQ2wErTy6uIo1aSdFg", info.similarArtist[0].id)
        assertEquals("ar-3kL9pQ2wErTy6uIo1aSdFg_0", info.similarArtist[0].coverArt)
    }

    @Test
    fun anArtistWithNoInfoIsEmpty() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build())
        assertEquals(ArtistInfo(), client().artistInfo("x"))
    }

    @Test
    fun setRatingSendsTheSongAndStars() = runTest {
        answer("ping")
        answer("ping")
        client().setRating("XEjJFBng9tY4swUvEp7Wj7", 4)
        client().setRating("XEjJFBng9tY4swUvEp7Wj7", 0)

        val rate = server.takeRequest().url
        assertTrue(rate.encodedPath.endsWith("/rest/setRating"))
        assertEquals("XEjJFBng9tY4swUvEp7Wj7", rate.queryParameter("id"))
        assertEquals("4", rate.queryParameter("rating"))
        assertEquals("0", server.takeRequest().url.queryParameter("rating"))
    }
}
