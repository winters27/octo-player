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
        .addMigrations(MIGRATION_18_19)
        .addCallback(CatalogCallback)

// Empties the working rows on every open. Nothing is running yet, so a copy
// or merge they belonged to was cut off and starts again from the beginning.
object CatalogCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            WORKING_TABLES.forEach { db.execSQL("DELETE FROM `$it`") }
            db.execSQL(
                "UPDATE source_meta SET stage = '$STAGE_NONE', stageOwner = NULL, generation = 0, stagedTracks = 0, stagedAlbums = 0, " +
                    "stagedArtists = 0 WHERE stage != '$STAGE_NONE'",
            )
            db.execSQL("UPDATE merge_state SET claimActive = 0 WHERE claimActive != 0")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

// Every table whose rows last only as long as one copy or merge.
val WORKING_TABLES = listOf(
    "stage_track", "stage_album", "stage_artist",
    "sync_song", "sync_album", "sync_artist", "sync_order", "sync_starred",
    "merge_candidate", "merged_id_next", "album_next", "artist_next",
    "take_in_log", "rating_note",
)
