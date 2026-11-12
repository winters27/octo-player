package app.winters.octo.backup

import androidx.room.withTransaction
import app.winters.octo.catalog.NewItem
import app.winters.octo.listening.timed
import app.winters.octo.playback.rename
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

// A restore writes from the job's rows a page at a time: a song liked
// twice in the file, or already liked, is liked once; a song rated twice
// takes its last stars, 1 to 5 only; a playlist's songs go to its new set
// in order, repeats kept. A swap between the plan and the restore is
// followed in each page's own transaction with no second match: a renamed
// song is written under its new id, and one gone is written nowhere. Each
// like and rating page is written inside a Page hold of the merge lock; no
// statement reads more than a page.
@RunWith(RobolectricTestRunner::class)
class RestoreApplyTest {
    private val rig = BackupRig(openTestCatalog())

    @After
    fun close() = rig.close()

    private fun key(i: Int) = SongKey("rk-t$i", "Song $i", "Artist", "Album", 200_000)

    // A swap's rename: the song under its new id, its old id's copy merged
    // into it, and the swap count moved.
    private suspend fun rename(from: String, to: String) = rig.db.withTransaction {
        rig.db.rename(from, to)
        rig.db.workDao().raiseSwapCount()
    }

    @Test
    fun aRestoreWritesEachPageFollowed() = timed(60) {
        rig.library((0 until 1_200).map { backupTrack("t$it", "Song $it", relinkKey = "rk-t$it") })
        rig.like("t3", 1)
        val backup = Backup(
            likes = (0 until 1_100).map(::key) + listOf(key(5), key(3), key(9)),
            ratings = (0 until 600).map { RatedSong(key(it), it % 5 + 1) } + listOf(RatedSong(key(1), 5), RatedSong(key(2), 0)),
            playlists = listOf(PlaylistBackup("Mix", (0 until 700).map { key(it % 650) })),
        )
        val opened = runBlocking { rig.songs.read { ByteArrayInputStream(encodeBackup(backup).toByteArray()) } } as BackupOpened.Opened
        val plan = runBlocking { rig.songs.plan(opened.file, emptyList()) }
        assertEquals(1_100, plan.likes)
        // Between the plan and the restore a swap renames one song and
        // takes another away.
        runBlocking {
            rename("t7", "t7-new")
            rig.db.openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = 't8'")
        }
        val liked = ArrayList<String>()
        val rated = LinkedHashMap<String, Int>()
        var outsideHold = false
        val made = ArrayList<NewItem>()
        rig.db.counted.reset()
        val ids = runBlocking {
            rig.songs.restore(
                opened.file,
                plan,
                create = { name, pages ->
                    pages { page -> made += page }
                    "id-$name"
                },
                liked = { liked += it },
                rate = { id, stars ->
                    if (!rig.merge.holdsMergeLock()) outsideHold = true
                    rated[id] = stars
                },
            )
        }
        assertTrue("${rig.db.counted.maxRead}", rig.db.counted.maxRead!!.rowsRead <= 600)
        assertEquals(mapOf("mix" to "id-Mix"), ids)
        // Liked once each, the one already liked left as it was.
        val expected = (0 until 1_100).map { "t$it" }.filter { it != "t3" && it != "t8" }.map { if (it == "t7") "t7-new" else it }
        assertEquals(expected, liked)
        val likedNow = rig.db.query("SELECT trackId FROM liked_track", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.toSet()
        assertTrue("t7-new" in likedNow)
        assertFalse("t7" in likedNow || "t8" in likedNow)
        // Last stars win; 0 is no rating.
        assertFalse(outsideHold)
        assertEquals(5, rated["t1"])
        assertEquals(3, rated["t2"])
        assertEquals(3, rated["t7-new"])
        assertFalse("t8" in rated || "t7" in rated)
        // The playlist in order, repeats kept, followed, the gone song left out.
        val wanted = (0 until 700).map { "t${it % 650}" }.filter { it != "t8" }.map { if (it == "t7") "t7-new" else it }
        assertEquals(wanted, made.map { it.trackId })
        runBlocking { rig.songs.close(opened.file) }
        assertEquals(0, rig.db.query("SELECT COUNT(*) FROM match_query", null).use { it.moveToFirst(); it.getInt(0) })
    }
}
