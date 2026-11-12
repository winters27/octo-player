package app.winters.octo.discovery

import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.userMessage
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// A server list read short of room stops with the list's own words and
// leaves the last whole read in place: the page keeps showing it. Its own
// rows and its drained file are gone.
@RunWith(RobolectricTestRunner::class)
class ServerListDiskFullTest {
    private val db = openTestCatalog()
    private val server = ListServer((0 until 2_000).associate { "s$it" to Song("s$it", title = "Song $it", artist = "Artist") })

    @After
    fun close() {
        server.close()
        db.close()
    }

    @Test
    fun aReadShortOfRoomKeepsTheLastWholeRead() {
        server.lists["L"] = (0 until 2_000).map { "s$it" }
        var free = Long.MAX_VALUE
        val drains = Files.createTempDirectory("drain").toFile()
        val reads = db.serverReads(server, space = FreeSpace { free }, drains = drains)
        runBlocking { reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1")) }
        free = 1_000_000
        try {
            runBlocking { reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:2")) }
            fail("read with no room")
        } catch (e: NotEnoughSpace) {
            assertEquals(SpaceFor.List, e.doing)
            assertTrue(e.userMessage(), e.userMessage().startsWith("Not enough space to show this list. Free up "))
        }
        assertEquals(2_000, runBlocking { reads.shownIds(WorkOwners.marked("vm:1")) }.size)
        assertEquals(0, db.query("SELECT COUNT(*) FROM server_entry WHERE owner = ?", arrayOf(WorkOwners.marked("vm:2"))).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(emptyList<String>(), drains.listFiles().orEmpty().map { it.name })
    }
}
