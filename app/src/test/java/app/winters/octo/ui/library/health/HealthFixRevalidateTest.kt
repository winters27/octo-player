package app.winters.octo.ui.library.health

import androidx.room.withTransaction
import app.winters.octo.catalog.titleLookup
import app.winters.octo.health.FixStep
import app.winters.octo.health.duplicateFix
import app.winters.octo.health.findDuplicates
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A fix made from a report checks its set again first, by its songs'
// current ids: (a) a set the library no longer pairs (a copy retitled since
// the report) is found gone, so its Fix opens nothing and the page checks
// the library again at once; (b) a set whose song was renamed since still
// holds, read under the new id, and its fix removes the lesser copy as it
// is now; (e) a set that lost one copy opens as the check finds it now;
// (c) a split album whose part was retagged since is found gone and opens
// no Join; (f) a split album the server has nothing of would join nothing,
// so it counts as gone too; (g) a split album that lost one part opens its
// Join as the check finds it now, with steps; (d) Fix all over (a), (b)
// and a clean set sends only (b)'s and the clean one's steps, a page of
// sets at a time. Guard: the base fixed sets as shown, and a set found
// gone left its line on the page.
@RunWith(RobolectricTestRunner::class)
class HealthFixRevalidateTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun exec(sql: String, vararg args: Any?) = db.openHelper.writableDatabase.execSQL(sql, args)

    private fun song(id: String, title: String, album: String, ms: Long, albumId: String = "al-$album") = exec(
        "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey, genre, " +
            "lookupKey, looseKey) VALUES (?, 'device', ?, ?, ?, ?, 'Artist', 'ar', ?, ?, ?, 0, '', '', ?, ?)",
        id, id, title, title.lowercase(), title.lowercase(), album, albumId, ms,
        titleLookup(title, "Artist").lookupKey, titleLookup(title, "Artist").looseKey,
    )

    private fun copy(id: String, song: String, title: String, album: String, ms: Long, mime: String) = exec(
        "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, albumOrder, relinkKey, genre, " +
            "mimeType, mergedId, seq) VALUES (?, 'device', ?, ?, ?, ?, 'Artist', 'ar', ?, ?, ?, 0, 0, '', '', ?, ?, (SELECT COUNT(*) FROM source_track))",
        id, id, title, title.lowercase(), title.lowercase(), album, "al-$album", ms, mime, song,
    )

    private fun album(id: String, title: String) = exec(
        "INSERT INTO album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, songCount, durationMs, addedAt) VALUES (?, 'device', ?, ?, ?, ?, 'X', 'arX', 1, 0, 0)",
        id, id, title, title.lowercase(), title.lowercase(),
    )

    @Test
    fun fixesCheckTheirSetsAgain() = timed(60) {
        album("al-Views", "Views")
        album("al-Same", "Same Title")
        album("al-Same2", "Same Title")
        listOf("al-Trio1", "al-Trio2", "al-Trio3", "al-Lone1", "al-Lone2").forEach { album(it, it.removePrefix("al-").dropLast(1)) }
        // (a) two songs whose copies read the same.
        song("a1", "Song A", "Views", 180_000)
        song("a2", "Song A", "Views", 181_000)
        copy("ca1", "a1", "Song A", "Views", 180_000, "audio/flac")
        copy("ca2", "a2", "Song A", "Views", 181_000, "audio/mpeg")
        // (b) two songs whose copies read the same, one renamed after the
        // report.
        song("b1", "Song B", "Views", 200_000)
        song("b2", "Song B", "Views", 200_500)
        copy("cb1", "b1", "Song B", "Views", 200_000, "audio/flac")
        copy("b2", "b2", "Song B", "Views", 201_000, "audio/mpeg")
        // A clean set.
        song("c1", "Song C", "Views", 220_000)
        song("c2", "Song C", "Views", 220_400)
        copy("cc1", "c1", "Song C", "Views", 220_000, "audio/flac")
        copy("cc2", "c2", "Song C", "Views", 220_500, "audio/mpeg")
        // (e) three songs whose copies read the same; one retitled after.
        song("e1", "Song E", "Views", 240_000)
        song("e2", "Song E", "Views", 240_200)
        song("e3", "Song E", "Views", 240_400)
        copy("ce1", "e1", "Song E", "Views", 240_000, "audio/flac")
        copy("ce2", "e2", "Song E", "Views", 240_200, "audio/mpeg")
        copy("ce3", "e3", "Song E", "Views", 240_400, "audio/mpeg")
        // (c) one album split in two by its id.
        song("s1", "One", "Same Title", 100_000, "al-Same")
        song("s2", "Two", "Same Title", 100_000, "al-Same2")
        // (g) one album split in three; one part retagged after.
        song("t1", "One", "Trio", 100_000, "al-Trio1")
        song("t2", "Two", "Trio", 100_000, "al-Trio2")
        song("t3", "Three", "Trio", 100_000, "al-Trio3")
        // (f) one album split in two that the server has none of.
        song("l1", "One", "Lone", 100_000, "al-Lone1")
        song("l2", "Two", "Lone", 100_000, "al-Lone2")
        val rig = HealthRig(db, db.testMerge(null))
        val today = rig.today()
        val groups = findDuplicates(db.sourceDao().tracks("device").sortedBy { it.seq }, SourceCopyHealth)
            .associateBy { g -> g.copies.map { it.id }.sorted().joinToString() }
        val a = groups.getValue("ca1, ca2")
        val b = groups.getValue("b2, cb1")
        val clean = groups.getValue("cc1, cc2")
        val e = groups.getValue("ce1, ce2, ce3")
        val splits = today.report.splitAlbums.associateBy { it.parts[0].songs[0].album }
        val split = splits.getValue("Same Title")
        val trio = splits.getValue("Trio")
        val lone = splits.getValue("Lone")
        assertEquals(3, trio.parts.size)

        // What the library does after the report.
        db.withTransaction {
            exec("UPDATE source_track SET title = 'Something Else', searchKey = 'something else', sortKey = 'something else' WHERE id = 'ca2'")
            song("b2n", "Song B", "Views", 200_500)
            exec("UPDATE source_track SET mergedId = 'b2n' WHERE mergedId = 'b2'")
            exec("DELETE FROM track WHERE id = 'b2'")
            exec("UPDATE source_track SET title = 'Song E Live', searchKey = 'song e live', sortKey = 'song e live' WHERE id = 'ce3'")
            exec("UPDATE track SET album = 'Different' WHERE id = 's2'")
            exec("UPDATE track SET album = 'Elsewhere' WHERE id = 't3'")
        }
        val recheck = HealthRecheck(db, rig.merge)
        val everySong: suspend (List<String>) -> Map<String, String> = { ids -> ids.associateWith { "n-$it" } }

        // (a) gone: nothing opens, and the page checks again.
        val nowA = recheck.duplicates(a)
        assertTrue("$nowA", nowA is Recheck.Changed)
        assertNull(nowA.now)
        var gone = recheck.gone.value
        assertNull(recheck.fixSheet("Song A", a, null))
        assertEquals(gone + 1, recheck.gone.value)

        // (b) the same set under its song's new id.
        val nowB = recheck.duplicates(b)
        assertTrue("$nowB", nowB is Recheck.Same)
        val fix = duplicateFix(nowB.now!!, ServerCopyHealth, nowB.now!!.best)
        assertEquals(listOf("b2"), fix.remove.map { it.id })
        assertEquals(listOf("b2n"), fix.remove.map { it.mergedId })
        gone = recheck.gone.value
        val sheetB = recheck.fixSheet("Song B", b, b.copies.first { it.id == "b2" })!!
        assertEquals("Song B", sheetB.title)
        assertEquals("b2n", sheetB.keep!!.mergedId)
        assertEquals(gone, recheck.gone.value)

        // (e) changed: opens on the set as the check finds it now.
        val sheetE = recheck.fixSheet("Song E", e, e.copies.first { it.id == "ce3" })!!
        assertEquals(listOf("ce1", "ce2"), sheetE.group.copies.map { it.id }.sorted())
        assertNull(sheetE.keep)
        assertEquals(gone, recheck.gone.value)

        // (c) the split album is gone: no Join opens.
        val nowSplit = recheck.split(split)
        assertTrue("$nowSplit", nowSplit is Recheck.Changed)
        assertNull(recheck.joinSheet("Same Title", split, everySong))
        assertEquals(gone + 1, recheck.gone.value)

        // (f) still split, but nothing of it on the server: nothing to join.
        gone = recheck.gone.value
        assertTrue(recheck.split(lone) is Recheck.Same)
        assertNull(recheck.joinSheet("Lone", lone) { emptyMap() })
        assertEquals(gone + 1, recheck.gone.value)

        // (g) changed: opens on the two parts left, with their steps.
        gone = recheck.gone.value
        val join = recheck.joinSheet("Trio", trio, everySong)!!
        assertTrue(join.steps.isNotEmpty())
        assertTrue("${join.steps}", join.steps.all { it is FixStep.JoinAlbum && it.id in setOf("n-t1", "n-t2") })
        assertTrue("${join.steps}", join.steps.none { it.id == "n-t3" })
        assertEquals(gone, recheck.gone.value)

        // (d) Fix all: each page of sets checked just before its steps go.
        val all = recheck.duplicatePage(listOf(a, b, clean))
        assertEquals(listOf(false, true, true), all.map { it is Recheck.Same })
        val sets = listOf(a, b, clean).map { set -> FixSet(group = set, steps = set.copies.drop(1).map { FixStep.Remove(it.nativeId, it.title, copy = true) }) }
        gone = recheck.gone.value
        val checked = ArrayList<Int>()
        val source = FixAllSteps(sets, { page -> checked += page.size; recheck.samePage(page) }, page = 2)
        assertEquals(3, source.total)
        val first = source.next(null)
        assertEquals(listOf(2), checked)
        assertEquals(listOf(sets[1].steps.single()), first.map { it.step })
        val second = source.next(first.last().ord)
        assertEquals(listOf(sets[2].steps.single()), second.map { it.step })
        assertTrue(second.single().ord > first.single().ord)
        assertEquals(listOf(2, 1), checked)
        assertTrue(source.next(second.last().ord).isEmpty())
        assertEquals(gone + 1, recheck.gone.value)
    }
}
