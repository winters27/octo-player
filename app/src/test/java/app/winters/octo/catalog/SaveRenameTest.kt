package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

// Each group of a save publishes the renames it made as it commits: by the
// time the next reader runs, they are in save_rename (for the triggers,
// the reads and the writers that follow them), and the songs it renamed by
// follow have gone to the published flow, which ends each save with null.
// A rename's new id stands as it is published, and a row written with an
// old id after it lands on the new one.
@RunWith(RobolectricTestRunner::class)
class SaveRenameTest {
    private val server = "server:rename.test"

    @Test
    fun eachGroupPublishesItsRenamesAsItCommits() = runBlocking {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                val songs = if (bound == SAVE_GROUP_FLOOR) 30 else 300
                db.put(library(server, songs))
                rebuildSettled(db, server)
                // The phone gets every song: each server song is renamed, by
                // follow, to the phone's copy.
                db.put(library(DEVICE, songs))
                val hooks = GroupHooks()
                val merge = db.boundMerge(server, bound, hooks)
                val heard = CopyOnWriteArrayList<Map<String, String>?>()
                val listening = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { merge.published.collect { heard += it } }
                val problems = ArrayList<String>()
                var published = 0
                hooks.between = { turn ->
                    val now = heard.filterNotNull().flatMap { it.entries }.associate { it.key to it.value }
                    for ((old, new) in now) {
                        if (db.stringOf("SELECT new FROM save_rename WHERE kind = 'track' AND old = ? AND byKey = 0", old) != new) problems += "turn $turn: $old -> $new is not in force"
                        if (db.longOf("SELECT COUNT(*) FROM track WHERE id = ?", new) != 1L) problems += "turn $turn: $new does not stand"
                    }
                    if (now.size > published) published = now.size
                }
                merge.rebuild()
                withTimeout(5_000) { while (heard.lastOrNull() != null || heard.isEmpty()) kotlinx.coroutines.yield() }
                listening.cancel()
                assertTrue(problems.take(5).joinToString("\n"), problems.isEmpty())
                assertNull("the save's end is heard", heard.last())
                val renames = heard.filterNotNull()
                assertTrue("bound $bound: renames heard in ${renames.size} groups", renames.size > 1)
                val all = renames.flatMap { it.entries }.associate { it.key to it.value }
                assertEquals("bound $bound", (0 until songs).associate { "$server:s$it" to "$DEVICE:s$it" }, all)
                // A row written with an old id after the save lands on its new id.
                db.exec("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES (?, '', 7)", "$server:s3")
                assertEquals("$DEVICE:s3", db.stringOf("SELECT trackId FROM liked_track WHERE likedAt = 7"))
                assertTrue(published > 0)
            } finally {
                db.close()
            }
        }
    }
}
