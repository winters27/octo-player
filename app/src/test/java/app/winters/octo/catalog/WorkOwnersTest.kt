package app.winters.octo.catalog

import androidx.room.withTransaction
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkOwnersTest {
    // Tokens lead with this launch's mark and name their kind, and no two
    // numbered ones are the same; a token minted after a new launch carries
    // that launch's mark, so it never names an older launch's rows.
    @Test
    fun tokensNameTheirLaunchAndKind() {
        val owners = WorkOwners()
        val numbered = listOf(owners.vm(), owners.car(), owners.draft(), owners.copy(), owners.export(), owners.hold(), owners.match(), owners.fix(), owners.walk(), owners.vm())
        assertEquals(numbered.size, numbered.toSet().size)
        assertTrue(numbered.all { it.startsWith(LaunchMark.prefix) })
        assertEquals(listOf("vm", "car", "draft", "copy", "export", "hold", "match", "fix", "walk", "vm"), numbered.map(WorkOwners::kindOf))
        assertTrue(numbered.all { it.substringAfter(LaunchMark.prefix).substringAfter(':').toLong() > 0 })
        assertEquals("${LaunchMark.prefix}queue:7", owners.queue(7))
        assertEquals("${LaunchMark.prefix}live:l1", owners.live("l1"))
        assertEquals("${LaunchMark.prefix}report", WorkOwners.REPORT)
        val older = owners.vm()
        val before = LaunchMark.current
        LaunchMark.next()
        assertTrue(LaunchMark.current != before)
        val newer = owners.vm()
        assertTrue("$newer", newer.startsWith(LaunchMark.prefix) && !older.startsWith(LaunchMark.prefix))
        val (from, until) = WorkOwners.kindRange("vm")
        assertTrue(newer >= from && newer < until)
        assertTrue(older < from || older >= until)
    }

    // A clear asked for while an owner is pinned runs at its last unpin, once;
    // one asked for while it is not pinned runs at once.
    @Test
    fun clearsWaitForTheLastPin() = runBlocking {
        val owners = WorkOwners()
        val cleared = ArrayList<String>()
        assertTrue(owners.clear(WorkOwners.marked("vm:1")) { cleared += "now" })
        owners.pin(WorkOwners.marked("vm:2"))
        owners.pin(WorkOwners.marked("vm:2"))
        assertFalse(owners.clear(WorkOwners.marked("vm:2")) { cleared += "rows" })
        assertFalse(owners.clear(WorkOwners.marked("vm:2")) { cleared += "pins" })
        owners.unpin(WorkOwners.marked("vm:2"))
        assertEquals(listOf("now"), cleared)
        assertTrue(owners.pinned(WorkOwners.marked("vm:2")))
        owners.unpin(WorkOwners.marked("vm:2"))
        assertEquals(listOf("now", "rows", "pins"), cleared)
        assertFalse(owners.pinned(WorkOwners.marked("vm:2")))
        owners.unpin(WorkOwners.marked("vm:2"))
        assertEquals(3, cleared.size)
    }

    // A published generation goes only once nothing pins it: a reader pins
    // the one published when it reads, and keeps it however many follow.
    @Test
    fun generationsGoAtTheirLastPin() {
        val owners = WorkOwners()
        assertNull(owners.pinPublished(WorkOwners.marked("report")))
        assertEquals(emptyList<Long>(), owners.publish(WorkOwners.marked("report"), 1))
        assertEquals(1L, owners.pinPublished(WorkOwners.marked("report")))
        assertEquals(1L, owners.pinPublished(WorkOwners.marked("report")))
        assertEquals(emptyList<Long>(), owners.publish(WorkOwners.marked("report"), 2))
        assertFalse(owners.unpinGen(WorkOwners.marked("report"), 1))
        assertEquals(listOf(2L), owners.publish(WorkOwners.marked("report"), 3))
        assertEquals(3L, owners.publishedGen(WorkOwners.marked("report")))
        assertTrue(owners.unpinGen(WorkOwners.marked("report"), 1))
        assertFalse(owners.unpinGen(WorkOwners.marked("report"), 1))
        assertEquals(3L, owners.pinPublished(WorkOwners.marked("report")))
        assertFalse(owners.unpinGen(WorkOwners.marked("report"), 3))
        assertEquals(listOf(3L), owners.publish(WorkOwners.marked("report"), 4))
    }

    // An owner's rows go a page at a time, each page its own statement, and
    // no other owner's rows go with them.
    @Test
    fun anOwnersRowsGoInPages() = runBlocking {
        val db = openTestCatalog()
        db.withTransaction {
            val w = db.openHelper.writableDatabase
            repeat(1_234) { w.execSQL("INSERT INTO playlist_set_pin (owner, playlistId, itemSet) VALUES (?, ?, 0)", arrayOf<Any?>(WorkOwners.marked("vm:1"), "p$it")) }
            repeat(10) { w.execSQL("INSERT INTO playlist_set_pin (owner, playlistId, itemSet) VALUES (?, ?, 0)", arrayOf<Any?>(WorkOwners.marked("vm:2"), "p$it")) }
        }
        db.counted.reset()
        db.counted.recording = true
        assertEquals(1_234L, db.deleteOwned("playlist_set_pin", WorkOwners.marked("vm:1")))
        db.counted.recording = false
        val deletes = db.counted.own.filter { it.sql.startsWith("DELETE") }
        assertEquals(listOf(500, 500, 234), deletes.map { it.rowsChanged })
        db.openHelper.writableDatabase.query("SELECT owner, COUNT(*) FROM playlist_set_pin GROUP BY owner").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(WorkOwners.marked("vm:2"), c.getString(0))
            assertEquals(10, c.getInt(1))
            assertFalse(c.moveToNext())
        }
        db.close()
    }
}
