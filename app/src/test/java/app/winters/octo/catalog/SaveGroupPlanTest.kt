package app.winters.octo.catalog

import android.database.sqlite.SQLiteDatabase
import app.winters.octo.device.DEVICE
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.sql.Connection

// Every statement the find ran, as the counting log recorded it, reads
// through keys on SQLite 3.21 and on the current SQLite, both with the
// rename triggers installed: no program walks a table of the library from
// its start (Rewind or Last) or builds a table or a sort in memory
// (OpenEphemeral, SorterOpen), and no plan sorts, indexes on the fly or
// lists a subquery, but over the save's own work tables, which hold one
// find's rows.
@RunWith(RobolectricTestRunner::class)
class SaveGroupPlanTest {
    @Test
    fun everyFindStatementReadsThroughKeysOnBothEngines() = runBlocking {
        val recorded = LinkedHashSet<String>()
        for (case in listOf("copy", "switch", "skipped")) recorded += findStatements(case)
        assertTrue("no statement recorded", recorded.size > 30)
        val problems = ArrayList<String>()
        OldSqlite.open().use { old ->
            val current = SQLiteDatabase.create(null)
            try {
                OldSqlite.schemaStatements(19).forEach(current::execSQL)
                assertEquals(SAVE_RENAME_TRIGGERS.size, old.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'").use { it.next(); it.getInt(1) } })
                for (sql in recorded) {
                    val oldProgram = program(old, sql)
                    val currentProgram = program(current, sql)
                    problems += check("3.21", sql, oldProgram)
                    problems += check("current", sql, currentProgram)
                    if (!oldProgram.saveOnly) {
                        OldSqlite.plan(old, sql, List(parameters(old, sql)) { null })
                            .filter { "TEMP B-TREE" in it || "AUTOMATIC" in it || "LIST SUBQUERY" in it }
                            .forEach { problems += "3.21 plan: $it in $sql" }
                    }
                }
            } finally {
                current.close()
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // Every statement the apply ran, from the counting log, on the same
    // three libraries: the same rules, but for the merge's own record of a
    // row a source (source_meta), a row an account (account_sync) and its
    // one row (merge_state), which its bookkeeping reads whole.
    @Test
    fun everyApplyStatementReadsThroughKeysOnBothEngines() = runBlocking {
        val recorded = LinkedHashSet<String>()
        for (case in listOf("copy", "switch", "skipped")) recorded += findStatements(case, apply = true)
        assertTrue("no statement recorded", recorded.size > 30)
        val small = setOf("source_meta", "account_sync", "merge_state")
        val problems = ArrayList<String>()
        OldSqlite.open().use { old ->
            val current = SQLiteDatabase.create(null)
            try {
                OldSqlite.schemaStatements(19).forEach(current::execSQL)
                for (sql in recorded) {
                    val oldProgram = program(old, sql)
                    problems += check("3.21", sql, oldProgram, small)
                    problems += check("current", sql, program(current, sql), small)
                    if (!oldProgram.saveOnly && oldProgram.tables.any { it !in small }) {
                        OldSqlite.plan(old, sql, List(parameters(old, sql)) { null })
                            .filter { "TEMP B-TREE" in it || "AUTOMATIC" in it || "LIST SUBQUERY" in it }
                            .forEach { problems += "3.21 plan: $it in $sql" }
                    }
                }
            } finally {
                current.close()
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // The find's walk of copies out of the library seeks only rows still
    // merged: each of its statements searches the folder index
    // (sourceId, storageVolume, folder, mergedId, id) with no scan, the
    // seeks for rows with a constraint on mergedId past a value, the seeks
    // for the next folder by source and storage, on SQLite 3.21 and the
    // current one. It finds every merged row of a kept server, in folders
    // with and without a name and a storage, and nothing of a kept server
    // whose rows are no song's already.
    @Test
    fun theUnmergeWalkSeeksOnlyRowsStillMerged() = runBlocking {
        val db = openTestCatalog()
        try {
            val server = "server:walk.test"
            db.put(library(DEVICE, 6))
            db.put(library(server, 6))
            rebuildSettled(db, server)
            val kept = "server:kept.test"
            val quiet = "server:quiet.test"
            val merged = ArrayList<String>()
            var n = 0
            for (volume in listOf(null, "", "vol2")) {
                for (folder in listOf(null, "", "Music/A", "Music/B")) {
                    for (k in 0 until 3) {
                        val id = "$kept:${n++}"
                        val song = if (k == 0) "" else "$DEVICE:s${k + 1}"
                        if (song.isNotEmpty()) merged += id
                        insertRow(db, "source_track", mapOf("id" to id, "sourceId" to kept, "nativeId" to "$n", "title" to "T", "storageVolume" to volume, "folder" to folder, "mergedId" to song, "relinkKey" to ""))
                    }
                }
            }
            repeat(5) { insertRow(db, "source_track", mapOf("id" to "$quiet:$it", "sourceId" to quiet, "nativeId" to "$it", "title" to "T", "mergedId" to "", "relinkKey" to "")) }
            val claim = claimAndBuild(db, server).second
            val (_, ran) = db.counted.record { runBlocking { SaveFind(db, 2_000).find(claim) } }
            val walk = ran.filter { it.label == SAVE_UNMERGES }.map { it.sql }.toSet()
            assertTrue("no statement of the walk recorded", walk.size >= 4)
            val problems = ArrayList<String>()
            OldSqlite.open().use { old ->
                for (sql in walk) {
                    val args = List(parameters(old, sql)) { "x" }
                    for ((engine, plan) in listOf("3.21" to OldSqlite.plan(old, sql, args), "current" to CurrentSqlite.plan(sql, args))) {
                        val text = plan.joinToString(" / ")
                        if (plan.any { it.trimStart().startsWith("SCAN") }) problems += "$engine: a scan in $sql: $text"
                        if (plan.any { "TEMP B-TREE" in it }) problems += "$engine: a sort in $sql: $text"
                        when {
                            "s.mergedId" in sql -> if (plan.none { "index_source_track_folder" in it && "mergedId>?" in it }) problems += "$engine: no seek past a merged id in $sql: $text"
                            "storageVolume" in sql -> if (plan.none { "index_source_track_folder" in it && "sourceId=?" in it }) problems += "$engine: no seek of the next folder in $sql: $text"
                        }
                    }
                }
            }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
            val unmerged = db.query("SELECT id FROM save_found WHERE tbl = ?", arrayOf(FOUND_UNMERGE)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            assertEquals(merged.toSet(), unmerged)
            assertTrue("the quiet server's rows were found", unmerged.none { it.startsWith(quiet) })
        } finally {
            db.close()
        }
    }

    // A program's opcodes, each with the table its cursor reads (an index's
    // table for an index), and whether every table it reads is the save's.
    private class Program(val ops: List<Triple<String, Long, String?>>, val tables: Set<String>) {
        val saveOnly: Boolean get() = tables.all { it.startsWith("save_") }
    }

    private fun check(engine: String, sql: String, program: Program, small: Set<String> = emptySet()): List<String> = buildList {
        for ((op, _, table) in program.ops) {
            // A cursor of no table is a temporary one, whose making the
            // next line judges.
            if ((op == "Rewind" || op == "Last") && table != null && !table.startsWith("save_") && table !in small) add("$engine: $op over $table in $sql")
            if ((op == "OpenEphemeral" || op == "SorterOpen") && !program.saveOnly && program.tables.any { it !in small }) add("$engine: $op in $sql")
        }
    }

    private fun program(conn: Connection, sql: String): Program {
        val roots = conn.createStatement().use { st ->
            st.executeQuery("SELECT rootpage, tbl_name FROM sqlite_master WHERE rootpage > 0").use { rs -> buildMap { while (rs.next()) put(rs.getLong(1), rs.getString(2)) } }
        }
        val rows = conn.prepareStatement("EXPLAIN $sql").use { st ->
            repeat(st.parameterMetaData.parameterCount) { st.setObject(it + 1, null) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(Triple(rs.getString("opcode"), rs.getLong("p1"), rs.getLong("p2"))) } }
        }
        return programOf(rows, roots)
    }

    private fun program(db: SQLiteDatabase, sql: String): Program {
        val roots = db.rawQuery("SELECT rootpage, tbl_name FROM sqlite_master WHERE rootpage > 0", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
        }
        val count = maxOf(Regex("""\?(\d+)""").findAll(sql).maxOfOrNull { it.groupValues[1].toInt() } ?: 0, Regex("""\?(?!\d)""").findAll(sql).count())
        val rows = db.rawQuery("EXPLAIN $sql", Array(count) { "1" }).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(c.getColumnIndexOrThrow("opcode")), c.getLong(c.getColumnIndexOrThrow("p1")), c.getLong(c.getColumnIndexOrThrow("p2")))) }
        }
        return programOf(rows, roots)
    }

    private fun programOf(rows: List<Triple<String, Long, Long>>, roots: Map<Long, String>): Program {
        val cursors = HashMap<Long, String>()
        for ((op, p1, p2) in rows) if (op == "OpenRead" || op == "OpenWrite") roots[p2]?.let { cursors[p1] = it }
        return Program(rows.map { (op, p1, _) -> Triple(op, p1, cursors[p1]) }, cursors.values.toSet())
    }

    private fun parameters(conn: Connection, sql: String): Int = conn.prepareStatement(sql).use { it.parameterMetaData.parameterCount }

    // The statements one find ran (or with `apply`, one save's apply), by
    // text, on a library of each kind: a copy waiting (with every kind of
    // row that follows ids), a server switch, and a skipped merge.
    private suspend fun findStatements(case: String, apply: Boolean = false): Set<String> {
        val db = openTestCatalog()
        try {
            val server = "server:plan.test"
            val other = "server:plan-other.test"
            db.put(library(DEVICE, 6))
            db.put(library(if (case == "switch") other else server, 24))
            rebuildSettled(db, if (case == "switch") other else server)
            val songs = db.query("SELECT id, relinkKey FROM track ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            insertRow(db, "playlist", mapOf("id" to "p", "name" to "P", "createdAt" to 1, "updatedAt" to 1))
            for ((i, s) in (songs + ("gone:1" to "drake|song 3")).withIndex()) {
                val (id, key) = s
                insertRow(db, "liked_track", mapOf("trackId" to id, "relinkKey" to key, "likedAt" to i))
                insertRow(db, "track_rating", mapOf("trackId" to id, "relinkKey" to key, "rating" to 3, "ratedAt" to i))
                insertRow(db, "play_event", mapOf("trackId" to id, "relinkKey" to key, "startedAt" to i, "playedMs" to 1, "durationMs" to 1))
                insertRow(db, "playlist_item", mapOf("playlistId" to "p", "itemSet" to 0, "trackId" to id, "relinkKey" to key, "ord" to i, "itemKey" to i))
                insertRow(db, "playlist_edit_log", mapOf("playlistId" to "p", "itemSet" to 0, "op" to 0, "itemKey" to i, "ord" to i, "trackId" to id, "relinkKey" to key))
                for (t in listOf("playlist_entry", "server_entry")) insertRow(db, t, mapOf("read" to 1, "owner" to WorkOwners.marked("vm:0"), "position" to i, "trackId" to id, "relinkKey" to key).filterKeys { it != (if (t == "playlist_entry") "owner" else "read") })
                insertRow(db, "playlist_match", mapOf("id" to i, "read" to 1, "trackId" to id, "relinkKey" to key, "title" to "t$i"))
                insertRow(db, "server_match", mapOf("id" to i, "owner" to WorkOwners.marked("vm:0"), "trackId" to id, "relinkKey" to key, "title" to "t$i"))
                insertRow(db, "playlist_local", mapOf("itemPosition" to i, "itemKey" to i, "trackId" to id, "relinkKey" to key))
                insertRow(db, "playlist_next", mapOf("position" to i, "trackId" to id, "relinkKey" to key))
                insertRow(db, "lists_album_entry", mapOf("owner" to WorkOwners.marked("vm:0"), "position" to i, "shownId" to id))
                insertRow(db, "lists_server_dir", mapOf("owner" to WorkOwners.marked("vm:0"), "position" to i, "kind" to 1, "trackId" to id, "relinkKey" to key))
                insertRow(db, "queue_entry", mapOf("queueId" to 1, "entry" to i, "trackId" to id, "listId" to 1))
                insertRow(db, "online_song", mapOf("id" to "find:$i", "sourceId" to server, "nativeId" to "$i", "title" to "F", "artist" to "A", "album" to "", "durationMs" to 1, "seenAt" to 1, "adoptedId" to id))
                insertRow(db, "rating_tap", mapOf("sourceRowId" to id, "rating" to 2))
            }
            val albums = db.query("SELECT id, searchKey FROM album", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            for ((i, a) in (albums + ("gone:album" to "album 1")).withIndex()) {
                insertRow(db, "liked_album", mapOf("albumId" to a.first, "relinkKey" to a.second, "likedAt" to i))
                insertRow(db, "pinned_item", mapOf("kind" to "album", "itemId" to a.first, "relinkKey" to a.second, "position" to i))
            }
            insertRow(db, "liked_artist", mapOf("artistId" to "gone:artist", "relinkKey" to "drake", "likedAt" to 1))
            insertRow(db, "pinned_item", mapOf("kind" to "artist", "itemId" to "gone:artist", "relinkKey" to "drake", "position" to 50))
            val claim = when (case) {
                "skipped" -> null
                "switch" -> {
                    db.stage(library(server, 26), generation = 3)
                    claimAndBuild(db, server).second
                }
                else -> {
                    val copy = library(server, 24)
                    db.stage(
                        copy.copy(
                            tracks = copy.tracks.mapIndexedNotNull { i, t ->
                                when {
                                    i % 5 == 0 -> null
                                    t.albumId.endsWith("album1") -> t.copy(albumId = "$server:album8", rating = 2, playCount = 1)
                                    else -> t.copy(seq = 50L - i)
                                }
                            } + song(server, "s40", "Song 40", "album3"),
                            albums = copy.albums.map { if (it.id.endsWith("album1")) it.copy(id = "$server:album8", nativeId = "album8") else it },
                        ),
                        generation = 5,
                    )
                    db.exec("INSERT INTO rating_note (sourceRowId, rating) VALUES (?, 5)", "$server:s3")
                    claimAndBuild(db, server).second
                }
            }
            if (apply) {
                val merge = db.boundMerge(server, 200)
                val (_, ran) = db.counted.record {
                    runBlocking { if (claim == null) merge.saveSkipped() else merge.saveBuilt(claim.sources, claim.staged, server) }
                }
                val own = ran.filter { it.label == SAVE_APPLY && !it.bookkeeping }
                assertTrue("$case: nothing of the apply recorded", own.isNotEmpty())
                return own.map { it.sql }.toSet()
            }
            val (_, statements) = db.counted.record { runBlocking { SaveFind(db, 200).find(claim) } }
            val own = statements.filter { it.label == SAVE_FIND && !it.bookkeeping }
            assertTrue("$case: nothing recorded", own.isNotEmpty())
            return own.map { it.sql }.toSet()
        } finally {
            db.close()
        }
    }
}
