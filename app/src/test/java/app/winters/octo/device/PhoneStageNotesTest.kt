package app.winters.octo.device

import app.winters.octo.catalog.MergeHooks
import app.winters.octo.catalog.STAGE_BUILDING
import app.winters.octo.catalog.STAGE_NONE
import app.winters.octo.catalog.SourceMetaEntity
import app.winters.octo.catalog.library
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.put
import app.winters.octo.catalog.stage
import app.winters.octo.catalog.stringOf
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A rating tapped during a server copy's merge is noted so that merge's
// swap shows it, and the note ends with that merge even while the phone's
// copy is being built: the phone brings no server ratings. A later tap,
// with no merge running, is the one the next phone swap shows.
@RunWith(RobolectricTestRunner::class)
class PhoneStageNotesTest {
    private val server = "server:home.test"
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private suspend fun phoneMeta(stage: String, generation: Long) {
        val stamp = db.workDao().metas().firstOrNull { it.sourceId == DEVICE }?.stamp ?: 0
        db.workDao().putMeta(SourceMetaEntity(DEVICE, null, null, stamp, stage, generation, 0, 0, 0))
    }

    @Test
    fun aNoteFromAMergeDuringAPhoneBuildIsNotShownByTheNextPhoneSwap() = runBlocking {
        db.put(library(DEVICE, 4))
        db.put(library(server, 3, msShift = 100))
        db.testMerge(server).rebuild()
        val song = db.stringOf("SELECT id FROM track WHERE title = 'Song 0'")!!
        val user = db.userDao()

        // A server copy's merge, with the phone's copy being built meanwhile;
        // the rating is tapped between its claim and its swap.
        phoneMeta(STAGE_BUILDING, 1)
        db.stage(library(server, 3, msShift = 100), generation = 2)
        val tapping = object : MergeHooks() {
            override suspend fun claimed() = user.noteRating(song, 3, null, 1L, copies = true)
        }
        db.testMerge(server, tapping).rebuild()
        assertEquals(3L, db.longOf("SELECT rating FROM track WHERE id = ?", song))
        assertEquals("notes left after the merge", 0L, db.longOf("SELECT COUNT(*) FROM rating_note"))

        // The phone's build ends with nothing to swap in; later a new rating
        // is tapped while nothing is merging.
        phoneMeta(STAGE_NONE, 1)
        user.noteRating(song, 5, null, 2L, copies = true)

        // The phone's next copy is swapped in: the later rating stays.
        db.stage(library(DEVICE, 5), generation = 3)
        db.testMerge(server).rebuild()
        assertEquals(5L, db.longOf("SELECT rating FROM track WHERE id = ?", song))
    }
}
