package app.winters.octo.ui.home

import app.winters.octo.catalog.GroupHooks
import app.winters.octo.catalog.SAVE_GROUP_ENTRIES
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import app.winters.octo.catalog.albumRow
import app.winters.octo.catalog.boundMerge
import app.winters.octo.catalog.library
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.put
import app.winters.octo.device.DEVICE
import app.winters.octo.playback.CarHomePicks
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Home's surprise picks and the car's random shelves are made again when
// the library's size changes, read only while no save is applying: across
// the groups of a save, a run's pages among them, they stay; once it is in
// they change once.
@RunWith(RobolectricTestRunner::class)
class SavePicksTest {
    // Notes the plan's group of each turn, so a run (one group over several
    // turns) can be told.
    private class Turns : GroupHooks() {
        val groupOf = ConcurrentHashMap<Long, Long>()

        override suspend fun inGroup(turn: Long, group: Long) {
            groupOf[turn] = group
            super.inGroup(turn, group)
        }
    }

    @Test
    fun picksStayAcrossAGroupedSaveAndChangeOnceAfterIt() = runBlocking {
        withTimeout(120_000) {
            for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
                val where = "bound $bound"
                val db = openTestCatalog()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val songs = if (bound == SAVE_GROUP_FLOOR) 30 else 1_200
                    db.put(library(DEVICE, songs))
                    db.boundMerge(null, bound).rebuild()
                    val before = db.longOf("SELECT COUNT(*) FROM track").toInt()
                    // Ten songs more, every song on one new album (one set,
                    // over the bound: a run of pages), a third retitled.
                    db.put(
                        library(DEVICE, songs + 10).let { lib ->
                            lib.copy(
                                tracks = lib.tracks.map { t ->
                                    val moved = t.copy(albumId = "$DEVICE:big", album = "Big")
                                    if (t.nativeId.removePrefix("s").toInt() % 3 == 0) moved.copy(title = t.title + " (Live)") else moved
                                },
                                albums = listOf(albumRow(DEVICE, "big", "Big")),
                            )
                        },
                    )
                    val hooks = Turns()
                    val merge = db.boundMerge(null, bound, hooks)
                    // Home's key, as its view model collects it.
                    val keys = Collections.synchronizedList(ArrayList<Pair<Int, Long?>>())
                    scope.launch { homePickKey(merge.applyStamp, { db.dao().libraryTrackCount() }, flowOf(1L)).collect { keys += it } }
                    withTimeout(10_000) { while (keys.isEmpty()) yield() }
                    assertEquals(listOf(before to 1L), keys.toList())
                    // The car's shelves, asked between every two groups.
                    val picked = AtomicLong()
                    val car = CarHomePicks(merge.applyStamp, { db.dao().libraryTrackCount().first() }, { 1L }) { picked.incrementAndGet() }
                    val first = car.now()
                    val carAt = ArrayList<Long>()
                    val homeAt = ArrayList<Int>()
                    val fresh = ArrayList<Pair<Int, Long?>?>()
                    val counts = ArrayList<Long>()
                    hooks.between = { turn ->
                        counts += db.longOf("SELECT COUNT(*) FROM track")
                        carAt += car.now()
                        // A key collected afresh between groups answers
                        // nothing until the save is in.
                        if (turn <= 2) fresh += withTimeoutOrNull(2_000) { homePickKey(merge.applyStamp, { db.dao().libraryTrackCount() }, flowOf(1L)).first() }
                        homeAt += keys.size
                    }
                    merge.rebuild()
                    val after = db.longOf("SELECT COUNT(*) FROM track").toInt()
                    assertTrue("$where: the save changed nothing", after != before)
                    assertTrue("$where: ${carAt.size} parks", carAt.size >= 2)
                    assertTrue("$where: the count had not moved by any park: $counts from $before", counts.any { it != before.toLong() })
                    val runs = hooks.groupOf.values.groupingBy { it }.eachCount().filterValues { it > 1 }
                    assertTrue("$where: no run parked: ${hooks.groupOf}", runs.isNotEmpty())
                    // Between groups nothing was picked again.
                    assertEquals("$where: the car picked again between groups", List(carAt.size) { first }, carAt)
                    assertEquals("$where: Home re-rolled between groups", List(homeAt.size) { 1 }, homeAt)
                    assertEquals("$where: Home's key answered between groups", listOf(null, null), fresh)
                    // Once in, each changes once.
                    withTimeout(10_000) { while (keys.size < 2) yield() }
                    repeat(50) { yield() }
                    assertEquals(where, listOf(before to 1L, after to 1L), keys.toList())
                    val again = car.now()
                    assertEquals("$where: the car picked again once", first + 1, again)
                    assertEquals(again, car.now())
                } finally {
                    scope.cancel()
                    db.close()
                }
            }
        }
    }
}
