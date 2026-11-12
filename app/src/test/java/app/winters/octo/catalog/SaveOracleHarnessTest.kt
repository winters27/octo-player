package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The oracle (SaveOracle) a save is measured by fails on each mutation of
// the list: a column dropped, a reordered insert where the rowid is the
// key, a set left out, a reference sent to a song picked from what has
// landed, and an adoption followed by key. It compares rows by key, never
// by rowid elsewhere; it leaves the carve-outs to their own assertions;
// and today's adoptions (TodayAdoptions) change adopted finds alone, each
// to its old song's final merged id.
@RunWith(RobolectricTestRunner::class)
class SaveOracleHarnessTest {
    private val server = "server:oracle.test"

    // A library after today's swap of a phone file that a server copy and
    // a USB copy both match, with two songs of one relink key: the adopted
    // find on the server's copy follows to the phone's song, while its key
    // would take it to the lower id.
    private suspend fun swapped(adoptions: Boolean = true): CatalogDatabase {
        val db = openTestCatalog()
        fun source(id: String, ms: Long) = SourceCatalog(id, id == DEVICE, listOf(song(id, "s0", "Song 0", "album0", ms = ms)), listOf(albumRow(id, "album0", "Album 0")), listOf(artistRow(id)))
        db.put(source(server, 198_000))
        db.put(source("usb", 202_000))
        rebuildSettled(db, server)
        insertRow(db, "online_song", mapOf("id" to "find:1", "sourceId" to server, "nativeId" to "1", "title" to "F", "artist" to "A", "album" to "", "durationMs" to 1, "seenAt" to 1, "adoptedId" to "$server:s0"))
        insertRow(db, "liked_track", mapOf("trackId" to "$server:s0", "relinkKey" to "drake|song 0", "likedAt" to 1))
        insertRow(db, "playlist", mapOf("id" to "p", "name" to "P", "createdAt" to 1, "updatedAt" to 1))
        repeat(3) { insertRow(db, "play_event", mapOf("trackId" to "usb:s0", "relinkKey" to "drake|song 0", "startedAt" to it, "playedMs" to 1, "durationMs" to 1)) }
        val phone = source(DEVICE, 200_000)
        db.put(phone.copy(tracks = phone.tracks + song(DEVICE, "a0", "Song 0", "album1", ms = 400_000), albums = phone.albums + albumRow(DEVICE, "album1", "Album 1")))
        val (today, _) = claimAndBuild(db, server)
        todaySwap(db, today)
        if (adoptions) todayAdoptions(db)
        return db
    }

    @Test
    fun eachMutationOfTheListFailsTheOracle() = runBlocking {
        val db = swapped()
        try {
            val expected = SaveOracle.image(db.openHelper.writableDatabase)
            fun failsAfter(what: String, change: (CatalogDatabase) -> Unit) {
                val copy = copyCatalog(db)
                try {
                    change(copy)
                    val lines = SaveOracle.differences(expected, SaveOracle.image(copy.openHelper.writableDatabase))
                    assertTrue("$what: the oracle saw no difference", lines.isNotEmpty())
                } finally {
                    copy.close()
                }
            }
            // A copy as it is equals what it was copied from.
            val same = copyCatalog(db)
            assertEquals(emptyList<String>(), SaveOracle.differences(expected, SaveOracle.image(same.openHelper.writableDatabase)))
            same.close()
            failsAfter("a dropped column") { it.exec("UPDATE track SET genre = 'x'") }
            failsAfter("a reordered insert") {
                it.exec("CREATE TEMP TABLE plays AS SELECT * FROM play_event ORDER BY id DESC")
                it.exec("DELETE FROM play_event")
                it.exec("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) SELECT trackId, relinkKey, startedAt, playedMs, durationMs FROM plays")
            }
            failsAfter("a skipped set") { it.exec("DELETE FROM track WHERE id = ?", "$DEVICE:s0") }
            failsAfter("a target picked from what has landed") { it.exec("UPDATE liked_track SET trackId = ? WHERE trackId = ?", "$DEVICE:a0", "$DEVICE:s0") }
            failsAfter("an adoption followed by key") {
                it.exec("UPDATE online_song SET adoptedId = ? WHERE id = 'find:1'", "$DEVICE:a0")
            }
        } finally {
            db.close()
        }
    }

    // The same rows inserted in another order compare equal where the rowid
    // is no key, and the carve-outs stay out of the compare, each kept
    // beside it.
    @Test
    fun rowsCompareByKeyAndCarveOutsStayApart() = runBlocking {
        val db = swapped()
        try {
            val expected = SaveOracle.image(db.openHelper.writableDatabase)
            val copy = copyCatalog(db)
            try {
                copy.exec("CREATE TEMP TABLE songs AS SELECT * FROM track ORDER BY rowid DESC")
                copy.exec("DELETE FROM track")
                copy.exec("INSERT INTO track SELECT * FROM songs")
                copy.exec("UPDATE merge_state SET swapCount = swapCount + 5, totalsAccount = 'a'")
                copy.exec("INSERT INTO save_rename (kind, old, new, byKey, `key`) VALUES ('track', 'x', 'y', 0, '')")
                val actual = SaveOracle.image(copy.openHelper.writableDatabase)
                assertEquals(emptyList<String>(), SaveOracle.differences(expected, actual))
                val order = "SELECT group_concat(id, ',') FROM (SELECT id FROM track ORDER BY rowid)"
                assertTrue("the songs' rowids moved", copy.stringOf(order) != db.stringOf(order))
                val carved = actual.carved.getValue("merge_state (carved)").rows.values.single()
                assertTrue("the swap count is kept apart: $carved", (expected.carved.getValue("merge_state (carved)").rows.values.single()[0] as Long) + 5 == carved[0])
                assertEquals(1, actual.carved.getValue("save_rename").rows.size)
            } finally {
                copy.close()
            }
        } finally {
            db.close()
        }
    }

    // Today's adoptions change adopted finds and nothing else, each to the
    // final merged id of the source row it named; here, one moves.
    @Test
    fun todaysAdoptionsMoveAdoptedFindsAloneToTheirSongsFinalMergedId() = runBlocking {
        val with = swapped(adoptions = true)
        val without = swapped(adoptions = false)
        try {
            val changes = SaveOracle.changes(SaveOracle.image(without.openHelper.writableDatabase), SaveOracle.image(with.openHelper.writableDatabase))
            assertEquals(setOf("online_song"), changes.keys)
            val moved = changes.getValue("online_song").changed
            assertTrue("no adoption moved", moved.isNotEmpty())
            for ((key, cols) in moved) {
                assertEquals(setOf("adoptedId"), cols.keys)
                val (old, new) = cols.getValue("adoptedId")
                assertEquals("$key", with.stringOf("SELECT mergedId FROM source_track WHERE id = ?", old), new)
            }
        } finally {
            with.close()
            without.close()
        }
    }

    // A copy keeps the work rows its file held: no open cleanup runs on it.
    @Test
    fun aCopyKeepsItsWorkRows() = runBlocking {
        val db = openTestCatalog()
        try {
            db.stage(library(server, 5), generation = 2)
            val copy = copyCatalog(db)
            try {
                assertEquals(5L, copy.longOf("SELECT COUNT(*) FROM stage_track"))
                assertEquals(STAGE_READY, copy.stringOf("SELECT stage FROM source_meta WHERE sourceId = ?", server))
            } finally {
                copy.close()
            }
        } finally {
            db.close()
        }
    }
}
