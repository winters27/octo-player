package app.winters.octo.catalog

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

// How the library database is opened, the same in the app and in tests.
//
// Write-ahead logging always: Room would pick a rollback journal on phones
// Android calls low on memory, and there a long write (a merge's swap)
// would hold up every read.
fun RoomDatabase.Builder<CatalogDatabase>.catalogSetup(): RoomDatabase.Builder<CatalogDatabase> =
    setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(MIGRATION_18_19, MIGRATION_19_20)
        .addCallback(CatalogCallback)

// A new library starts with the one row each of the playlist row keys and
// the queue's ids, and with the triggers that move a row written with a
// renamed id (SAVE_RENAME_TRIGGERS), made once its tables are. An upgraded
// one gets them from the upgrade.
//
// Each open writes flags only, and deletes no row: this launch's mark
// (LaunchMark) into merge_state, and the stages and flags of work the app's
// end cut off. Nothing runs yet, so a copy being built or waiting is over:
// its source's stage is none and marked clearing, so its rows go a page at
// a time before the source's next copy stages anything (clearStage), and
// nothing of it counts as placed. While a save is part applied (its record
// in merge_state), the copies it claimed keep their stages, counts and
// flags, and the claim stays active, so the launch's repair resumes it.
// Every work table's rows go by its own clearer (WORK_TABLE_CLEARERS), a
// page at a time.
object CatalogCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO playlist_meta (id, nextItemKey) VALUES (0, 1)")
        db.execSQL("INSERT INTO queue_meta (id, nextQueueId, nextListId) VALUES (0, 1, 1)")
        SAVE_RENAME_TRIGGERS.forEach(db::execSQL)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL(ENSURE_MERGE_STATE_SQL)
            db.execSQL("UPDATE merge_state SET launchMark = ? WHERE id = 0", arrayOf<Any?>(LaunchMark.current))
            val applying = db.query("SELECT applyClaim IS NOT NULL FROM merge_state WHERE id = 0").use { it.moveToFirst() && it.getInt(0) == 1 }
            // The copies a part-applied save claimed are its own; every other
            // copy is over.
            val over = if (applying) "stage NOT IN ('$STAGE_NONE', '$STAGE_CLAIMED')" else "stage != '$STAGE_NONE'"
            db.execSQL(
                "UPDATE source_meta SET stage = '$STAGE_NONE', stageOwner = NULL, generation = 0, stagedTracks = 0, stagedAlbums = 0, " +
                    "stagedArtists = 0, stageClearing = 1, movedTracks = 0, movedAlbums = 0, movedArtists = 0 WHERE $over",
            )
            // No copy that is over is placed anywhere.
            db.execSQL(
                "UPDATE source_meta SET movedTracks = 0, movedAlbums = 0, movedArtists = 0 " +
                    "WHERE stage = '$STAGE_NONE' AND (movedTracks != 0 OR movedAlbums != 0 OR movedArtists != 0)",
            )
            if (!applying) db.execSQL("UPDATE merge_state SET claimActive = 0 WHERE claimActive != 0")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

// Every table whose rows last only as long as the work that wrote them: a
// copy or merge, a rebuild of the play totals, a playlist sync step, a phone
// scan, a walk of a playlist, a list screen, car read or queue fill, a live
// list's picks, Library health's report and a fix's undo, or a backup or
// playlist file matched against the library. The queue's own tables are not
// among them: a queue outlives the app. The library's open deletes none of
// them; each has its clearer in WORK_TABLE_CLEARERS.
val WORKING_TABLES = listOf(
    "stage_track", "stage_album", "stage_artist",
    "sync_song", "sync_album", "sync_artist", "sync_order", "sync_starred",
    "merge_candidate", "merged_id_next", "album_next", "artist_next",
    "take_in_log", "rating_note",
    "play_total_diff",
    "playlist_entry", "playlist_key", "playlist_match", "playlist_lookup",
    "playlist_local", "playlist_next", "playlist_send", "playlist_change",
    "playlist_set_pin", "playlist_edit_log",
    "scan_file", "scan_row", "scan_cluster", "scan_group", "scan_tally", "scan_genre_name", "scan_artist",
    "lists_folder_order", "lists_sort_copy", "lists_album_entry", "lists_server_dir",
    "server_entry", "server_key", "server_match", "server_lookup", "server_head",
    "live_song", "live_state",
    "health_run", "health_song", "health_tag", "health_node", "health_member", "health_group", "health_quality",
    "health_part", "health_code", "health_tally", "health_split", "health_split_song", "fix_undo",
    "match_query", "match_title", "match_lookup",
)

// Every work table keyed by an owner, so an owner's release leaves none of
// its rows behind. A playlist set pin goes through PlaylistSets.releasePin,
// which also lets the set's edit log and old sets go.
val OWNED_WORK_TABLES = listOf(
    "sync_order", "playlist_set_pin", "scan_tally",
    "lists_folder_order", "lists_sort_copy", "lists_album_entry", "lists_server_dir",
    "server_entry", "server_key", "server_match", "server_lookup", "server_head",
    "live_song", "live_state",
    "health_song", "health_member", "health_group", "health_split", "health_split_song", "fix_undo",
    "match_query", "match_title", "match_lookup",
)

const val PIN_TABLE = "playlist_set_pin"

// What takes a work table's rows, each a page at a time, since the
// library's open deletes none.
enum class WorkClearer {
    // The launch's sweep of older launches' owners (sweepOlderLaunches),
    // beside each owner's own release.
    Owners,

    // The launch's sweep of older launches' playlist pins, through
    // PlaylistSets.releasePin, which lets their edit log and old sets go.
    Pins,

    // A server copy's start: the server's pages, the giant albums' order,
    // the stars read.
    CopyStart,

    // A phone scan's start (PhoneScan.drainWork).
    ScanStart,

    // A source's stage, by its clearing mark (CatalogMerge.clearStage).
    Stage,

    // The merge's own work rows (CatalogMerge.clearWork), before the next
    // claim and whenever no save is part applied.
    MergeWork,

    // A rebuild of the play totals, at its opening.
    TotalsRebuild,

    // A playlist sync step, at its start (PlaylistSteps.withWork).
    PlaylistStep,

    // Library health's first build of a launch (the report's rows with no
    // owner; its owned rows go by the sweep).
    HealthBuild,
}

// Every work table, the save's own among them, with its clearer.
val WORK_TABLE_CLEARERS: Map<String, WorkClearer> = buildMap {
    for (t in listOf("stage_track", "stage_album", "stage_artist")) put(t, WorkClearer.Stage)
    for (t in listOf("sync_song", "sync_album", "sync_artist", "sync_order", "sync_starred")) put(t, WorkClearer.CopyStart)
    for (t in listOf("merge_candidate", "merged_id_next", "album_next", "artist_next", "take_in_log", "rating_note") + SAVE_WORK_TABLES) put(t, WorkClearer.MergeWork)
    put("play_total_diff", WorkClearer.TotalsRebuild)
    for (t in listOf("playlist_entry", "playlist_key", "playlist_match", "playlist_lookup", "playlist_local", "playlist_next", "playlist_send", "playlist_change")) {
        put(t, WorkClearer.PlaylistStep)
    }
    for (t in listOf("playlist_set_pin", "playlist_edit_log")) put(t, WorkClearer.Pins)
    for (t in listOf("scan_file", "scan_row", "scan_cluster", "scan_group", "scan_tally", "scan_genre_name", "scan_artist")) put(t, WorkClearer.ScanStart)
    for (t in listOf("health_run", "health_tag", "health_node", "health_quality", "health_part", "health_code", "health_tally")) put(t, WorkClearer.HealthBuild)
    for (t in OWNED_WORK_TABLES - keys) put(t, WorkClearer.Owners)
}
