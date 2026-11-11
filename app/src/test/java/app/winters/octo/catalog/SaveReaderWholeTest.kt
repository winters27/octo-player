package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// A reader parked between every two groups of a save (and every two pages
// of a run) finds the library whole: every song has a copy to play (or a
// rename in force to a song that has); no row that follows an id, nor the
// library's own id columns, names a missing song, album or artist, but rows
// that did before the save; a song's old and new ids both stand only in the
// middle of a run; each album and artist row is its old row or its final
// one; every song's play total is what a recount gives; and a like written
// since the claim shows on its song, old id or new. In a server switch's
// run, each old song with somewhere to go plays there, and one with nowhere
// keeps its own copy, as during the copy. At the floor bound and the
// default, each with more than one group and a run parked in.
@RunWith(RobolectricTestRunner::class)
class SaveReaderWholeTest {
    private val server = "server:whole.test"
    private val other = "server:whole-other.test"
    private val account = "home"

    @Test
    fun anEverydayCopyIsWholeAtEveryGroup() = runBlocking {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                db.put(library(DEVICE, 10))
                val songs = if (bound == SAVE_GROUP_FLOOR) 40 else 400
                val copy = library(server, songs)
                db.put(copy)
                rebuildSettled(db, server)
                followingRows(db, Random(5), server)
                // A gone album of many songs is one big set, a run.
                val next = copy.copy(
                    tracks = copy.tracks.mapIndexedNotNull { i, t ->
                        when {
                            i % 9 == 0 -> null
                            i < songs / 2 -> t.copy(albumId = "$server:big")
                            i % 4 == 0 -> t.copy(title = t.title + " (Live)", relinkKey = t.relinkKey + " live")
                            else -> t
                        }
                    } + (songs until songs + 4).map { song(server, "s$it", "Song $it", "album2") },
                    albums = copy.albums + albumRow(server, "big", "Big"),
                )
                for (t in copy.tracks.take(songs / 2)) {
                    repeat(2) { n -> db.exec("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, ?, ?, 1, 1)", t.id, t.relinkKey, 10L + n) }
                }
                db.stage(next, generation = 6)
                whole(db, bound, server, "everyday, bound $bound")
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun aServerSwitchIsWholeAtEveryPage() = runBlocking {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                db.put(library(DEVICE, 4))
                val songs = if (bound == SAVE_GROUP_FLOOR) 30 else 220
                db.put(library(other, songs))
                rebuildSettled(db, other)
                followingRows(db, Random(6), server)
                // One old song played often enough that its move is a run.
                val heavy = "$other:s10"
                repeat((bound / 8).toInt()) { n ->
                    db.exec("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, ?, ?, 1, 1)", heavy, "drake|song 10", 50L + n)
                }
                // The new server lacks some of the old one's songs, which have
                // nowhere to go.
                db.stage(library(server, songs - 3), generation = 4)
                whole(db, bound, server, "server switch, bound $bound", oldSide = other)
            } finally {
                db.close()
            }
        }
    }

    private suspend fun whole(db: CatalogDatabase, bound: Long, server: String, where: String, oldSide: String? = null) = withTimeout(120_000) {
        db.settleTotals(account)
        val before = db.dangling()
        assertEquals("$where: the start's own id columns", emptyList<String>(), ownDangling(db))
        val startAlbums = rows(db, "album")
        val startArtists = rows(db, "artist")
        val oldSongs = if (oldSide == null) emptyList() else db.query("SELECT id FROM track WHERE sourceId = ?", arrayOf(oldSide)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val groups = HashMap<Long, Long>()
        val bothStand = HashMap<Long, Long>()
        val albumsAt = ArrayList<Set<List<Any?>>>()
        val artistsAt = ArrayList<Set<List<Any?>>>()
        var liked: String? = null
        val problems = ArrayList<String>()
        val danglingAt = HashMap<Long, Set<String>>()
        // What the find planned, as it left it before the first group: its
        // found rows, and each reference with a target (table, kind, key,
        // old id, target).
        val planned = ArrayList<List<String>>()
        val refs = ArrayList<List<Any?>>()
        val hooks = object : GroupHooks() {
            override suspend fun checking() {
                planned.addAll(db.query("SELECT tbl, op, id, oldId, newId, setId, groupId FROM save_found", null).use { c ->
                    buildList { while (c.moveToNext()) add((0 until 7).map { c.getString(it) ?: "-" }) }
                })
                refs.addAll(db.query("SELECT tbl, kind, keyText, keyNum, keyNum2, oldId, target FROM save_ref WHERE target IS NOT NULL AND tbl != ?", arrayOf(REF_RENAME)).use { c ->
                    buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5), c.getString(6))) }
                })
                super.checking()
            }

            override suspend fun inGroup(turn: Long, group: Long) {
                groups[turn] = group
                super.inGroup(turn, group)
            }
        }
        fun foundFor(ids: List<String>): List<String> = planned.filter { row -> ids.take(3).any { it == row[2] || it == row[3] || it == row[4] } }.map { it.joinToString(" ") }
        hooks.between = { turn ->
            val at = "$where, after group turn $turn"
            val copyless = db.query(
                "SELECT t.id FROM track t WHERE NOT EXISTS (SELECT 1 FROM source_track s WHERE s.mergedId = t.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM save_rename r JOIN source_track s ON s.mergedId = r.new WHERE r.kind = 'track' AND r.old = t.id)",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            if (copyless.isNotEmpty()) problems += "$at (group ${groups[turn]}): songs with no copy to play $copyless; planned ${foundFor(copyless)}"
            danglingAt[turn] = db.dangling() - before
            val own = ownDangling(db)
            if (own.isNotEmpty()) problems += "$at (group ${groups[turn]}): ${own.size} of the library's own id columns name missing rows ${own.take(5)}; planned ${foundFor(own.take(2).map { it.split(' ')[1] })}"
            bothStand[turn] = db.longOf(
                "SELECT COUNT(*) FROM save_rename r WHERE r.kind = 'track' AND EXISTS (SELECT 1 FROM track WHERE id = r.old) AND EXISTS (SELECT 1 FROM track WHERE id = r.new)",
            )
            albumsAt += rows(db, "album")
            artistsAt += rows(db, "artist")
            val off = db.totalsOff(account)
            if (off.isNotEmpty()) problems += "$at: play totals off ${off.take(3)}"
            // A like written since the claim, on a song that has an id now;
            // it shows on that song at every park after, by its old id or new.
            val song = liked ?: db.stringOf("SELECT id FROM track WHERE id NOT IN (SELECT trackId FROM liked_track) AND sourceId != ? ORDER BY id LIMIT 1", DEVICE)?.also {
                insertRow(db, "liked_track", mapOf("trackId" to it, "relinkKey" to db.stringOf("SELECT relinkKey FROM track WHERE id = ?", it), "likedAt" to 99))
                liked = it
            }
            if (song != null) {
                val now = db.stringOf("SELECT new FROM save_rename WHERE kind = 'track' AND old = ?", song)
                val shows = db.longOf("SELECT COUNT(*) FROM liked_track WHERE trackId = ? OR trackId = ?", song, now ?: song)
                if (shows == 0L) problems += "$at: the like written on $song shows on neither id"
            }
            for (old in oldSongs) {
                val target = db.stringOf("SELECT new FROM save_rename WHERE kind = 'track' AND old = ?", old)
                val stands = db.longOf("SELECT COUNT(*) FROM track WHERE id = ?", old) == 1L
                when {
                    target != null && db.longOf("SELECT COUNT(*) FROM source_track WHERE mergedId = ?", target) == 0L -> problems += "$at: $old goes to $target, which has no copy"
                    target == null && stands && db.longOf("SELECT COUNT(*) FROM source_track WHERE id = ? AND mergedId = ?", old, old) == 0L ->
                        problems += "$at: $old, with nowhere to go, lost its own copy"
                }
            }
        }
        db.boundMerge(server, bound, hooks).rebuild()
        // A row left on a song gone for good names it from then on, as the
        // save leaves it; any other row naming a missing id is a problem.
        val after = db.dangling()
        for ((turn, dangling) in danglingAt) {
            val wrong = dangling - after
            if (wrong.isNotEmpty()) problems += "$where, after group turn $turn: rows naming missing ids ${wrong.take(5)}"
        }
        // Every row a rename in force reaches has moved to its new id.
        for (t in SAVE_RENAME_TARGETS) {
            val left = db.longOf("SELECT COUNT(*) FROM `${t.table}` o WHERE ${t.condition { "o.`$it`" }}")
            if (left > 0) problems += "$where: $left rows of ${t.table} that a rename in force reaches are left on their old ids"
        }
        // Every reference the find gave a target holds it now, unless its
        // table's guard kept it: the new id was taken there already.
        assertTrue("$where: the find planned no reference", refs.isNotEmpty())
        // A reference whose target is its own id moves nothing: its row now
        // holds a key what it names (or one of that one's copies) is filed under.
        val (rekeys, moves) = refs.partition { it[5] == it[6] }
        val unmoved = moves.filter { ref -> stillOld(db, ref) }
        if (unmoved.isNotEmpty()) problems += "$where: ${unmoved.size} references the find planned are left on their old ids, ${unmoved.take(3)}"
        val unkeyed = rekeys.filter { ref ->
            val id = ref[6] as String
            val t = SAVE_RENAME_TARGETS.first { it.table == ref[0] }
            val pin = if (t.kind == null) " AND o.kind = '${ref[1]}'" else ""
            if (ref[1] == RENAME_SONG) {
                db.longOf(
                    "SELECT COUNT(*) FROM `${ref[0]}` o JOIN track t ON t.id = o.trackId WHERE o.trackId = ? AND o.relinkKey IS NOT t.relinkKey " +
                        "AND NOT EXISTS (SELECT 1 FROM source_track s WHERE s.mergedId = o.trackId AND s.relinkKey = o.relinkKey)",
                    id,
                ) > 0
            } else {
                val kind = ref[1] as String
                db.longOf(
                    "SELECT COUNT(*) FROM `${ref[0]}` o JOIN $kind x ON x.id = o.`${t.column}` WHERE o.`${t.column}` = ?$pin AND o.relinkKey IS NOT x.searchKey " +
                        "AND NOT EXISTS (SELECT 1 FROM track tr JOIN source_track s ON s.mergedId = tr.id JOIN source_$kind a ON a.id = s.${kind}Id " +
                        "WHERE tr.${kind}Id = x.id AND a.searchKey = o.relinkKey)",
                    id,
                ) > 0
            }
        }
        if (unkeyed.isNotEmpty()) problems += "$where: ${unkeyed.size} songs the find gave keys hold rows under a key no copy holds, ${unkeyed.take(3)}"
        val finalAlbums = rows(db, "album")
        val finalArtists = rows(db, "artist")
        albumsAt.forEachIndexed { i, rows -> rows.filter { it !in startAlbums && it !in finalAlbums }.take(1).forEach { problems += "$where, park $i: album row $it is neither its old nor its final row" } }
        artistsAt.forEachIndexed { i, rows -> rows.filter { it !in startArtists && it !in finalArtists }.take(1).forEach { problems += "$where, park $i: artist row $it is neither its old nor its final row" } }
        // Both ids of a song stand only between the pages of one run.
        for ((turn, count) in bothStand) {
            val midRun = groups[turn + 1] != null && groups[turn + 1] == groups[turn]
            if (count > 0 && !midRun) problems += "$where: after turn $turn, $count songs stand under both ids outside a run"
        }
        assertTrue(problems.take(20).joinToString("\n"), problems.isEmpty())
        val runParks = groups.keys.count { groups[it + 1] != null && groups[it + 1] == groups[it] }
        assertTrue("$where: ${groups.size} groups", groups.values.toSet().size > 1)
        assertTrue("$where: no park inside a run", runParks > 0)
        assertTrue("$where: the like was never written", liked != null)
        assertEquals("$where: the like is on its song", 1L, db.longOf("SELECT COUNT(*) FROM liked_track l JOIN track t ON t.id = l.trackId WHERE l.likedAt = 99"))
        assertTrue("$where: parks ${bothStand.size}", bothStand.size >= 2)
    }

    // Whether a planned reference (table, kind, key, old id, target) still
    // holds its old id with nothing taking its new one: its row found by its
    // table's key, and its table's guard read for the new id.
    private fun stillOld(db: CatalogDatabase, ref: List<Any?>): Boolean {
        val (tbl, kind, keyText) = ref
        val t = SAVE_RENAME_TARGETS.first { it.table == tbl }
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()
        t.keyText?.let { where += "`$it` = ?"; args += keyText }
        t.keyNums.getOrNull(0)?.let { where += "`$it` = ?"; args += ref[3] }
        t.keyNums.getOrNull(1)?.let { where += "`$it` = ?"; args += ref[4] }
        if (t.kind == null) {
            where += "`kind` = ?"
            args += kind
        }
        where += "`${t.column}` = ?"
        args += ref[5]
        if (db.longOf("SELECT COUNT(*) FROM `${t.table}` WHERE ${where.joinToString(" AND ")}", *args.toTypedArray()) == 0L) return false
        return when (t.guard) {
            RenameGuard.None -> true
            RenameGuard.Taken -> {
                val pin = if (t.kind == null) " AND `kind` = ?" else ""
                db.longOf("SELECT COUNT(*) FROM `${t.table}` WHERE `${t.column}` = ?$pin", *listOfNotNull(ref[6], if (t.kind == null) kind else null).toTypedArray()) == 0L
            }
            RenameGuard.InList -> {
                val list = db.longOf("SELECT listId FROM `${t.table}` WHERE queueId = ? AND entry = ?", ref[3], ref[4])
                db.longOf("SELECT COUNT(*) FROM `${t.table}` WHERE `${t.column}` = ? AND queueId = ? AND listId = ?", ref[6], ref[3], list) == 0L
            }
        }
    }

    // The library's own id columns that name a missing row, each as the
    // row and the id it names.
    private fun ownDangling(db: CatalogDatabase): List<String> = listOf(
        "SELECT 'song ' || t.id || ' album ' || t.albumId FROM track t WHERE NOT EXISTS (SELECT 1 FROM album a WHERE a.id = t.albumId)",
        "SELECT 'song ' || t.id || ' artist ' || t.artistId FROM track t WHERE NOT EXISTS (SELECT 1 FROM artist a WHERE a.id = t.artistId)",
        "SELECT 'album ' || b.id || ' artist ' || b.artistId FROM album b WHERE NOT EXISTS (SELECT 1 FROM artist a WHERE a.id = b.artistId)",
        "SELECT 'copy ' || s.id || ' song ' || s.mergedId FROM source_track s WHERE s.mergedId != '' AND NOT EXISTS (SELECT 1 FROM track t WHERE t.id = s.mergedId)",
    ).flatMap { query -> db.query(query, null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } }

    private fun rows(db: CatalogDatabase, table: String): Set<List<Any?>> =
        db.query("SELECT * FROM `$table`", null).use { c ->
            buildSet {
                while (c.moveToNext()) add((0 until c.columnCount).map { i -> if (c.isNull(i)) null else c.getString(i) })
            }
        }
}
