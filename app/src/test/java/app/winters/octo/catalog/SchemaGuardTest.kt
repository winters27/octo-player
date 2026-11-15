package app.winters.octo.catalog

import android.app.ActivityManager
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.schemaFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class SchemaGuardTest {
    private fun columns(db: CatalogDatabase, table: String): List<String> =
        db.openHelper.writableDatabase.query("PRAGMA table_info(`$table`)").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(1) + " " + c.getString(2) + " " + c.getInt(3) else null }.toList()
        }

    // A trigger's statement as SQLite keeps it, spaces and IF NOT EXISTS
    // aside.
    private fun normalized(sql: String): String = sql.replace("IF NOT EXISTS ", "").replace(Regex("""\s+"""), " ").trim()

    private fun triggers(db: CatalogDatabase): List<String> =
        db.openHelper.writableDatabase.query("SELECT sql FROM sqlite_master WHERE type = 'trigger'").use { c ->
            buildList { while (c.moveToNext()) add(normalized(c.getString(0))) }
        }.sorted()

    // An empty version 18 library file, made from Room's own schema for 18,
    // in a folder of its own.
    private fun version18(): File {
        val dir = Files.createTempDirectory("g").toFile()
        SQLiteDatabase.openOrCreateDatabase(File(dir, CATALOG_FILE), null).use { db ->
            OldSqlite.schemaStatements(18).forEach(db::execSQL)
            val identity = Json.parseToJsonElement(schemaFile(18).readText()).jsonObject["database"]!!.jsonObject["identityHash"]!!.jsonPrimitive.content
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$identity')")
            db.version = 18
        }
        return dir
    }

    @Test
    fun writeAheadLoggingEvenOnLowMemoryPhones() = runBlocking {
        val am = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ActivityManager::class.java)
        shadowOf(am).setIsLowRamDevice(true)
        val db = openTestCatalog()
        db.openHelper.writableDatabase.query("PRAGMA journal_mode").use { it.moveToFirst(); assertEquals("wal", it.getString(0)) }
        db.close()
    }

    @Test
    fun stageTablesHaveTheSourceColumns() = runBlocking {
        val db = openTestCatalog()
        assertEquals(columns(db, "source_track"), columns(db, "stage_track"))
        assertEquals(columns(db, "source_album"), columns(db, "stage_album"))
        assertEquals(columns(db, "source_artist"), columns(db, "stage_artist"))
        assertEquals(columns(db, "album"), columns(db, "album_next"))
        assertEquals(columns(db, "artist"), columns(db, "artist_next"))
        // Every library song column is a candidate column too.
        val candidate = columns(db, "merge_candidate").toSet()
        columns(db, "track").forEach { assertTrue("merge_candidate lacks $it", it in candidate) }
        db.close()
    }

    // An owner's release deletes from one list of tables: every work table
    // with an owner column is on it, and nothing else, so a new copy kind
    // can never leave its rows behind.
    @Test
    fun everyOwnedWorkTableIsReleased() = runBlocking {
        val db = openTestCatalog()
        val owned = WORKING_TABLES.filter { t -> columns(db, t).any { it.startsWith("owner ") } }
        db.close()
        assertTrue("found no owned work table", owned.size > 10)
        assertEquals(owned.sorted(), OWNED_WORK_TABLES.sorted())
    }

    // The open deletes no row: it writes this launch's mark and resets the
    // flags of work the app's end cut off. A copy waiting is over: its stage
    // is none and marked clearing (its rows go by clearStage), and nothing
    // of it counts as placed.
    @Test
    fun openWritesFlagsOnlyAndKeepsEveryRow() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("g").toFile()
        openTestCatalog(dir).apply {
            val w = openHelper.writableDatabase
            w.execSQL("INSERT INTO take_in_log VALUES (1, 'track', 'x')")
            w.execSQL("INSERT INTO rating_note VALUES ('x', 3)")
            w.execSQL("INSERT INTO sync_song (songId, albumKey, json) VALUES ('s', 'a', '{}')")
            w.execSQL("INSERT INTO source_meta VALUES ('server:h', 'acct', 'acct', 5, '$STAGE_READY', 7, 1, 1, 1, 0, 3, 4, 5)")
            w.execSQL("UPDATE merge_state SET skipKey = 'key', claimActive = 1, server = 'server:h' WHERE id = 0")
            w.execSQL("INSERT INTO rating_tap VALUES ('x', 4)")
            close()
        }
        LaunchMark.next()
        val db = openTestCatalog(dir)
        val w = db.openHelper.writableDatabase
        for (t in listOf("take_in_log", "rating_note", "sync_song")) w.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); assertEquals("$t is emptied on open", 1, it.getInt(0)) }
        w.query("SELECT stage, generation, stamp, stageClearing, movedTracks, movedAlbums, movedArtists FROM source_meta").use {
            it.moveToFirst()
            assertEquals(STAGE_NONE, it.getString(0))
            assertEquals(0L, it.getLong(1))
            // The stamp and the skip key are kept: they are not working rows.
            assertEquals(5L, it.getLong(2))
            // The stage's rows go by its clear, which the mark asks for, and
            // none of its rows is placed anywhere.
            assertEquals(1, it.getInt(3))
            assertEquals(listOf(0, 0, 0), listOf(it.getInt(4), it.getInt(5), it.getInt(6)))
        }
        w.query("SELECT skipKey, claimActive, server, launchMark FROM merge_state").use {
            it.moveToFirst()
            assertEquals("key", it.getString(0))
            assertEquals(0, it.getInt(1))
            // Which server the library holds is kept too.
            assertEquals("server:h", it.getString(2))
            assertEquals(LaunchMark.current, it.getLong(3))
        }
        // A tap the server has yet to confirm outlives a restart.
        w.query("SELECT rating FROM rating_tap WHERE sourceRowId = 'x'").use {
            assertTrue(it.moveToFirst())
            assertEquals(4, it.getInt(0))
        }
        db.close()
    }

    // While a save is part applied (its record stands), the open keeps the
    // copies it claimed with their counts and places, the claim, the take-in
    // log and the rating notes; another copy waiting is over as ever.
    @Test
    fun openKeepsAPartAppliedSavesClaim() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("g").toFile()
        openTestCatalog(dir).apply {
            val w = openHelper.writableDatabase
            w.execSQL("INSERT INTO take_in_log VALUES (7, 'track', 'x')")
            w.execSQL("INSERT INTO rating_note VALUES ('x', 3)")
            w.execSQL("INSERT INTO source_meta VALUES ('server:h', NULL, 'acct', 5, '$STAGE_CLAIMED', 7, 10, 2, 1, 0, 3, 4, 5)")
            w.execSQL("INSERT INTO source_meta VALUES ('device', NULL, NULL, 2, '$STAGE_BUILDING', 8, 4, 1, 1, 0, 1, 1, 1)")
            w.execSQL("UPDATE merge_state SET claimActive = 1, applyClaim = ?, applyAccount = 'acct' WHERE id = 0", arrayOf<Any?>(SaveRecord(server = "server:h").text()))
            close()
        }
        val db = openTestCatalog(dir)
        val w = db.openHelper.writableDatabase
        w.query("SELECT sourceId, stage, generation, stagedTracks, stageClearing, movedTracks FROM source_meta ORDER BY sourceId").use {
            it.moveToFirst()
            assertEquals(listOf("device", STAGE_NONE, "0", "0", "1", "0"), (0..5).map(it::getString))
            it.moveToNext()
            assertEquals(listOf("server:h", STAGE_CLAIMED, "7", "10", "0", "3"), (0..5).map(it::getString))
        }
        assertEquals(1L, db.longOf("SELECT claimActive FROM merge_state"))
        assertEquals("acct", db.stringOf("SELECT applyAccount FROM merge_state"))
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM take_in_log"))
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM rating_note"))
        db.close()
    }

    // Every work table, the save's own among them, has its clearer, and
    // nothing else does: a work table added later fails here until it has
    // one.
    @Test
    fun everyWorkTableHasAClearer() {
        assertEquals((WORKING_TABLES + SAVE_WORK_TABLES).sorted(), WORK_TABLE_CLEARERS.keys.sorted())
        assertTrue("the sweep reaches no job's table", SWEPT_WORK_TABLES.none { it in JOB_OWNED_TABLES })
        assertTrue(JOB_OWNED_TABLES.all { WORK_TABLE_CLEARERS[it] != WorkClearer.Owners })
    }

    // The triggers that move a row written with a renamed id are the list's,
    // word for word, in a new library and in one upgraded from version 18,
    // where they come after every table the upgrade copies (a copy after
    // them would have dropped its table's).
    @Test
    fun theRenameTriggersAreTheListsInANewLibraryAndAnUpgradedOne() = runBlocking {
        val expected = SAVE_RENAME_TRIGGERS.map(::normalized).sorted()
        assertEquals(SAVE_RENAME_TARGETS.size * 2, expected.size)
        val fresh = openTestCatalog()
        assertEquals("a new library", expected, triggers(fresh))
        fresh.close()
        val upgraded = openTestCatalog(version18())
        upgraded.openHelper.writableDatabase.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(CATALOG_VERSION, it.getInt(0)) }
        assertEquals("an upgraded library", expected, triggers(upgraded))
        upgraded.close()
    }

    // Every column of every table has its class (SCHEMA_COLUMNS), and none is
    // classed that the schema lacks: a column added later fails here until
    // it is classed. The columns a trigger follows are exactly the trigger
    // list's.
    @Test
    fun everyColumnOfEveryTableIsClassed() = runBlocking {
        val db = openTestCatalog()
        val w = db.openHelper.writableDatabase
        val tables = w.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')",
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val found = tables.associateWith { t -> columns(db, t).map { it.substringBefore(' ') }.toSet() }
        db.close()
        assertTrue("found ${found.size} tables", found.size > 90)
        assertEquals("tables with no classes, or classes with no table", found.keys.sorted(), SCHEMA_COLUMNS.keys.sorted())
        val unclassed = found.flatMap { (t, cols) -> (cols - SCHEMA_COLUMNS[t].orEmpty().keys).map { "$t.$it" } }
        assertEquals("columns with no class", emptyList<String>(), unclassed)
        val stale = SCHEMA_COLUMNS.flatMap { (t, cols) -> (cols.keys - found[t].orEmpty()).map { "$t.$it" } }
        assertEquals("classed columns the schema lacks", emptyList<String>(), stale)
        val followed = SCHEMA_COLUMNS.flatMap { (t, cols) -> cols.filterValues { it == IdClass.Trigger }.keys.map { "$t.$it" } }
        assertEquals(SAVE_RENAME_TARGETS.map { "${it.table}.${it.column}" }.sorted(), followed.sorted())
    }

    // A song's lookup keys have no default, in the library and in the
    // merge's candidates, so a write that leaves them out fails rather than
    // filing the song under no key.
    @Test
    fun theLookupKeysHaveNoDefault() = runBlocking {
        val db = openTestCatalog()
        val w = db.openHelper.writableDatabase
        for (table in listOf("track", "merge_candidate")) {
            w.query("PRAGMA table_info(`$table`)").use { c ->
                var seen = 0
                while (c.moveToNext()) {
                    if (c.getString(1) !in setOf("lookupKey", "looseKey")) continue
                    seen++
                    assertEquals("$table.${c.getString(1)} not null", 1, c.getInt(3))
                    assertTrue("$table.${c.getString(1)} has no default", c.isNull(4))
                }
                assertEquals(table, 2, seen)
            }
        }
        val refused = runCatching {
            w.execSQL(
                "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt) " +
                    "VALUES ('t', 'd', 't', 'T', 't', 't', 'A', 'a', 'B', 'b', 1, 1)",
            )
        }
        assertTrue("a song written without its keys", refused.isFailure)
        db.close()
    }

    // A song made in code files under its own title's lookup keys unless it
    // is given others, and so does one read back from JSON written before
    // the keys were kept (Home keeps the chart songs it showed that way).
    @Test
    fun aSongMadeInCodeFilesUnderItsOwnKeys() {
        val song = TrackEntity(
            id = "t", sourceId = "d", nativeId = "t", title = "The Band - Song (Live)", searchKey = "song", sortKey = "song",
            artist = "The Band", artistId = "a", album = "B", albumId = "b", trackNo = null, discNo = null, year = null,
            durationMs = 1, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
        )
        val keys = titleLookup(song.title, song.artist)
        assertTrue(keys.lookupKey.isNotEmpty())
        assertEquals(keys.lookupKey, song.lookupKey)
        assertEquals(keys.looseKey, song.looseKey)
        assertEquals("given keys stay", "x", song.copy(lookupKey = "x").lookupKey)
        val written = Json.encodeToJsonElement(TrackEntity.serializer(), song.copy(lookupKey = "x", looseKey = "y")).jsonObject
        assertTrue("lookupKey" in written)
        val older = kotlinx.serialization.json.JsonObject(written.filterKeys { it != "lookupKey" && it != "looseKey" })
        val read = Json.decodeFromJsonElement(TrackEntity.serializer(), older)
        assertEquals(song.lookupKey, read.lookupKey)
        assertEquals(song.looseKey, read.looseKey)
    }

    // Each trigger's table keeps the primary key save_ref keeps its rows by
    // (its text part, then its number parts, and a pin's kind), and keeps a
    // relink key exactly where a rename by key may reach it.
    @Test
    fun eachRenameTargetKeepsItsTablesKey() = runBlocking {
        val db = openTestCatalog()
        val w = db.openHelper.writableDatabase
        for (t in SAVE_RENAME_TARGETS) {
            val info = w.query("PRAGMA table_info(`${t.table}`)").use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getString(1), c.getString(2), c.getInt(5))) }
            }
            val key = info.filter { it.third > 0 }.sortedBy { it.third }.map { it.first }
            val kept = listOfNotNull(t.keyText) + t.keyNums + (if (t.kind == null) listOf("kind") else emptyList())
            assertEquals(t.table, key.sorted(), kept.sorted())
            val types = info.associate { it.first to it.second }
            t.keyText?.let { assertEquals("${t.table}.$it", "TEXT", types[it]) }
            t.keyNums.forEach { assertEquals("${t.table}.$it", "INTEGER", types[it]) }
            assertTrue(t.keyNums.size <= 2)
            assertEquals("${t.table} keeps a relink key", "relinkKey" in types, t.keyed)
            assertTrue("${t.table} follows renames by follow alone or by key, not both", !(t.keyed && t.followOnly))
            assertEquals("${t.table}.${t.column}", "TEXT", types[t.column])
        }
        db.close()
    }

    // A new library has the one row of playlist row keys and the one row of
    // queue ids, each starting at 1.
    @Test
    fun aNewLibraryHasItsCounters() = runBlocking {
        val db = openTestCatalog()
        val w = db.openHelper.writableDatabase
        w.query("SELECT id, nextItemKey FROM playlist_meta").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
            assertEquals(1L, it.getLong(1))
        }
        w.query("SELECT id, nextQueueId, nextListId FROM queue_meta").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(listOf(0L, 1L, 1L), (0..2).map(it::getLong))
        }
        db.close()
    }

    // The play totals, a family playlist's base, the playlist row keys, what
    // the last phone scan saw and the whole queue, edits waiting on a list
    // included, outlive a restart; so do the work rows beside them, which
    // their own clearers take.
    @Test
    fun keptRowsOutliveAnOpen() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("g").toFile()
        val kept = listOf("play_total", "playlist_synced_song", "playlist_meta", "phone_scan_state", "queue_entry", "queue_list", "queue_pending", "queue_account", "queue_meta")
        // The owners in the form the app writes them, after this launch's mark.
        val vm = WorkOwners.marked("vm:1")
        val live = WorkOwners.marked("live:l")
        val report = WorkOwners.REPORT
        val fix = WorkOwners.marked("fix:1")
        val match = WorkOwners.marked("match:1")
        openTestCatalog(dir).apply {
            val w = openHelper.writableDatabase
            w.execSQL("INSERT INTO play_total VALUES ('t', 'al', 'ar', 3, 10, NULL, 1, 5, 's', 'a')")
            w.execSQL("INSERT INTO playlist (id, name, createdAt, updatedAt) VALUES ('p', 'P', 1, 1)")
            w.execSQL("INSERT INTO playlist_synced_song (playlistId, songId, copies, baseSet) VALUES ('p', 's', 2, 0)")
            w.execSQL("UPDATE playlist_meta SET nextItemKey = 7")
            w.execSQL("INSERT INTO phone_scan_state (id, digest) VALUES (0, 'd')")
            w.execSQL("INSERT INTO queue_entry VALUES (1, 1, 't', 1, 1, 1, 1, NULL)")
            w.execSQL("INSERT INTO queue_list (listId, queueId, autoplay, seed, fillState) VALUES (1, 1, 0, 0, 1)")
            w.execSQL("INSERT INTO queue_pending VALUES (1, 1, 'remove', '{}')")
            w.execSQL("INSERT INTO queue_account (account, queueId, positionMs, repeatMode, shuffle, savedAt) VALUES ('', 1, 0, 0, 0, 1)")
            w.execSQL("UPDATE queue_meta SET nextQueueId = 2, nextListId = 2")
            w.execSQL("INSERT INTO play_total_diff (trackId, op) VALUES ('t', 0)")
            w.execSQL("INSERT INTO playlist_set_pin VALUES ('$vm', 'p', 0)")
            w.execSQL("INSERT INTO playlist_edit_log (playlistId, itemSet, op, itemKey, ord, trackId, relinkKey) VALUES ('p', 0, 1, 1, 0, 't', '')")
            w.execSQL("INSERT INTO scan_genre_name VALUES ('rock', 'Rock')")
            w.execSQL("INSERT INTO live_song VALUES ('$live', 1, x'01', 't', 1000)")
            w.execSQL("INSERT INTO live_state VALUES ('$live', 'l', 1, 'q', 1, 1000, 1, 0)")
            w.execSQL("INSERT INTO health_run VALUES (0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)")
            w.execSQL("INSERT INTO health_song VALUES ('$report', 1, 't', 1, x'00', x'00', 'al', 0, 1, x'00')")
            w.execSQL("INSERT INTO health_tag VALUES (1, 'k', 1, 100)")
            w.execSQL("INSERT INTO health_node VALUES (1, 1, 1, 2, 0)")
            w.execSQL("INSERT INTO health_member VALUES ('$report', 1, 1, 0, 0, 'c', 't')")
            w.execSQL("INSERT INTO health_group VALUES ('$report', 1, x'00', x'00', 'c', 1, 2, 'Tags', 'None', 2)")
            w.execSQL("INSERT INTO health_quality VALUES (1, 't', 'FLAC')")
            w.execSQL("INSERT INTO health_part VALUES (1, 'al', 'b', 'k', 't', 1, 'A', 'a', '', '')")
            w.execSQL("INSERT INTO health_code VALUES (1, 'al', 0, 'r')")
            w.execSQL("INSERT INTO health_tally VALUES (1, 'al', 'A', 1)")
            w.execSQL("INSERT INTO health_split VALUES ('$report', 1, x'00', x'00', 'al', 1, 'T', 'A', '', '', 0)")
            w.execSQL("INSERT INTO health_split_song VALUES ('$report', 1, 1, 0, 0, 1, x'00', 't')")
            w.execSQL("INSERT INTO fix_undo VALUES ('$fix', 1, 'tags', 't', 'T', '{}')")
            w.execSQL("INSERT INTO match_query (owner, id, kind, list, value, relinkKey, title, artist, album, durationMs, wholeKey, nameKey, parts) VALUES ('$match', 1, 0, 0, 0, '', 'T', 'A', 'B', 1, 'w', '', '')")
            w.execSQL("INSERT INTO match_title (owner, query, rank, title, artist, lengthMs, versions, hasArtist) VALUES ('$match', 1, 1, 'T', 'A', 0, '', 1)")
            w.execSQL("INSERT INTO match_lookup VALUES ('$match', 'k', 5, 0)")
            close()
        }
        val db = openTestCatalog(dir)
        val w = db.openHelper.writableDatabase
        kept.forEach { t -> w.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); assertEquals(t, 1, it.getInt(0)) } }
        w.query("SELECT nextItemKey FROM playlist_meta").use { it.moveToFirst(); assertEquals(7L, it.getLong(0)) }
        w.query("SELECT nextQueueId, nextListId FROM queue_meta").use { it.moveToFirst(); assertEquals(2L, it.getLong(0)); assertEquals(2L, it.getLong(1)) }
        kept.forEach { assertTrue("$it is emptied on open", it !in WORKING_TABLES) }
        val readers = listOf(
            "live_song", "live_state", "health_run", "health_song", "health_tag", "health_node", "health_member", "health_group",
            "health_quality", "health_part", "health_code", "health_tally", "health_split", "health_split_song", "fix_undo",
            "match_query", "match_title", "match_lookup",
        )
        // The work rows beside them stay too, for their own clearers.
        (listOf("play_total_diff", "playlist_set_pin", "playlist_edit_log", "scan_genre_name") + readers).forEach { t ->
            w.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); assertEquals(t, 1, it.getInt(0)) }
            assertTrue("$t has no clearer", t in WORK_TABLE_CLEARERS)
        }
        db.close()
    }
}

// What a column holds, as far as a library id goes:
// - Trigger: a library id a rename trigger follows (SAVE_RENAME_TARGETS);
// - Save: a library or source row's own id, or the copy's, merge's or
//   save's work, which only they write;
// - Owner: a library id the work that wrote it follows itself, reading it
//   again once the library changed: a download (by its rules' opening), a
//   list's, a queue fill's, a report's or a match's rows (by the swap count
//   they date from), and the play totals (by their rebuild);
// - None: no library id (a server's ids, a source row's id, a count, a key).
internal enum class IdClass { Trigger, Save, Owner, None }

// Every table's columns, each with its class.
internal val SCHEMA_COLUMNS: Map<String, Map<String, IdClass>> = run {
    fun cols(trigger: String = "", save: String = "", owner: String = "", none: String = ""): Map<String, IdClass> {
        val all = listOf(IdClass.Trigger to trigger, IdClass.Save to save, IdClass.Owner to owner, IdClass.None to none)
            .flatMap { (cls, names) -> names.split(' ').filter { it.isNotEmpty() }.map { it to cls } }
        check(all.map { it.first }.toSet().size == all.size) { "a column classed twice: $all" }
        return all.toMap()
    }

    val trackRest = "sourceId nativeId title searchKey sortKey artist album trackNo discNo year durationMs addedAt mimeType sizeBytes " +
        "artwork uri albumOrder relinkKey genre onPhone rating explicit artistSortKey albumSortKey lookupKey looseKey"
    val albumRest = "sourceId nativeId title searchKey sortKey artist year songCount durationMs addedAt artwork releaseTypes"
    val artistRest = "sourceId name searchKey sortKey albumCount songCount artwork"
    val sourceTrackRest = "sourceId nativeId title searchKey sortKey artist album trackNo discNo year durationMs addedAt mimeType " +
        "sizeBytes artwork uri albumOrder relinkKey genre bitrate sampleRate bitDepth playCount lastPlayedAt starredAt trackGain " +
        "albumGain trackPeak albumPeak baseGain fallbackGain rating originalYear genres artists composer bpm comment explicit " +
        "discTitle mbRecordingId mbAlbumId mbReleaseGroupId mbArtistIds personal seq titleKey matchKey keysVersion folder fileName storageVolume"
    // A server listing's song as a sync read and a server list hold it,
    // with the server's own ids, beside the library song it resolved to.
    val listed = "position songId external title artist lengthMs album albumId artistId coverId mimeType bitrate explicit kind groupId firstFind held relinkKey"

    mapOf(
        // the library, its sources, a copy's stage and the merge's output
        "track" to cols(save = "id artistId albumId", none = trackRest),
        "album" to cols(save = "id artistId", none = albumRest),
        "artist" to cols(save = "id", none = artistRest),
        "source_track" to cols(save = "id artistId albumId mergedId", none = sourceTrackRest),
        "source_album" to cols(save = "id artistId", none = "$albumRest seq"),
        "source_artist" to cols(save = "id", none = "$artistRest seq"),
        "stage_track" to cols(save = "id artistId albumId mergedId", none = sourceTrackRest),
        "stage_album" to cols(save = "id artistId", none = "$albumRest seq"),
        "stage_artist" to cols(save = "id", none = "$artistRest seq"),
        "merge_candidate" to cols(
            save = "id artistId albumId",
            none = "$trackRest seq titleKey matchKey originalYear credits discKey lastSourceRank createdRank mixed",
        ),
        "merged_id_next" to cols(save = "sourceRowId mergedId"),
        "album_next" to cols(save = "id artistId", none = albumRest),
        "artist_next" to cols(save = "id", none = artistRest),
        "take_in_log" to cols(none = "generation kind id"),
        "rating_note" to cols(none = "sourceRowId rating"),
        "rating_tap" to cols(none = "sourceRowId rating"),
        "source_meta" to cols(
            none = "sourceId copyOf stageOwner stamp stage generation stagedTracks stagedAlbums stagedArtists stageClearing " +
                "movedTracks movedAlbums movedArtists",
        ),
        "account_sync" to cols(none = "account sourceId syncedAt songs albums rowsVersion lastScan afterSyncDone"),
        "merge_state" to cols(
            none = "id skipKey claimActive server swapCount applyClaim applySkipKey applyAccount applyFirstCopy applyScan " +
                "applyStopped totalsAccount launchMark",
        ),
        "file_tags" to cols(
            none = "mediaId modifiedAt size readOk title artist albumArtist album trackNo discNo year compilation mbAlbumId genres " +
                "tagsVersion originalYear artists composer bpm comment explicit discTitle mbRecordingId mbReleaseGroupId mbArtistIds " +
                "sortTitle sortAlbum sortAlbumArtist trackGain albumGain trackPeak albumPeak",
        ),
        // what the listener made
        "liked_track" to cols(trigger = "trackId", none = "relinkKey likedAt"),
        "track_rating" to cols(trigger = "trackId", none = "relinkKey rating ratedAt"),
        "play_event" to cols(trigger = "trackId", none = "id relinkKey startedAt playedMs durationMs"),
        "liked_album" to cols(trigger = "albumId", none = "relinkKey likedAt"),
        "liked_artist" to cols(trigger = "artistId", none = "relinkKey likedAt"),
        "pinned_item" to cols(trigger = "itemId", none = "kind relinkKey position"),
        "playlist" to cols(
            none = "id name createdAt updatedAt serverId sourceId syncedAt syncedName serverStamp octoNotice family syncedSongs " +
                "familyMayAdd familyMayRemove familyMayRename familyMayDelete itemSet baseSet syncedSongsKept upkeep octoImport upkeepKind",
        ),
        // playlist folders, where playlists sit, the folder changes waiting to go, and the base step's marks
        "playlist_folder" to cols(none = "space id name parent created"),
        "playlist_place" to cols(none = "space playlistKey folderId localOnly"),
        "folder_op" to cols(none = "seq space kind folderId name parent playlistKeys at"),
        "playlist_base_mark" to cols(none = "sourceId at"),
        "playlist_item" to cols(trigger = "trackId", none = "id playlistId itemSet relinkKey ord itemKey serverSongId"),
        "playlist_synced_song" to cols(none = "playlistId songId copies baseSet"),
        "playlist_meta" to cols(none = "id nextItemKey"),
        "playlist_set_pin" to cols(none = "owner playlistId itemSet"),
        "playlist_edit_log" to cols(trigger = "trackId", none = "seq playlistId itemSet op itemKey ord relinkKey serverSongId"),
        "online_song" to cols(
            trigger = "adoptedId",
            none = "id sourceId nativeId title artist album albumId artistId durationMs coverId mimeType bitrate seenAt requestedAt explicit",
        ),
        "download" to cols(owner = "trackId", none = "sourceId serverId path sizeBytes format state progress addedAt reason title artist fileSize titleKey"),
        // a copy's pages of the server's library
        "sync_song" to cols(none = "seq songId albumKey json"),
        "sync_album" to cols(none = "id seq json place"),
        "sync_artist" to cols(none = "id seq json"),
        "sync_order" to cols(none = "owner discKey trackKey sortKeyHex seq songId"),
        "sync_starred" to cols(none = "generation kind id starredAt"),
        // listening
        "synced_star" to cols(none = "account serverId"),
        "synced_rating" to cols(none = "account serverId rating"),
        "pending_play" to cols(none = "account serverId startedAt"),
        "sent_play" to cols(none = "account serverId folded"),
        "sent_recent" to cols(none = "account serverId startedAt"),
        "listening_import" to cols(none = "id doneAt"),
        "play_total" to cols(owner = "trackId albumId artistId", none = "plays lastPlayedAt recentAt phonePlays phoneLastAt sortKey artistSortKey"),
        "play_total_diff" to cols(owner = "trackId albumId artistId", none = "op plays lastPlayedAt recentAt phonePlays phoneLastAt sortKey artistSortKey"),
        // a playlist sync step's rows
        "playlist_entry" to cols(trigger = "trackId", none = "read $listed"),
        "playlist_key" to cols(none = "read key"),
        "playlist_match" to cols(trigger = "trackId", none = "id read title artist lengthMs relinkKey"),
        "playlist_lookup" to cols(none = "read lookupKey groupId lengthMs"),
        "playlist_local" to cols(trigger = "trackId", none = "itemPosition itemKey serverSongId anchor rank relinkKey taken"),
        "playlist_next" to cols(trigger = "trackId", none = "position serverSongId relinkKey rank keptKey"),
        "playlist_send" to cols(none = "slot position songId rank added"),
        "playlist_change" to cols(none = "kind position songId copy done"),
        // a phone scan's rows, which become the phone's source rows
        "scan_file" to cols(
            none = "mediaId seq uri fileName folder volume modifiedAt sizeBytes durationMs addedAt mimeType fbTitle fbArtist " +
                "fbAlbumArtist fbAlbum fbTrackNo fbDiscNo fbYear fbGenres",
        ),
        "scan_row" to cols(none = "mediaId seq groupId track fileNo discKey numKey nameOrder"),
        "scan_cluster" to cols(none = "nameKey groupId firstSeq files mbFiles byFiles mbFirst byFirst mbVaries byVaries"),
        "scan_group" to cols(none = "id nameKey splitKey clusterSeq firstSeq"),
        "scan_tally" to cols(none = "owner value n first"),
        "scan_genre_name" to cols(none = "genreKey name"),
        "scan_artist" to cols(save = "artistId", none = "firstSeq albums songs newestAt artwork sortName"),
        "phone_scan_state" to cols(none = "id digest tracks albums artists"),
        // the queue: a list's cursor, anchor and waiting edits are read again
        // by its own build, which dates its rows by the swap count
        "queue_entry" to cols(trigger = "trackId", none = "queueId entry listId qk sk durationMs skBefore"),
        "queue_list" to cols(
            owner = "cursor anchor",
            none = "listId queueId source autoplay spec seed fillState phase filled0 filled1 phase0End expected swapCount attempts " +
                "qBase qStep sBase sStep copyOwner songsOnce",
        ),
        "queue_pending" to cols(owner = "body", none = "listId seq op"),
        "queue_account" to cols(none = "account queueId currentEntry positionMs repeatMode shuffle floorQ floorS healedAt savedAt"),
        "queue_meta" to cols(none = "id nextQueueId nextListId"),
        // a list screen's, a car read's or a queue fill's rows
        "lists_folder_order" to cols(owner = "trackId", none = "owner lead ties"),
        "lists_sort_copy" to cols(owner = "trackId", none = "owner segment lead leadKey liked tieKey sortKey titleKey sourceId serverId"),
        "lists_album_entry" to cols(trigger = "shownId", none = "owner position songId isFind albumOrder trackNo discNo"),
        "lists_server_dir" to cols(trigger = "trackId", none = "owner position kind id name coverArt songCount lead ties relinkKey"),
        "server_entry" to cols(trigger = "trackId", none = "owner $listed"),
        "server_key" to cols(none = "owner key"),
        "server_match" to cols(trigger = "trackId", none = "id owner title artist lengthMs relinkKey"),
        "server_lookup" to cols(none = "owner lookupKey groupId"),
        "server_head" to cols(
            none = "owner kind listId title artist artistId year songCount cover cut complete readSeq swapCount currentAt positionMs",
        ),
        "live_song" to cols(owner = "trackId", none = "owner gen orderKey durationMs"),
        "live_state" to cols(none = "owner listId gen queryHash count durationMs usedAt swapCount"),
        // Library health's report and a fix's undo
        "health_run" to cols(
            none = "id gen building duplicates splitAlbums noLength noTrackNumber noAlbumArtist noCover noYear noGenre checked swapCount",
        ),
        "health_song" to cols(owner = "trackId albumId", none = "owner gen mask k1 k2 disc trackKey k3"),
        "health_tag" to cols(none = "gen key firstNode firstSeconds"),
        "health_node" to cols(none = "gen node parent size tagged"),
        "health_member" to cols(owner = "copyId trackId", none = "owner gen root sound tags"),
        "health_group" to cols(owner = "bestId", none = "owner gen k1 k2 root size basis reason songs"),
        "health_quality" to cols(owner = "trackId", none = "gen text"),
        "health_part" to cols(owner = "albumId firstId", none = "gen bucket firstKey size artist artistKey albumArtists years"),
        "health_code" to cols(owner = "albumId", none = "gen kind code"),
        "health_tally" to cols(owner = "albumId", none = "gen value n"),
        "health_split" to cols(owner = "leadAlbum", none = "owner gen k1 k2 setId title artist reasons differences linesBefore"),
        "health_split_song" to cols(owner = "trackId", none = "owner gen setId part disc trackKey titleKey"),
        "fix_undo" to cols(owner = "id payload", none = "owner ord kind title"),
        // a backup's or a playlist file's songs matched against the library
        "match_query" to cols(
            owner = "relinkId wholeId titleId pathId foundId",
            none = "owner id kind list value relinkKey title artist album durationMs wholeKey nameKey parts wholeDist titleDist pathEnd pos skip",
        ),
        "match_title" to cols(owner = "p1Id p2Id soleId", none = "owner query rank title artist lengthMs versions hasArtist p1Rank p2Rank soleCount"),
        "match_lookup" to cols(none = "owner key target rank"),
        // a save's find, sets and groups, and the renames in force
        "save_found" to cols(save = "id oldId newId", none = "seq tbl op seenRating newRating cost setId groupId"),
        "save_ref" to cols(save = "keyText oldId target", none = "seq tbl kind keyNum keyNum2 relinkKey setId groupId"),
        "save_node" to cols(save = "id", none = "node kind parent size"),
        "save_set" to cols(none = "setId cost groupId"),
        "save_rename" to cols(save = "old new", none = "kind byKey key"),
    )
}
