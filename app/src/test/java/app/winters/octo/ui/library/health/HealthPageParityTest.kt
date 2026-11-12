package app.winters.octo.ui.library.health

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.data.serverSongIds
import app.winters.octo.health.HealthCheck
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.library.health.today.TodayLine
import app.winters.octo.ui.paging.SqlOrder
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import app.winters.octo.ui.library.health.today.fixAllPreview as todayPreview
import app.winters.octo.ui.library.health.today.joinPlan as todayJoin
import app.winters.octo.ui.library.health.today.lines as todayLines
import app.winters.octo.ui.library.health.today.lookupBatch as todayLookup

// A labeled pin. Each check's page, read a few lines at a time from the
// published report, shows today's lines: each set of copies under its
// heading and words with its songs and their notes, each split album under
// its heading with its songs part by part, and each check's songs; every
// set's Fix and Keep see today's set and copy, every album's Join shows
// where today's plan was made, every song's Look up has its id on the
// server. Fix all's plans, made from the report a page of sets or songs at a
// time, count, preview and send today's: the counts, the first page of
// lines, and every step, each page of sets checked just before its steps
// go. The bulk look-up takes today's songs.
@RunWith(RobolectricTestRunner::class)
class HealthPageParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val source = "device"

    private suspend fun library(seed: Int): HealthRig {
        db.openHelper.writableDatabase.execSQL("DELETE FROM source_track")
        db.openHelper.writableDatabase.execSQL("DELETE FROM track")
        db.openHelper.writableDatabase.execSQL("DELETE FROM album")
        db.healthLibrary(albums = 50, seed = seed)
        return HealthRig(db, page = 9, cache = 16).keys().also { it.built() }
    }

    private suspend fun copies(): List<SourceTrackEntity> =
        db.sourceDao().sourceIds().flatMap { db.sourceDao().tracks(it) }.filter { it.mergedId.isNotEmpty() }
            .sortedWith { a, b -> SqlOrder.text(a.sourceId, b.sourceId).takeIf { it != 0 } ?: a.seq.compareTo(b.seq) }

    private suspend fun fields(copies: List<SourceTrackEntity>) =
        TrackHealth(db.dao().albums().first().associate { it.id to it.artist }, copies.associateBy { it.mergedId })

    @Test
    fun eachChecksPageIsTodays() = timed(240) {
        for (seed in 1..2) {
            val rig = library(seed)
            val today = rig.today()
            val copies = copies()
            val serverIds = serverSongIds(copies, source)
            val fields = fields(copies)
            for (check in HealthCheck.entries) {
                db.counted.reset()
                val page = rig.page(check, source)
                assertTrue("$check: ${db.counted.maxRead}", (db.counted.maxRead?.rowsRead ?: 0) <= 900)
                val shown = page.map { (_, row) ->
                    when (row) {
                        is HealthRow.Copies -> TodayLine.Heading(row.title, row.detail)
                        is HealthRow.Split -> TodayLine.Heading(row.title, row.detail)
                        is HealthRow.Song -> TodayLine.Song(row.track.id, row.note)
                    }
                }
                assertEquals("seed $seed: $check", todayLines(today, check), shown)
                val loader = rig.build.lines(check, source)
                assertEquals("seed $seed: $check count", page.size, loader.count())
                // Read back from the end, and jumped into, the same lines.
                if (page.size > 3) {
                    assertEquals(page.dropLast(1).takeLast(5).reversed().map { it.key }, loader.before(page.last().key, 5).map { it.key })
                    assertEquals(page[page.size / 2].key, loader.keyAt(page.size / 2))
                    assertEquals(page.size / 2, loader.survey(page[page.size / 2].key).anchorAt)
                }
                // What each line's buttons act on.
                var set = -1
                var album = -1
                for ((_, row) in page) {
                    when (row) {
                        is HealthRow.Copies -> {
                            set++
                            assertEquals(today.copyGroups[set].copies.map { it.id }, row.group.copies.map { it.id })
                        }
                        is HealthRow.Split -> {
                            album++
                            assertEquals("seed $seed album $album", todayJoin(today, album, fields, serverIds) != null, row.joinable)
                        }
                        is HealthRow.Song -> {
                            if (check == HealthCheck.Duplicates) {
                                assertEquals(today.copyGroups[set].copies.first { it.mergedId == row.track.id }.id, row.copy?.id)
                            }
                            val looksUp = check.tag != null && check != HealthCheck.NoCover
                            assertEquals(if (looksUp) serverIds[row.track.id] else null, row.serverId)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun fixAllPlansAreTodays() = timed(240) {
        for (seed in 1..2) {
            val rig = library(seed)
            val today = rig.today()
            val copies = copies()
            val serverIds = serverSongIds(copies, source)
            val fields = fields(copies)
            val tracks = db.dao().tracks().first()
            for (check in listOf(HealthCheck.Duplicates, HealthCheck.SplitAlbums, HealthCheck.NoCover, HealthCheck.NoYear, HealthCheck.NoGenre, HealthCheck.NoAlbumArtist)) {
                for (edit in listOf(true, false)) {
                    val pin = todayPreview(today, tracks, fields, check, edit, source, serverIds)
                    val counted = rig.plans.count(check, source, edit)!!
                    assertEquals("seed $seed: $check", pin.count to pin.steps.size, counted.count to counted.total)
                    db.counted.reset()
                    val plan = rig.plans.open(check, source, edit, counted)!!
                    assertTrue("$check: ${db.counted.maxRead}", (db.counted.maxRead?.rowsRead ?: 0) <= 900)
                    assertEquals("seed $seed: $check", pin.count, plan.count)
                    assertEquals("seed $seed: $check", pin.lines.take(PREVIEW_LINES), plan.lines)
                    assertEquals("seed $seed: $check", pin.steps, rig.steps(plan))
                }
            }
            // Each page of sets is checked just before its own steps go.
            val events = ArrayList<String>()
            val plan = rig.plans.open(HealthCheck.Duplicates, source, true)!!
            val steps = plan.steps { sets -> events += "check ${sets.size}"; sets.map { true } }
            var after: Long? = null
            while (true) {
                val page = steps.next(after)
                if (page.isEmpty()) break
                page.forEach { events += "send" }
                after = page.last().ord
            }
            steps.close()
            assertTrue(events.first().startsWith("check"))
            assertTrue("$events", events.filter { it.startsWith("check") }.all { it.removePrefix("check ").toInt() <= FIX_ALL_RECHECK_PAGE })
            // The bulk look-up.
            val noNumber = today.report.songs(HealthCheck.NoTrackNumber)
            assertEquals(todayLookup(noNumber, serverIds).map { it.first.id to it.second }, rig.plans.lookUpBatch(HealthCheck.NoTrackNumber, source).map { it.first.id to it.second })
        }
    }

    // A preview counts more than it lists: the first page, then how many
    // more.
    @Test
    fun aPreviewHoldsItsFirstPageOnly() = timed(120) {
        val rig = library(4)
        val counted = rig.plans.count(HealthCheck.NoCover, source, true)!!
        assertTrue("${counted.count} songs with no cover", counted.count > PREVIEW_LINES)
        val plan = rig.plans.open(HealthCheck.NoCover, source, true)!!
        assertEquals(PREVIEW_LINES, plan.lines.size)
        assertEquals(counted.count, plan.count)
        assertEquals(counted.count, rig.steps(plan).size)
        // Put aside, its report goes once a newer one is published.
        val other = rig.plans.open(HealthCheck.NoCover, source, true)!!
        other.putAside()
    }
}
