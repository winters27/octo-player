package app.winters.octo.desktop.versions

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.STREAM_VERSION_CHANGES_EXTENSION
import app.winters.octo.subsonic.Song
import app.winters.octo.versions.FeedPlan
import app.winters.octo.versions.StreamVersionCursor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// What the desktop reads of the songs given another version: one read at a
// time, read on from where it was left (across a restart too), only on a
// server that offers it, and before a song found online is asked about.
class StreamVersionFeedTest {
    @get:Rule val temp = TemporaryFolder()

    private fun changes(epoch: String, seq: Long, change: String) =
        """"streamVersionChanges":{"epoch":"$epoch","seq":$seq,"complete":true,"change":[$change]}"""

    // Red when: two asks make two reads, or the cursor is not kept and sent.
    @Test
    fun theListIsReadOnFromWhereItWasLeftOneReadAtATime() = runBlocking {
        FakeServer().use { server ->
            val since = mutableListOf<String?>()
            server.answerBy("getStreamVersionChanges") { request ->
                val from = request.url.queryParameter("since")
                since += from
                when (from) {
                    null -> server.ok(changes("e1", 7, """{"seq":6,"ids":["X","Y"]},{"seq":7,"ids":["Z"]}"""), "octo")
                    "7" -> server.ok(changes("e1", 8, """{"seq":8,"ids":["W"]}"""), "octo")
                    else -> server.ok(changes("e1", 8, ""), "octo")
                }
            }
            val settings = SettingsStore(File(temp.root, "settings.json"), 0)
            val connection = server.connection(listOf("$STREAM_VERSION_CHANGES_EXTENSION:1"))
            val plans = mutableListOf<FeedPlan>()
            val feed = StreamVersionFeed(this, settings, { connection }) { plans += it }
            listOf(async { feed.refresh() }, async { feed.refresh() }).awaitAll()
            assertEquals(listOf<String?>(null), since)
            assertEquals("7 e1", settings.current.versionCursors[""])
            feed.refresh()
            assertEquals(listOf(null, "7"), since)
            assertEquals(setOf("W"), plans.last().dropIds)
            assertFalse(plans.last().dropAll)
            // Another run reads on from the kept cursor.
            StreamVersionFeed(this, settings, { connection }) {}.refresh()
            assertEquals(listOf(null, "7", "8"), since)
        }
    }

    // Red when: the extension check or the outside-song check is dropped.
    @Test
    fun onlyAServerThatOffersItIsAskedAndOnlyBeforeASongFoundOnline() = runBlocking {
        FakeServer().use { server ->
            server.answer("getStreamVersionChanges", changes("e1", 1, ""), "octo")
            val settings = SettingsStore(File(temp.root, "settings.json"), 0)
            var connection = server.connection()
            val feed = StreamVersionFeed(this, settings, { connection }) {}
            feed.refresh()
            assertTrue(server.endpoints().none { it == "getStreamVersionChanges" })
            connection = server.connection(listOf("$STREAM_VERSION_CHANGES_EXTENSION:1"))
            feed.beforeOpening(Song("s1", "In the library"))
            assertTrue(server.endpoints().none { it == "getStreamVersionChanges" })
            feed.beforeOpening(Song("ext-1", "Found", isExternal = true))
            assertEquals(1, server.endpoints().count { it == "getStreamVersionChanges" })
        }
    }

    @Test
    fun aCursorReadsBackAsItWasKept() {
        val cursor = StreamVersionCursor("e-1 2", 42)
        assertEquals(cursor, cursorOf(cursorText(cursor)))
        assertNull(cursorOf("e1"))
        assertNull(cursorOf("7 "))
    }

    // Red when: the gate lets a library song, a song fetched into the
    // library since, or a server that does not list the extension through.
    @Test
    fun onlyASongFoundOnlineThatStillStreamsOffersVersions() {
        val found = Song("ext-1", "Found", isExternal = true)
        assertTrue(streamVersionsOffered(true, found, null))
        assertFalse(streamVersionsOffered(false, found, null))
        assertFalse(streamVersionsOffered(true, found, "nd-1"))
        assertFalse(streamVersionsOffered(true, Song("s1", "In the library"), null))
        assertFalse(streamVersionsOffered(true, null, null))
    }
}
