package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// The find (SaveFind) finds exactly what today's swap writes (TodaySwap.kt,
// with the one adoption TodayAdoptions.kt adds): the find runs on the
// library as the claim leaves it, today's swap runs on the same rows after
// it, and every row today's swap added, removed or changed, by its table's
// key, is a found row or a reference with the same values, and nothing
// else is. A skipped merge likewise against today's skipped merge. Each
// case runs at a small bound and at the default.
@RunWith(RobolectricTestRunner::class)
class SaveFindTodayTest {
    private val server = "server:find.test"
    private val other = "server:other.test"

    @Test
    fun anEverydayCopyIsFoundAsTodaySwapsIt() = cases { bound ->
        val db = openTestCatalog()
        try {
            val phone = library(DEVICE, 12)
            val copy = library(server, 30)
            db.put(phone)
            db.put(copy)
            rebuildSettled(db, server)
            userRows(db, Random(1))
            // The server's next copy: songs retitled, rerated, gone and new,
            // an album renamed, and one row taken in while it was built.
            val next = copy.copy(
                tracks = copy.tracks.mapIndexedNotNull { i, t ->
                    when {
                        i % 7 == 0 -> null
                        i % 5 == 0 -> t.copy(title = t.title + " (Live)", searchKey = t.searchKey + " live", relinkKey = t.relinkKey + " live")
                        i % 4 == 0 -> t.copy(rating = 4, playCount = 3, lastPlayedAt = 1_000L + i)
                        i % 6 == 0 -> t.copy(seq = 100L + i)
                        else -> t
                    }
                } + (30 until 34).map { song(server, "s$it", "Song $it", "album3", no = it % 10 + 1) },
                albums = copy.albums.map { if (it.id.endsWith("album1")) it.copy(title = "Album One") else it },
            )
            db.stage(next, generation = 7)
            db.exec("INSERT INTO take_in_log (generation, kind, id) VALUES (7, 'track', ?)", "$server:s7")
            db.exec("INSERT INTO rating_note (sourceRowId, rating) VALUES (?, 5)", "$server:s3")
            // Taps on a gone copy, a kept one and the copy taken in.
            db.exec("INSERT INTO rating_tap (sourceRowId, rating) VALUES (?, 2), (?, 3), (?, 4)", "$server:s14", "$server:s3", "$server:s7")
            checkAgainstToday(db, server, bound)
        } finally {
            db.close()
        }
    }

    @Test
    fun aServerSwitchIsFoundAsTodaySwapsIt() = cases { bound ->
        val db = openTestCatalog()
        try {
            db.put(library(DEVICE, 4))
            val old = library(other, 20)
            // Two of the old server's songs share a relink key: both go to
            // the new server's one song with it, the first written first.
            db.put(old.copy(tracks = old.tracks + song(other, "dup", "Song 9", "album0", ms = 290_000)))
            rebuildSettled(db, other)
            for (id in listOf("$other:s9", "$other:dup")) {
                insertRow(db, "liked_track", mapOf("trackId" to id, "relinkKey" to "drake|song 9", "likedAt" to 1))
                insertRow(db, "track_rating", mapOf("trackId" to id, "relinkKey" to "drake|song 9", "rating" to 2, "ratedAt" to 1))
            }
            userRows(db, Random(2))
            // The new server has the same songs under its own ids, and two
            // of its own; the old one's stay kept, out of the library.
            val next = library(server, 22)
            db.stage(next, generation = 3)
            checkAgainstToday(db, server, bound)
        } finally {
            db.close()
        }
    }

    @Test
    fun aPhoneScanIsFoundAsTodaySwapsIt() = cases { bound ->
        val db = openTestCatalog()
        try {
            val phone = library(DEVICE, 16)
            db.put(phone)
            db.put(library(server, 16))
            rebuildSettled(db, server)
            userRows(db, Random(3))
            // Phone files go (their server copies become the library songs,
            // and what followed them follows the copies), one is retagged.
            db.put(
                phone.copy(
                    tracks = phone.tracks.filterIndexed { i, _ -> i % 3 != 0 }
                        .map { if (it.id == "$DEVICE:s4") it.copy(title = "Song Four", relinkKey = "drake|song four") else it },
                ),
            )
            checkAgainstToday(db, server, bound)
        } finally {
            db.close()
        }
    }

    // Rows that meet: two likes and two ratings whose songs merge into one,
    // a queue list holding two songs that merge, favorites and pins of two
    // albums that merge into one, and a rating moved onto a song no server
    // rated.
    @Test
    fun rowsThatMeetOnOneSongAreFoundAsTodaySwapsThem() = cases { bound ->
        val db = openTestCatalog()
        try {
            // One song on the server and on a USB stick, 4 s apart, so two
            // songs; then the phone gets it, 2 s from each, and both become
            // copies of the phone's song.
            fun source(id: String, ms: Long, extra: Int) = SourceCatalog(
                id, id == DEVICE,
                listOf(song(id, "s0", "Song 0", "album0", ms = ms)) + (1..extra).map { song(id, "s$it", "Song ${it + 10 * extra}", "album1", ms = 230_000L + it) },
                listOf(albumRow(id, "album0", "Album 0"), albumRow(id, "album1", "Album ${extra + 1}")),
                listOf(artistRow(id)),
            )
            val phone = source(DEVICE, 200_000, 1)
            db.put(phone.copy(tracks = phone.tracks.drop(1), albums = phone.albums.drop(1)))
            db.put(source(server, 198_000, 2))
            db.put(source("usb", 202_000, 3))
            rebuildSettled(db, server)
            val x1 = "$server:s0"
            val x2 = "usb:s0"
            // Each written later id first, so a walk in the order rows were
            // written and one in the order of their ids part ways.
            for (id in listOf(x2, x1)) {
                insertRow(db, "liked_track", mapOf("trackId" to id, "relinkKey" to "drake|song 0", "likedAt" to 1))
                insertRow(db, "track_rating", mapOf("trackId" to id, "relinkKey" to "drake|song 0", "rating" to if (id == x1) 3 else 4, "ratedAt" to 1))
                insertRow(db, "queue_entry", mapOf("queueId" to 1, "entry" to id.length.toLong(), "trackId" to id, "listId" to 1))
            }
            insertRow(db, "queue_entry", mapOf("queueId" to 1, "entry" to 99, "trackId" to x2, "listId" to 2))
            for (album in listOf("usb:album0", "$server:album0")) {
                insertRow(db, "liked_album", mapOf("albumId" to album, "relinkKey" to "album 0", "likedAt" to 1))
                insertRow(db, "pinned_item", mapOf("kind" to "album", "itemId" to album, "relinkKey" to "album 0", "position" to album.length))
            }
            insertRow(db, "online_song", mapOf("id" to "find:1", "sourceId" to server, "nativeId" to "1", "title" to "F", "artist" to "A", "album" to "", "durationMs" to 1, "seenAt" to 1, "adoptedId" to x1))
            db.put(phone)
            checkAgainstToday(db, server, bound)
        } finally {
            db.close()
        }
    }

    @Test
    fun aSkippedMergeIsFoundAsTodaySkipsIt() = cases { bound ->
        val db = openTestCatalog()
        try {
            db.put(library(DEVICE, 6))
            db.put(library(server, 6))
            rebuildSettled(db, server)
            // Written since with ids a merge took away: a server copy's id
            // (it follows its song), an old id with a song's relink key, and
            // a rating moved onto a song showing none.
            insertRow(db, "liked_track", mapOf("trackId" to "$server:s2", "relinkKey" to "drake|song 2", "likedAt" to 1))
            insertRow(db, "play_event", mapOf("trackId" to "gone:9", "relinkKey" to "drake|song 4", "startedAt" to 5, "playedMs" to 1, "durationMs" to 1))
            insertRow(db, "track_rating", mapOf("trackId" to "$server:s5", "relinkKey" to "drake|song 5", "rating" to 4, "ratedAt" to 1))
            db.exec("UPDATE track SET rating = 0 WHERE id = ?", "$DEVICE:s5")
            insertRow(db, "pinned_item", mapOf("kind" to "album", "itemId" to "gone:album", "relinkKey" to "album 0", "position" to 0))
            checkAgainstToday(db, null, bound, skipped = true)
        } finally {
            db.close()
        }
    }

    @Test
    fun seededLibrariesAreFoundAsTodaySwapsThem() = cases { bound ->
        for (seed in listOf(41L, 42L, 43L)) {
            val db = openTestCatalog()
            try {
                val (phone, copy) = MergeRowsGen.sources(seed, 240)
                db.put(phone)
                db.put(copy)
                rebuildSettled(db, MergeRowsGen.SERVER)
                userRows(db, Random(seed))
                db.stage(MergeRowsGen.secondCopy(seed + 100, copy), generation = 9)
                checkAgainstToday(db, MergeRowsGen.SERVER, bound, label = "seed $seed")
            } finally {
                db.close()
            }
        }
    }

    // Each case at a bound that splits everything into many transactions,
    // and at the default.
    private fun cases(case: suspend (Long) -> Unit) = runBlocking {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) case(bound)
    }

    // Rows of every kind that follow library ids, on library songs, server
    // copies' ids, ids no source has and ids with no song's key.
    private suspend fun userRows(db: CatalogDatabase, random: Random) {
        val songs = db.query("SELECT id, relinkKey FROM track ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        val copies = db.query("SELECT id, relinkKey FROM source_track ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        val albums = db.query("SELECT id, searchKey FROM album ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        val artists = db.query("SELECT id, searchKey FROM artist ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        val ids = songs + copies + listOf("gone:1" to "drake|song 1", "gone:2" to "", "gone:3" to "nobody|nothing")
        fun pick() = ids[random.nextInt(ids.size)]
        insertRow(db, "playlist", mapOf("id" to "p", "name" to "P", "createdAt" to 1, "updatedAt" to 1))
        val liked = HashSet<String>()
        val rated = HashSet<String>()
        repeat(40) { n ->
            val (id, key) = pick()
            if (liked.add(id)) insertRow(db, "liked_track", mapOf("trackId" to id, "relinkKey" to key, "likedAt" to n))
            val (rid, rkey) = pick()
            if (rated.add(rid)) insertRow(db, "track_rating", mapOf("trackId" to rid, "relinkKey" to rkey, "rating" to 1 + n % 5, "ratedAt" to n))
            val (pid, pkey) = pick()
            insertRow(db, "play_event", mapOf("trackId" to pid, "relinkKey" to pkey, "startedAt" to n, "playedMs" to 1, "durationMs" to 1))
            val (iid, ikey) = pick()
            insertRow(db, "playlist_item", mapOf("playlistId" to "p", "itemSet" to 0, "trackId" to iid, "relinkKey" to ikey, "ord" to n, "itemKey" to n))
            val (eid, ekey) = pick()
            insertRow(db, "playlist_edit_log", mapOf("playlistId" to "p", "itemSet" to 0, "op" to 0, "itemKey" to n, "ord" to n, "trackId" to eid, "relinkKey" to ekey))
            val (qid, _) = pick()
            insertRow(db, "queue_entry", mapOf("queueId" to 1 + n % 2, "entry" to n, "trackId" to qid, "listId" to n % 3))
            val (sid, skey) = pick()
            insertRow(db, "server_entry", mapOf("owner" to WorkOwners.marked("vm:${n % 2}"), "position" to n, "trackId" to sid, "relinkKey" to skey))
            val (lid, _) = pick()
            insertRow(db, "lists_album_entry", mapOf("owner" to WorkOwners.marked("vm:0"), "position" to n, "shownId" to lid, "isFind" to n % 4 / 3))
            val (did, dkey) = pick()
            insertRow(db, "lists_server_dir", mapOf("owner" to WorkOwners.marked("vm:0"), "position" to n, "kind" to n % 3 / 2, "trackId" to did, "relinkKey" to dkey))
            val (aid, _) = pick()
            insertRow(db, "online_song", mapOf("id" to "find:$n", "sourceId" to server, "nativeId" to "$n", "title" to "F", "artist" to "A", "album" to "", "durationMs" to 1, "seenAt" to 1, "adoptedId" to if (n % 2 == 0) aid else ""))
        }
        for ((n, album) in (albums + ("gone:album" to "album 2")).withIndex()) {
            if (n % 2 == 0) insertRow(db, "liked_album", mapOf("albumId" to album.first, "relinkKey" to album.second, "likedAt" to n))
            if (n % 3 == 0) insertRow(db, "pinned_item", mapOf("kind" to "album", "itemId" to album.first, "relinkKey" to album.second, "position" to n))
        }
        for ((n, artist) in artists.withIndex()) {
            insertRow(db, "liked_artist", mapOf("artistId" to artist.first, "relinkKey" to artist.second, "likedAt" to n))
            insertRow(db, "pinned_item", mapOf("kind" to "artist", "itemId" to artist.first, "relinkKey" to artist.second, "position" to 100 + n))
        }
    }

    private suspend fun checkAgainstToday(db: CatalogDatabase, server: String?, bound: Long, skipped: Boolean = false, label: String = "") {
        val claims = if (skipped) null else claimAndBuild(db, server)
        val sql = db.openHelper.writableDatabase
        val nextCount = "SELECT COUNT(*) FROM merged_id_next"
        val counts = arrayListOf(db.longOf(nextCount))
        val before = SaveOracle.image(sql)
        counts += db.longOf(nextCount)
        val result = SaveFind(db, bound).find(claims?.second)
        counts += db.longOf(nextCount)
        val found = db.query("SELECT tbl, op, id, oldId, newId, seenRating, newRating, groupId FROM save_found", null).use { c ->
            buildList { while (c.moveToNext()) add(Found(c.getString(0), c.getInt(1), c.getString(2), c.str(3), c.str(4), c.intOrNull(5), c.intOrNull(6), c.longOrNull(7))) }
        }
        val refs = db.query("SELECT tbl, kind, keyText, keyNum, keyNum2, oldId, relinkKey, target, groupId FROM save_ref", null).use { c ->
            buildList { while (c.moveToNext()) add(Ref(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5), c.getString(6), c.getString(7), c.longOrNull(8))) }
        }
        if (claims == null) {
            todaySkipped(db)
        } else {
            todaySwap(db, claims.first)
            todayAdoptions(db)
        }
        todayKeys(db)
        val after = SaveOracle.image(sql)
        val changes = SaveOracle.changes(before, after)
        val where = "$label bound $bound"

        // Source rows of the claimed copies, and every source row's song.
        val expected = HashSet<String>()
        val claimed = claims?.second?.staged?.keys.orEmpty()
        val library = claims?.second?.sources.orEmpty()
        for (table in listOf("source_track", "source_album", "source_artist")) {
            val ch = changes[table] ?: continue
            ch.added.forEach { (k, row) ->
                expected += "$table new ${k[0]}"
                val merged = row.getOrNull(ch.columns.indexOf("mergedId"))
                if (table == "source_track" && merged != null && merged != "") expected += "merged_id ${k[0]} '' -> $merged"
            }
            ch.removed.keys.forEach { expected += "$table gone ${it[0]}" }
            ch.changed.forEach { (k, cols) ->
                val rest = cols.keys - "mergedId" - "seq"
                if (rest.isNotEmpty()) expected += "$table changed ${k[0]}"
                if ("seq" in cols) expected += "$table placed ${k[0]}"
                cols["mergedId"]?.let { (old, new) ->
                    val source = library.firstOrNull { (k[0] as String).startsWith("$it:") }
                    expected += if (source != null) "merged_id ${k[0]} '$old' -> $new" else "unmerge ${k[0]} $old"
                }
            }
            assertTrue("$where: rows of $table changed outside a claimed copy", (ch.added.keys + ch.removed.keys + ch.changed.filter { it.value.keys != setOf("mergedId") }.keys)
                .all { k -> claimed.any { (k[0] as String).startsWith("$it:") } })
        }
        // Library rows.
        for (table in listOf("track", "album", "artist")) {
            val ch = changes[table] ?: continue
            val rating = ch.columns.indexOf("rating")
            ch.added.forEach { (k, row) -> expected += "$table new ${k[0]}" + if (table == "track") " rating ${row[rating]}" else "" }
            ch.removed.forEach { (k, _) -> expected += "$table gone ${k[0]}" }
            ch.changed.forEach { (k, cols) ->
                val op = if (table == "track" && cols.keys == setOf("rating")) "rated" else "changed"
                expected += "$table $op ${k[0]}" + if (table == "track") " rating ${after.tables.getValue(table).rows.getValue(k)[rating]}" else ""
            }
        }
        changes["rating_tap"]?.removed?.keys?.forEach { expected += "rating_tap gone ${it[0]}" }
        val foundLines = found.filter { it.tbl != FOUND_TOTALS }.map { f ->
            val op = listOf("new", "changed", "placed", "gone", "rated")[f.op]
            when (f.tbl) {
                FOUND_MERGED_ID -> "merged_id ${f.id} '${f.oldId}' -> ${f.newId}"
                FOUND_UNMERGE -> "unmerge ${f.id} ${f.oldId}"
                "track" -> "track $op ${f.id}" + if (f.op == FOUND_GONE) "" else " rating ${f.newRating?.toLong()}"
                else -> "${f.tbl} $op ${f.id}"
            }
        }
        val detail = (expected - foundLines.toSet()).filter { it.startsWith("merged_id") }.take(3).joinToString { line ->
            val id = line.split(' ')[1]
            "$id: next ${db.stringOf("SELECT mergedId FROM merged_id_next WHERE sourceRowId = ?", id)} (merged_id_next rows $counts, " +
                "then ${db.longOf(nextCount)}, source rows ${db.longOf("SELECT COUNT(*) FROM source_track")}), before " +
                before.tables.getValue("source_track").let { it.rows[listOf(id)]?.get(it.columns.indexOf("mergedId")) } +
                ", found ${found.filter { it.id == id }.map { "${it.tbl}/${it.op}" }}"
        }
        assertSameLines("$where: found rows $detail", expected, foundLines)
        // A song's rating as the find saw it is the one before the swap.
        for (f in found.filter { it.tbl == "track" && it.op != FOUND_NEW }) {
            val row = before.tables.getValue("track").rows.getValue(listOf(f.id))
            assertEquals("$where: ${f.id} seen rating", row[before.tables.getValue("track").columns.indexOf("rating")], f.seenRating?.toLong())
        }

        // Every row that follows an id: where today's relinks moved it, and
        // whether its relink key took its song's (as the key the row holds
        // after its move). A row keyed by the id it holds moves as one key
        // gone and one come, the rest of the row the same but for its relink
        // key where its table's keys follow their songs.
        val moved = HashSet<String>()
        for (t in SAVE_RENAME_TARGETS) {
            val ch = changes[t.table] ?: continue
            val key = after.tables.getValue(t.table).key
            val at = key.indexOf(t.column)
            val follows = t.table in TODAY_KEYED_TABLES
            val keyAt = if (follows) ch.columns.indexOf("relinkKey") else -1
            if (at < 0) {
                assertTrue("$where: ${t.table} rows added or removed", ch.added.isEmpty() && ch.removed.isEmpty())
            } else {
                fun rest(k: List<Any?>, row: List<Any?>) = k.filterIndexed { i, _ -> i != at } + row.filterIndexed { i, _ -> i != keyAt }
                val come = ch.added.entries.groupBy({ rest(it.key, it.value) }, { it }).mapValues { it.value.toMutableList() }
                for ((k, row) in ch.removed) {
                    val to = come[rest(k, row)]?.removeFirstOrNull()
                    assertTrue("$where: ${t.table} $k went, and no row like it came", to != null)
                    moved += "${t.table} $k ${k[at]} -> ${to!!.key[at]}"
                    if (keyAt >= 0 && to.value[keyAt] != row[keyAt]) moved += "${t.table} ${to.key} rekey -> ${to.value[keyAt]}"
                }
                assertTrue("$where: ${t.table} rows came with no row like them gone", come.values.all { it.isEmpty() })
            }
            ch.changed.forEach { (k, cols) ->
                val allowed = if (follows) setOf(t.column, "relinkKey") else setOf(t.column)
                assertTrue("$where: ${t.table} $k changed more than its id and its key: ${cols.keys}", cols.keys.isNotEmpty() && allowed.containsAll(cols.keys))
                cols[t.column]?.let { (old, new) -> moved += "${t.table} $k $old -> $new" }
                cols["relinkKey"]?.let { (_, new) -> moved += "${t.table} $k rekey -> $new" }
            }
        }
        val refLines = refs.filter { it.tbl != REF_RENAME }.map { r ->
            val t = SAVE_RENAME_TARGETS.single { it.table == r.tbl }
            val key = after.tables.getValue(t.table).key.map { col ->
                when (col) {
                    t.keyText -> r.keyText
                    t.keyNums.getOrNull(0) -> r.keyNum
                    t.keyNums.getOrNull(1) -> r.keyNum2
                    "kind" -> r.kind
                    else -> error("${t.table}.$col is no part of a reference's key")
                }
            }
            // A reference that names its own id as its target sets the row's key.
            if (r.target == r.oldId) {
                val named = after.tables.getValue(SaveFindCheck.liveOf(r.kind))
                val column = if (r.kind == RENAME_SONG) "relinkKey" else "searchKey"
                "${r.tbl} $key rekey -> ${named.rows[listOf(r.target)]?.get(named.columns.indexOf(column))}"
            } else {
                "${r.tbl} $key ${r.oldId} -> ${r.target}"
            }
        }
        assertSameLines("$where: references", moved, refLines)

        // Nothing else changed but the bookkeeping the apply's last steps
        // write, which is not the find's.
        val checked = setOf("source_track", "source_album", "source_artist", "track", "album", "artist", "rating_tap") + SAVE_RENAME_TARGETS.map { it.table }
        val bookkeeping = setOf("source_meta", "account_sync", "merge_state", "take_in_log", "rating_note")
        assertEquals("$where: tables changed", emptySet<String>(), changes.keys - checked - bookkeeping)

        // A rename by follow is the song the old id's source row became, and
        // every rename's id is in the library.
        for (r in refs.filter { it.tbl == REF_RENAME }) {
            val final = after.tables.getValue(SaveFindCheck.liveOf(r.kind)).rows
            assertTrue("$where: rename of ${r.oldId} to ${r.target}, not in the library", listOf(r.target) in final)
            if (r.kind == RENAME_SONG && r.keyNum == 0L) {
                assertEquals("$where: ${r.oldId} follows", db.stringOf("SELECT mergedId FROM merged_id_next WHERE sourceRowId = ?", r.oldId), r.target)
            }
        }

        // Planned: every row is in a group, the groups the find says.
        assertTrue("$where: rows with no group", found.all { it.groupId != null } && refs.all { it.groupId != null })
        val groups = (found.mapNotNull { it.groupId } + refs.mapNotNull { it.groupId }).toSet()
        assertEquals("$where: groups", result.groups, groups.maxOrNull() ?: 0L)
        assertEquals("$where: found rows", result.found, found.size.toLong())
        assertEquals("$where: references", result.refs, refs.size.toLong())
    }

    private fun assertSameLines(what: String, expected: Collection<String>, actual: Collection<String>) {
        val missing = expected.toSet() - actual.toSet()
        val extra = actual.toSet() - expected.toSet()
        assertTrue("$what: missing ${missing.sorted().take(20)}; not expected ${extra.sorted().take(20)}", missing.isEmpty() && extra.isEmpty())
        assertEquals("$what: a row found twice", actual.size, actual.toSet().size)
    }

    private class Found(val tbl: String, val op: Int, val id: String, val oldId: String?, val newId: String?, val seenRating: Int?, val newRating: Int?, val groupId: Long?)

    private class Ref(
        val tbl: String,
        val kind: String,
        val keyText: String,
        val keyNum: Long,
        val keyNum2: Long,
        val oldId: String,
        val relinkKey: String,
        val target: String,
        val groupId: Long?,
    )
}

internal object SaveFindCheck {
    fun liveOf(kind: String) = when (kind) {
        RENAME_ALBUM -> "album"
        RENAME_ARTIST -> "artist"
        else -> "track"
    }
}

private fun android.database.Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

private fun android.database.Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

private fun android.database.Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

// Inserts a row with the given values and, in every other column that needs
// one, a value of its type: 0 for a number, "" for text. A row whose key a
// row holds already is left out.
internal fun insertRow(db: CatalogDatabase, table: String, values: Map<String, Any?>) {
    val sql = db.openHelper.writableDatabase
    val fill = sql.query("PRAGMA table_info(`$table`)").use { c ->
        buildMap {
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1
                val default = !c.isNull(c.getColumnIndexOrThrow("dflt_value"))
                val pk = c.getInt(c.getColumnIndexOrThrow("pk"))
                val type = c.getString(c.getColumnIndexOrThrow("type"))
                if (name !in values && notNull && !default && !(pk > 0 && type == "INTEGER")) put(name, if (type == "TEXT") "" else 0L)
            }
        }
    }
    val all = values + fill
    sql.execSQL("INSERT OR IGNORE INTO `$table` (${all.keys.joinToString { "`$it`" }}) VALUES (${all.keys.joinToString { "?" }})", all.values.toTypedArray())
}
