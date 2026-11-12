package app.winters.octo.listening

import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.device.DEVICE
import app.winters.octo.playback.FindMap
import app.winters.octo.playback.RealLengths
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// ListenBrainz's "playing now" for a song the player still holds by an id
// a save renamed: once the save is in, that id names no library song, and
// the song is read by the id it is now, so the listen still goes, with the
// song's own details.
@RunWith(RobolectricTestRunner::class)
class ListenedSongsTest {
    private val db = openTestCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val source = "server:home.test"

    @After
    fun close() {
        scope.cancel()
        server.close()
        db.close()
    }

    @Test
    fun anOldIdAfterItsSaveStillSendsTheSong() = runBlocking {
        withTimeout(60_000) {
            db.put(library(source, 10))
            db.testMerge(source).rebuild()
            val old = "$source:s3"
            // The phone's copies join: the save renames every song to the
            // phone's id, and the old one names no library song.
            db.put(library(DEVICE, 10))
            db.testMerge(source).rebuild()
            val new = "$DEVICE:s3"
            assertEquals(0L, db.query("SELECT COUNT(*) FROM track WHERE id = ?", arrayOf(old)).use { it.moveToFirst(); it.getLong(0) })
            assertEquals(new, db.userDao().currentId("track", old))
            val songs = ListenedSongs(db.dao(), db.sourceDao(), db.onlineDao(), db.userDao(), RealLengths(), FindMap(db.onlineDao(), RealLengths(), scope))
            val played = songs.of(old)
            assertNotNull("nothing to send for the song's old id", played)
            assertEquals(songs.of(new), played)
            assertEquals(listOf("Song 3", "Drake", "Views"), listOf(played!!.title, played.artist, played.album))
            assertTrue("the play reaches the server through its copy there", played.reachesServer)
            // It goes to ListenBrainz as playing now, with those details.
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"status":"ok"}""").build())
            val listen = played.listenAt(1_700_000_000_000)!!
            assertEquals(SubmitResult.Sent, ListenBrainzApi(OkHttpClient(), server.url("/")).playingNow("3fa85f64-5717-4562-b3fc-2c963f66afa6", listen, "1"))
            val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("playing_now", sent.getValue("listen_type").jsonPrimitive.content)
            val track = sent.getValue("payload").jsonArray.single().jsonObject.getValue("track_metadata").jsonObject
            assertEquals("Song 3", track.getValue("track_name").jsonPrimitive.content)
            assertEquals("Drake", track.getValue("artist_name").jsonPrimitive.content)
            assertEquals("Views", track.getValue("release_name").jsonPrimitive.content)
        }
    }
}
