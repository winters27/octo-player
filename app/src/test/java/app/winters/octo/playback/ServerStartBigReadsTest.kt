package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.discovery.START_RENAMED_TO_SQL
import app.winters.octo.discovery.ServerHooks
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.startAdoptedAsSql
import app.winters.octo.discovery.startCandidatesSql
import app.winters.octo.discovery.startEndsSql
import app.winters.octo.discovery.startFindsSql
import app.winters.octo.discovery.startLinkedToSql
import app.winters.octo.discovery.startLinksSql
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.counted
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

// A big start reads the same rows, runs the same statements and compares
// the same titles whatever the list's size, from the end of its drain to
// its hand-off: the first
// entry, the play queue's own entry (named by its place, with every entry
// before it checked for its song by strings alone), and a song tapped.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ServerStartBigReadsTest {
    private class Reads(val rows: Long, val parses: Long, val statements: Int)

    // What one start read after its drain, on a list of `size` entries.
    private fun measure(size: Int, start: (ServerStartRig) -> PlayRequest): Reads {
        val songs = (0 until size).associate { "s$it" to Song("s$it", title = "Song ${wordOf(it)}", artist = "Artist") }
        val rig = ServerStartRig(songs)
        try {
            // The first 100 songs are linked; the queue's own entry, the
            // 60th, matches its library song by title alone.
            rig.db.putLibrary((0 until 100).filter { it != 60 }.map { libraryTrack("t$it", "Song ${wordOf(it)}", "Artist") }) { "s${it.id.removePrefix("t")}" }
            rig.db.putLibrary(listOf(libraryTrack("t60", "Song ${wordOf(60)}", "Artist")), serverId = { null })
            rig.server.lists["L"] = (0 until size).map { "s$it" }
            rig.server.queue = (0 until size).map { "s$it" }
            rig.server.current = "s60"
            val drained = AtomicLong(-1)
            val parses = AtomicLong()
            rig.reads.hooks = object : ServerHooks() {
                override suspend fun drained(owner: String) = drained.set(rig.db.counted.mark())

                override fun parsed() {
                    if (drained.get() >= 0) parses.incrementAndGet()
                }
            }
            val request = start(rig)
            rig.settle()
            rig.db.counted.reset()
            rig.db.counted.recording = true
            try {
                assertTrue(rig.play(request).started)
            } finally {
                rig.db.counted.recording = false
            }
            val handed = rig.handed[request.token!!]!!
            val from = drained.get().takeIf { it >= 0 } ?: rig.db.counted.statements.firstOrNull()?.seq?.minus(1) ?: 0
            val ran = rig.db.counted.statements.filter { it.seq in (from + 1)..handed && !it.bookkeeping }
            return Reads(ran.sumOf { it.rowsRead.toLong() }, parses.get(), ran.size)
        } finally {
            rig.close()
        }
    }

    private fun assertConstant(what: String, start: (ServerStartRig) -> PlayRequest) {
        val small = measure(100, start)
        val big = measure(100_000, start)
        assertTrue("$what: the counting log caught the start's reads", small.statements > 0 && big.statements > 0)
        assertEquals("$what: rows read", small.rows, big.rows)
        assertEquals("$what: titles compared", small.parses, big.parses)
        assertEquals("$what: statements run", small.statements, big.statements)
    }

    private fun token(rig: ServerStartRig) = runBlocking { rig.store.claimList() }

    @Test
    fun theFirstEntrysStartReadsTheSameAtAnySize() = assertConstant("first") { rig ->
        PlayRequest(ListSpec.Online(OnlineKind.List, "L"), shownOverWindow = true, token = token(rig))
    }

    @Test
    fun thePlayQueuesOwnEntryReadsTheSameAtAnySize() = assertConstant("the queue's entry") { rig ->
        PlayRequest(ListSpec.ServerQueue, shownOverWindow = true, token = token(rig))
    }

    @Test
    fun aTappedSongsStartReadsTheSameAtAnySize() = assertConstant("tapped") { rig ->
        PlayRequest(ListSpec.Online(OnlineKind.List, "L"), ListStart.Row("t42", null), shownOverWindow = true, token = token(rig))
    }

    // Every statement a start runs reads by an index, on SQLite 3.21 and on
    // a current one: no table scanned, no sort or index made on the fly.
    @Test
    fun everyStatementAStartRunsReadsByAnIndexOnBothEngines() {
        val statements = mapOf(
            "links" to startLinksSql(3),
            "candidates" to startCandidatesSql(2),
            "finds" to startFindsSql(3),
            "renamed to" to START_RENAMED_TO_SQL,
            "the songs ending on one" to startEndsSql(2),
            "the server songs linked to them" to startLinkedToSql(2),
            "the finds that became them" to startAdoptedAsSql(2),
            "a start id's rename" to renamedSongsSql(3),
        )
        val problems = ArrayList<String>()
        OldSqlite.open().use { conn ->
            for ((name, sql) in statements) {
                val args = List(sql.count { it == '?' }) { null }
                val lines = OldSqlite.plan(conn, sql, args) + CurrentSqlite.plan(sql, args)
                assertTrue("$name planned", lines.isNotEmpty())
                problems += lines.filter { line ->
                    (line.startsWith("SCAN") && "CONSTANT ROW" !in line) || "TEMP B-TREE" in line || "AUTOMATIC" in line
                }.map { "$name: $it" }
            }
        }
        assertEquals("start statements that scan or sort", emptyList<String>(), problems)
    }
}
