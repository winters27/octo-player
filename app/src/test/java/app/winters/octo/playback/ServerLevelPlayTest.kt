package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.folders.LevelRow
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.today.sortFolderSongs
import app.winters.octo.subsonic.Song
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.folders.ServerLevelPages
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListReads
import app.winters.octo.ui.paging.RowKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

// A tap on a song of a server folder that shows more than QUEUE_WINDOW
// songs plays the whole folder from it, in the order shown, as today's page
// played the folder's songs from the one tapped: a 2,000-song folder shown
// in Title order, the song at 1,200 tapped, plays at once, and the queue
// holds all 2,000 in Title order with the tapped song at its place among
// them. The play reads the folder into its own rows, which go with its
// build.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerLevelPlayTest {
    private val words = listOf("amber", "Birch", "cedar", "Dune", "ember", "Fjord", "grove", "Heath", "inlet", "Juniper")
    private val random = Random(23)
    private val titles = (0 until 2_000).map { "${words[random.nextInt(words.size)]} ${words[random.nextInt(words.size)]} ${random.nextInt(50)}" }
    private val songs = (0 until 2_000).associate { "srv$it" to Song("srv$it", title = titles[it], artist = "Artist") }

    @Test
    fun aTapInABigFolderPlaysTheWholeFolderInTheOrderShown() {
        NetworkQueueRig(songs).use { net ->
            net.db.putLibrary((0 until 2_000).map { libraryTrack("t$it", titles[it]) }) { "srv${it.id.removePrefix("t")}" }
            val own = songs.keys.shuffled(Random(5))
            net.server.folders["L"] = emptyList<String>() to own
            val title = SortOrder(SongSort.Title, descending = false)
            val want = sortFolderSongs(todayResolve(net.db, SERVER_SOURCE, own.map { songs.getValue(it) }), title).map { it.id }
            assertEquals(2_000, want.size)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val pages = ServerLevelPages(scope, net.folders, net.owners::vm, ListReads.Plain, { emptyFlow<Unit>() }, { setOf("L") }, scope, MutableStateFlow(title))
                runBlocking { pages.load("L")!!.join() }
                val page = (pages.levels.value["L"] as LoadState.Ready).data
                val shown = runBlocking { walked(page) }
                assertEquals("the page shows the folder in Title order", want, shown.map { (it.row as LevelRow.Song).track.id })
                val tapped = shown[1_200]
                val id = (tapped.row as LevelRow.Song).track.id
                val (spec, start) = runBlocking { pages.tapped("L", id, tapped.key) }!!
                assertEquals(ListSpec.ServerFolder("L", recursive = false, title), spec)
                assertTrue(net.play(PlayRequest(spec, start, shownOverWindow = true)).started)
                assertEquals("the tapped song plays at once", id, net.current())
                net.joined()
                assertEquals("every song of the folder, in Title order", want, net.songs())
                assertEquals(id, net.current())
                assertEquals(1_200, net.songs().indexOf(id))
                val (from, until) = app.winters.octo.catalog.WorkOwners.kindRange("queue")
                assertEquals("the play's rows went with its build", 0, net.count("SELECT COUNT(*) FROM lists_server_dir WHERE owner >= ? AND owner < ?", from, until))
                pages.close()
            } finally {
                runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            }
        }
    }

    // Every song row the page shows, a page at a time.
    private suspend fun walked(page: app.winters.octo.ui.folders.ServerLevelPage): List<Keyed<LevelRow>> {
        val loader = page.handle.loader
        val out = ArrayList<Keyed<LevelRow>>()
        var after: RowKey? = null
        while (true) {
            val rows = loader.after(after, 100)
            out += rows.filter { it.row is LevelRow.Song }
            if (rows.size < 100) return out
            after = rows.last().key
        }
    }
}
