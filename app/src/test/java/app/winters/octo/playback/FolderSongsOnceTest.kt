package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.testing.PhoneFile
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.phoneLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A song with two phone files in two folders, merged into one library song,
// shows in one folder, as today, and a play of everything under their
// common folder queues it once, played whole or built in pages.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class FolderSongsOnceTest {
    private val rig = QueueRig(page = 50)

    @After
    fun close() = rig.close()

    private fun files(extra: Int) = PhoneFiles(2 + extra) { i ->
        when (i) {
            0, 1 -> PhoneFile(
                id = i + 1L, displayName = "01 Twice.flac", relativePath = if (i == 0) "Music/X/" else "Music/Y/",
                title = "Twice", artist = "Band", album = "Same", albumArtist = "Band", track = 1, year = 2000,
            )
            else -> PhoneFile(
                id = i + 1L, displayName = "%04d Other.flac".format(i), relativePath = "Music/Z/",
                title = "Other $i", artist = "Band", album = "Many", albumArtist = "Band", track = i, year = 2001,
            )
        }
    }

    private fun scan(extra: Int) {
        val phone = rig.db.phoneLibrary(files(extra), rig.merge)
        rig.run { phone.rescan() }
        // The two files are one library song, as the merge makes them.
        rig.run {
            val ids = twiceIds()
            if (ids.size > 1) {
                val sql = rig.db.openHelper.writableDatabase
                sql.execSQL("UPDATE source_track SET mergedId = ? WHERE mergedId IN (${ids.joinToString { "?" }})", arrayOf<Any?>(ids[0], *ids.toTypedArray()))
                sql.execSQL("DELETE FROM track WHERE id IN (${ids.drop(1).joinToString { "?" }})", arrayOf<Any?>(*ids.drop(1).toTypedArray()))
            }
        }
    }

    private suspend fun twiceIds(): List<String> = rig.db.queueDao().ids(app.winters.octo.catalog.queueQuery("SELECT id FROM track WHERE title = 'Twice' ORDER BY id"))

    private fun twice(): List<String> = rig.run { rig.db.queueDao().ids(app.winters.octo.catalog.queueQuery("SELECT id FROM track WHERE title = 'Twice'")) }

    @Test
    fun theMergedSongPlaysOnceFromTheParent() {
        scan(extra = 20)
        val id = twice().single()
        rig.play(PlayRequest(ListSpec.Folder("", recursive = true), null))
        rig.settle()
        assertEquals(1, rig.held().count { it == id })
        assertEquals(1, rig.songs().count { it == id })
        assertEquals(21, rig.songs().size)
    }

    @Test
    fun theMergedSongIsBuiltOnceFromTheParent() {
        scan(extra = 700)
        val id = twice().single()
        rig.play(PlayRequest(ListSpec.Folder("", recursive = true), null, shownOverWindow = true))
        rig.joined()
        assertEquals(1, rig.songs().count { it == id })
        assertEquals(701, rig.songs().size)
    }
}
