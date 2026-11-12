package app.winters.octo.backup

import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

// A backup of 20,000 likes and a 20,000-song phone playlist is written,
// read, planned and restored with no statement reading more than a page
// of rows: the export walks the likes, the ratings and each playlist a page
// at a time, and the restore matches and writes a page at a time. Guard:
// the base read every like, every song and every playlist entry whole.
@RunWith(RobolectricTestRunner::class)
class BackupResidencyTest {
    private val rig = BackupRig(openTestCatalog(), page = 100)

    @After
    fun close() = rig.close()

    private val n = 20_000

    // A title of its own for each song, in letters, since a title's lookup
    // key keeps its letters alone.
    private fun title(i: Int): String {
        val letters = StringBuilder()
        var k = i
        repeat(4) {
            letters.append('a' + k % 26)
            k /= 26
        }
        return "Song $letters"
    }

    @Test
    fun aBackupOf20000LikesAndA20000SongPlaylistHoldsAPage() = timed(240) {
        rig.library((0 until n).map { backupTrack("t$it", title(it), "Artist ${it % 31}", "Album ${it % 97}") })
        val sql = rig.db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            val like = sql.compileStatement("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES (?, ?, ?)")
            for (i in 0 until n) {
                like.clearBindings()
                like.bindString(1, "t$i")
                like.bindString(2, "rk-t$i")
                like.bindLong(3, 10_000L + i)
                like.executeInsert()
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        rig.playlist("p", "Everything", 100, (0 until n).map { "t$it" })
        rig.db.counted.reset()
        val (bytes, answer) = rig.export(Backup(createdAt = 1))
        assertEquals(BackupSave.Saved, answer)
        val export = rig.db.counted.maxRead
        assertTrue("the export read: $export", export != null && export.rowsRead <= 600)

        rig.db.counted.reset()
        val opened = runBlocking { rig.songs.read { ByteArrayInputStream(bytes!!) } } as BackupOpened.Opened
        val plan = runBlocking { rig.songs.plan(opened.file, emptyList()) }
        assertEquals(n, plan.likes)
        val planned = rig.db.counted.maxRead
        assertTrue("the plan read: $planned", planned != null && planned.rowsRead <= 600)

        rig.db.counted.reset()
        var made = 0
        runBlocking {
            rig.songs.restore(
                opened.file,
                plan,
                create = { name, pages ->
                    pages { page -> made += page.size }
                    "id-$name"
                },
                liked = {},
                rate = { _, _ -> },
            )
        }
        val restored = rig.db.counted.maxRead
        assertTrue("the restore read: $restored", restored != null && restored.rowsRead <= 600)
        assertEquals(n, made)
    }
}
