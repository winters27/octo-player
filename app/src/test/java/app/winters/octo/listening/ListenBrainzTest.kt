package app.winters.octo.listening

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Building what goes to ListenBrainz, the queue, retries and which plays go.
class ListenBrainzTest {
    private val song = PlayedSong("Glory Box", "Portishead", "Dummy", 306_000, 11, reachesServer = false)

    private fun JsonObject.first(): JsonObject = getValue("payload").jsonArray.single().jsonObject

    @Test
    fun aPlayCarriesItsStartInSecondsAndTheSong() {
        val listen = song.listenAt(1_700_000_123_456)!!
        val body = listensJson("single", listOf(listen), "0.1.0")

        assertEquals("single", body.getValue("listen_type").jsonPrimitive.content)
        val play = body.first()
        assertEquals(1_700_000_123L, play.getValue("listened_at").jsonPrimitive.long)
        val meta = play.getValue("track_metadata").jsonObject
        assertEquals("Portishead", meta.getValue("artist_name").jsonPrimitive.content)
        assertEquals("Glory Box", meta.getValue("track_name").jsonPrimitive.content)
        assertEquals("Dummy", meta.getValue("release_name").jsonPrimitive.content)
    }

    @Test
    fun additionalInfoNamesOctoAndTheSongDetails() {
        val info = listensJson("single", listOf(song.listenAt(1_000_000)!!), "0.1.0")
            .first().getValue("track_metadata").jsonObject.getValue("additional_info").jsonObject

        assertEquals("Octo", info.getValue("media_player").jsonPrimitive.content)
        assertEquals("0.1.0", info.getValue("media_player_version").jsonPrimitive.content)
        assertEquals("Octo", info.getValue("submission_client").jsonPrimitive.content)
        assertEquals("0.1.0", info.getValue("submission_client_version").jsonPrimitive.content)
        assertEquals(306_000, info.getValue("duration_ms").jsonPrimitive.int)
        // A string, as ListenBrainz documents it.
        assertTrue(info.getValue("tracknumber").jsonPrimitive.isString)
        assertEquals("11", info.getValue("tracknumber").jsonPrimitive.content)
    }

    @Test
    fun unknownDetailsAreLeftOut() {
        val bare = PlayedSong("Song", "Artist", "", 0, null, reachesServer = false).listenAt(5_000)!!
        val meta = listensJson("single", listOf(bare), "1").first().getValue("track_metadata").jsonObject
        val info = meta.getValue("additional_info").jsonObject

        assertFalse("release_name" in meta)
        assertFalse("duration_ms" in info)
        assertFalse("tracknumber" in info)
    }

    @Test
    fun playingNowHasNoTime() {
        val body = listensJson("playing_now", listOf(song.listenAt(1_000_000)!!), "1")
        assertEquals("playing_now", body.getValue("listen_type").jsonPrimitive.content)
        assertFalse("listened_at" in body.first())
    }

    @Test
    fun aSongWithoutTitleOrArtistIsNotSent() {
        assertNull(song.copy(title = " ").listenAt(1_000))
        assertNull(song.copy(artist = "").listenAt(1_000))
    }

    @Test
    fun oneIsSingleAndMoreIsImport() {
        assertEquals("single", listenType(1))
        assertEquals("import", listenType(2))
        assertEquals("import", listenType(MAX_LISTENS_PER_REQUEST))
    }

    @Test
    fun batchesHoldAtMostOneThousandOldestFirst() {
        var queue = (1..2_500).map { Listen(it.toLong(), "t$it", "a") }
        val sizes = mutableListOf<Int>()
        while (queue.isNotEmpty()) {
            val batch = nextBatch(queue)
            assertEquals(queue.first(), batch.first())
            sizes += batch.size
            queue = queue - batch.toSet()
        }
        assertEquals(listOf(1_000, 1_000, 500), sizes)
    }

    @Test
    fun theQueueKeepsEachPlayOnceInOrder() {
        val queue = emptyList<Listen>()
            .plusListen(Listen(200, "b", "x"))
            .plusListen(Listen(100, "a", "x"))
            .plusListen(Listen(200, "b", "x"))
        assertEquals(listOf(100L, 200L), queue.map { it.listenedAt })
    }

    @Test
    fun aFullQueueLetsTheOldestGo() {
        val queue = (1..MAX_QUEUED_LISTENS + 3).fold(emptyList<Listen>()) { q, i -> q.plusListen(Listen(i.toLong(), "t", "a")) }
        assertEquals(MAX_QUEUED_LISTENS, queue.size)
        assertEquals(4L, queue.first().listenedAt)
    }

    @Test
    fun retriesBackOffAndStopGrowing() {
        assertEquals(0L, retryDelayMs(0))
        assertEquals(30_000L, retryDelayMs(1))
        assertEquals(60_000L, retryDelayMs(2))
        assertEquals(120_000L, retryDelayMs(3))
        assertEquals(6 * 60 * 60 * 1000L, retryDelayMs(15))
        assertEquals(6 * 60 * 60 * 1000L, retryDelayMs(500))
        (1..40).zipWithNext().forEach { (a, b) -> assertTrue(retryDelayMs(b) >= retryDelayMs(a)) }
    }

    @Test
    fun allSongsSendsEverything() {
        assertTrue(shouldSend(SendPlays.All, reachesServer = true))
        assertTrue(shouldSend(SendPlays.All, reachesServer = false))
    }

    @Test
    fun onlySongsOnThisPhoneLeavesOutWhatTheServerHears() {
        assertFalse(shouldSend(SendPlays.PhoneOnly, reachesServer = true))
        assertTrue(shouldSend(SendPlays.PhoneOnly, reachesServer = false))
    }

    @Test
    fun theServerHearsFindsAndSongsWithAServerCopy() {
        assertTrue(playReachesServer("find:abc", emptyList()))
        assertTrue(playReachesServer("merged:1", listOf("device", "server:home")))
        assertFalse(playReachesServer("merged:1", listOf("device")))
        assertFalse(playReachesServer("file:content%3A%2F%2Fx::", emptyList()))
    }

    @Test
    fun onlyTokenShapedTextIsATokenAtAll() {
        assertTrue(looksLikeToken("3fa85f64-5717-4562-b3fc-2c963f66afa6"))
        assertFalse(looksLikeToken("has space in it"))
        assertFalse(looksLikeToken("abcdefgh\r\nX-Other: 1"))
        assertFalse(looksLikeToken("short"))
    }

    @Test
    fun callsKeepASecondApartAndWaitOutAnEmptyWindow() {
        var now = 10_000L
        val limits = RateLimits { now }
        assertEquals(0L, limits.waitMs())
        limits.called()
        assertEquals(1_000L, limits.waitMs())
        now += 1_000
        assertEquals(0L, limits.waitMs())

        limits.heard(remaining = 3, resetInSeconds = 20)
        assertEquals(0L, limits.blockedMs())
        limits.heard(remaining = 0, resetInSeconds = 20)
        assertEquals(20_000L, limits.waitMs())
        assertEquals(20_000L, limits.blockedMs())
        now += 20_000
        assertEquals(0L, limits.waitMs())
    }
}
