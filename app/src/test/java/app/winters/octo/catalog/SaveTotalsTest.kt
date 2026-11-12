package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.listening.TotalsHooks
import app.winters.octo.listening.TotalsRig
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Each group and page of a save keeps the play totals of the songs it
// touches equal to a recount (totalOf over their own rows): the phone's
// part by the plays moved, the copies' part counted again. After every
// group and page, with no refresh asked for and waiting, every song's
// stored total is a recount's: a song played three times the bound merging
// into another (a run), a song split in two, a played copy moving song, a
// copy's play record changed, an Octo send noted with its refresh still to
// come, a rebuild parked between its diff and its apply while groups land,
// and an account change in the middle of a save. A rebuild counts that much
// played song again in a transaction of its own.
@RunWith(RobolectricTestRunner::class)
class SaveTotalsTest {
    private val server = "server:totals.test"

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(120_000) { block() } }

    // A save of what `change` stages after `start`, with the totals checked
    // against a recount after every group and at its end. Answers how many
    // groups the save took and how many of them were pages of a run.
    private suspend fun checked(db: CatalogDatabase, bound: Long, account: String?, where: String, waiting: Set<String> = emptySet()): Pair<Int, Int> {
        val off = ArrayList<String>()
        val groups = HashMap<Long, Long>()
        val hooks = object : GroupHooks(db) {
            override suspend fun inGroup(turn: Long, group: Long) {
                groups[turn] = group
                super.inGroup(turn, group)
            }
        }
        // A song whose refresh waits is checked once a group counted it.
        val counted = HashSet<String>()
        hooks.between = { turn ->
            for (song in waiting) if (db.storedTotals()[song] == db.recountTotals(account)[song]) counted += song
            db.totalsOff(account).filter { line -> waiting.none { line.startsWith("$it:") } || counted.any { line.startsWith("$it:") } }
                .takeIf { it.isNotEmpty() }?.let { lines ->
                    val songs = lines.take(2).map { it.substringBefore(": stored") }
                    off += "$where, after turn $turn (group ${groups[turn]}): ${lines.take(3)}; left ${foundOf(db, songs)}; planned ${hooks.plannedFor(songs)}"
                }
        }
        try {
            db.boundMerge(server, bound, hooks).rebuild()
        } catch (e: IllegalStateException) {
            val ids = Regex("""\S+:\S+""").findAll(e.message.orEmpty()).map { it.value }.toList()
            throw AssertionError("$where: ${e.message}; planned ${hooks.plannedFor(ids)}; groups $groups", e)
        }
        db.totalsOff(account).takeIf { it.isNotEmpty() }?.let { off += "$where, at the end: ${it.take(3)}" }
        assertTrue(off.take(10).joinToString("\n"), off.isEmpty())
        assertTrue("$where: no group", groups.isNotEmpty())
        return groups.size to groups.keys.count { groups[it + 1] != null && groups[it + 1] == groups[it] }
    }

    // What the save still holds found for these songs, for a problem's words.
    private fun foundOf(db: CatalogDatabase, songs: List<String>): List<String> = songs.flatMap { id ->
        db.query("SELECT tbl, op, id, oldId, newId, setId, groupId FROM save_found WHERE id = ?1 OR oldId = ?1 OR newId = ?1", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add((0 until 7).joinToString(" ") { c.getString(it) ?: "-" }) }
        }
    }

    private fun CatalogDatabase.plays(trackId: String, count: Int, from: Long = 1_000) {
        val sql = openHelper.writableDatabase
        sql.beginTransaction()
        try {
            repeat(count) { sql.execSQL("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, '', ?, 1, 1)", arrayOf<Any?>(trackId, from + it)) }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    // A server song played three times the bound, on its own id, merges
    // into the phone's copy of it: its plays move in the pages of one run.
    @Test
    fun aSongPlayedThreeTimesTheBoundMergesInARun() = run {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                db.put(library(server, 6))
                db.put(library(DEVICE, 2))
                db.testMergeSettled()
                val heavy = "$server:s4"
                db.plays(heavy, (3 * bound).toInt())
                db.settleTotals("home")
                // The phone gets the song: its id becomes the phone's.
                db.put(library(DEVICE, 6))
                val (groups, runPages) = checked(db, bound, "home", "bound $bound")
                assertTrue("bound $bound: $groups groups, $runPages run pages", runPages > 0)
                assertEquals((3 * bound), db.longOf("SELECT plays FROM play_total WHERE trackId = ?", "$DEVICE:s4"))
            } finally {
                db.close()
            }
        }
    }

    // The same much-played song, counted again by a rebuild: its recount
    // sits alone in its transaction.
    @Test
    fun aRebuildCountsAMuchPlayedSongInATransactionOfItsOwn() = run {
        val db = openTestCatalog()
        try {
            val merge = db.boundMerge(server, SAVE_GROUP_FLOOR)
            db.put(library(DEVICE, 6))
            merge.rebuild()
            db.plays("$DEVICE:s2", (3 * SAVE_GROUP_FLOOR).toInt())
            for (i in listOf(1, 3, 4)) db.plays("$DEVICE:s$i", 2)
            val rig = TotalsRig(db, merge)
            // Each apply transaction's songs: the diff rows it took away.
            val pages = ArrayList<Set<String>>()
            var left = emptySet<String>()
            fun diff() = db.query("SELECT trackId FROM play_total_diff", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            rig.totals.hooks = object : TotalsHooks() {
                override suspend fun beforeApply() {
                    left = diff()
                }

                override suspend fun applied() {
                    val now = diff()
                    pages += left - now
                    left = now
                }
            }
            rig.started()
            assertEquals("pages $pages", listOf(setOf("$DEVICE:s1"), setOf("$DEVICE:s2"), setOf("$DEVICE:s3", "$DEVICE:s4")), pages)
            assertTrue(db.totalsOff("home").isEmpty())
            assertEquals(3 * SAVE_GROUP_FLOOR, db.longOf("SELECT plays FROM play_total WHERE trackId = ?", "$DEVICE:s2"))
        } finally {
            db.close()
        }
    }

    // A song with a phone file and a played server copy splits in two when
    // the file is retagged: its copy, and the plays on its copy, go with the
    // new song.
    @Test
    fun aSplitAndAMovedCopyKeepTheTotals() = run {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                val copy = library(server, 8)
                db.put(library(DEVICE, 8))
                db.put(copy.copy(tracks = copy.tracks.map { if (it.nativeId in setOf("s2", "s5")) it.copy(playCount = 4, lastPlayedAt = 9_000) else it }))
                db.testMergeSettled()
                db.plays("$DEVICE:s2", 3)
                db.plays("$DEVICE:s5", 5)
                db.settleTotals("home")
                val phone = library(DEVICE, 8)
                db.put(phone.copy(tracks = phone.tracks.map { if (it.nativeId == "s2") it.copy(title = "Other", searchKey = "other", relinkKey = "drake|other") else it }))
                checked(db, bound, "home", "split, bound $bound")
                assertEquals(4L, db.longOf("SELECT plays FROM play_total WHERE trackId = ?", "$server:s2"))
            } finally {
                db.close()
            }
        }
    }

    // A server copy's play record changes in its next copy.
    @Test
    fun aCopysPlayRecordChangingKeepsTheTotals() = run {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                db.put(library(DEVICE, 4))
                val copy = library(server, 12)
                db.put(copy)
                db.testMergeSettled()
                db.settleTotals("home")
                db.stage(copy.copy(tracks = copy.tracks.map { if (it.nativeId.removePrefix("s").toInt() % 3 == 0) it.copy(playCount = 7, lastPlayedAt = 5_000) else it }), generation = 2)
                checked(db, bound, "home", "play records, bound $bound")
                assertEquals(7L, db.longOf("SELECT plays FROM play_total WHERE trackId = ?", "$server:s6"))
            } finally {
                db.close()
            }
        }
    }

    // A send of a phone play noted against a copy (sent_recent) whose
    // refresh has not run yet, then a split: the group counts the copy
    // again from its rows, so the send is held once, never twice.
    @Test
    fun aSendWithItsRefreshToComeIsCountedOnce() = run {
        val db = openTestCatalog()
        try {
            val copy = library(server, 6)
            db.put(library(DEVICE, 6))
            db.put(copy.copy(tracks = copy.tracks.map { if (it.nativeId == "s3") it.copy(playCount = 5, lastPlayedAt = 20_000) else it }))
            db.testMergeSettled()
            db.plays("$DEVICE:s3", 2)
            db.settleTotals("home")
            // Octo sent one of the phone's plays to the server, which holds
            // it in its count; the totals' refresh for it has not run.
            db.exec("INSERT INTO sent_play (account, serverId, folded) VALUES ('home', 's3', 0)")
            db.exec("INSERT INTO sent_recent (account, serverId, startedAt) VALUES ('home', 's3', 10000)")
            val phone = library(DEVICE, 6)
            db.put(phone.copy(tracks = phone.tracks.map { if (it.nativeId == "s3") it.copy(title = "Elsewhere", searchKey = "elsewhere", relinkKey = "drake|elsewhere") else it }))
            checked(db, SAVE_GROUP_FLOOR, "home", "a send to come", waiting = setOf("$DEVICE:s3"))
            assertEquals("the copy's five plays, one of them Octo's", 4L, db.longOf("SELECT plays FROM play_total WHERE trackId = ?", "$server:s3"))
        } finally {
            db.close()
        }
    }

    // A rebuild that wrote its diff and waits to apply it while a save's
    // groups land, then applies: what the groups wrote stands.
    @Test
    fun aRebuildParkedBetweenItsDiffAndApplyWhileGroupsLand() = run {
        val db = openTestCatalog()
        try {
            val merge = db.boundMerge(server, SAVE_GROUP_FLOOR)
            db.put(library(server, 20))
            db.put(library(DEVICE, 4))
            merge.rebuild()
            for (i in 0 until 20) db.plays("$server:s$i", 3)
            val rig = TotalsRig(db, merge).started()
            val parked = CompletableDeferred<Unit>()
            val go = CompletableDeferred<Unit>()
            rig.totals.hooks = object : TotalsHooks() {
                override suspend fun beforeApply() {
                    if (parked.complete(Unit)) go.await()
                }
            }
            // A play since the totals were built, so the diff holds a row:
            // for a server song the next save merges into the phone's.
            db.plays("$server:s5", 1, from = 50_000)
            rig.totals.rebuild()
            parked.await()
            val count = db.longOf("SELECT swapCount FROM merge_state WHERE id = 0")
            // The phone gets the songs: their plays move in groups.
            db.put(library(DEVICE, 20))
            merge.rebuild()
            assertTrue("no group landed", db.longOf("SELECT swapCount FROM merge_state WHERE id = 0") > count + 1)
            val applying = rig.totals.applying.value
            go.complete(Unit)
            rig.totals.applying.first { it >= applying + 2 }
            val off = db.totalsOff("home")
            assertTrue(off.take(3).joinToString("\n"), off.isEmpty())
            assertEquals(4L, db.longOf("SELECT plays FROM play_total WHERE trackId = ?", "$DEVICE:s5"))
        } finally {
            db.close()
        }
    }

    // The account in use changes between two groups: the rebuild it asks
    // for writes the new account, and the groups after count for it.
    @Test
    fun anAccountChangeInTheMiddleOfASave() = run {
        val db = openTestCatalog()
        try {
            val merge = db.boundMerge(server, SAVE_GROUP_FLOOR)
            val copy = library(server, 16)
            db.put(copy.copy(tracks = copy.tracks.map { it.copy(playCount = 3, lastPlayedAt = 9_000) }))
            db.put(library(DEVICE, 2))
            merge.rebuild()
            for (i in 0 until 16) {
                db.exec("INSERT INTO sent_play (account, serverId, folded) VALUES ('other', ?, 2)", "s$i")
            }
            val rig = TotalsRig(db, merge).started()
            db.put(library(DEVICE, 16))
            var changed = false
            val hooks = object : GroupHooks() {}
            hooks.between = { turn ->
                if (turn == 1L && !changed) {
                    changed = true
                    // The account changes, and its rebuild runs to its end
                    // (its apply turns `applying` odd and back) before the
                    // next group.
                    val applying = rig.totals.applying.value
                    rig.account.value = "other"
                    rig.totals.applying.first { it >= applying + 2 }
                    assertEquals("other", db.stringOf("SELECT totalsAccount FROM merge_state WHERE id = 0"))
                }
            }
            merge.hooks = hooks
            merge.rebuild()
            assertTrue("the account never changed between groups", changed)
            assertTrue("one group only", hooks.turns.get() > 1)
            val off = db.totalsOff("other")
            assertTrue(off.take(3).joinToString("\n"), off.isEmpty())
            assertEquals("three plays, two of them Octo's", 1L, db.longOf("SELECT plays FROM play_total WHERE trackId = ?", "$DEVICE:s5"))
        } finally {
            db.close()
        }
    }

    // A first merge of the sources as they are, its clears done.
    private suspend fun CatalogDatabase.testMergeSettled() = rebuildSettled(this, server)
}
