package app.winters.octo.catalog

import androidx.sqlite.db.SupportSQLiteDatabase
import app.winters.octo.device.DEVICE
import app.winters.octo.testing.Counted
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// No write transaction of the find costs more than the bound: each one's
// cost measured from what it ran (every row each write changed, by
// total_changes(), times one and each index its write touches, plus the
// tracker's probe a row; every row read through an index, by its plan),
// against what the find planned for it, at a small bound and the default.
// The find's writes, its sets' unions, the sets' roots, set ids and costs,
// the groups and its clears are all measured. A bound under the floor is
// refused, and the floor is the largest unit of the schema's work.
@RunWith(RobolectricTestRunner::class)
class SaveGroupBoundTest {
    private val server = "server:bound.test"
    private val other = "server:bound-other.test"

    @Test
    fun noFindTransactionCostsMoreThanItsBound() = runBlocking {
        for (bound in listOf(SAVE_GROUP_FLOOR, 400L, SAVE_GROUP_ENTRIES)) {
            for (switch in listOf(false, true)) {
                val db = openTestCatalog()
                try {
                    val claim = fixture(db, switch)
                    val planned = ArrayList<Pair<String, SaveCost>>()
                    val hooks = object : SaveFindHooks() {
                        override fun wrote(what: String, cost: SaveCost) {
                            planned += what to cost
                        }
                    }
                    val log = db.counted
                    val (found, statements) = log.record { runBlocking { SaveFind(db, bound, hooks).find(claim) } }
                    val where = "bound $bound${if (switch) ", server switch" else ""}"
                    val transactions = log.transactions.filter { it.label == SAVE_FIND }
                    assertTrue("$where: no transaction recorded", transactions.isNotEmpty())
                    assertEquals("$where: transactions against planned", planned.size, transactions.size)
                    val measure = Measure(db.openHelper.writableDatabase)
                    val begins = transactions.map { it.beginSeq } + Long.MAX_VALUE
                    for ((i, t) in transactions.withIndex()) {
                        val inside = statements.filter { it.inTransaction && !it.bookkeeping && it.seq > begins[i] && it.seq < begins[i + 1] && it.label == SAVE_FIND }
                        val cost = measure.of(inside)
                        val (what, plan) = planned[i]
                        assertTrue("$where: transaction $i ($what) writes ${cost.writes}, planned ${plan.writes}", cost.writes <= plan.writes)
                        assertTrue("$where: transaction $i ($what) reads ${cost.reads}, planned ${plan.reads}", cost.reads <= plan.reads)
                        assertTrue("$where: transaction $i ($what) planned ${plan.total}, over $bound", plan.total <= bound)
                        assertEquals("$where: transaction $i ($what) rows", t.totalChanges, inside.sumOf { it.totalChanges ?: 0 })
                    }
                    // The plan packs its sets: a group of its own for each set
                    // over what a group holds (a run), and never fewer groups
                    // than the rest's cost needs; a small bound makes runs.
                    val capacity = bound - SaveCosts.of(db.openHelper.writableDatabase, SAVE_COST_TABLES).groupOverhead()
                    val runs = db.longOf("SELECT COUNT(*) FROM save_set WHERE cost > ?", capacity)
                    val rest = db.longOf("SELECT COALESCE(SUM(cost), 0) FROM save_set WHERE cost <= ?", capacity) +
                        db.longOf("SELECT COALESCE(SUM(cost), 0) FROM save_found WHERE setId IS NULL")
                    assertEquals("$where: runs", runs, found.runs)
                    assertTrue("$where: ${found.groups} groups for $runs runs and $rest more", found.groups >= runs + (rest + capacity - 1) / capacity)
                    // Each group but a run holds no more than a group holds.
                    val fills = db.query(
                        "SELECT g, SUM(c) FROM (SELECT groupId AS g, cost AS c FROM save_set UNION ALL SELECT groupId, cost FROM save_found WHERE setId IS NULL) " +
                            "WHERE g NOT IN (SELECT groupId FROM save_set WHERE cost > ?) GROUP BY g",
                        arrayOf(capacity),
                    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) } }
                    fills.forEach { (g, fill) -> assertTrue("$where: group $g holds $fill, over $capacity", fill <= capacity) }
                    assertTrue("$where: no groups", found.groups > 0)
                    if (bound == SAVE_GROUP_FLOOR) assertTrue("$where: no run at the floor", found.runs > 0)
                    assertTrue("$where: no set made", db.saveDao().nodeCount() > 0 && planned.any { it.first == "found" } && planned.any { it.first == "roots" })
                    if (switch) assertTrue("$where: no reference written", planned.any { it.first == "refs" })
                } finally {
                    db.close()
                }
            }
        }
    }

    // No transaction of the apply costs more than the bound, measured from
    // what it ran; a group that is no run costs no more than the plan gave
    // it (its sets' and rows' costs, which the find worked out from the
    // apply's per-row constants, and what every group writes), so no
    // constant of the model is below what its statements measure. The last
    // steps and the renames' clear are bounded too. The floor, 400 and
    // 4,096, an everyday copy and a server switch.
    @Test
    fun noApplyTransactionCostsMoreThanItsBoundOrItsPlan() = guarded(300) {
        var checkedAll = 0
        for (bound in listOf(SAVE_GROUP_FLOOR, 400L, SAVE_GROUP_ENTRIES)) {
            for (switch in listOf(false, true)) {
                val db = openTestCatalog()
                try {
                    val claim = fixture(db, switch)
                    val where = "bound $bound${if (switch) ", server switch" else ""}"
                    val sql = db.openHelper.writableDatabase
                    // The plan, as the save's own find makes it again.
                    SaveFind(db, bound).find(claim)
                    val overhead = SaveCosts.of(sql, SAVE_COST_TABLES).groupOverhead()
                    val capacity = bound - overhead
                    val planned = HashMap<Long, Long>()
                    db.query("SELECT groupId, SUM(cost) FROM save_set GROUP BY groupId", null).use { c -> while (c.moveToNext()) planned.merge(c.getLong(0), c.getLong(1), Long::plus) }
                    db.query("SELECT groupId, SUM(cost) FROM save_found WHERE setId IS NULL GROUP BY groupId", null).use { c -> while (c.moveToNext()) planned.merge(c.getLong(0), c.getLong(1), Long::plus) }
                    val runs = db.query("SELECT groupId FROM save_set WHERE cost > ?", arrayOf(capacity)).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
                    val log = db.counted
                    val groupOf = HashMap<Long, Long>()
                    val marks = HashMap<Long, Long>()
                    val hooks = object : GroupHooks() {
                        override suspend fun inGroup(turn: Long, group: Long) {
                            groupOf[turn] = group
                            marks[turn] = log.mark()
                            super.inGroup(turn, group)
                        }
                    }
                    val merge = db.boundMerge(server, bound, hooks)
                    val (_, statements) = log.record { runBlocking { merge.saveBuilt(claim.sources, claim.staged, server) } }
                    val transactions = log.transactions.filter { it.label == SAVE_APPLY }.sortedBy { it.beginSeq }
                    assertTrue("$where: no apply transaction recorded", transactions.size > 2)
                    val measure = Measure(sql)
                    val begins = transactions.map { it.beginSeq } + Long.MAX_VALUE
                    var checkedGroups = 0
                    var largest = 0L
                    var closest = ""
                    var closestShare = -1.0
                    for ((i, t) in transactions.withIndex()) {
                        val inside = statements.filter { it.inTransaction && !it.bookkeeping && it.seq > begins[i] && it.seq < begins[i + 1] && it.label == SAVE_APPLY }
                        val cost = measure.of(inside)
                        largest = maxOf(largest, cost.total)
                        val turn = marks.entries.firstOrNull { it.value > begins[i] && it.value < begins[i + 1] }?.key
                        val group = turn?.let { groupOf[it] }
                        val breakdown = inside.groupBy { it.sql.take(60) }.mapValues { (_, s) -> measure.of(s).total }.entries.sortedByDescending { it.value }.take(8)
                        assertTrue("$where: transaction $i (group $group) costs ${cost.total} (${cost.reads} reads, ${cost.writes} writes), over $bound: $breakdown", cost.total <= bound)
                        assertEquals("$where: transaction $i rows", t.totalChanges, inside.sumOf { it.totalChanges ?: 0 })
                        if (group != null && group !in runs) {
                            val plan = planned.getValue(group) + overhead
                            assertTrue("$where: group $group measures ${cost.total} (${cost.reads} reads, ${cost.writes} writes), planned $plan: $breakdown", cost.total <= plan)
                            checkedGroups++
                            if (cost.total.toDouble() / plan > closestShare) {
                                closestShare = cost.total.toDouble() / plan
                                closest = "group $group ${cost.total} of $plan planned"
                            }
                        }
                    }
                    // At the floor a server switch's every set is a run.
                    assertTrue("$where: no group checked against its plan: groups $groupOf, runs $runs, ${transactions.size} transactions", checkedGroups > 0 || groupOf.values.all { it in runs })
                    checkedAll += checkedGroups
                    // The costs measured, for the proof: the largest
                    // transaction, the group nearest its plan, and each
                    // statement's most in one run.
                    val most = statements.filter { it.label == SAVE_APPLY && it.inTransaction && !it.bookkeeping }
                        .groupBy { it.sql.take(70) }
                        .mapValues { (_, s) -> s.maxOf { measure.of(listOf(it)).total } }
                    println("SaveGroupBoundTest $where: ${transactions.size} transactions, largest $largest; nearest its plan $closest; per statement run " +
                        most.entries.sortedByDescending { it.value }.joinToString(" | ") { "${it.value} ${it.key}" })
                    if (bound == SAVE_GROUP_FLOOR) assertTrue("$where: no run at the floor", runs.isNotEmpty() && groupOf.values.groupBy { it }.any { it.value.size > 1 })
                } finally {
                    db.close()
                }
            }
        }
        assertTrue("no group checked against its plan", checkedAll > 0)
    }

    // A save that has finished leaves none of the merge's work rows once
    // its clear after it has run: the find's rows go with the merge's
    // output, and only the renames in force stay.
    @Test
    fun aFinishedSaveLeavesNoMergeWork() = guarded {
        val db = openTestCatalog()
        try {
            db.put(library(DEVICE, 8))
            db.put(library(other, 30))
            val first = db.testMerge(other)
            first.rebuild()
            first.awaitClear(MERGE_WORK_CLEAR)
            db.stage(library(server, 30), generation = 4)
            val merge = db.testMerge(server)
            merge.rebuild()
            merge.awaitStage(server, 4)
            merge.awaitClear(MERGE_WORK_CLEAR)
            assertFalse("merge work is left", db.workDao().hasMergeWork())
            for (table in listOf("save_found", "save_ref", "save_node", "save_set")) assertEquals(table, 0L, db.longOf("SELECT COUNT(*) FROM `$table`"))
            assertTrue("the renames in force stay", db.longOf("SELECT COUNT(*) FROM save_rename") > 0)
        } finally {
            db.close()
        }
    }

    // At the default bound, no read of the find takes more than the app's
    // read page, over a library whose tables hold several pages of rows:
    // the bound sizes the find's write transactions only.
    @Test
    fun noFindStatementReadsMoreThanAReadPage() = guarded(300) {
        val db = openTestCatalog()
        try {
            val songs = 3 * MERGE_PAGE
            db.put(library(DEVICE, songs))
            val copy = library(server, songs)
            db.put(copy)
            rebuildSettled(db, server)
            db.stage(copy.copy(tracks = copy.tracks.mapIndexed { i, t -> if (i % 2 == 0) t.copy(title = t.title + " (Live)", relinkKey = t.relinkKey + " live") else t }), generation = 4)
            val claim = claimAndBuild(db, server).second
            val (found, statements) = db.counted.record { runBlocking { SaveFind(db).find(claim) } }
            assertTrue("the find found nothing", found.found > 0)
            val reads = statements.filter { it.rowsRead > 0 }
            assertTrue("no find read recorded", reads.isNotEmpty())
            val most = reads.maxBy { it.rowsRead }
            assertTrue("a find statement read ${most.rowsRead} rows: ${most.sql.take(160)}", most.rowsRead <= MERGE_PAGE)
            assertTrue("no find statement read a whole page", reads.any { it.rowsRead == MERGE_PAGE })
        } finally {
            db.close()
        }
    }

    // A skipped merge whose save found nothing leaves nothing to clear, so
    // no clear runs after it; one whose save found a row to move clears it.
    @Test
    fun aSkippedMergeClearsOnlyWhatItsSaveFound() = guarded {
        val db = openTestCatalog()
        try {
            db.put(library(server, 3))
            db.put(library(DEVICE, 3))
            val clears = AtomicInteger()
            val hooks = object : GroupHooks() {
                override fun cleared(what: String, rows: Long) {
                    if (what == MERGE_WORK_CLEAR) clears.incrementAndGet()
                }
            }
            val merge = db.boundMerge(server, SAVE_GROUP_ENTRIES, hooks)
            merge.rebuild()
            merge.awaitClear(MERGE_WORK_CLEAR)
            val claimed = clears.get()
            assertTrue("the claimed merge's rows were not cleared", claimed > 0)
            merge.rebuild()
            merge.awaitClear(MERGE_WORK_CLEAR)
            assertEquals("a skipped merge that found nothing ran a clear", claimed, clears.get())
            // A like written with a server copy's id, which is no library song.
            db.exec("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('$server:s0', '', 1)")
            merge.rebuild()
            merge.awaitClear(MERGE_WORK_CLEAR)
            assertEquals("a skipped merge that found a row did not clear it", claimed + 1, clears.get())
            assertFalse("merge work is left", db.workDao().hasMergeWork())
        } finally {
            db.close()
        }
    }

    // The find's rows count as the merge's work rows until they are
    // cleared, and their clear is bounded too.
    @Test
    fun theFindsRowsAreWorkRowsAndClearAPageATransaction() = runBlocking {
        val db = openTestCatalog()
        try {
            val work = db.workDao()
            assertFalse(work.hasMergeWork())
            for (table in listOf("save_found", "save_ref", "save_node", "save_set")) {
                when (table) {
                    "save_found" -> db.exec("INSERT INTO save_found (tbl, op, id, cost) VALUES ('track', 0, 'x', 1)")
                    "save_ref" -> db.exec("INSERT INTO save_ref (tbl, kind, keyText, keyNum, keyNum2, oldId, relinkKey, target) VALUES ('liked_track', 'track', 'x', 0, 0, 'x', '', 'y')")
                    "save_node" -> db.exec("INSERT INTO save_node (node, kind, id, parent, size) VALUES (1, 'track', 'x', 1, 1)")
                    else -> db.exec("INSERT INTO save_set (setId, cost) VALUES (1, 1)")
                }
                assertTrue("$table rows are no merge work", work.hasMergeWork())
                db.exec("DELETE FROM `$table`")
                assertFalse(work.hasMergeWork())
            }
            repeat(300) { i ->
                db.exec("INSERT INTO save_found (tbl, op, id, cost) VALUES ('track', 0, ?, 1)", "s$i")
                db.exec("INSERT INTO save_ref (tbl, kind, keyText, keyNum, keyNum2, oldId, relinkKey, target) VALUES ('liked_track', 'track', ?, 0, 0, ?, '', 'y')", "s$i", "s$i")
                db.exec("INSERT INTO save_node (node, kind, id, parent, size) VALUES (?, 'track', ?, ?, 1)", i + 1, "s$i", i + 1)
                db.exec("INSERT INTO save_set (setId, cost) VALUES (?, 1)", i + 1)
            }
            db.exec("INSERT INTO save_rename (kind, old, new, byKey, `key`) VALUES ('track', 'a', 'b', 0, '')")
            val planned = ArrayList<SaveCost>()
            val hooks = object : SaveFindHooks() {
                override fun wrote(what: String, cost: SaveCost) {
                    planned += cost
                }
            }
            val log = db.counted
            val (_, statements) = log.record { runBlocking { SaveFind(db, SAVE_GROUP_FLOOR, hooks).clear() } }
            val transactions = log.transactions.filter { it.label == SAVE_FIND }
            assertTrue("no clear recorded", transactions.size > 4)
            assertEquals(planned.size, transactions.size)
            val measure = Measure(db.openHelper.writableDatabase)
            val begins = transactions.map { it.beginSeq } + Long.MAX_VALUE
            for (i in transactions.indices) {
                val cost = measure.of(statements.filter { it.inTransaction && !it.bookkeeping && it.seq > begins[i] && it.seq < begins[i + 1] })
                assertTrue("clear $i writes ${cost.writes}, planned ${planned[i].writes}", cost.writes <= planned[i].writes)
                assertTrue("clear $i reads ${cost.reads}, planned ${planned[i].reads}", cost.reads <= planned[i].reads)
                assertTrue("clear $i over the bound", planned[i].total <= SAVE_GROUP_FLOOR)
            }
            assertFalse(work.hasMergeWork())
            assertEquals("the renames stay", 1L, db.longOf("SELECT COUNT(*) FROM save_rename"))
        } finally {
            db.close()
        }
    }

    @Test
    fun aBoundUnderTheFloorIsRefused() {
        val db = openTestCatalog()
        try {
            assertThrows(IllegalArgumentException::class.java) { SaveFind(db, SAVE_GROUP_FLOOR - 1) }
            assertEquals(SAVE_GROUP_FLOOR, SaveFind(db, SAVE_GROUP_FLOOR).bound)
            assertEquals(SAVE_GROUP_ENTRIES, SaveFind(db).bound)
        } finally {
            db.close()
        }
    }

    // The floor is the largest single unit of the save's work as the schema
    // makes it: a run's page holding a gone song alone (its found row read,
    // its own copy looked up three ways, the song's row and its own copy's
    // gone with their indexes and found rows, two songs' marks read and
    // totals counted again, and what every group costs), over a union at
    // the deepest find (two finds, two nodes made, a link and a size
    // written) and a song row with all its indexes, its reads and its found
    // row's removal.
    @Test
    fun theFloorIsTheLargestUnitOfWork() {
        val db = openTestCatalog()
        try {
            val sql = db.openHelper.writableDatabase
            val measure = Measure(sql)
            val whole = { table: String -> 1 + measure.indexes(table).size + 1L }
            val changed = { table: String, column: String -> 1 + measure.indexes(table).count { column in it } + 1L }
            val union = 2 * SAVE_DEEPEST_FIND + 2 + 2 * whole("save_node") + changed("save_node", "parent") + changed("save_node", "size")
            val song = APPLY_READS + whole("track") + whole("save_found")
            val copyGone = APPLY_READS + whole("source_track") + whole("save_found")
            val recount = TOTALS_READS + TOTALS_READS_PER_COPY * SAVE_MOST_COPIES + whole("play_total") + whole("save_found")
            val overhead = GROUP_READS + changed("merge_state", "swapCount")
            val runPage = 1 + 3 + song + copyGone + 2 * (1 + recount) + overhead
            assertEquals(maxOf(union, song, runPage), SAVE_GROUP_FLOOR)
            assertEquals(SAVE_GROUP_FLOOR, SaveCosts.of(sql, SAVE_COST_TABLES).largestUnit())
            assertTrue("a song row is a smaller unit than a union", song < union)
            assertTrue("a union is a smaller unit than a run's page", union < runPage)
        } finally {
            db.close()
        }
    }

    // A claim of three library sources (the phone, a folder of its own and
    // the server in use) saves at the floor with a bound raised to its own
    // largest unit, whose recounts read three copies a song: every
    // transaction stays within that bound, and the merge's own bound is the
    // floor still.
    @Test
    fun aSavesBoundHoldsItsOwnCopies() = guarded(300) {
        val db = openTestCatalog()
        try {
            val third = "local:third"
            db.put(library(DEVICE, 8))
            db.put(library(third, 30, msShift = 50))
            val copy = library(server, 30, msShift = 100)
            db.put(copy)
            rebuildSettled(db, server)
            val ids = db.query("SELECT id, relinkKey FROM track ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            for ((i, row) in ids.withIndex()) {
                repeat(2) { insertRow(db, "play_event", mapOf("trackId" to row.first, "relinkKey" to row.second, "startedAt" to i * 10 + it, "playedMs" to 1, "durationMs" to 1)) }
            }
            db.stage(copy.copy(tracks = copy.tracks.mapIndexedNotNull { i, t -> if (i % 5 == 0) null else if (i % 2 == 0) t.copy(title = t.title + " (Live)", relinkKey = t.relinkKey + " live") else t }), generation = 4)
            val claim = claimAndBuild(db, server).second
            assertEquals("the claim's sources", 3, claim.sources.size)
            val sql = db.openHelper.writableDatabase
            val costs = SaveCosts.of(sql, SAVE_COST_TABLES)
            val merge = db.boundMerge(server, SAVE_GROUP_FLOOR)
            val bound = merge.saveBoundFor(3)
            assertEquals("a three-copy save's bound", costs.largestUnit(3), bound)
            assertTrue("$bound is the two-copy floor", bound > SAVE_GROUP_FLOOR)
            assertEquals(SAVE_GROUP_FLOOR, merge.bound)
            val log = db.counted
            val (_, statements) = log.record { runBlocking { merge.saveBuilt(claim.sources, claim.staged, server) } }
            val transactions = log.transactions.filter { it.label == SAVE_APPLY }.sortedBy { it.beginSeq }
            assertTrue("no apply transaction recorded", transactions.size > 2)
            val measure = Measure(sql)
            val begins = transactions.map { it.beginSeq } + Long.MAX_VALUE
            var largest = 0L
            for ((i, _) in transactions.withIndex()) {
                val inside = statements.filter { it.inTransaction && !it.bookkeeping && it.seq > begins[i] && it.seq < begins[i + 1] && it.label == SAVE_APPLY }
                val cost = measure.of(inside)
                largest = maxOf(largest, cost.total)
                assertTrue("transaction $i costs ${cost.total} (${cost.reads} reads, ${cost.writes} writes), over its save's bound $bound", cost.total <= bound)
            }
            println("SaveGroupBoundTest three copies: ${transactions.size} transactions, largest $largest of $bound")
        } finally {
            db.close()
        }
    }

    // A library with a copy waiting: changed, gone and new songs, a gone
    // album of many songs (one big set), and rows that follow them. With
    // `switch`, the copy is a new server's and the old one's songs go.
    private suspend fun fixture(db: CatalogDatabase, switch: Boolean): SaveClaim {
        db.put(library(DEVICE, 8))
        val copy = library(if (switch) other else server, 60)
        db.put(copy)
        rebuildSettled(db, if (switch) other else server)
        val ids = db.query("SELECT id, relinkKey FROM track ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        for ((i, row) in ids.withIndex()) {
            insertRow(db, "liked_track", mapOf("trackId" to row.first, "relinkKey" to row.second, "likedAt" to i))
            repeat(3) { insertRow(db, "play_event", mapOf("trackId" to row.first, "relinkKey" to row.second, "startedAt" to i * 10 + it, "playedMs" to 1, "durationMs" to 1)) }
        }
        val next = if (switch) {
            library(server, 62)
        } else {
            copy.copy(
                tracks = copy.tracks.mapIndexedNotNull { i, t ->
                    when {
                        i % 9 == 0 -> null
                        t.albumId.endsWith("album2") -> t.copy(albumId = "$server:album9")
                        i % 4 == 0 -> t.copy(title = t.title + " (Live)", relinkKey = t.relinkKey + " live")
                        else -> t
                    }
                } + (60 until 64).map { song(server, "s$it", "Song $it", "album6") },
                albums = copy.albums.map { if (it.id.endsWith("album2")) it.copy(id = "$server:album9", nativeId = "album9") else it },
            )
        }
        db.stage(next, generation = 4)
        return claimAndBuild(db, server).second
    }
}

// What a transaction cost, measured from what it ran: each write's rows
// (with its triggers' writes) times one and every index its write touches,
// and the tracker's probe a row; each statement's rows times the lookups
// its plan makes through an index.
internal class Measure(private val sql: SupportSQLiteDatabase) {
    private val indexCache = HashMap<String, List<List<String>>>()
    private val searchCache = HashMap<String, Int>()

    fun indexes(table: String): List<List<String>> = indexCache.getOrPut(table) {
        val names = sql.query("PRAGMA index_list(`$table`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
        names.map { name -> sql.query("PRAGMA index_info(`$name`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } } }
    }

    fun of(statements: List<Counted>): SaveCost {
        var reads = 0L
        var writes = 0L
        for (s in statements) {
            val words = s.sql.trimStart().uppercase()
            val write = words.startsWith("INSERT") || words.startsWith("UPDATE") || words.startsWith("DELETE")
            val rows = if (write) s.totalChanges ?: s.rowsChanged.toLong() else s.rowsRead.toLong()
            reads += searches(s.sql) * maxOf(rows, 1L)
            if (!write) continue
            val table = Regex("""^\s*(?:INSERT(?:\s+OR\s+\w+)?\s+INTO|UPDATE(?:\s+OR\s+\w+)?|DELETE\s+FROM)\s+[`"]?(\w+)""", RegexOption.IGNORE_CASE).find(s.sql)!!.groupValues[1]
            val touched = if (words.startsWith("UPDATE")) {
                val set = setColumns(s.sql)
                indexes(table).count { index -> index.any { it in set } }
            } else {
                indexes(table).size
            }
            writes += rows * (1 + touched) + rows
        }
        return SaveCost(reads, writes)
    }

    // The lookups through an index or key one run of the statement makes,
    // by its plan on the current SQLite.
    private fun searches(statement: String): Int = searchCache.getOrPut(statement) {
        CurrentSqlite.plan(statement, emptyList()).count { it.startsWith("SEARCH") }
    }

    // The columns an UPDATE's SET list names, at the outer level.
    private fun setColumns(statement: String): Set<String> {
        val start = Regex("""\bSET\b""", RegexOption.IGNORE_CASE).find(statement)!!.range.last + 1
        var depth = 0
        val parts = ArrayList<String>()
        val part = StringBuilder()
        var i = start
        while (i < statement.length) {
            val ch = statement[i]
            if (depth == 0 && statement.regionMatches(i, " WHERE ", 0, 7, ignoreCase = true)) break
            when (ch) {
                '(' -> depth++
                ')' -> depth--
            }
            if (ch == ',' && depth == 0) {
                parts += part.toString()
                part.clear()
            } else {
                part.append(ch)
            }
            i++
        }
        parts += part.toString()
        return parts.map { it.substringBefore('=').trim().trim('`') }.toSet()
    }
}
