package app.winters.octo.server

import app.winters.octo.data.Drains
import app.winters.octo.discovery.ListServer
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// A labeled pin. The resume card's offer, read from the drained answer a
// page at a time, holds one song and offers what today's whole read
// offered (remoteQueueOf of playQueue or playQueueByIndex at c2208eaa):
// the same song, position, save time and app, with the current song found
// by id after its entries, not found, named twice, and by place; an empty
// queue offers nothing. No drained file is left.
@RunWith(RobolectricTestRunner::class)
class ResumeOfferTest {
    private val songs = (0 until 3_000).associate { "s$it" to Song("s$it", title = "Song $it", artist = "Artist $it") }
    private val server = ListServer(songs)
    private val drains = Files.createTempDirectory("drain").toFile()

    @After
    fun close() = server.close()

    private fun check(byIndex: Boolean) {
        val client = server.client()
        val today = runBlocking { if (byIndex) client.playQueueByIndex()?.let(::remoteQueueOf) else client.playQueue()?.let(::remoteQueueOf) }
        val offered = runBlocking { offeredQueue(client, byIndex, Drains(drains)) }
        if (today == null) {
            assertNull(offered)
        } else {
            assertEquals(listOf(today.current), offered!!.songs)
            assertEquals(today.current, offered.current)
            assertEquals(today.positionMs, offered.positionMs)
            assertEquals(today.changedAt, offered.changedAt)
            assertEquals(today.changedBy, offered.changedBy)
        }
        assertEquals(emptyList<String>(), drains.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun anOfferIsTodays() {
        server.queue = (0 until 3_000).map { "s$it" } + listOf("s17")
        server.position = 61_000
        for (current in listOf("s2999", "s-none", "s17", "s0")) {
            server.current = current
            check(byIndex = false)
        }
        for (index in listOf(0, 1_234, 3_000, 9_999)) {
            server.currentIndex = index
            check(byIndex = true)
        }
        server.queue = emptyList()
        check(byIndex = false)
        check(byIndex = true)
    }
}
