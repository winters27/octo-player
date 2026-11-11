package app.winters.octo.playlists

import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.data.spaceWords
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

// A matching job checks the disk before it stages anything and says what
// it was doing when the disk is short: "restore this backup" with how many
// MB to free, or "keep this list" for a playlist file; a disk that fills
// while the job writes is "some space", and the job's rows go either way.
// The space a staged entry takes is what MATCH_BYTES_PER_ENTRY says,
// measured on 3,000 entries with long titles and paths. Guard: the base
// matched in memory and wrote nothing.
@RunWith(RobolectricTestRunner::class)
class MatchDiskTest {
    private fun used(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    @Test
    fun aShortDiskIsToldBeforeStaging() = timed(30) {
        val db = openTestCatalog()
        try {
            val jobs = matchJobs(db, space = FreeSpace { 1_000 })
            val refused = runCatching { jobs.run(MatchKind.Backup, SpaceFor.Restore, 2_000) { error("staged on a short disk") } }.exceptionOrNull()
            assertTrue("$refused", refused is NotEnoughSpace)
            refused as NotEnoughSpace
            val needed = 2_000 * MATCH_BYTES_PER_ENTRY * 2 * 11 / 10
            assertEquals(needed, refused.neededBytes)
            assertEquals(
                "Not enough space to restore this backup. Free up ${(needed + 999_999) / 1_000_000} MB, then try again.",
                spaceWords(refused.doing, refused.neededBytes),
            )
            val playlist = runCatching { jobs.run(MatchKind.Playlist, SpaceFor.KeepList, 2_000) { error("staged on a short disk") } }.exceptionOrNull() as NotEnoughSpace
            assertTrue(spaceWords(playlist.doing, playlist.neededBytes).startsWith("Not enough space to keep this list. Free up "))
        } finally {
            db.close()
        }
    }

    @Test
    fun aDiskThatFillsEndsTheJobWithItsRowsGone() = timed(60) {
        val db = openTestCatalog()
        try {
            db.matchLibrary(60)
            db.counted.fullDisk = { sql -> sql.startsWith("UPDATE match_query SET wholeId") }
            val full = runCatching {
                matchJobs(db, page = 5).run(MatchKind.Backup, SpaceFor.Restore, 10) { job ->
                    job.stageSongs(0, (0 until 10).map { MatchSong(title = MATCH_TITLES[it], artist = "Drake") })
                    job.walk()
                }
            }.exceptionOrNull()
            db.counted.fullDisk = null
            assertTrue("$full", full is NotEnoughSpace)
            full as NotEnoughSpace
            assertEquals("Not enough space to restore this backup. Free up some space, then try again.", spaceWords(full.doing, full.neededBytes))
            listOf("match_query", "match_title", "match_lookup").forEach { t ->
                assertEquals(t, 0, db.query("SELECT COUNT(*) FROM $t", null).use { it.moveToFirst(); it.getInt(0) })
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun anEntryTakesWhatItsConstantSays() = timed(60) {
        val dir = createTempDirectory("catalog").toFile()
        val db = openTestCatalog(dir)
        try {
            val long = " (Live at the Royal Albert Hall, London, 14 March 1971) [2011 Remastered Deluxe Edition]"
            db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val before = used(dir)
            val entries = 3_000
            matchJobs(db, page = 500).run(MatchKind.Playlist, SpaceFor.KeepList, entries.toLong()) { job ->
                (0 until entries).chunked(500).forEach { chunk ->
                    job.stageEntries(
                        chunk.map { i ->
                            M3uEntry(
                                "/storage/emulated/0/Music/The Royal Philharmonic Orchestra and Chorus/Greatest Hits $long/%02d Artist $i - Song $i$long.flac".format(i % 99),
                                seconds = 200, artist = "Artist $i & The Royal Philharmonic Orchestra and Chorus", title = "Song $i$long", line = i,
                            )
                        },
                    )
                }
                val perEntry = (used(dir) - before) / entries
                println("MEASURED match entries=$entries perEntry=$perEntry")
                println("RESULT match_bytes_per_entry=$perEntry")
                assertTrue("measured $perEntry, constant $MATCH_BYTES_PER_ENTRY", perEntry <= MATCH_BYTES_PER_ENTRY)
                assertTrue("measured $perEntry, constant $MATCH_BYTES_PER_ENTRY", MATCH_BYTES_PER_ENTRY <= perEntry * 11 / 10 + 10)
            }
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }
}
