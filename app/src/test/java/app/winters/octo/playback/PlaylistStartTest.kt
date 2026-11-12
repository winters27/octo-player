package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.OWNED_WORK_TABLES
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.insertRow
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.paging.ListLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A playlist's start reads with no pin, each statement finding the set the
// playlist shows inside itself. Whatever set a release flips to between any
// two of the start's statements, the play starts on a row of the list as
// the statement that chose it saw it, and a tapped song the list no longer
// holds plays alone, as today. What plays after the start is the build's,
// which pins its own set: with the set flipped and released between the
// start and the build, the queue's head is still the start's read. A walk's
// release reads first and clears what it holds, and a walk let go adds no
// row.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class PlaylistStartTest {
    // Set 0 shows the first 300 library songs twice over, set 1 the next
    // 300 twice over: no song in both.
    private fun songOf(set: Int, i: Int) = "t${2 * (set * 300 + i % 300)}"

    // A set's rows; with `keys`, set 1's rows keep set 0's row keys (each
    // now holding another song), else each row's key is new. Row keys are
    // taken from the playlists' next key, as every write of a set takes
    // them, so a pin made after sees every row.
    private fun setRows(rig: ServerStartRig, set: Int, rows: Int, keys: Boolean = false) {
        for (i in 0 until rows) {
            val id = songOf(set, i)
            val key = if (keys) i else set * 10_000 + i
            insertRow(rig.db, "playlist_item", mapOf("playlistId" to "pl", "itemSet" to set, "trackId" to id, "relinkKey" to "rk-$id", "ord" to i, "itemKey" to key))
        }
        rig.db.openHelper.writableDatabase.execSQL("UPDATE playlist_meta SET nextItemKey = MAX(nextItemKey, (SELECT MAX(itemKey) + 1 FROM playlist_item)) WHERE id = 0")
    }

    private fun playlist(rig: ServerStartRig, rows: Int) {
        rig.routeLibrary()
        runBlocking { rig.db.userDao().insertPlaylist(PlaylistEntity("pl", "List", createdAt = 1, updatedAt = 1)) }
        setRows(rig, 0, rows)
    }

    // A release as a sync's flip and its settle leave it: set 1 shown, set
    // 0's rows gone.
    private fun flipAndRelease(rig: ServerStartRig, rows: Int, keys: Boolean = false) {
        setRows(rig, 1, rows, keys)
        val sql = rig.db.openHelper.writableDatabase
        sql.execSQL("UPDATE playlist SET itemSet = 1 WHERE id = 'pl'")
        sql.execSQL("DELETE FROM playlist_item WHERE playlistId = 'pl' AND itemSet = 0")
    }

    private fun play(rig: ServerStartRig, request: PlayRequest): PlayAnswer =
        rig.play(request.copy(token = runBlocking { rig.store.claimList() }))

    private class Case(val name: String, val rows: Int, val statements: Int, val request: PlayRequest)

    private val cases = listOf(
        Case("first", 600, 1, PlayRequest(ListSpec.Playlist("pl"), null, shownOverWindow = true)),
        Case("shuffled", 600, 3, PlayRequest(ListSpec.Playlist("pl"), null, shuffle = true, shownOverWindow = true)),
        Case("a place", 600, 2, PlayRequest(ListSpec.Playlist("pl"), ListStart.At(300, 0), shownOverWindow = true)),
        Case("whole", 40, 1, PlayRequest(ListSpec.Playlist("pl"), null, shownOverWindow = false)),
    )

    @Test
    fun aSetFlippedBetweenAnyTwoStatementsStartsOnARowOfTheListThen() {
        var visited = 0
        for (case in cases) {
            for (flipAt in -1 until case.statements) {
                ServerStartRig(routeSongs).use { rig ->
                    playlist(rig, case.rows)
                    var seen = 0
                    // A shuffled start's place drawn past the first, so it reads a key, then its row.
                    rig.starts.random = kotlin.random.Random(1)
                    rig.starts.hooks = object : StartHooks() {
                        override suspend fun statement(n: Int) {
                            seen++
                            if (n == flipAt) flipAndRelease(rig, case.rows)
                        }
                    }
                    val answer = play(rig, case.request)
                    val where = "${case.name}, flipped before statement $flipAt"
                    assertTrue("$where: ${answer.refusal}", answer.started)
                    assertEquals("$where: the start's statements", case.statements, seen)
                    visited += seen
                    // The statement that chose the row is the last; a flip at or
                    // before it shows set 1, none shows set 0.
                    val shownSet = if (flipAt >= 0) 1 else 0
                    val songs = (0 until case.rows).map { songOf(shownSet, it) }.toSet()
                    assertTrue("$where: ${rig.current()} is no song of set $shownSet", rig.current() in songs)
                }
            }
        }
        assertEquals("hook points visited", cases.sumOf { it.statements * (it.statements + 1) }, visited)
    }

    @Test
    fun aTappedSongTheListNoLongerHoldsPlaysAlone() {
        ServerStartRig(routeSongs).use { rig ->
            playlist(rig, 600)
            val tapped = songOf(0, 7)
            var seen = 0
            rig.starts.hooks = object : StartHooks() {
                override suspend fun statement(n: Int) {
                    seen++
                    if (n == 0) flipAndRelease(rig, 600)
                }
            }
            assertTrue(play(rig, PlayRequest(ListSpec.Playlist("pl"), ListStart.Track(tapped), shownOverWindow = true)).started)
            assertEquals("the start's statements", 1, seen)
            assertEquals(listOf(tapped), rig.held())
        }
    }

    // The set flipped and released after the start read it and before its
    // build: the head is the start's read, the rest the build's own set.
    // A whole start's queue is its read. A big one's build finds the start
    // again in the set it pins, its row by key, else its song, and queues
    // the rest of that set around it: with the rows' keys kept, the row that
    // kept the start's key is the start's own (its new song is left out, as
    // a server list's start entry leaves out the copy's song); with new
    // keys and the start's song gone from the set, the whole set comes
    // after the start.
    @Test
    fun theQueuesHeadIsTheStartsReadWithTheSetFlippedBeforeTheBuild() {
        for ((rows, keys) in listOf(40 to false, 600 to true, 600 to false)) {
            ServerStartRig(routeSongs).use { rig ->
                playlist(rig, rows)
                var flipped = 0
                rig.starts.hooks = object : StartHooks() {
                    override suspend fun letGo(owner: String, loader: ListLoader<*>) {
                        flipped++
                        flipAndRelease(rig, rows, keys)
                    }
                }
                assertTrue(play(rig, PlayRequest(ListSpec.Playlist("pl"), null, shownOverWindow = rows > 40)).started)
                assertEquals("the flip ran", 1, flipped)
                rig.joined()
                val queue = rig.songs()
                val where = "$rows rows, keys kept $keys: ${queue.take(4)} ... ${queue.size}, words ${rig.words}"
                println("PlaylistStartTest queue after the flip: $where")
                val expected = when {
                    rows == 40 -> (0 until rows).map { songOf(0, it) }
                    keys -> listOf(songOf(0, 0)) + (1 until rows).map { songOf(1, it) }
                    else -> listOf(songOf(0, 0)) + (0 until rows).map { songOf(1, it) }
                }
                assertEquals(where, expected, queue)
                assertEquals(where, songOf(0, 0), rig.current())
            }
        }
    }

    // A walk's release clears a row it holds, and a walk let go adds no row
    // afterwards, whatever is asked of its loader.
    @Test
    fun aWalksReleaseClearsWhatItHoldsAndALetGoWalkAddsNone() {
        for (spec in listOf(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), ListSpec.Playlist("pl"))) {
            ServerStartRig(routeSongs).use { rig ->
                playlist(rig, 600)
                var walk: String? = null
                var after: Int? = null
                rig.starts.hooks = object : StartHooks() {
                    override suspend fun read(owner: String) {
                        walk = owner
                        insertRow(
                            rig.db, "lists_sort_copy",
                            mapOf(
                                "owner" to owner, "segment" to 0, "lead" to 0, "leadKey" to 0, "liked" to 0, "tieKey" to 0, "sortKey" to "a",
                                "trackId" to "t0", "titleKey" to ByteArray(1), "sourceId" to "", "serverId" to "",
                            ),
                        )
                    }

                    override suspend fun letGo(owner: String, loader: ListLoader<*>) {
                        loader.count()
                        loader.after(null, 5)
                        loader.keyAt(3)
                        loader.small()
                        after = held(rig, owner)
                    }
                }
                assertTrue(play(rig, PlayRequest(spec, null, shownOverWindow = true)).started)
                assertTrue("$spec: the walk's owner seen", walk != null && WorkOwners.kindOf(walk!!) == "walk")
                assertEquals("$spec: rows the let-go walk holds", 0, after)
                assertEquals("$spec: rows the walk left", 0, held(rig, walk!!))
            }
        }
    }

    private fun held(rig: ServerStartRig, owner: String): Int = OWNED_WORK_TABLES.sumOf { table ->
        rig.db.query("SELECT COUNT(*) FROM `$table` WHERE owner = ?", arrayOf<Any?>(owner)).use { it.moveToFirst(); it.getInt(0) }
    }
}
