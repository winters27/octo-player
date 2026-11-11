package app.winters.octo.catalog

import app.winters.octo.catalog.today.movedItem
import app.winters.octo.catalog.today.ordAt
import app.winters.octo.catalog.today.renumbered
import app.winters.octo.catalog.today.restoredItems
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// The one-row edits on a sparse order against today's edits, which
// renumbered the whole playlist each time (catalog/today): adds, removals
// with and without their Undo, and moves both ways, on a 3,000-row
// playlist whose places pass Int's range. After every edit the songs run
// in today's order, every row keeps its key, and no edit reads or writes
// more than a page of rows.
@RunWith(RobolectricTestRunner::class)
class PlaylistEditParityTest {
    private val db = openTestCatalog()
    private val edits = PlaylistEdits(db, PlaylistSets(db))

    @After
    fun close() = db.close()

    private fun ref(n: Int) = SongToAdd("device:${n % 700}", "k${n % 700}")

    // Today's rows, each named by the key the new rows take, so the two
    // can be compared row by row.
    private fun today(rows: List<Pair<Long, SongToAdd>>) =
        rows.mapIndexed { i, (key, ref) -> PlaylistItemEntity(id = key, playlistId = "p", trackId = ref.trackId, relinkKey = ref.relinkKey, ord = ordAt(i), itemKey = key) }

    private suspend fun shown(): List<Pair<Long, String>> = db.userDao().playlistItems("p").map { it.itemKey to it.trackId }

    private fun List<PlaylistItemEntity>.named() = map { it.id to it.trackId }

    private suspend fun add(model: List<PlaylistItemEntity>, refs: List<SongToAdd>): List<PlaylistItemEntity> {
        val added = edits.add("p", { emit -> emit(refs) }, distinct = false, newOnly = false, now = 1)!!
        assertEquals(refs.size, added.count)
        return renumbered(model + today(((added.before + 1)..added.last).zip(refs)))
    }

    @Test
    fun everyEditRunsInTodaysOrder() = runBlocking {
        db.userDao().insertPlaylist(PlaylistEntity("p", "P", 1, 1))
        var model = add(emptyList(), (0 until 3_000).map(::ref))
        assertEquals(model.named(), shown())
        assertTrue("places pass Int's range", db.userDao().playlistItems("p").last().ord > Int.MAX_VALUE)

        val random = Random(7)
        val log = db.counted
        repeat(300) { step ->
            log.reset()
            when (random.nextInt(4)) {
                0 -> model = add(model, (0 until 1 + random.nextInt(5)).map { ref(3_000 + step * 5 + it) })
                1, 2 -> {
                    val at = random.nextInt(model.size)
                    val row = model[at]
                    val removed = edits.remove("p", row.id, now = 2)!!
                    model = renumbered(model - row)
                    // Half the removals are taken back at once, as Undo does.
                    if (random.nextBoolean()) {
                        edits.restore("p", removed, now = 3)
                        model = restoredItems(model, row.copy(ord = ordAt(at)))
                    }
                }
                else -> {
                    val from = model[random.nextInt(model.size)]
                    val onto = model[random.nextInt(model.size)]
                    edits.move("p", from.id, onto.id, now = 4)
                    model = movedItem(model, from.id, onto.id)
                }
            }
            log.maxRead?.let { assertTrue("step $step read $it", it.rowsRead <= 500) }
            assertEquals("step $step", model.named(), shown())
        }
    }

    // Moving a row back and forth between the same two neighbors runs out
    // of places between them: the playlist is spaced out again, keeping
    // every key, and the order stays today's.
    @Test
    fun aCrowdedMoveSpacesThePlaylistOutAgain() = runBlocking {
        db.userDao().insertPlaylist(PlaylistEntity("p", "P", 1, 1))
        var model = add(emptyList(), (0 until 40).map(::ref))
        repeat(30) {
            val a = model[10]
            val b = model[20]
            edits.move("p", b.id, a.id, now = 5)
            model = movedItem(model, b.id, a.id)
            assertEquals(model.named(), shown())
        }
        val keys = shown().map { it.first }
        assertEquals(keys.toSet().size, keys.size)
    }
}
