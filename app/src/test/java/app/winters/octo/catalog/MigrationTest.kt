package app.winters.octo.catalog

import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.winters.octo.playlists.syncedSongsOf
import app.winters.octo.server.StagedCopy
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Starred
import app.winters.octo.testing.FakeSubsonic
import app.winters.octo.testing.GenShape
import app.winters.octo.testing.LibraryGen
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.schemaFile
import app.winters.octo.testing.testContext
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.paging.Utf16Key
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    // A version 18 library file, made from Room's own schema for 18, with
    // rows in every table the upgrade copies or reads, in an order that
    // differs from their ids'.
    private fun version18(dir: File, rows: Int = 300): File {
        val file = File(dir, CATALOG_FILE)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            OldSqlite.schemaStatements(18).forEach(db::execSQL)
            val identity = Json.parseToJsonElement(schemaFile(18).readText()).jsonObject["database"]!!.jsonObject["identityHash"]!!.jsonPrimitive.content
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$identity')")
            for (i in rows downTo 1) {
                val src = if (i % 2 == 0) "device" else "server:host"
                db.execSQL(
                    "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, " +
                        "durationMs, addedAt, albumOrder, relinkKey, genre, mergedId) VALUES " +
                        "('$src:$i', '$src', '$i', 'T$i', 't$i', 't$i', 'A', '$src:a', 'Al', '$src:al', ${i * 1000}, $i, ${i % 7}, 'k$i', '', '$src:$i')",
                )
                db.execSQL(
                    "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey, genre) " +
                        "VALUES ('$src:$i', '$src', '$i', ?, 't$i', 't$i', 'The Artist', '$src:a', 'An Album', '$src:al', ${i * 1000}, $i, 'k$i', " +
                        "'${if (i % 3 == 0) "ROCK" else "Rock"}')",
                    arrayOf<Any?>(titleOf(i)),
                )
            }
            for (i in 1..20) {
                db.execSQL("INSERT INTO source_album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, songCount, durationMs, addedAt) VALUES ('al$i', 'device', '$i', 'Al', 'al', 'al', 'A', 'a', 1, 1, 1)")
                db.execSQL("INSERT INTO source_artist (id, sourceId, name, searchKey, sortKey, albumCount, songCount) VALUES ('ar$i', 'device', 'A', 'a', 'a', 1, 1)")
            }
            // Only the phone's artist and the server's album are in the library.
            db.execSQL("INSERT INTO artist (id, sourceId, name, searchKey, sortKey, albumCount, songCount) VALUES ('device:a', 'device', 'The Artist', 'the artist', 'artist', 1, 1)")
            db.execSQL("INSERT INTO album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, songCount, durationMs, addedAt) VALUES ('server:host:al', 'server:host', 'al', 'An Album', 'an album', 'album', 'A', 'a', 1, 1, 1)")
            for (i in rows downTo 1 step 3) {
                db.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('device:$i', 'k$i', ${1000 - i})")
                db.execSQL("INSERT INTO track_rating (trackId, relinkKey, rating, ratedAt) VALUES ('device:$i', 'k$i', ${i % 5 + 1}, ${1000 - i})")
            }
            DOWNLOAD_TITLES.forEachIndexed { i, title ->
                // Two downloads of one song, the later row last.
                db.execSQL(
                    "INSERT INTO download (trackId, sourceId, serverId, path, sizeBytes, state, progress, addedAt, reason, title, artist) VALUES " +
                        "('server:host:${i / 2}', 'server:host', 's${DOWNLOAD_TITLES.size - i}', '', $i, 'Done', 1.0, $i, 'manual', '${title.replace("'", "''")}', 'A')",
                )
            }
            db.execSQL("INSERT INTO playlist (id, name, createdAt, updatedAt, family, syncedSongs) VALUES ('big', 'Big', 1, 1, 1, '${BIG_BASE}')")
            db.execSQL("INSERT INTO playlist (id, name, createdAt, updatedAt, family, syncedSongs) VALUES ('empty', 'Empty', 1, 1, 1, '')")
            db.execSQL("INSERT INTO playlist (id, name, createdAt, updatedAt) VALUES ('none', 'None', 1, 1)")
            // A long playlist whose order runs against its ids, with a tie
            // on one place, and a last row taken out, so the id counter is
            // past the largest id.
            for (i in 1..PLAYLIST_ROWS + 1) {
                db.execSQL("INSERT INTO playlist_item (playlistId, trackId, relinkKey, position, serverSongId) VALUES ('none', 'device:${i % 150 * 2 + 2}', 'k$i', ${placeOf(i)}, 's$i')")
            }
            db.execSQL("DELETE FROM playlist_item WHERE id = ${PLAYLIST_ROWS + 1}")
            for (p in 0 until rows) {
                val id = if (p % 10 == 9) "find:gone" else "device:${(p % (rows / 2) + 1) * 2}"
                db.execSQL("INSERT INTO queue_item (position, trackId, shuffledPosition) VALUES ($p, '$id', ${rows - 1 - p})")
            }
            db.execSQL("INSERT INTO queue_state (id, currentIndex, positionMs, repeatMode, shuffle, savedAt) VALUES (0, 7, 1234, 2, 1, 99)")
            // Plays, one taken out in the middle and the last two at the end,
            // so the id counter is past every id left; and finds, written in
            // an order that differs from their ids', every third adopted.
            for (i in 1..PLAYS + 2) {
                db.execSQL(
                    "INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(playOf(i), "k$i", (i * 7_919L) % 100_000, i * 10L, i * 1_000L),
                )
            }
            db.execSQL("DELETE FROM play_event WHERE id IN (5, ${PLAYS + 1}, ${PLAYS + 2})")
            for (i in FINDS downTo 1) {
                db.execSQL(
                    "INSERT INTO online_song (id, sourceId, nativeId, title, artist, album, durationMs, seenAt, requestedAt, adoptedId) " +
                        "VALUES (?, 'server:host', ?, ?, 'A', '', ?, ?, ?, ?)",
                    arrayOf<Any?>("find:$i", "$i", "F$i", i * 1_000L, i.toLong(), if (i % 3 == 0) i.toLong() else 0L, adoptedOf(i)),
                )
            }
            db.version = 18
        }
        return file
    }

    @Test
    fun upgradesEveryRowAndRoomAcceptsTheSchema() = runBlocking {
        val dir = Files.createTempDirectory("m").toFile()
        version18(dir)
        // Room checks every table and index against the entities on open,
        // and throws on any difference.
        val db = openTestCatalog(dir)
        val conn = db.openHelper.writableDatabase
        conn.query("SELECT COUNT(*) FROM source_track").use { it.moveToFirst(); assertEquals(300, it.getInt(0)) }
        conn.query("SELECT COUNT(*) FROM track").use { it.moveToFirst(); assertEquals(300, it.getInt(0)) }
        // seq is the old rowid times SEQ_SPAN: the first row written (id
        // 300) comes first, and every row keeps its old order, SEQ_SPAN
        // apart from the next.
        conn.query("SELECT id, seq, keysVersion, titleKey, folder, fileName, storageVolume FROM source_track ORDER BY seq LIMIT 1").use {
            it.moveToFirst()
            assertEquals("device:300", it.getString(0))
            assertEquals(SEQ_SPAN, it.getLong(1))
            assertEquals(0, it.getInt(2))
            assertEquals("", it.getString(3))
            // A phone file's place comes with the next scan.
            assertTrue(it.isNull(4) && it.isNull(5) && it.isNull(6))
        }
        val written = (300 downTo 1).map { i -> if (i % 2 == 0) "device:$i" else "server:host:$i" }
        assertEquals("the old order", written, conn.strings("SELECT id FROM source_track ORDER BY seq"))
        for (table in listOf("source_track", "source_album", "source_artist")) {
            val places = conn.longs("SELECT seq FROM `$table` ORDER BY rowid")
            assertTrue("$table: ${places.take(5)}", places.isNotEmpty() && places.first() == SEQ_SPAN && places.zipWithNext().all { (a, b) -> b - a == SEQ_SPAN })
        }
        conn.query("SELECT COUNT(*) FROM source_album WHERE seq = rowid * $SEQ_SPAN").use { it.moveToFirst(); assertEquals(20, it.getInt(0)) }
        conn.query("SELECT COUNT(*) FROM source_artist WHERE seq = rowid * $SEQ_SPAN").use { it.moveToFirst(); assertEquals(20, it.getInt(0)) }

        // A song files under its artist's and album's sort keys where the
        // library has them, otherwise under the names lowercased.
        conn.query("SELECT artistSortKey, albumSortKey FROM track WHERE id = 'device:2'").use {
            it.moveToFirst()
            assertEquals("artist", it.getString(0))
            assertEquals("an album", it.getString(1))
        }
        conn.query("SELECT artistSortKey, albumSortKey FROM track WHERE id = 'server:host:1'").use {
            it.moveToFirst()
            assertEquals("the artist", it.getString(0))
            assertEquals("album", it.getString(1))
        }
        // Genres compare without case.
        conn.query("SELECT COUNT(*) FROM track WHERE genre = 'rock'").use { it.moveToFirst(); assertEquals(300, it.getInt(0)) }
        // The track rows kept their order and their titles, and each files
        // under its title's lookup keys, read with its artist.
        conn.query("SELECT id FROM track ORDER BY rowid LIMIT 1").use { it.moveToFirst(); assertEquals("device:300", it.getString(0)) }
        conn.query("SELECT nativeId, title, artist, lookupKey, looseKey FROM track ORDER BY rowid").use { c ->
            var rows = 0
            var keyless = 0
            while (c.moveToNext()) {
                rows++
                val title = c.getString(1)
                assertEquals(titleOf(c.getString(0).toInt()), title)
                val written = SongIdentity.titleLookupKeys(title, c.getString(2))
                if (written.isEmpty()) keyless++
                assertEquals(title, written, if (written.isEmpty()) emptySet() else setOf(c.getString(3), c.getString(4)))
                assertEquals(title, titleLookup(title, c.getString(2)).lookupKey, c.getString(3))
                assertEquals(title, titleLookup(title, c.getString(2)).looseKey, c.getString(4))
            }
            assertEquals(300, rows)
            assertTrue("a title with no key", keyless > 0)
        }

        // Likes and ratings, every row, in the order they were written.
        val liked = (300 downTo 1 step 3).map { "device:$it" }
        assertEquals(liked, conn.strings("SELECT trackId FROM liked_track ORDER BY rowid"))
        assertEquals(liked, conn.strings("SELECT trackId FROM track_rating ORDER BY rowid"))
        conn.query("SELECT rating, ratedAt FROM track_rating WHERE trackId = 'device:3'").use {
            it.moveToFirst()
            assertEquals(4, it.getInt(0))
            assertEquals(997L, it.getLong(1))
        }

        // Downloads in their old order, each with its title's key.
        conn.query("SELECT title, titleKey, serverId FROM download ORDER BY rowid").use { c ->
            var i = 0
            while (c.moveToNext()) {
                assertEquals(DOWNLOAD_TITLES[i], c.getString(0))
                assertArrayEquals(DOWNLOAD_TITLES[i], Utf16Key(sortKey(DOWNLOAD_TITLES[i])), c.getBlob(1))
                assertEquals("s${DOWNLOAD_TITLES.size - i}", c.getString(2))
                i++
            }
            assertEquals(DOWNLOAD_TITLES.size, i)
        }

        // A family playlist's base became a row per song with its count;
        // its cell is empty and the base is marked kept, an empty one too.
        val counted = conn.query("SELECT songId, copies, baseSet FROM playlist_synced_song WHERE playlistId = 'big'").use { c ->
            buildMap { while (c.moveToNext()) { assertEquals(0L, c.getLong(2)); put(c.getString(0), c.getInt(1)) } }
        }
        assertEquals(syncedSongsOf(BIG_BASE)!!.groupingBy { it }.eachCount(), counted)
        conn.query("SELECT COUNT(*) FROM playlist_synced_song WHERE playlistId != 'big'").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        conn.query("SELECT id, syncedSongs, syncedSongsKept, itemSet, baseSet FROM playlist ORDER BY rowid").use { c ->
            val kept = mapOf("big" to 1, "empty" to 1, "none" to 0)
            while (c.moveToNext()) {
                assertTrue(c.isNull(1))
                assertEquals(c.getString(0), kept[c.getString(0)], c.getInt(2))
                assertEquals(0L, c.getLong(3))
                assertEquals(0L, c.getLong(4))
            }
        }
        conn.query("PRAGMA foreign_key_check(playlist_synced_song)").use { assertEquals(0, it.count) }

        // Every playlist row keeps its id, takes it as its key, sits in set 0,
        // and keeps its place as a sparse ord, past an Int from place 2,048
        // on; the order (ord, itemKey) is the old (position, id).
        val order = (1..PLAYLIST_ROWS).sortedWith(compareBy({ placeOf(it) }, { it })).map { it.toLong() }
        conn.query("SELECT id, itemSet, ord, itemKey, trackId, relinkKey, serverSongId FROM playlist_item WHERE playlistId = 'none' ORDER BY ord, itemKey").use { c ->
            val ids = mutableListOf<Long>()
            while (c.moveToNext()) {
                val id = c.getLong(0)
                ids += id
                assertEquals(0L, c.getLong(1))
                assertEquals(placeOf(id.toInt()) * (1L shl 20), c.getLong(2))
                assertEquals(id, c.getLong(3))
                assertEquals("device:${id % 150 * 2 + 2}", c.getString(4))
                assertEquals("k$id", c.getString(5))
                assertEquals("s$id", c.getString(6))
            }
            assertEquals(order, ids)
        }
        val items = db.userDao().playlistItems("none")
        assertEquals(order, items.map { it.id })
        assertTrue(items.last().ord > Int.MAX_VALUE)
        assertEquals(2_999L shl 20, items.last().ord)
        conn.query("PRAGMA foreign_key_check(playlist_item)").use { assertEquals(0, it.count) }
        // The id counter carried over, and new row keys go on past every id.
        conn.query("SELECT seq FROM sqlite_sequence WHERE name = 'playlist_item'").use { it.moveToFirst(); assertEquals(PLAYLIST_ROWS + 1L, it.getLong(0)) }
        conn.query("SELECT id, nextItemKey FROM playlist_meta").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
            assertEquals(PLAYLIST_ROWS + 1L, it.getLong(1))
            assertTrue(!it.moveToNext())
        }

        // The saved queue: one generation, one list, every song in play order
        // with its shuffle place and its length, and where playback was.
        conn.query("SELECT entry, trackId, listId, qk, sk, durationMs FROM queue_entry WHERE queueId = 1 ORDER BY qk").use { c ->
            var p = 0
            while (c.moveToNext()) {
                val id = if (p % 10 == 9) "find:gone" else "device:${(p % 150 + 1) * 2}"
                assertEquals((1L shl 32) or p.toLong(), c.getLong(0))
                assertEquals(id, c.getString(1))
                assertEquals(1L, c.getLong(2))
                assertEquals((1L shl 62) + p * (1L shl 40), c.getLong(3))
                assertEquals((1L shl 60) + (1L shl 61) + (299 - p) * (1L shl 40), c.getLong(4))
                assertEquals(if (id == "find:gone") 0L else (p % 150 + 1) * 2 * 1000L, c.getLong(5))
                p++
            }
            assertEquals(300, p)
        }
        conn.query("SELECT listId, queueId, autoplay, fillState, source, spec, attempts FROM queue_list").use {
            assertTrue(it.moveToFirst())
            assertEquals(listOf(1L, 1L, 0L, 0L), (0..3).map(it::getLong))
            assertTrue(it.isNull(4) && it.isNull(5))
            assertEquals(0, it.getInt(6))
            assertTrue(!it.moveToNext())
        }
        conn.query("SELECT account, queueId, currentEntry, positionMs, repeatMode, shuffle, floorQ, floorS, healedAt, savedAt FROM queue_account").use {
            assertTrue(it.moveToFirst())
            assertEquals("", it.getString(0))
            assertEquals(1L, it.getLong(1))
            assertEquals((1L shl 32) or 7L, it.getLong(2))
            assertEquals(listOf(1234L, 2L, 1L), (3..5).map(it::getLong))
            assertTrue(it.isNull(6) && it.isNull(7) && it.isNull(8))
            assertEquals(99L, it.getLong(9))
            assertTrue(!it.moveToNext())
        }
        conn.query("SELECT id, nextQueueId, nextListId FROM queue_meta").use {
            assertTrue(it.moveToFirst())
            assertEquals(listOf(0L, 2L, 2L), (0..2).map(it::getLong))
        }
        // Every play, in id order with its values, and the id counter past
        // the last ids taken out: a new play takes the next id there is.
        val plays = (1..PLAYS).filter { it != 5 }
        conn.query("SELECT id, trackId, relinkKey, startedAt, playedMs, durationMs FROM play_event ORDER BY id").use { c ->
            for (i in plays) {
                assertTrue(c.moveToNext())
                assertEquals(i.toLong(), c.getLong(0))
                assertEquals(playOf(i), c.getString(1))
                assertEquals("k$i", c.getString(2))
                assertEquals(listOf((i * 7_919L) % 100_000, i * 10L, i * 1_000L), (3..5).map(c::getLong))
            }
            assertTrue(!c.moveToNext())
        }
        conn.query("SELECT seq FROM sqlite_sequence WHERE name = 'play_event'").use { it.moveToFirst(); assertEquals(PLAYS + 2L, it.getLong(0)) }
        conn.execSQL("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES ('device:2', 'k', 1, 1, 1)")
        conn.query("SELECT MAX(id) FROM play_event").use { it.moveToFirst(); assertEquals(PLAYS + 3L, it.getLong(0)) }
        // Every find, in the order written, with what it knew.
        conn.query("SELECT id, title, durationMs, seenAt, requestedAt, adoptedId FROM online_song ORDER BY rowid").use { c ->
            for (i in FINDS downTo 1) {
                assertTrue(c.moveToNext())
                assertEquals("find:$i", c.getString(0))
                assertEquals("F$i", c.getString(1))
                assertEquals(listOf(i * 1_000L, i.toLong(), if (i % 3 == 0) i.toLong() else 0L), (2..4).map(c::getLong))
                assertEquals(adoptedOf(i), c.getString(5))
            }
            assertTrue(!c.moveToNext())
        }
        // The open's one row of merge state: no swap yet, this launch's mark.
        conn.query("SELECT swapCount, launchMark FROM merge_state").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(0L, it.getLong(0))
            assertEquals(LaunchMark.current, it.getLong(1))
        }
        // The other readers' work tables are made, empty: a live list's
        // picks, Library health's report and a fix's undo, and a file's
        // songs matched against the library.
        listOf(
            "live_song", "live_state", "health_run", "health_song", "health_tag", "health_node", "health_member", "health_group",
            "health_quality", "health_part", "health_code", "health_tally", "health_split", "health_split_song", "fix_undo",
            "match_query", "match_title", "match_lookup",
        ).forEach { t -> conn.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); assertEquals(t, 0, it.getInt(0)) } }
        db.close()
    }

    // A file with no saved queue gets no list and no account for one.
    @Test
    fun noSavedQueueMakesNoAccount() = runBlocking {
        val dir = Files.createTempDirectory("m").toFile()
        version18(dir, rows = 20)
        SQLiteDatabase.openDatabase(File(dir, CATALOG_FILE).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM queue_item")
            it.execSQL("DELETE FROM queue_state")
        }
        val db = openTestCatalog(dir)
        val conn = db.openHelper.writableDatabase
        for (table in listOf("queue_entry", "queue_list", "queue_account")) {
            conn.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(table, 0, it.getInt(0)) }
        }
        conn.query("SELECT nextQueueId, nextListId FROM queue_meta").use { it.moveToFirst(); assertEquals(2L, it.getLong(0)) }
        db.close()
    }

    // The plays and the finds are copied too: a small library with a long
    // play history and many finds still has all the room the upgrade takes
    // asked for (UpgradeGuard), measured as MigrationSpaceTest measures.
    @Test
    fun playsAndFindsFitWhatTheUpgradeAsksFor() {
        val file = File(Files.createTempDirectory("m").toFile(), CATALOG_FILE)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA auto_vacuum = FULL")
            OldSqlite.schemaStatements(18).forEach(db::execSQL)
            db.beginTransaction()
            for (i in 0 until 200) {
                db.execSQL(
                    "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey) " +
                        "VALUES (?, 'server:h', ?, ?, ?, ?, 'An Artist', 'server:h:a', 'An Album', 'server:h:al', 200000, 1, ?)",
                    arrayOf<Any?>("server:h:$i", "$i", "Song $i", "song $i", "song $i", "an artist|song $i|200"),
                )
            }
            for (i in 0 until 60_000) {
                db.execSQL(
                    "INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, ?, ?, 200000, 200000)",
                    arrayOf<Any?>("server:h:${i % 200}", "an artist|song ${i % 200}|200", 1_700_000_000_000L + i * 1_000L),
                )
            }
            for (i in 0 until 20_000) {
                db.execSQL(
                    "INSERT INTO online_song (id, sourceId, nativeId, title, artist, album, albumId, artistId, durationMs, coverId, seenAt) " +
                        "VALUES (?, 'server:h', ?, ?, 'Someone Else', 'Another Album', ?, ?, 200000, ?, 1)",
                    arrayOf<Any?>("find:outside-$i", "outside-$i", "Found Song Number $i", "album-$i", "artist-$i", "cover-$i"),
                )
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            db.version = 18
        }
        val asked = (UpgradeGuard(file) { 0L }.apply { check() }.state.value as UpgradeState.NeedsSpace).bytes
        val (before, peak) = upgradePeak(file)
        println("MEASURED plays=60000 finds=20000 file=$before peak=$peak ratio=${peak * 100 / before}% asked=$asked")
        assertTrue("the upgrade took $peak bytes over a $before-byte file, asked $asked", peak <= asked)
    }

    // The file's size and the most the upgrade took on top of it, measured
    // as it ends, before its one transaction commits, with Android's
    // settings (auto-vacuum, secure delete) and a small page cache, so
    // everything it wrote is on disk in the file and its log.
    private fun upgradePeak(file: File): Pair<Long, Long> {
        fun onDisk(): Long = listOf("", "-wal", "-journal").sumOf { File(file.path + it).length() }
        val before = onDisk()
        var peak = 0L
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(testContext())
                .name(file.path)
                .callback(object : SupportSQLiteOpenHelper.Callback(CATALOG_VERSION) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.enableWriteAheadLogging()
                        db.query("PRAGMA wal_autocheckpoint = 0").close()
                        db.query("PRAGMA secure_delete = ON").close()
                        db.query("PRAGMA cache_size = -256").close()
                    }

                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_18_19.migrate(db)
                        peak = onDisk() - before
                    }
                })
                .build(),
        )
        helper.writableDatabase
        helper.close()
        return before to peak
    }

    // The migration's statements on SQLite 3.21: every index is created on
    // an empty table (album-sized source_album and source_artist apart),
    // every index the schema has is among them, no copy sorts or builds a
    // temporary table, and every statement it runs with values, and every
    // read, is planned with no sort and no list built in memory.
    @Test
    fun noIndexIsBuiltOverSongRows() {
        val conn = OldSqlite.open(18)
        conn.createStatement().use { st ->
            repeat(50) { i -> st.execute("INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, albumOrder, relinkKey, genre) VALUES ('s$i','d','$i','t','t','t','a','a','a','a',1,1,0,'k','')") }
            repeat(50) { i -> st.execute("INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt) VALUES ('s$i','d','$i','t','t','t','a','a','a','a',1,1)") }
            st.execute("INSERT INTO artist (id, sourceId, name, searchKey, sortKey, albumCount, songCount) VALUES ('a', 'd', 'a', 'a', 'a', 1, 1)")
            st.execute("INSERT INTO album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, songCount, durationMs, addedAt) VALUES ('a', 'd', 'a', 'a', 'a', 'a', 'a', 'a', 1, 1, 1)")
            repeat(50) { i -> st.execute("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('s$i', 'k', $i)") }
            repeat(50) { i -> st.execute("INSERT INTO track_rating (trackId, relinkKey, rating, ratedAt) VALUES ('s$i', 'k', 3, $i)") }
            repeat(1_200) { i ->
                st.execute("INSERT INTO download (trackId, sourceId, serverId, path, sizeBytes, state, progress, addedAt, reason, title, artist) VALUES ('s${i % 50}', 'd', '$i', '', 1, 'Done', 1.0, $i, 'manual', 'T$i', 'a')")
            }
            st.execute("INSERT INTO playlist (id, name, createdAt, updatedAt, family, syncedSongs) VALUES ('p', 'P', 1, 1, 1, '${BIG_BASE}')")
            repeat(50) { i -> st.execute("INSERT INTO playlist_item (playlistId, trackId, relinkKey, position) VALUES ('p', 's$i', 'k', $i)") }
            repeat(50) { i -> st.execute("INSERT INTO queue_item (position, trackId, shuffledPosition) VALUES ($i, 's$i', ${49 - i})") }
            st.execute("INSERT INTO queue_state (id, currentIndex, positionMs, repeatMode, shuffle, savedAt) VALUES (0, 3, 0, 0, 0, 1)")
            repeat(50) { i -> st.execute("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES ('s${i % 7}', 'k', ${(i * 31) % 50}, 1, 1)") }
            repeat(50) { i ->
                st.execute(
                    "INSERT INTO online_song (id, sourceId, nativeId, title, artist, album, durationMs, seenAt, adoptedId) " +
                        "VALUES ('find:$i', 'd', '$i', 'F', 'A', '', 1, 1, '${if (i % 2 == 0) "s$i" else ""}')",
                )
            }
        }
        val recorded = RecordingDatabase(conn).also { MIGRATION_18_19.migrate(it) }
        val allowed = setOf("source_album", "source_artist")
        recorded.indexRowsBefore.forEach { (sql, rows) ->
            val table = INDEX_TARGET.find(sql)!!.groupValues[2]
            if (table !in allowed) assertEquals("index on $table built over rows: $sql", 0L, rows)
        }
        // Every index of 19 is made by the upgrade, or was already there as it is.
        val created = recorded.indexRowsBefore.keys.map { INDEX_TARGET.find(it)!!.groupValues[1] }.toSet()
        val kept = OldSqlite.schemaStatements(18).filter { INDEX_TARGET.containsMatchIn(it) }.toSet()
        OldSqlite.schemaStatements(19).filter { INDEX_TARGET.containsMatchIn(it) }.forEach {
            assertTrue("not made by the upgrade: $it", INDEX_TARGET.find(it)!!.groupValues[1] in created || it in kept)
        }
        assertEquals(
            setOf(
                "_new_source_track", "_new_liked_track", "_new_track_rating", "_new_download", "_new_playlist_item",
                "_new_play_event", "_new_online_song",
            ),
            recorded.copyOpcodes.keys.mapNotNull { COPY_TARGET.find(it)?.groupValues?.get(1) }.filter { it.startsWith("_new_") }.toSet(),
        )
        // The songs are copied a page at a time by rowid, each row with its
        // lookup keys, into the new table made with its indexes.
        val trackPages = recorded.planned.keys.filter { "FROM `track` WHERE rowid > ?" in it }
        assertEquals(1, trackPages.size)
        assertTrue(OldSqlite.plan(conn, trackPages.single(), listOf(0L)).any { "INTEGER PRIMARY KEY" in it })
        assertTrue(recorded.planned.keys.any { it.startsWith("INSERT INTO `_new_track`") && "`lookupKey`, `looseKey`" in it })
        // The rename triggers are the upgrade's last statements, every one of
        // them, so no table copied after them lost its own.
        val triggers = recorded.order.withIndex().filter { it.value.startsWith("CREATE TRIGGER") }
        assertEquals(SAVE_RENAME_TRIGGERS, triggers.map { it.value })
        assertEquals((recorded.order.size - triggers.size until recorded.order.size).toList(), triggers.map { it.index })
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM track WHERE lookupKey != '' AND looseKey != ''").use { it.next(); assertEquals(50, it.getInt(1)) }
            st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'").use { it.next(); assertEquals(SAVE_RENAME_TRIGGERS.size, it.getInt(1)) }
            st.executeQuery("SELECT COUNT(*) FROM play_event").use { it.next(); assertEquals(50, it.getInt(1)) }
            st.executeQuery("SELECT COUNT(*) FROM online_song WHERE adoptedId != ''").use { it.next(); assertEquals(25, it.getInt(1)) }
        }
        assertTrue("the saved queue is not copied", recorded.copyOpcodes.keys.any { "INTO `queue_entry`" in it })
        recorded.copyOpcodes.forEach { (sql, ops) ->
            assertTrue("sort in $sql", "SorterOpen" !in ops)
            assertTrue("temporary table in $sql", "OpenEphemeral" !in ops)
        }
        assertTrue("no title key was filled in", recorded.planned.keys.any { "`titleKey` = ?" in it })
        assertTrue("no base was read", recorded.planned.keys.any { "substr(`syncedSongs`" in it })
        recorded.planned.forEach { (sql, problems) -> assertEquals(sql, emptyList<String>(), problems) }
        // The download pages walk the rowid, never the whole table at once.
        recorded.planned.keys.filter { it.startsWith("SELECT rowid, `title` FROM `download`") }.forEach {
            assertTrue(OldSqlite.plan(conn, it, listOf(0L)).joinToString(), OldSqlite.plan(conn, it, listOf(0L)).any { line -> "INTEGER PRIMARY KEY" in line })
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM download WHERE length(titleKey) > 0").use { it.next(); assertEquals(1_200, it.getInt(1)) }
            st.executeQuery("SELECT COUNT(*), SUM(copies) FROM playlist_synced_song").use {
                it.next()
                assertEquals(syncedSongsOf(BIG_BASE)!!.toSet().size, it.getInt(1))
                assertEquals(syncedSongsOf(BIG_BASE)!!.size, it.getInt(2))
            }
            st.executeQuery("SELECT COUNT(*) FROM queue_entry").use { it.next(); assertEquals(50, it.getInt(1)) }
            st.executeQuery("SELECT COUNT(*) FROM playlist_item WHERE itemKey = id AND itemSet = 0 AND ord = (id - 1) * 1048576").use { it.next(); assertEquals(50, it.getInt(1)) }
        }
    }

    // A server's library as a copy files it, written into a version 18 file
    // row by row in its source's order, as the copies before version 19
    // left it: after the upgrade the first everyday copy, which adds one
    // song in the middle of the library, asks a copy again's room, writes
    // the new song's row and moves no place of any song, album or artist.
    @Test
    fun theFirstCopyAfterTheUpgradeWritesOnlyTheNewRows() = runBlocking {
        val source = "server:upgrade.test"
        val library = LibraryGen.server(21, GenShape(songs = 400, albums = 30, artists = 8, untitled = 0.0, noTrackNo = 0.0))
        // The rows as a copy makes them, in the source's order.
        val made = openTestCatalog()
        made.copyOf(source, library, MergeHooks())
        val dir = Files.createTempDirectory("m").toFile()
        val file = File(dir, CATALOG_FILE)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            OldSqlite.schemaStatements(18).forEach(old::execSQL)
            val identity = Json.parseToJsonElement(schemaFile(18).readText()).jsonObject["database"]!!.jsonObject["identityHash"]!!.jsonPrimitive.content
            old.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$identity')")
            val from = made.openHelper.writableDatabase
            for (table in listOf("source_track", "source_album", "source_artist", "track", "album", "artist")) {
                val columns = old.rawQuery("PRAGMA table_info(`$table`)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
                val order = if (table.startsWith("source_")) "seq" else "rowid"
                from.query("SELECT ${columns.joinToString { "`$it`" }} FROM `$table` ORDER BY $order").use { c ->
                    val insert = "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})"
                    while (c.moveToNext()) old.execSQL(insert, Array<Any?>(columns.size) { i -> valueOf(c, i) })
                }
            }
            old.version = 18
        }
        made.close()

        val db = openTestCatalog(dir)
        val conn = db.openHelper.writableDatabase
        for (table in listOf("source_track", "source_album", "source_artist")) {
            val places = conn.longs("SELECT seq FROM `$table` WHERE sourceId = '$source' ORDER BY seq")
            assertTrue("$table spaced: ${places.take(5)}", places.size > 5 && places.zipWithNext().all { (a, b) -> b - a == SEQ_SPAN })
        }
        val before = conn.strings("SELECT id || '=' || seq FROM source_track WHERE sourceId = '$source' ORDER BY seq")
        // One song added at the end of an album in the middle of the list.
        val album = library.albums[library.albums.size / 2]
        val inAlbum = library.songs.filter { it.albumId == album.id }
        assertTrue("the album holds songs", inAlbum.isNotEmpty())
        val added = inAlbum.last().copy(id = "added-1", title = "Zz Added", track = (inAlbum.mapNotNull { it.track }.maxOrNull() ?: 0) + 1)
        val songs = library.songs.toMutableList().apply { add(indexOf(inAlbum.last()) + 1, added) }
        val steps = Collections.synchronizedMap(LinkedHashMap<String, Long>())
        val asked = Collections.synchronizedList(ArrayList<SaveKind>())
        db.copyOf(
            source,
            Library(songs, library.albums, library.artists),
            object : MergeHooks() {
                override fun groupStep(turn: Long, step: String, rows: Long) {
                    steps.merge(step, rows, Long::plus)
                }

                override fun spaceAsked(kind: SaveKind, bytes: Long) {
                    asked += kind
                }
            },
        )
        // The upgraded file records no server it was merged for, which says
        // nothing: the copy of a source the library holds songs from asks a
        // copy again's room, never a switch's.
        assertEquals(listOf(SaveKind.Recopy), asked.toList())
        assertTrue("$steps", steps.isNotEmpty())
        assertEquals("no statement for places runs: $steps", emptyList<String>(), steps.keys.filter { it.startsWith("update seq of") })
        assertEquals("$steps", 1L, steps["insert source_track"])
        assertEquals("$steps", 0L, steps["update source_track"] ?: 0L)
        assertEquals("$steps", 0L, steps["delete source_track"] ?: 0L)
        val after = conn.strings("SELECT id || '=' || seq FROM source_track WHERE sourceId = '$source' AND id != '$source:added-1' ORDER BY seq")
        assertEquals("every song keeps its place", before, after)
        db.close()
    }

    // A whole copy of `library` into this library from a fake server, its
    // merge's steps told to `hooks`, the stage cleared after it.
    private suspend fun CatalogDatabase.copyOf(source: String, library: Library, hooks: MergeHooks) {
        val merge = testMerge(source, hooks)
        val fake = FakeSubsonic(library, Starred())
        try {
            StagedCopy(this, merge, FreeSpace { Long.MAX_VALUE }).copy("acc-1", source, fake.client())
        } finally {
            fake.server.close()
        }
        merge.awaitStageClear(source)
    }

    private fun valueOf(c: android.database.Cursor, i: Int): Any? = when (c.getType(i)) {
        android.database.Cursor.FIELD_TYPE_NULL -> null
        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
        else -> c.getString(i)
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.strings(sql: String): List<String> =
        query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.longs(sql: String): List<Long> =
        query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    private companion object {
        const val PLAYLIST_ROWS = 3_000
        const val PLAYS = 400
        const val FINDS = 30

        // A song's title: plain, with its artist in front, with a version,
        // with an accent, and empty, with no key at all.
        fun titleOf(i: Int): String = when (i % 5) {
            0 -> "T$i"
            1 -> "The Artist - Song $i"
            2 -> "Song $i (Live) [2011 Remaster]"
            3 -> "Été $i"
            else -> ""
        }

        // A play's song, and the library song a find became.
        fun playOf(i: Int): String = "device:${i % 40 * 2 + 2}"

        fun adoptedOf(i: Int): String = if (i % 3 == 0) "device:${i * 2}" else ""

        // A playlist row's place at version 18: the last ids first, and id 7
        // tied with id 10.
        fun placeOf(id: Int): Int = if (id == 7) PLAYLIST_ROWS - 10 else PLAYLIST_ROWS - id

        // Titles a sort key changes: an article, a capital, an accent, and
        // characters past the first plane.
        val DOWNLOAD_TITLES = listOf("The Song", "a Song", "Été", "Zebra", "😀 Smile", "An Échange", "Song", "Ünïcode")

        // A base longer than two of the upgrade's 262,144-character windows,
        // with songs repeated, empty lines and lines cut by the windows' ends.
        val BIG_BASE: String = buildString {
            var i = 0
            while (length < 262_144 * 2 + 1_000) {
                append("server-song-").append(i % 9_000).append('\n')
                if (i % 5_000 == 0) append('\n')
                i++
            }
            append("last-song")
        }
    }
}

private val INDEX_TARGET = Regex("""CREATE (?:UNIQUE )?INDEX IF NOT EXISTS `([^`]+)` ON `([^`]+)`""")
private val COPY_TARGET = Regex("""INSERT INTO `([^`]+)`""")

// Runs a migration's statements on a JDBC connection and notes, before
// each runs, what an index is built over, how a copy runs, and how each
// statement run with values, and each read, is planned.
private class RecordingDatabase(private val conn: java.sql.Connection) : androidx.sqlite.db.SupportSQLiteDatabase by unsupported() {
    val indexRowsBefore = LinkedHashMap<String, Long>()
    val copyOpcodes = LinkedHashMap<String, List<String>>()
    val planned = LinkedHashMap<String, List<String>>()

    // Every statement run, writes and reads, in order.
    val order = ArrayList<String>()

    override fun execSQL(sql: String) {
        order += sql
        INDEX_TARGET.find(sql)?.let { m ->
            conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM `${m.groupValues[2]}`").use { rs -> rs.next(); indexRowsBefore[sql] = rs.getLong(1) } }
        }
        if (sql.startsWith("INSERT INTO `")) copyOpcodes[sql] = OldSqlite.opcodes(conn, sql)
        conn.createStatement().use { it.execute(sql) }
    }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        order += sql
        planned.putIfAbsent(sql, OldSqlite.planProblems(conn, sql, bindArgs.toList()))
        conn.prepareStatement(sql).use { st ->
            bindArgs.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.execute()
        }
    }

    override fun query(query: String): android.database.Cursor = query(query, emptyArray())

    override fun query(query: String, bindArgs: Array<out Any?>): android.database.Cursor {
        order += query
        planned.putIfAbsent(query, OldSqlite.planProblems(conn, query, bindArgs.toList()))
        conn.prepareStatement(query).use { st ->
            bindArgs.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                val cursor = MatrixCursor(columns.toTypedArray())
                while (rs.next()) cursor.addRow((1..columns.size).map { rs.getObject(it) })
                return cursor
            }
        }
    }
}

// Every other database call fails the test.
private fun unsupported(): androidx.sqlite.db.SupportSQLiteDatabase =
    java.lang.reflect.Proxy.newProxyInstance(
        androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as androidx.sqlite.db.SupportSQLiteDatabase
