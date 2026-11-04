package app.winters.octo.catalog

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Version 19: each source row keeps its place in its source's order and
// its song keys, the library gets indexes that settle ties by id, and the
// tables for copying and merging without holding the library in memory.
//
// The two song-sized tables are copied into new ones that already have
// their indexes, in their old order, so every row's place (seq) is its old
// rowid and no index is built over a full table.
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // source_track
        db.execSQL("DROP INDEX IF EXISTS `index_source_track_sourceId`")
        db.execSQL("DROP INDEX IF EXISTS `index_source_track_mergedId`")
        db.execSQL("CREATE TABLE IF NOT EXISTS `_new_source_track` ($SOURCE_TRACK_COLUMNS, PRIMARY KEY(`id`))")
        sourceTrackIndices("_new_source_track").forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO `_new_source_track` ($SOURCE_TRACK_V18, `seq`, `titleKey`, `matchKey`, `keysVersion`) " +
                "SELECT $SOURCE_TRACK_V18, rowid, '', '', 0 FROM `source_track` ORDER BY rowid",
        )
        db.execSQL("DROP TABLE `source_track`")
        db.execSQL("ALTER TABLE `_new_source_track` RENAME TO `source_track`")

        // track
        listOf("albumId", "artistId", "sourceId", "sortKey", "searchKey", "relinkKey").forEach {
            db.execSQL("DROP INDEX IF EXISTS `index_track_$it`")
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS `_new_track` ($TRACK_COLUMNS, PRIMARY KEY(`id`))")
        trackIndices("_new_track").forEach(db::execSQL)
        db.execSQL("INSERT INTO `_new_track` ($TRACK_V18) SELECT $TRACK_V18 FROM `track` ORDER BY rowid")
        db.execSQL("DROP TABLE `track`")
        db.execSQL("ALTER TABLE `_new_track` RENAME TO `track`")

        // source_album and source_artist, album-sized
        for (table in listOf("source_album", "source_artist")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `seq` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `$table` SET `seq` = rowid")
            db.execSQL("DROP INDEX IF EXISTS `index_${table}_sourceId`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_sourceId_seq` ON `$table` (`sourceId`, `seq`)")
        }

        NEW_TABLES.forEach(db::execSQL)
    }
}

// The columns source_track had at version 18, in order.
private const val SOURCE_TRACK_V18 =
    "`id`, `sourceId`, `nativeId`, `title`, `searchKey`, `sortKey`, `artist`, `artistId`, `album`, `albumId`, " +
        "`trackNo`, `discNo`, `year`, `durationMs`, `addedAt`, `mimeType`, `sizeBytes`, `artwork`, `uri`, " +
        "`albumOrder`, `relinkKey`, `genre`, `bitrate`, `sampleRate`, `bitDepth`, `playCount`, `lastPlayedAt`, " +
        "`starredAt`, `trackGain`, `albumGain`, `trackPeak`, `albumPeak`, `baseGain`, `fallbackGain`, `rating`, " +
        "`mergedId`, `originalYear`, `genres`, `artists`, `composer`, `bpm`, `comment`, `explicit`, `discTitle`, " +
        "`mbRecordingId`, `mbAlbumId`, `mbReleaseGroupId`, `mbArtistIds`, `personal`"

private const val TRACK_V18 =
    "`id`, `sourceId`, `nativeId`, `title`, `searchKey`, `sortKey`, `artist`, `artistId`, `album`, `albumId`, " +
        "`trackNo`, `discNo`, `year`, `durationMs`, `addedAt`, `mimeType`, `sizeBytes`, `artwork`, `uri`, " +
        "`albumOrder`, `relinkKey`, `genre`, `onPhone`, `rating`, `explicit`"

// Column definitions as Room writes them for the entities at version 19.
private const val SOURCE_TRACK_COLUMNS =
    "`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `nativeId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`searchKey` TEXT NOT NULL, `sortKey` TEXT NOT NULL, `artist` TEXT NOT NULL, `artistId` TEXT NOT NULL, " +
        "`album` TEXT NOT NULL, `albumId` TEXT NOT NULL, `trackNo` INTEGER, `discNo` INTEGER, `year` INTEGER, " +
        "`durationMs` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `mimeType` TEXT, `sizeBytes` INTEGER, " +
        "`artwork` TEXT, `uri` TEXT, `albumOrder` INTEGER NOT NULL, `relinkKey` TEXT NOT NULL, " +
        "`genre` TEXT NOT NULL, `bitrate` INTEGER, `sampleRate` INTEGER, `bitDepth` INTEGER, " +
        "`playCount` INTEGER, `lastPlayedAt` INTEGER, `starredAt` INTEGER, `trackGain` REAL, " +
        "`albumGain` REAL, `trackPeak` REAL, `albumPeak` REAL, `baseGain` REAL, `fallbackGain` REAL, " +
        "`rating` INTEGER, `mergedId` TEXT NOT NULL DEFAULT '', `originalYear` INTEGER, " +
        "`genres` TEXT NOT NULL DEFAULT '', `artists` TEXT NOT NULL DEFAULT '', `composer` TEXT, " +
        "`bpm` INTEGER, `comment` TEXT, `explicit` INTEGER, `discTitle` TEXT, `mbRecordingId` TEXT, " +
        "`mbAlbumId` TEXT, `mbReleaseGroupId` TEXT, `mbArtistIds` TEXT NOT NULL DEFAULT '', " +
        "`personal` INTEGER NOT NULL DEFAULT 0, `seq` INTEGER NOT NULL DEFAULT 0, " +
        "`titleKey` TEXT NOT NULL DEFAULT '', `matchKey` TEXT NOT NULL DEFAULT '', " +
        "`keysVersion` INTEGER NOT NULL DEFAULT 0"

private const val TRACK_COLUMNS =
    "`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `nativeId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`searchKey` TEXT NOT NULL, `sortKey` TEXT NOT NULL, `artist` TEXT NOT NULL, `artistId` TEXT NOT NULL, " +
        "`album` TEXT NOT NULL, `albumId` TEXT NOT NULL, `trackNo` INTEGER, `discNo` INTEGER, `year` INTEGER, " +
        "`durationMs` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `mimeType` TEXT, `sizeBytes` INTEGER, " +
        "`artwork` TEXT, `uri` TEXT, `albumOrder` INTEGER NOT NULL DEFAULT 0, " +
        "`relinkKey` TEXT NOT NULL DEFAULT '', `genre` TEXT NOT NULL DEFAULT '', " +
        "`onPhone` INTEGER NOT NULL DEFAULT 1, `rating` INTEGER NOT NULL DEFAULT 0, `explicit` INTEGER"

private const val SOURCE_ALBUM_COLUMNS =
    "`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `nativeId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`searchKey` TEXT NOT NULL, `sortKey` TEXT NOT NULL, `artist` TEXT NOT NULL, `artistId` TEXT NOT NULL, " +
        "`year` INTEGER, `songCount` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
        "`artwork` TEXT, `releaseTypes` TEXT NOT NULL DEFAULT '', `seq` INTEGER NOT NULL DEFAULT 0"

private const val SOURCE_ARTIST_COLUMNS =
    "`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `name` TEXT NOT NULL, `searchKey` TEXT NOT NULL, " +
        "`sortKey` TEXT NOT NULL, `albumCount` INTEGER NOT NULL, `songCount` INTEGER NOT NULL, `artwork` TEXT, " +
        "`seq` INTEGER NOT NULL DEFAULT 0"

private const val ALBUM_COLUMNS =
    "`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `nativeId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`searchKey` TEXT NOT NULL, `sortKey` TEXT NOT NULL, `artist` TEXT NOT NULL, `artistId` TEXT NOT NULL, " +
        "`year` INTEGER, `songCount` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
        "`artwork` TEXT, `releaseTypes` TEXT NOT NULL DEFAULT ''"

private const val ARTIST_COLUMNS =
    "`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `name` TEXT NOT NULL, `searchKey` TEXT NOT NULL, " +
        "`sortKey` TEXT NOT NULL, `albumCount` INTEGER NOT NULL, `songCount` INTEGER NOT NULL, `artwork` TEXT"

private fun sourceTrackIndices(table: String) = listOf(
    "CREATE INDEX IF NOT EXISTS `index_source_track_mergedId` ON `$table` (`mergedId`)",
    "CREATE INDEX IF NOT EXISTS `index_source_track_sourceId_albumOrder_seq_id` ON `$table` (`sourceId`, `albumOrder`, `seq`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_source_track_sourceId_seq` ON `$table` (`sourceId`, `seq`)",
    "CREATE INDEX IF NOT EXISTS `index_source_track_sourceId_nativeId` ON `$table` (`sourceId`, `nativeId`)",
    "CREATE INDEX IF NOT EXISTS `index_source_track_albumId` ON `$table` (`albumId`)",
    "CREATE INDEX IF NOT EXISTS `index_source_track_artistId` ON `$table` (`artistId`)",
    "CREATE INDEX IF NOT EXISTS `index_source_track_keysVersion_id` ON `$table` (`keysVersion`, `id`)",
)

private fun trackIndices(table: String) = listOf(
    "CREATE INDEX IF NOT EXISTS `index_track_albumId` ON `$table` (`albumId`)",
    "CREATE INDEX IF NOT EXISTS `index_track_artistId` ON `$table` (`artistId`)",
    "CREATE INDEX IF NOT EXISTS `index_track_sourceId` ON `$table` (`sourceId`)",
    "CREATE INDEX IF NOT EXISTS `index_track_sortKey` ON `$table` (`sortKey`)",
    "CREATE INDEX IF NOT EXISTS `index_track_searchKey_id` ON `$table` (`searchKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_relinkKey_id` ON `$table` (`relinkKey`, `id`)",
)

// Every table new in version 19, with its indexes, empty.
private val NEW_TABLES = listOf(
    "CREATE TABLE IF NOT EXISTS `stage_track` ($SOURCE_TRACK_COLUMNS, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_stage_track_sourceId_albumOrder_seq_id` ON `stage_track` (`sourceId`, `albumOrder`, `seq`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_stage_track_sourceId_seq` ON `stage_track` (`sourceId`, `seq`)",
    "CREATE INDEX IF NOT EXISTS `index_stage_track_sourceId_nativeId` ON `stage_track` (`sourceId`, `nativeId`)",
    "CREATE TABLE IF NOT EXISTS `stage_album` ($SOURCE_ALBUM_COLUMNS, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_stage_album_sourceId_seq` ON `stage_album` (`sourceId`, `seq`)",
    "CREATE TABLE IF NOT EXISTS `stage_artist` ($SOURCE_ARTIST_COLUMNS, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_stage_artist_sourceId_seq` ON `stage_artist` (`sourceId`, `seq`)",
    "CREATE TABLE IF NOT EXISTS `sync_song` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` TEXT NOT NULL, `albumKey` TEXT NOT NULL, `json` TEXT NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_song_songId` ON `sync_song` (`songId`)",
    "CREATE INDEX IF NOT EXISTS `index_sync_song_albumKey_seq` ON `sync_song` (`albumKey`, `seq`)",
    "CREATE TABLE IF NOT EXISTS `sync_album` (`id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_sync_album_seq` ON `sync_album` (`seq`)",
    "CREATE TABLE IF NOT EXISTS `sync_artist` (`id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_sync_artist_seq` ON `sync_artist` (`seq`)",
    "CREATE TABLE IF NOT EXISTS `sync_order` (`owner` TEXT NOT NULL, `discKey` INTEGER NOT NULL, `trackKey` INTEGER NOT NULL, `sortKeyHex` TEXT NOT NULL, `seq` INTEGER NOT NULL, `songId` TEXT NOT NULL, PRIMARY KEY(`owner`, `seq`))",
    "CREATE INDEX IF NOT EXISTS `index_sync_order_owner_discKey_trackKey_sortKeyHex_seq` ON `sync_order` (`owner`, `discKey`, `trackKey`, `sortKeyHex`, `seq`)",
    "CREATE TABLE IF NOT EXISTS `sync_starred` (`generation` INTEGER NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, `starredAt` TEXT, PRIMARY KEY(`generation`, `kind`, `id`))",
    "CREATE TABLE IF NOT EXISTS `merge_candidate` ($TRACK_COLUMNS, `seq` INTEGER NOT NULL, `titleKey` TEXT NOT NULL, `matchKey` TEXT NOT NULL, `originalYear` INTEGER, `credits` INTEGER NOT NULL, `discKey` INTEGER NOT NULL, `lastSourceRank` INTEGER NOT NULL, `createdRank` INTEGER NOT NULL, `mixed` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_merge_candidate_albumId_titleKey_lastSourceRank_seq_durationMs` ON `merge_candidate` (`albumId`, `titleKey`, `lastSourceRank`, `seq`, `durationMs`)",
    "CREATE INDEX IF NOT EXISTS `index_merge_candidate_matchKey_titleKey_lastSourceRank_seq_durationMs` ON `merge_candidate` (`matchKey`, `titleKey`, `lastSourceRank`, `seq`, `durationMs`)",
    "CREATE INDEX IF NOT EXISTS `index_merge_candidate_albumId_discKey_trackNo_seq` ON `merge_candidate` (`albumId`, `discKey`, `trackNo`, `seq`)",
    "CREATE INDEX IF NOT EXISTS `index_merge_candidate_albumId_seq` ON `merge_candidate` (`albumId`, `seq`)",
    "CREATE INDEX IF NOT EXISTS `index_merge_candidate_seq` ON `merge_candidate` (`seq`)",
    "CREATE TABLE IF NOT EXISTS `merged_id_next` (`sourceRowId` TEXT NOT NULL, `mergedId` TEXT NOT NULL, PRIMARY KEY(`sourceRowId`))",
    "CREATE INDEX IF NOT EXISTS `index_merged_id_next_mergedId` ON `merged_id_next` (`mergedId`)",
    "CREATE TABLE IF NOT EXISTS `album_next` ($ALBUM_COLUMNS, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `artist_next` ($ARTIST_COLUMNS, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `take_in_log` (`generation` INTEGER NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, PRIMARY KEY(`generation`, `kind`, `id`))",
    "CREATE TABLE IF NOT EXISTS `rating_note` (`sourceRowId` TEXT NOT NULL, `rating` INTEGER NOT NULL, PRIMARY KEY(`sourceRowId`))",
    "CREATE TABLE IF NOT EXISTS `rating_tap` (`sourceRowId` TEXT NOT NULL, `rating` INTEGER NOT NULL, PRIMARY KEY(`sourceRowId`))",
    "CREATE TABLE IF NOT EXISTS `source_meta` (`sourceId` TEXT NOT NULL, `copyOf` TEXT, `stageOwner` TEXT, `stamp` INTEGER NOT NULL, `stage` TEXT NOT NULL, `generation` INTEGER NOT NULL, `stagedTracks` INTEGER NOT NULL, `stagedAlbums` INTEGER NOT NULL, `stagedArtists` INTEGER NOT NULL, PRIMARY KEY(`sourceId`))",
    "CREATE TABLE IF NOT EXISTS `account_sync` (`account` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL, `songs` INTEGER NOT NULL, `albums` INTEGER NOT NULL, `rowsVersion` INTEGER NOT NULL, `lastScan` TEXT, `afterSyncDone` INTEGER NOT NULL, PRIMARY KEY(`account`))",
    "CREATE TABLE IF NOT EXISTS `merge_state` (`id` INTEGER NOT NULL, `skipKey` TEXT, `claimActive` INTEGER NOT NULL, `server` TEXT, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `synced_star` (`account` TEXT NOT NULL, `serverId` TEXT NOT NULL, PRIMARY KEY(`account`, `serverId`))",
    "CREATE TABLE IF NOT EXISTS `synced_rating` (`account` TEXT NOT NULL, `serverId` TEXT NOT NULL, `rating` INTEGER NOT NULL, PRIMARY KEY(`account`, `serverId`))",
    "CREATE TABLE IF NOT EXISTS `pending_play` (`account` TEXT NOT NULL, `serverId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, PRIMARY KEY(`account`, `serverId`, `startedAt`))",
    "CREATE INDEX IF NOT EXISTS `index_pending_play_account_startedAt_serverId` ON `pending_play` (`account`, `startedAt`, `serverId`)",
    "CREATE TABLE IF NOT EXISTS `sent_play` (`account` TEXT NOT NULL, `serverId` TEXT NOT NULL, `folded` INTEGER NOT NULL, PRIMARY KEY(`account`, `serverId`))",
    "CREATE TABLE IF NOT EXISTS `sent_recent` (`account` TEXT NOT NULL, `serverId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, PRIMARY KEY(`account`, `serverId`, `startedAt`))",
    "CREATE TABLE IF NOT EXISTS `listening_import` (`id` INTEGER NOT NULL, `doneAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
)
