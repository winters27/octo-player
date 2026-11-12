package app.winters.octo.listening

import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.titleLookup
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A labeled pin: the stored totals and every list read from them or walked
// for History equal today's whole-list functions (the frozen copies in
// today/) on a generated library: phone plays, copies on two servers, sent
// plays held and not, copies of songs the library lacks, albums missing
// from the album table, equal counts and times, and a switch of account.
// Pages are small so every walk crosses many page ends.
@RunWith(RobolectricTestRunner::class)
class PlayTotalsParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun rig(account: String? = "home") = TotalsRig(db, db.testMerge(HOME), account, page = 7)

    private fun List<PlayedTrack>.counts() = map { Triple(it.track.id, it.plays, it.lastPlayedAt) }

    // Every stored song with a play, as today counts it.
    private fun stored(): Map<String, Pair<Int, Long>> {
        val rows = HashMap<String, Pair<Int, Long>>()
        db.query("SELECT trackId, plays, lastPlayedAt FROM play_total WHERE plays > 0", null).use { c ->
            while (c.moveToNext()) rows[c.getString(0)] = c.getInt(1) to c.getLong(2)
        }
        return rows
    }

    private suspend fun todayCounts(account: String?) = TodayPlays(db, account).tracks().associate { it.track.id to (it.plays to it.lastPlayedAt) }

    @Test
    fun storedTotalsAreTodaysCounts() = timed(30) {
        db.fillPlays(seed = 11, songs = 3_000, albums = 120)
        rig().started()
        assertEquals(todayCounts("home"), stored())
    }

    @Test
    fun theShelvesAreTodays() = timed(30) {
        db.fillPlays(seed = 12, songs = 3_000, albums = 120)
        val rig = rig().started()
        assertEquals(TodayPlays(db, "home").shelves(NOW).ids(), rig.shelves().ids())
    }

    @Test
    fun historysListsAreTodays() = timed(30) {
        db.fillPlays(seed = 13, songs = 2_000, albums = 80)
        val rig = rig().started()
        val today = TodayPlays(db, "home")
        for (since in listOf(null, NOW - 28 * DAY, NOW - 182 * DAY)) {
            assertEquals("since $since", today.mostPlayed(since, 100).counts(), value(rig.history.mostPlayed(since, 100).first()).counts())
        }
        assertEquals(
            today.recent(HISTORY_RECENT).map { it.track.id to it.at },
            rig.history.recentPlays(HISTORY_RECENT).first().map { it.track.id to it.at },
        )
    }

    @Test
    fun anotherAccountCountsTheSentPlaysItHolds() = timed(30) {
        db.fillPlays(seed = 14, songs = 1_000, albums = 40)
        val rig = rig().started()
        val version = rig.totals.version.value
        rig.account.value = "other"
        rig.totals.version.first { it > version }
        assertEquals(todayCounts("other"), stored())
    }

    // SQLite orders text by code point, so "�" comes before an emoji;
    // String.compareTo orders them the other way. Songs with such ids are
    // each counted once, from both streams.
    @Test
    fun idsPastTheBasicPlaneMergeInSqlitesOrder() = timed {
        for (id in listOf("a�", "a😀", "b")) {
            db.exec(
                "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey, " +
                    "lookupKey, looseKey) VALUES (?, 'device', ?, 'Song', 'song', 'song', 'Artist', 'ar', 'Album', 'al', 0, 0, '', ?, ?)",
                id, id, SONG_KEYS.lookupKey, SONG_KEYS.looseKey,
            )
            db.exec("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, '', 1000, 1000, 1000)", id)
            db.exec(
                "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, " +
                    "albumOrder, relinkKey, genre, mergedId, playCount, lastPlayedAt) VALUES (?, ?, ?, 'Song', 'song', 'song', 'Artist', 'ar', 'Album', 'al', 0, 0, 0, '', '', ?, 2, 5000)",
                "$HOME:$id", HOME, "n$id", id,
            )
        }
        rig().started()
        assertEquals(todayCounts("home"), stored())
        assertEquals(mapOf("a�" to (3 to 5000L), "a😀" to (3 to 5000L), "b" to (3 to 5000L)), stored())
    }

    // Two copies of one song in a range: which counts depends on their
    // order, declared as the order they were written. The first is the
    // phone's own play sent there (within a second of it), the second a
    // play elsewhere; the other way round both would count.
    @Test
    fun copiesInARangeCountInTheOrderTheyWereWritten() = timed {
        val at = NOW - 3 * DAY
        db.exec(
            "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey, " +
                "lookupKey, looseKey) VALUES ('t', 'device', 't', 'Song', 'song', 'song', 'Artist', 'ar', 'Album', 'al', 0, 0, '', ?, ?)",
            SONG_KEYS.lookupKey, SONG_KEYS.looseKey,
        )
        db.exec("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES ('t', '', ?, 1000, 1000)", at)
        for ((native, last) in listOf("first" to at + 500, "second" to at + 5_000)) {
            db.exec(
                "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, " +
                    "albumOrder, relinkKey, genre, mergedId, playCount, lastPlayedAt) VALUES (?, ?, ?, 'Song', 'song', 'song', 'Artist', 'ar', 'Album', 'al', 0, 0, 0, '', '', 't', 4, ?)",
                "$HOME:$native", HOME, native, last,
            )
        }
        val rig = rig().started()
        val top = value(rig.history.mostPlayed(NOW - 28 * DAY, 100).first())
        assertEquals(listOf(Triple("t", 2, at + 5_000)), top.counts())
        assertEquals(TodayPlays(db, "home").mostPlayed(NOW - 28 * DAY, 100).counts(), top.counts())
    }

    private companion object {
        // The lookup keys of the songs written here, all "Song" by "Artist".
        val SONG_KEYS = titleLookup("Song", "Artist")
    }
}
