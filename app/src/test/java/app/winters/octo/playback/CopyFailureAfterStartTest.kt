package app.winters.octo.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogCallback
import app.winters.octo.catalog.LaunchMark
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.data.spaceWords
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.playlists.PieceBody
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.counted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

// Labeled guard: a list the server answers for whose copy fails after the
// tapped song plays leaves the queue that one song, with words for what is
// missing, and the copy's rows go: the disk full as the build copies the
// page's read, or the server's answer cut short as a build come back after
// the app was stopped reads it again.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CopyFailureAfterStartTest {
    private val songs = (0 until 2_000).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist") }

    @Test
    fun aCopyThatFailsAfterTheTappedSongPlaysLeavesThatSong() {
        NetworkQueueRig(songs).use { net ->
            net.db.putLibrary((0 until 2_000 step 2).map { libraryTrack("t$it", "Title $it") }) { "srv${it.id.removePrefix("t")}" }
            val entries = (0 until 2_000).map { "srv$it" }
            net.server.lists["L"] = entries
            val today = todayResolve(net.db, SERVER_SOURCE, entries.map { songs.getValue(it) }).map { it.id }
            runBlocking { net.reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1")) }
            // From here the disk is full for a server list's rows.
            net.db.counted.fullDisk = { sql -> sql.trimStart().startsWith("INSERT INTO server_entry") }
            val tapped = today[700]
            val key = runBlocking { net.reads.loader(WorkOwners.marked("vm:1")).keyOf(tapped) }
            assertTrue(net.play(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), ListStart.Row(tapped, key), shownOverWindow = true)).started)
            net.joined()
            assertEquals(listOf(tapped), net.songs())
            assertEquals(tapped, net.current())
            net.until("the queue's words") { spaceWords(SpaceFor.Queue, null) in net.words }
            net.db.counted.fullDisk = null
            assertEquals(0, net.count("SELECT COUNT(*) FROM server_entry WHERE owner LIKE '${WorkOwners.marked("queue:")}%'"))
            assertEquals(0, net.count("SELECT COUNT(*) FROM server_head WHERE owner LIKE '${WorkOwners.marked("queue:")}%'"))
        }
    }

    // The tapped song plays from the play's own read of the list; the app
    // stops before the build writes a page; the next launch opens the
    // database, which keeps every row, and its sweep takes the stopped
    // launch's working rows; the build comes back
    // with the queue and reads the list again, and the server's answer is
    // cut short partway: the queue keeps the tapped song, says the rest is
    // missing, and no copy row is left.
    @Test
    fun aStreamThatFailsAfterTheStartLeavesTheTappedSong() {
        val first = NetworkQueueRig(songs)
        var later: NetworkQueueRig? = null
        try {
            val db = first.db
            val server = first.server
            db.putLibrary((0 until 2_000 step 2).map { libraryTrack("t$it", "Title $it") }) { "srv${it.id.removePrefix("t")}" }
            val entries = (0 until 2_000).map { "srv$it" }
            server.lists["L"] = entries
            val tapped = todayResolve(db, SERVER_SOURCE, entries.map { songs.getValue(it) }).map { it.id }[700]
            val held = CompletableDeferred<Unit>()
            first.builds.buildHooks = object : BuildHooks() {
                override suspend fun beforePage(listId: Long, page: Int) {
                    held.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            }
            assertTrue(first.play(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), ListStart.Row(tapped, null), shownOverWindow = true)).started)
            assertEquals(tapped, first.current())
            first.until("the build is about to write") { held.isCompleted }
            first.window.saveState()
            first.settle()
            // The app stops.
            first.window.release()
            first.scope.cancel()
            first.store.scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            // The next launch: its open deletes no row, and its sweep takes
            // the stopped launch's.
            val kept = first.count("SELECT COUNT(*) FROM server_entry")
            LaunchMark.next()
            CatalogCallback.onOpen(db.openHelper.writableDatabase)
            assertEquals("the open deleted no row", kept, first.count("SELECT COUNT(*) FROM server_entry"))
            runBlocking { PlaylistSets(db).let { it.sweep(); it.idle() } }
            assertEquals(0, first.count("SELECT COUNT(*) FROM server_entry"))
            val asked = server.calls.count { it == "getPlaylist" }
            // From here the answer stops a third of the way in.
            server.listBody = { text ->
                val bytes = text.toByteArray()
                PieceBody(bytes.size.toLong()) { sink ->
                    sink.write(bytes, 0, bytes.size / 3)
                    sink.flush()
                    throw IOException("the connection dropped")
                }
            }
            val back = NetworkQueueRig(songs, db = db, server = server).also { later = it }
            val saved = runBlocking { back.window.readSaved("home") }!!
            assertTrue(back.window.restore(saved, "home"))
            back.joined()
            assertTrue("the list was asked again", server.calls.count { it == "getPlaylist" } > asked)
            assertEquals(listOf(tapped), back.songs())
            assertEquals(tapped, back.current())
            back.until("the build's words") { back.words.any { it.startsWith("Couldn't add") } }
            assertEquals(0, back.count("SELECT COUNT(*) FROM server_entry WHERE owner LIKE '${WorkOwners.marked("queue:")}%'"))
            assertEquals(0, back.count("SELECT COUNT(*) FROM server_head WHERE owner LIKE '${WorkOwners.marked("queue:")}%'"))
            assertEquals(0, back.count("SELECT COUNT(*) FROM server_key"))
        } finally {
            // The later rig holds the first one's database and server.
            val back = later
            if (back == null) {
                first.close()
            } else {
                back.close()
                first.player.release()
            }
        }
    }
}
