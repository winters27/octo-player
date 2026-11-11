package app.winters.octo.ui.library.health

import app.winters.octo.catalog.titleLookup
import app.winters.octo.health.HealthCheck
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A health build over 20,000 songs, each with a copy, and 3,000 more
// copies of one title in one bucket (some within a few seconds of each
// other, some not), holds a page: no statement reads more than 900 rows or
// binds more than 900 values, counted at the database below Room, and the
// giant bucket's sets come out whole. The base read every song and every
// copy at once.
@RunWith(RobolectricTestRunner::class)
class HealthResidencyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aBuildHoldsAPage() = timed(240) {
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            for (i in 0 until 20_000) {
                val keys = titleLookup("Song $i", "Artist")
                sql.execSQL(
                    "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey, genre, albumOrder, " +
                        "lookupKey, looseKey) VALUES (?, 'device', ?, ?, ?, ?, 'Artist', 'ar', 'Album', ?, ?, 0, '', '', ?, ?, ?)",
                    arrayOf<Any>("t$i", "t$i", "Song $i", "song $i", "song %05d".format(i), "al${i / 10}", 200_000 + i % 50, i % 10, keys.lookupKey, keys.looseKey),
                )
                sql.execSQL(
                    "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, albumOrder, " +
                        "relinkKey, genre, mergedId, seq) VALUES (?, 'device', ?, ?, ?, ?, 'Artist', 'ar', 'Album', ?, ?, 0, 0, '', '', ?, ?)",
                    arrayOf<Any>("c$i", "c$i", "Song $i", "song $i", "song $i", "al${i / 10}", 200_000 + i % 50, "t$i", i),
                )
            }
            for (j in 0 until 3_000) {
                sql.execSQL(
                    "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, albumOrder, " +
                        "relinkKey, genre, mergedId, seq) VALUES (?, 'device', ?, 'Intro', 'intro', 'intro', 'Artist', 'ar', 'Album', 'al0', ?, 0, 0, '', '', ?, ?)",
                    arrayOf<Any>("g$j", "g$j", 60_000L + (j / 3) * 10_000L, "t${j % 20_000}", 20_000 + j),
                )
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        val rig = HealthRig(db).keys()
        db.counted.reset()
        val shown = rig.built()
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 900)
        assertTrue("${db.counted.maxBound}", db.counted.maxBound!!.boundArgs <= 900)
        assertEquals(20_000, shown.checked)
        // Every three copies of "Intro" of one length are one set, and no
        // other copies are.
        val sets = rig.groups(shown.gen)
        assertEquals(1_000, sets.size)
        assertTrue(sets.all { (copies, _, _) -> copies.size == 3 && copies.all { it.startsWith("g") } })
        assertEquals(1_000, shown.count(HealthCheck.Duplicates))
    }
}
