package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// The play queue, shares, library scans, radio stations and who is listening.
class ServerControlsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    // A Navidrome answer with this payload.
    private fun answer(payload: String = "") {
        val extra = if (payload.isEmpty()) "" else ",$payload"
        val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","openSubsonic":true$extra}}"""
        server.enqueue(MockResponse.Builder().body(body).build())
    }

    private fun error(code: Int, message: String) {
        val body = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}"""
        server.enqueue(MockResponse.Builder().body(body).build())
    }

    @Test
    fun theIndexBasedFormSendsThePlaceInTheList() {
        assertEquals(
            listOf("id" to "a", "id" to "b", "id" to "a", "currentIndex" to "2", "position" to "61000"),
            queueSaveParams(listOf("a", "b", "a"), 2, 61_000, indexBased = true),
        )
    }

    @Test
    fun theOlderFormNamesTheCurrentSong() {
        assertEquals(
            listOf("id" to "a", "id" to "b", "current" to "b", "position" to "0"),
            queueSaveParams(listOf("a", "b"), 1, -5, indexBased = false),
        )
    }

    @Test
    fun anEmptyQueueSendsNoCurrentSong() {
        assertEquals(emptyList<Pair<String, String>>(), queueSaveParams(emptyList(), 0, 1000, indexBased = true))
    }

    @Test
    fun savesTheQueueEitherWay() = runTest {
        answer()
        answer()
        client().savePlayQueueByIndex(listOf("s1", "s2"), 1, 42_500)
        client().savePlayQueue(listOf("s1", "s2"), 1, 42_500)

        val byIndex = server.takeRequest().url
        assertTrue(byIndex.encodedPath.endsWith("/rest/savePlayQueueByIndex"))
        assertEquals(listOf("s1", "s2"), byIndex.queryParameterValues("id"))
        assertEquals("1", byIndex.queryParameter("currentIndex"))
        assertEquals("42500", byIndex.queryParameter("position"))
        assertNull(byIndex.queryParameter("current"))

        val legacy = server.takeRequest().url
        assertTrue(legacy.encodedPath.endsWith("/rest/savePlayQueue"))
        assertEquals(listOf("s1", "s2"), legacy.queryParameterValues("id"))
        assertEquals("s2", legacy.queryParameter("current"))
        assertEquals("42500", legacy.queryParameter("position"))
        assertNull(legacy.queryParameter("currentIndex"))
    }

    @Test
    fun readsTheSavedQueue() = runTest {
        answer(
            """"playQueueByIndex":{"entry":[{"id":"s1","title":"Nightcall"},{"id":"s2","title":"Odd Look"}],
            "currentIndex":1,"position":93000,"username":"winters","changed":"2026-09-26T10:15:30.5Z","changedBy":"Desktop player"}""",
        )
        answer(""""playQueue":{"entry":[{"id":"s1","title":"Nightcall"}],"current":"s1","position":12,"changedBy":"Old phone"}""")

        val byIndex = client().playQueueByIndex()!!
        assertEquals(listOf("s1", "s2"), byIndex.entry.map { it.id })
        assertEquals(1, byIndex.currentIndex)
        assertEquals(93_000L, byIndex.position)
        assertEquals("2026-09-26T10:15:30.5Z", byIndex.changed)
        assertEquals("Desktop player", byIndex.changedBy)

        val legacy = client().playQueue()!!
        assertEquals("s1", legacy.current)
        assertEquals(12L, legacy.position)
        assertEquals("Old phone", legacy.changedBy)
    }

    @Test
    fun noSavedQueueIsNull() = runTest {
        answer(""""playQueueByIndex":{}""")
        answer()
        assertNull(client().playQueueByIndex())
        assertNull(client().playQueue())
    }

    @Test
    fun shareParamsCarryTheExpiryInMilliseconds() {
        assertEquals(
            listOf("id" to "s1", "id" to "s2", "description" to "For Sam", "expires" to "1790000000000"),
            shareParams(listOf("s1", "s2"), "For Sam", 1_790_000_000_000),
        )
        // Never expiring and with no words, only the ids go.
        assertEquals(listOf("id" to "al1"), shareParams(listOf("al1"), "  ", null))
    }

    @Test
    fun createsAShare() = runTest {
        answer(
            """"shares":{"share":[{"id":"sh1","url":"https://music.example/share/sh1","description":"Nightcall",
            "username":"winters","created":"2026-09-26T10:00:00Z","expires":"2026-09-27T10:00:00Z","visitCount":0,
            "entry":[{"id":"s1","title":"Nightcall"}]}]}""",
        )
        val share = client().createShare(listOf("s1"), "Nightcall", 1_790_000_000_000)

        val url = server.takeRequest().url
        assertTrue(url.encodedPath.endsWith("/rest/createShare"))
        assertEquals(listOf("s1"), url.queryParameterValues("id"))
        assertEquals("Nightcall", url.queryParameter("description"))
        assertEquals("1790000000000", url.queryParameter("expires"))
        assertEquals("https://music.example/share/sh1", share.url)
        assertEquals("2026-09-27T10:00:00Z", share.expires)
        assertEquals("Nightcall", share.entry.single().title)
    }

    @Test
    fun sharingSwitchedOffIsAnError() = runTest {
        error(50, "User not authorized")
        error(70, "Sharing is disabled")
        try {
            client().createShare(listOf("s1"))
            fail("expected an error")
        } catch (e: SubsonicException.Server) {
            assertEquals(50, e.code)
        }
        try {
            client().createShare(listOf("s1"))
            fail("expected an error")
        } catch (e: SubsonicException.NotFound) {
            // Code 70.
        }
    }

    @Test
    fun listsAndDeletesShares() = runTest {
        answer(""""shares":{"share":[{"id":"sh1","url":"u1","visitCount":12},{"id":"sh2","url":"u2"}]}""")
        answer()
        val shares = client().shares()
        assertEquals(listOf("sh1", "sh2"), shares.map { it.id })
        assertEquals(12, shares[0].visitCount)
        assertNull(shares[1].expires)

        client().deleteShare("sh1")
        server.takeRequest()
        val delete = server.takeRequest().url
        assertTrue(delete.encodedPath.endsWith("/rest/deleteShare"))
        assertEquals("sh1", delete.queryParameter("id"))
    }

    @Test
    fun readsTheScanStatus() = runTest {
        answer(""""scanStatus":{"scanning":true,"count":1234,"folderCount":56,"lastScan":"2026-09-26T09:00:00Z"}""")
        answer(""""scanStatus":{"scanning":false,"count":2376}""")
        answer()

        val started = client().startScan()
        assertTrue(started.scanning)
        assertEquals(1234L, started.count)
        assertEquals(56L, started.folderCount)

        val done = client().scanStatus()
        assertFalse(done.scanning)
        assertEquals(2376L, done.count)

        // A server that answers with nothing is not scanning.
        assertEquals(ScanStatus(), client().scanStatus())

        assertTrue(server.takeRequest().url.encodedPath.endsWith("/rest/startScan"))
        assertTrue(server.takeRequest().url.encodedPath.endsWith("/rest/getScanStatus"))
    }

    @Test
    fun readsWhoIsListening() = runTest {
        answer(
            """"nowPlaying":{"entry":[{"id":"s1","title":"Nightcall","artist":"Kavinsky","username":"sam",
            "minutesAgo":3,"playerId":7,"playerName":"Desktop player"}]}""",
        )
        answer(""""nowPlaying":{}""")

        val entry = client().nowPlaying().single()
        assertEquals("sam", entry.username)
        assertEquals(3, entry.minutesAgo)
        assertEquals("7", entry.playerId)
        assertEquals("Desktop player", entry.playerName)
        assertEquals(emptyList<NowPlayingEntry>(), client().nowPlaying())
    }

    @Test
    fun editsRadioStations() = runTest {
        answer(
            """"internetRadioStations":{"internetRadioStation":[{"id":"r1","name":"FIP",
            "streamUrl":"https://stream.example/fip.mp3","homePageUrl":"https://fip.example"}]}""",
        )
        answer()
        answer()
        answer()

        val station = client().radioStationDetails().single()
        assertEquals("https://fip.example", station.homePageUrl)
        client().createRadioStation("https://stream.example/a.mp3", " Jazz ", "")
        client().updateRadioStation("r1", "https://stream.example/b.mp3", "FIP", "https://fip.example")
        client().deleteRadioStation("r1")

        server.takeRequest()
        val create = server.takeRequest().url
        assertTrue(create.encodedPath.endsWith("/rest/createInternetRadioStation"))
        assertEquals("https://stream.example/a.mp3", create.queryParameter("streamUrl"))
        assertEquals("Jazz", create.queryParameter("name"))
        assertNull(create.queryParameter("homepageUrl"))

        val update = server.takeRequest().url
        assertTrue(update.encodedPath.endsWith("/rest/updateInternetRadioStation"))
        assertEquals("r1", update.queryParameter("id"))
        assertEquals("https://fip.example", update.queryParameter("homepageUrl"))

        val delete = server.takeRequest().url
        assertTrue(delete.encodedPath.endsWith("/rest/deleteInternetRadioStation"))
        assertEquals("r1", delete.queryParameter("id"))
    }

    @Test
    fun aReadOnlyStationIsRefused() = runTest {
        error(70, "Octo's generated playlists are read-only")
        try {
            client().deleteRadioStation("or123")
            fail("expected an error")
        } catch (e: SubsonicException.NotFound) {
            assertTrue(e.message!!.contains("read-only"))
        }
    }
}
