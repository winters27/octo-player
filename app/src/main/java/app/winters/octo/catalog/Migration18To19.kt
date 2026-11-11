package app.winters.octo.catalog

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.winters.octo.ui.paging.Utf16Key

// Version 19: each source row keeps its place in its source's order and
// its song keys, each library song files under its title's lookup keys,
// the library gets an index for every order a list takes,
// and the tables for copying, merging, scanning, syncing playlists,
// keeping the queue and saving a merge in short writes, without holding
// the library in memory.
//
// Every table that gains an index is copied into a new one that already
// has its indexes, in its old order, so no index is built over a full
// table (SQLite would sort it in memory), and every row keeps its place:
// a source row's seq is its old rowid times SEQ_SPAN, so the next copy has
// room to place new rows between the old ones. The triggers that move a
// row written with a renamed id are made last, once no table is copied
// again (dropping a table drops its triggers).
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // source_track
        db.execSQL("DROP INDEX IF EXISTS `index_source_track_sourceId`")
        db.execSQL("DROP INDEX IF EXISTS `index_source_track_mergedId`")
        db.execSQL("CREATE TABLE IF NOT EXISTS `_new_source_track` ($SOURCE_TRACK_COLUMNS, PRIMARY KEY(`id`))")
        sourceTrackIndices("_new_source_track").forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO `_new_source_track` ($SOURCE_TRACK_V18, `seq`, `titleKey`, `matchKey`, `keysVersion`) " +
                "SELECT $SOURCE_TRACK_V18, rowid * $SEQ_SPAN, '', '', 0 FROM `source_track` ORDER BY rowid",
        )
        db.execSQL("DROP TABLE `source_track`")
        db.execSQL("ALTER TABLE `_new_source_track` RENAME TO `source_track`")

        // track, with the names it files under by artist and by album (the
        // artist's and album's own sort keys, or the name lowercased) and
        // its title's lookup keys, read with its artist
        listOf("albumId", "artistId", "sourceId", "sortKey", "searchKey", "relinkKey").forEach {
            db.execSQL("DROP INDEX IF EXISTS `index_track_$it`")
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS `_new_track` ($TRACK_COLUMNS, PRIMARY KEY(`id`))")
        trackIndices("_new_track").forEach(db::execSQL)
        copyTracks(db)
        db.execSQL("DROP TABLE `track`")
        db.execSQL("ALTER TABLE `_new_track` RENAME TO `track`")

        // source_album and source_artist, album-sized; a source's albums are
        // looked up by the source's own ids too
        for (table in listOf("source_album", "source_artist")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `seq` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `$table` SET `seq` = rowid * $SEQ_SPAN")
            db.execSQL("DROP INDEX IF EXISTS `index_${table}_sourceId`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_sourceId_seq` ON `$table` (`sourceId`, `seq`)")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_album_sourceId_nativeId` ON `source_album` (`sourceId`, `nativeId`)")

        // Likes, ratings and downloads, in rowid order, which a download's
        // lookup by song relies on (the last row of a song wins). A
        // download's title key is filled in below.
        copyInto(db, "liked_track", LIKED_TRACK_COLUMNS, ::likedTrackIndices, LIKED_TRACK_V18)
        copyInto(db, "track_rating", TRACK_RATING_COLUMNS, ::trackRatingIndices, TRACK_RATING_V18)
        db.execSQL("DROP INDEX IF EXISTS `index_download_trackId`")
        db.execSQL("DROP INDEX IF EXISTS `index_download_state`")
        copyInto(db, "download", DOWNLOAD_COLUMNS, ::downloadIndices, DOWNLOAD_V18, extra = "`titleKey`" to "X''")

        // Plays, a song's indexed by when they started, keeping their ids
        // and the id counter; and finds, indexed by the library song each
        // became. Their old indexes go first, since the copies make theirs
        // under the same names.
        db.execSQL("DROP INDEX IF EXISTS `index_play_event_trackId`")
        db.execSQL("DROP INDEX IF EXISTS `index_play_event_startedAt`")
        copyInto(db, "play_event", PLAY_EVENT_COLUMNS, ::playEventIndices, PLAY_EVENT_V18, keepCounter = true)
        db.execSQL("DROP INDEX IF EXISTS `index_online_song_sourceId`")
        db.execSQL("DROP INDEX IF EXISTS `index_online_song_requestedAt`")
        copyInto(db, "online_song", ONLINE_SONG_COLUMNS, ::onlineSongIndices, ONLINE_SONG_V18)

        // playlist: the sets its songs and base are in, and whether a base is
        // kept. The table is only added to, never copied, since its songs
        // point at it.
        db.execSQL("ALTER TABLE `playlist` ADD COLUMN `itemSet` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `playlist` ADD COLUMN `baseSet` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `playlist` ADD COLUMN `syncedSongsKept` INTEGER NOT NULL DEFAULT 0")

        // playlist_item: every row in its playlist's set 0, its place as a
        // sparse ord ORD_STEP apart (a 64-bit value), its id as its key, so
        // the order (ord, itemKey) is the old (position, id). The id counter
        // carries over, so no row id is handed out twice.
        db.execSQL("CREATE TABLE IF NOT EXISTS `_new_playlist_item` ($PLAYLIST_ITEM_COLUMNS)")
        playlistItemIndices("_new_playlist_item").forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO `_new_playlist_item` (`id`, `playlistId`, `itemSet`, `trackId`, `relinkKey`, `ord`, `itemKey`, `serverSongId`) " +
                "SELECT `id`, `playlistId`, 0, `trackId`, `relinkKey`, `position` * $ORD_STEP, `id`, `serverSongId` FROM `playlist_item` ORDER BY `id`",
        )
        carryCounter(db, "playlist_item")
        db.execSQL("DROP TABLE `playlist_item`")
        db.execSQL("ALTER TABLE `_new_playlist_item` RENAME TO `playlist_item`")

        NEW_TABLES.forEach(db::execSQL)

        // New playlist rows take keys past every row id there is.
        db.execSQL("INSERT INTO `playlist_meta` (`id`, `nextItemKey`) SELECT 0, COALESCE(MAX(`id`), 0) + 1 FROM `playlist_item`")

        QUEUE_COPY.forEach(db::execSQL)
        fillDownloadTitleKeys(db)
        moveSyncedSongs(db)

        // Last: no table is copied after these.
        SAVE_RENAME_TRIGGERS.forEach(db::execSQL)
    }
}

// Copies a table into a new one made with its indexes, keeping its rows'
// order, and puts the new one in its place. `extra` is a column the old
// table lacks and the value it starts with; `keepCounter` carries the id
// counter of a table whose ids are never handed out twice.
private fun copyInto(
    db: SupportSQLiteDatabase,
    table: String,
    columns: String,
    indices: (String) -> List<String>,
    copied: String,
    extra: Pair<String, String>? = null,
    keepCounter: Boolean = false,
) {
    db.execSQL("CREATE TABLE IF NOT EXISTS `_new_$table` ($columns)")
    indices("_new_$table").forEach(db::execSQL)
    val into = if (extra == null) copied else "$copied, ${extra.first}"
    val values = if (extra == null) copied else "$copied, ${extra.second}"
    db.execSQL("INSERT INTO `_new_$table` ($into) SELECT $values FROM `$table` ORDER BY rowid")
    if (keepCounter) carryCounter(db, table)
    db.execSQL("DROP TABLE `$table`")
    db.execSQL("ALTER TABLE `_new_$table` RENAME TO `$table`")
}

// Gives a table's copy the old table's id counter, which can be past every
// id left (its last rows deleted), so no id is handed out twice. The copy's
// own counter, from the ids it took, goes first.
private fun carryCounter(db: SupportSQLiteDatabase, table: String) {
    val lastId = db.query("SELECT `seq` FROM `sqlite_sequence` WHERE `name` = ?", arrayOf<Any?>(table)).use { c ->
        if (c.moveToFirst()) c.getLong(0) else null
    }
    db.execSQL("DELETE FROM `sqlite_sequence` WHERE `name` = ?", arrayOf<Any?>("_new_$table"))
    if (lastId != null) {
        db.execSQL("INSERT INTO `sqlite_sequence` (`name`, `seq`) VALUES (?, ?)", arrayOf<Any?>("_new_$table", lastId))
    }
}

// The library's songs into the new table a page at a time, in their old
// order, each with the keys its title is looked up by, which only Kotlin
// works out. Each row is written once, as one statement copying them all
// would write it.
private fun copyTracks(db: SupportSQLiteDatabase) {
    val read = "SELECT rowid, $TRACK_V18, " +
        "COALESCE((SELECT a.`sortKey` FROM `artist` a WHERE a.`id` = `track`.`artistId`), LOWER(`track`.`artist`)), " +
        "COALESCE((SELECT a.`sortKey` FROM `album` a WHERE a.`id` = `track`.`albumId`), LOWER(`track`.`album`)) " +
        "FROM `track` WHERE rowid > ? ORDER BY rowid LIMIT $MIGRATION_PAGE"
    val columns = "$TRACK_V18, `artistSortKey`, `albumSortKey`, `lookupKey`, `looseKey`"
    val insert = "INSERT INTO `_new_track` ($columns) VALUES (${columns.split(',').joinToString { "?" }})"
    val title = TRACK_V18.split(',').indexOfFirst { it.trim() == "`title`" } + 1
    val artist = TRACK_V18.split(',').indexOfFirst { it.trim() == "`artist`" } + 1
    var after = Long.MIN_VALUE
    while (true) {
        val page = db.query(read, arrayOf<Any?>(after)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Array<Any?>(c.columnCount) { i ->
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                                else -> c.getString(i)
                            }
                        },
                    )
                }
            }
        }
        if (page.isEmpty()) return
        for (row in page) {
            val keys = titleLookup(row[title] as String?, row[artist] as String?)
            db.execSQL(insert, arrayOf<Any?>(*row.copyOfRange(1, row.size), keys.lookupKey, keys.looseKey))
        }
        after = page.last()[0] as Long
    }
}

// Each download's title key, the order the Downloads page lists titles in,
// a page of rows at a time.
private fun fillDownloadTitleKeys(db: SupportSQLiteDatabase) {
    var after = 0L
    while (true) {
        val page = db.query("SELECT rowid, `title` FROM `download` WHERE rowid > ? ORDER BY rowid LIMIT $MIGRATION_PAGE", arrayOf<Any?>(after)).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
        }
        if (page.isEmpty()) return
        for ((rowid, title) in page) {
            db.execSQL("UPDATE `download` SET `titleKey` = ? WHERE rowid = ?", arrayOf<Any?>(Utf16Key(sortKey(title)), rowid))
        }
        after = page.last().first
    }
}

// A family playlist's base was one cell, the server's song ids one a line,
// as long as the playlist. Each becomes a row per song with its count, in
// base set 0, and the cell is emptied. The cell is read a window at a
// time, so no cursor and no string holds a whole cell; an id cut by a
// window's end is carried into the next.
private fun moveSyncedSongs(db: SupportSQLiteDatabase) {
    val playlists = db.query("SELECT `id` FROM `playlist` WHERE typeof(`syncedSongs`) = 'text' ORDER BY rowid").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }
    for (id in playlists) {
        var start = 1L
        var carry = ""
        while (true) {
            val window = db.query("SELECT substr(`syncedSongs`, ?, $SYNCED_WINDOW) FROM `playlist` WHERE `id` = ?", arrayOf<Any?>(start, id)).use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }
            val last = window.codePointCount(0, window.length) < SYNCED_WINDOW
            val text = carry + window
            val whole = if (last) text.length else text.lastIndexOf('\n') + 1
            addSyncedSongs(db, id, text.substring(0, whole))
            carry = text.substring(whole)
            if (last) break
            start += SYNCED_WINDOW
        }
        db.execSQL("UPDATE `playlist` SET `syncedSongs` = NULL, `syncedSongsKept` = 1 WHERE `id` = ?", arrayOf<Any?>(id))
    }
}

// Counts the ids of one window's whole lines into a playlist's base.
private fun addSyncedSongs(db: SupportSQLiteDatabase, playlistId: String, lines: String) {
    val counts = HashMap<String, Int>()
    for (song in lines.split('\n')) if (song.isNotEmpty()) counts[song] = (counts[song] ?: 0) + 1
    for ((song, n) in counts) {
        db.execSQL(
            "INSERT OR IGNORE INTO `playlist_synced_song` (`playlistId`, `songId`, `copies`, `baseSet`) VALUES (?, ?, 0, 0)",
            arrayOf<Any?>(playlistId, song),
        )
        db.execSQL(
            "UPDATE `playlist_synced_song` SET `copies` = `copies` + ? WHERE `playlistId` = ? AND `baseSet` = 0 AND `songId` = ?",
            arrayOf<Any?>(n, playlistId, song),
        )
    }
}

// Rows read or written per statement where the migration walks a table.
private const val MIGRATION_PAGE = 500

// How many characters of a base's cell one read takes: at most 1 MB in the
// cursor's window and half that as a Java string.
private const val SYNCED_WINDOW = 262_144

// The saved queue becomes queue 1, all of it one list (list 1) numbered in
// play order, with the shuffle order it had, for the account the queue was
// saved for, which the first restore names ('' until then).
private const val QUEUE_LIST_SHIFT = 32
private const val QUEUE_MID = 1L shl 62
private const val QUEUE_STEP = 1L shl 40
private const val QUEUE_SHUFFLE_MID = (1L shl 60) + (1L shl 61)

private val QUEUE_COPY = listOf(
    "INSERT INTO `queue_entry` (`queueId`, `entry`, `trackId`, `listId`, `qk`, `sk`, `durationMs`) " +
        "SELECT 1, (1 << $QUEUE_LIST_SHIFT) | q.`position`, q.`trackId`, 1, $QUEUE_MID + q.`position` * $QUEUE_STEP, " +
        "$QUEUE_SHUFFLE_MID + q.`shuffledPosition` * $QUEUE_STEP, " +
        "COALESCE((SELECT t.`durationMs` FROM `track` t WHERE t.`id` = q.`trackId`), 0) " +
        "FROM `queue_item` q ORDER BY q.`position`",
    "INSERT INTO `queue_list` (`listId`, `queueId`, `source`, `autoplay`, `spec`, `seed`, `fillState`, `phase`, `cursor`, `anchor`, " +
        "`phase0End`, `expected`, `swapCount`, `qBase`, `qStep`, `sBase`, `sStep`, `copyOwner`) " +
        "SELECT 1, 1, NULL, 0, NULL, 0, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL FROM `queue_state` WHERE `id` = 0",
    "INSERT INTO `queue_account` (`account`, `queueId`, `currentEntry`, `positionMs`, `repeatMode`, `shuffle`, `floorQ`, `floorS`, `healedAt`, `savedAt`) " +
        "SELECT '', 1, (1 << $QUEUE_LIST_SHIFT) | `currentIndex`, `positionMs`, `repeatMode`, `shuffle`, NULL, NULL, NULL, `savedAt` " +
        "FROM `queue_state` WHERE `id` = 0",
    "INSERT INTO `queue_meta` (`id`, `nextQueueId`, `nextListId`) VALUES (0, 2, 2)",
    // The old queue's tables go once their rows are in the new ones.
    "DROP TABLE `queue_item`",
    "DROP TABLE `queue_state`",
)

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
        "`keysVersion` INTEGER NOT NULL DEFAULT 0, `folder` TEXT, `fileName` TEXT, `storageVolume` TEXT"

private const val TRACK_COLUMNS =
    "`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `nativeId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`searchKey` TEXT NOT NULL, `sortKey` TEXT NOT NULL, `artist` TEXT NOT NULL, `artistId` TEXT NOT NULL, " +
        "`album` TEXT NOT NULL, `albumId` TEXT NOT NULL, `trackNo` INTEGER, `discNo` INTEGER, `year` INTEGER, " +
        "`durationMs` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `mimeType` TEXT, `sizeBytes` INTEGER, " +
        "`artwork` TEXT, `uri` TEXT, `albumOrder` INTEGER NOT NULL DEFAULT 0, " +
        "`relinkKey` TEXT NOT NULL DEFAULT '', `genre` TEXT NOT NULL COLLATE NOCASE DEFAULT '', " +
        "`onPhone` INTEGER NOT NULL DEFAULT 1, `rating` INTEGER NOT NULL DEFAULT 0, `explicit` INTEGER, " +
        "`artistSortKey` TEXT NOT NULL DEFAULT '', `albumSortKey` TEXT NOT NULL DEFAULT '', " +
        "`lookupKey` TEXT NOT NULL, `looseKey` TEXT NOT NULL"

// The columns of likes, ratings and downloads at version 18, and as Room
// writes them at version 19.
private const val LIKED_TRACK_V18 = "`trackId`, `relinkKey`, `likedAt`"

private const val LIKED_TRACK_COLUMNS =
    "`trackId` TEXT NOT NULL, `relinkKey` TEXT NOT NULL, `likedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`)"

private const val TRACK_RATING_V18 = "`trackId`, `relinkKey`, `rating`, `ratedAt`"

private const val TRACK_RATING_COLUMNS =
    "`trackId` TEXT NOT NULL, `relinkKey` TEXT NOT NULL, `rating` INTEGER NOT NULL, `ratedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`)"

private const val DOWNLOAD_V18 =
    "`trackId`, `sourceId`, `serverId`, `path`, `sizeBytes`, `format`, `state`, `progress`, `addedAt`, `reason`, " +
        "`title`, `artist`, `fileSize`"

private const val DOWNLOAD_COLUMNS =
    "`trackId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `serverId` TEXT NOT NULL, `path` TEXT NOT NULL, " +
        "`sizeBytes` INTEGER NOT NULL, `format` TEXT, `state` TEXT NOT NULL, `progress` REAL NOT NULL, " +
        "`addedAt` INTEGER NOT NULL, `reason` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
        "`fileSize` INTEGER, `titleKey` BLOB NOT NULL, PRIMARY KEY(`sourceId`, `serverId`)"

// The columns of plays and finds, the same at versions 18 and 19, and as
// Room writes them.
private const val PLAY_EVENT_V18 = "`id`, `trackId`, `relinkKey`, `startedAt`, `playedMs`, `durationMs`"

private const val PLAY_EVENT_COLUMNS =
    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, `relinkKey` TEXT NOT NULL, " +
        "`startedAt` INTEGER NOT NULL, `playedMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL"

private const val ONLINE_SONG_V18 =
    "`id`, `sourceId`, `nativeId`, `title`, `artist`, `album`, `albumId`, `artistId`, `durationMs`, `coverId`, " +
        "`mimeType`, `bitrate`, `seenAt`, `requestedAt`, `adoptedId`, `explicit`"

private const val ONLINE_SONG_COLUMNS =
    "`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `nativeId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`artist` TEXT NOT NULL, `album` TEXT NOT NULL, `albumId` TEXT, `artistId` TEXT, `durationMs` INTEGER NOT NULL, " +
        "`coverId` TEXT, `mimeType` TEXT, `bitrate` INTEGER, `seenAt` INTEGER NOT NULL, " +
        "`requestedAt` INTEGER NOT NULL DEFAULT 0, `adoptedId` TEXT NOT NULL DEFAULT '', `explicit` INTEGER, PRIMARY KEY(`id`)"

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
    "CREATE INDEX IF NOT EXISTS `index_source_track_folder` ON `$table` (`sourceId`, `storageVolume`, `folder`, `mergedId`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_source_track_sourceId_matchKey` ON `$table` (`sourceId`, `matchKey`, `durationMs`)",
)

private fun trackIndices(table: String) = listOf(
    "CREATE INDEX IF NOT EXISTS `index_track_sourceId` ON `$table` (`sourceId`)",
    "CREATE INDEX IF NOT EXISTS `index_track_searchKey_id` ON `$table` (`searchKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_relinkKey_id` ON `$table` (`relinkKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_sortKey_id` ON `$table` (`sortKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_title` ON `$table` (`sortKey`, `artistSortKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_by_artist` ON `$table` (`artistSortKey`, `albumSortKey`, `albumId`, `albumOrder`, `sortKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_by_album` ON `$table` (`albumSortKey`, `artistSortKey`, `albumId`, `albumOrder`, `sortKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_added` ON `$table` (`addedAt`, `albumId`, `albumOrder`, `sortKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_year` ON `$table` (`year`, `albumSortKey`, `albumId`, `albumOrder`, `sortKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_length` ON `$table` (`durationMs`, `sortKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_rating` ON `$table` (`rating`, `sortKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_genre` ON `$table` (`genre`, `sortKey`, `artistSortKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_album_songs` ON `$table` (`albumId`, `albumOrder`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_artist_songs` ON `$table` (`artistId`, `albumId`, `albumOrder`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_artist_artistId_rating` ON `$table` (`artist`, `artistId`, `rating`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_lookupKey_id` ON `$table` (`lookupKey`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_track_looseKey_id` ON `$table` (`looseKey`, `id`)",
)

private const val PLAYLIST_ITEM_COLUMNS =
    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` TEXT NOT NULL, `itemSet` INTEGER NOT NULL DEFAULT 0, " +
        "`trackId` TEXT NOT NULL, `relinkKey` TEXT NOT NULL, `ord` INTEGER NOT NULL, `itemKey` INTEGER NOT NULL, " +
        "`serverSongId` TEXT, FOREIGN KEY(`playlistId`) REFERENCES `playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE "

private fun playlistItemIndices(table: String) = listOf(
    "CREATE INDEX IF NOT EXISTS `index_playlist_item_set` ON `$table` (`playlistId`, `itemSet`, `ord`, `itemKey`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_item_set_key` ON `$table` (`playlistId`, `itemSet`, `itemKey`)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_item_set_track` ON `$table` (`playlistId`, `itemSet`, `trackId`)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_item_trackId` ON `$table` (`trackId`)",
)

private fun likedTrackIndices(table: String) = listOf(
    "CREATE INDEX IF NOT EXISTS `index_liked_track_likedAt` ON `$table` (`likedAt`, `trackId`)",
)

private fun trackRatingIndices(table: String) = listOf(
    "CREATE INDEX IF NOT EXISTS `index_track_rating_ratedAt` ON `$table` (`ratedAt`, `trackId`)",
)

private fun downloadIndices(table: String) = listOf(
    "CREATE INDEX IF NOT EXISTS `index_download_trackId` ON `$table` (`trackId`)",
    "CREATE INDEX IF NOT EXISTS `index_download_state` ON `$table` (`state`)",
    "CREATE INDEX IF NOT EXISTS `index_download_active` ON `$table` (`sourceId`, `state`, `addedAt`, `title`, `serverId`)",
    "CREATE INDEX IF NOT EXISTS `index_download_done_title` ON `$table` (`sourceId`, `state`, `titleKey`, `trackId`, `serverId`)",
    "CREATE INDEX IF NOT EXISTS `index_download_done_size` ON `$table` (`sourceId`, `state`, `sizeBytes`, `titleKey`, `trackId`, `serverId`)",
    "CREATE INDEX IF NOT EXISTS `index_download_done_added` ON `$table` (`sourceId`, `state`, `addedAt`, `titleKey`, `trackId`, `serverId`)",
)

private fun playEventIndices(table: String) = listOf(
    "CREATE INDEX IF NOT EXISTS `index_play_event_trackId_startedAt` ON `$table` (`trackId`, `startedAt`)",
    "CREATE INDEX IF NOT EXISTS `index_play_event_startedAt` ON `$table` (`startedAt`)",
)

private fun onlineSongIndices(table: String) = listOf(
    "CREATE INDEX IF NOT EXISTS `index_online_song_sourceId` ON `$table` (`sourceId`)",
    "CREATE INDEX IF NOT EXISTS `index_online_song_requestedAt` ON `$table` (`requestedAt`)",
    "CREATE INDEX IF NOT EXISTS `index_online_song_adoptedId` ON `$table` (`adoptedId`)",
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
    "CREATE TABLE IF NOT EXISTS `sync_album` (`id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `json` TEXT NOT NULL, `place` INTEGER, PRIMARY KEY(`id`))",
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
    "CREATE INDEX IF NOT EXISTS `index_merge_candidate_relinkKey_id` ON `merge_candidate` (`relinkKey`, `id`)",
    "CREATE TABLE IF NOT EXISTS `merged_id_next` (`sourceRowId` TEXT NOT NULL, `mergedId` TEXT NOT NULL, PRIMARY KEY(`sourceRowId`))",
    "CREATE INDEX IF NOT EXISTS `index_merged_id_next_mergedId` ON `merged_id_next` (`mergedId`)",
    "CREATE TABLE IF NOT EXISTS `album_next` ($ALBUM_COLUMNS, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_album_next_searchKey_id` ON `album_next` (`searchKey`, `id`)",
    "CREATE TABLE IF NOT EXISTS `artist_next` ($ARTIST_COLUMNS, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_artist_next_searchKey_id` ON `artist_next` (`searchKey`, `id`)",
    "CREATE TABLE IF NOT EXISTS `take_in_log` (`generation` INTEGER NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, PRIMARY KEY(`generation`, `kind`, `id`))",
    "CREATE TABLE IF NOT EXISTS `rating_note` (`sourceRowId` TEXT NOT NULL, `rating` INTEGER NOT NULL, PRIMARY KEY(`sourceRowId`))",
    "CREATE TABLE IF NOT EXISTS `rating_tap` (`sourceRowId` TEXT NOT NULL, `rating` INTEGER NOT NULL, PRIMARY KEY(`sourceRowId`))",
    "CREATE TABLE IF NOT EXISTS `source_meta` (`sourceId` TEXT NOT NULL, `copyOf` TEXT, `stageOwner` TEXT, `stamp` INTEGER NOT NULL, `stage` TEXT NOT NULL, `generation` INTEGER NOT NULL, `stagedTracks` INTEGER NOT NULL, `stagedAlbums` INTEGER NOT NULL, `stagedArtists` INTEGER NOT NULL, `stageClearing` INTEGER NOT NULL DEFAULT 0, `movedTracks` INTEGER NOT NULL DEFAULT 0, `movedAlbums` INTEGER NOT NULL DEFAULT 0, `movedArtists` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`sourceId`))",
    "CREATE TABLE IF NOT EXISTS `account_sync` (`account` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL, `songs` INTEGER NOT NULL, `albums` INTEGER NOT NULL, `rowsVersion` INTEGER NOT NULL, `lastScan` TEXT, `afterSyncDone` INTEGER NOT NULL, PRIMARY KEY(`account`))",
    "CREATE TABLE IF NOT EXISTS `merge_state` (`id` INTEGER NOT NULL, `skipKey` TEXT, `claimActive` INTEGER NOT NULL, `server` TEXT, " +
        "`swapCount` INTEGER NOT NULL DEFAULT 0, `applyClaim` TEXT, `applySkipKey` TEXT, `applyAccount` TEXT, " +
        "`applyFirstCopy` INTEGER NOT NULL DEFAULT 0, `applyScan` TEXT, `applyStopped` TEXT, `totalsAccount` TEXT, " +
        "`launchMark` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `synced_star` (`account` TEXT NOT NULL, `serverId` TEXT NOT NULL, PRIMARY KEY(`account`, `serverId`))",
    "CREATE TABLE IF NOT EXISTS `synced_rating` (`account` TEXT NOT NULL, `serverId` TEXT NOT NULL, `rating` INTEGER NOT NULL, PRIMARY KEY(`account`, `serverId`))",
    "CREATE TABLE IF NOT EXISTS `pending_play` (`account` TEXT NOT NULL, `serverId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, PRIMARY KEY(`account`, `serverId`, `startedAt`))",
    "CREATE INDEX IF NOT EXISTS `index_pending_play_account_startedAt_serverId` ON `pending_play` (`account`, `startedAt`, `serverId`)",
    "CREATE TABLE IF NOT EXISTS `sent_play` (`account` TEXT NOT NULL, `serverId` TEXT NOT NULL, `folded` INTEGER NOT NULL, PRIMARY KEY(`account`, `serverId`))",
    "CREATE TABLE IF NOT EXISTS `sent_recent` (`account` TEXT NOT NULL, `serverId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, PRIMARY KEY(`account`, `serverId`, `startedAt`))",
    "CREATE TABLE IF NOT EXISTS `listening_import` (`id` INTEGER NOT NULL, `doneAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    // play totals
    "CREATE TABLE IF NOT EXISTS `play_total` (`trackId` TEXT NOT NULL, `albumId` TEXT NOT NULL, `artistId` TEXT NOT NULL, `plays` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, `recentAt` INTEGER, `phonePlays` INTEGER NOT NULL, `phoneLastAt` INTEGER, `sortKey` TEXT NOT NULL, `artistSortKey` TEXT NOT NULL, PRIMARY KEY(`trackId`))",
    "CREATE INDEX IF NOT EXISTS `index_play_total_most` ON `play_total` (`plays` DESC, `lastPlayedAt` DESC, `trackId` ASC)",
    "CREATE INDEX IF NOT EXISTS `index_play_total_album` ON `play_total` (`albumId` ASC, `plays` DESC, `lastPlayedAt` DESC, `trackId` ASC)",
    "CREATE INDEX IF NOT EXISTS `index_play_total_artist` ON `play_total` (`artistId` ASC, `plays` DESC, `lastPlayedAt` DESC, `trackId` ASC)",
    "CREATE INDEX IF NOT EXISTS `index_play_total_recent` ON `play_total` (`recentAt` DESC, `trackId` ASC)",
    "CREATE INDEX IF NOT EXISTS `index_play_total_last` ON `play_total` (`lastPlayedAt` DESC, `trackId` ASC)",
    "CREATE INDEX IF NOT EXISTS `index_play_total_phone` ON `play_total` (`phoneLastAt` DESC, `trackId` ASC)",
    "CREATE INDEX IF NOT EXISTS `index_play_total_most_title` ON `play_total` (`plays`, `lastPlayedAt`, `sortKey`, `artistSortKey`, `trackId`)",
    "CREATE INDEX IF NOT EXISTS `index_play_total_recent_title` ON `play_total` (`lastPlayedAt`, `plays`, `sortKey`, `artistSortKey`, `trackId`)",
    "CREATE TABLE IF NOT EXISTS `play_total_diff` (`trackId` TEXT NOT NULL, `op` INTEGER NOT NULL, `albumId` TEXT, `artistId` TEXT, `plays` INTEGER, `lastPlayedAt` INTEGER, `recentAt` INTEGER, `phonePlays` INTEGER, `phoneLastAt` INTEGER, `sortKey` TEXT, `artistSortKey` TEXT, PRIMARY KEY(`trackId`))",
    // playlists: the base, row keys, set pins and the edit log
    "CREATE TABLE IF NOT EXISTS `playlist_synced_song` (`playlistId` TEXT NOT NULL, `songId` TEXT NOT NULL, `copies` INTEGER NOT NULL, `baseSet` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `baseSet`, `songId`), FOREIGN KEY(`playlistId`) REFERENCES `playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `playlist_meta` (`id` INTEGER NOT NULL, `nextItemKey` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `playlist_set_pin` (`owner` TEXT NOT NULL, `playlistId` TEXT NOT NULL, `itemSet` INTEGER NOT NULL, PRIMARY KEY(`owner`, `playlistId`))",
    "CREATE INDEX IF NOT EXISTS `index_playlist_set_pin_set` ON `playlist_set_pin` (`playlistId`, `itemSet`)",
    "CREATE TABLE IF NOT EXISTS `playlist_edit_log` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` TEXT NOT NULL, `itemSet` INTEGER NOT NULL, `op` INTEGER NOT NULL, `itemKey` INTEGER NOT NULL, `ord` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `relinkKey` TEXT NOT NULL, `serverSongId` TEXT)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_edit_log_key` ON `playlist_edit_log` (`playlistId`, `itemSet`, `itemKey`, `seq`)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_edit_log_ord` ON `playlist_edit_log` (`playlistId`, `itemSet`, `ord`, `itemKey`, `seq`)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_edit_log_trackId` ON `playlist_edit_log` (`trackId`)",
    // a playlist sync step's rows
    "CREATE TABLE IF NOT EXISTS `playlist_entry` (`read` INTEGER NOT NULL, $SERVER_ENTRY_COLUMNS, PRIMARY KEY(`read`, `position`))",
    "CREATE INDEX IF NOT EXISTS `index_playlist_entry_read_songId_position` ON `playlist_entry` (`read`, `songId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_entry_read_trackId_position` ON `playlist_entry` (`read`, `trackId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_entry_read_kind_songId_position` ON `playlist_entry` (`read`, `kind`, `songId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_entry_trackId` ON `playlist_entry` (`trackId`)",
    "CREATE TABLE IF NOT EXISTS `playlist_key` (`read` INTEGER NOT NULL, `key` TEXT NOT NULL, PRIMARY KEY(`read`, `key`))",
    "CREATE TABLE IF NOT EXISTS `playlist_match` (`id` INTEGER NOT NULL, `read` INTEGER NOT NULL, $SERVER_MATCH_COLUMNS, PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_match_read_title_artist_lengthMs` ON `playlist_match` (`read`, `title`, `artist`, `lengthMs`)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_match_trackId` ON `playlist_match` (`trackId`)",
    "CREATE TABLE IF NOT EXISTS `playlist_lookup` (`read` INTEGER NOT NULL, `lookupKey` TEXT NOT NULL, `groupId` INTEGER NOT NULL, `lengthMs` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`read`, `lookupKey`, `groupId`))",
    "CREATE INDEX IF NOT EXISTS `index_playlist_lookup_length` ON `playlist_lookup` (`read`, `lookupKey`, `lengthMs`, `groupId`)",
    "CREATE TABLE IF NOT EXISTS `playlist_local` (`itemPosition` INTEGER NOT NULL, `itemKey` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `serverSongId` TEXT, `anchor` INTEGER, `rank` INTEGER NOT NULL DEFAULT 0, `relinkKey` TEXT NOT NULL DEFAULT '', `taken` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`itemPosition`, `itemKey`))",
    "CREATE INDEX IF NOT EXISTS `index_playlist_local_anchor_itemPosition_itemKey` ON `playlist_local` (`anchor`, `itemPosition`, `itemKey`)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_local_trackId_itemPosition_itemKey` ON `playlist_local` (`trackId`, `itemPosition`, `itemKey`)",
    "CREATE INDEX IF NOT EXISTS `index_playlist_local_trackId_rank` ON `playlist_local` (`trackId`, `rank`)",
    "CREATE TABLE IF NOT EXISTS `playlist_next` (`position` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `serverSongId` TEXT, `relinkKey` TEXT NOT NULL, `rank` INTEGER NOT NULL DEFAULT 0, `keptKey` INTEGER, PRIMARY KEY(`position`))",
    "CREATE INDEX IF NOT EXISTS `index_playlist_next_trackId_position` ON `playlist_next` (`trackId`, `position`)",
    "CREATE TABLE IF NOT EXISTS `playlist_send` (`slot` INTEGER NOT NULL, `position` INTEGER NOT NULL, `songId` TEXT NOT NULL, `rank` INTEGER NOT NULL DEFAULT 0, `added` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`slot`, `position`))",
    "CREATE INDEX IF NOT EXISTS `index_playlist_send_slot_songId_position` ON `playlist_send` (`slot`, `songId`, `position`)",
    "CREATE TABLE IF NOT EXISTS `playlist_change` (`kind` INTEGER NOT NULL, `position` INTEGER NOT NULL, `songId` TEXT NOT NULL, `copy` INTEGER NOT NULL DEFAULT 0, `done` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`kind`, `position`))",
    "CREATE INDEX IF NOT EXISTS `index_playlist_change_kind_songId_position` ON `playlist_change` (`kind`, `songId`, `position`)",
    // a phone scan's rows, and what the last one saw
    "CREATE TABLE IF NOT EXISTS `scan_file` (`mediaId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `uri` TEXT NOT NULL, `fileName` TEXT NOT NULL, `folder` TEXT, `volume` TEXT, `modifiedAt` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `mimeType` TEXT, `fbTitle` TEXT, `fbArtist` TEXT, `fbAlbumArtist` TEXT, `fbAlbum` TEXT, `fbTrackNo` INTEGER, `fbDiscNo` INTEGER, `fbYear` INTEGER, `fbGenres` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`mediaId`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_file_seq` ON `scan_file` (`seq`)",
    "CREATE TABLE IF NOT EXISTS `scan_row` (`mediaId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `groupId` INTEGER NOT NULL, `track` INTEGER, `fileNo` INTEGER, `discKey` INTEGER NOT NULL, `numKey` INTEGER NOT NULL, `nameOrder` TEXT NOT NULL, PRIMARY KEY(`mediaId`))",
    "CREATE INDEX IF NOT EXISTS `index_scan_row_groupId_seq` ON `scan_row` (`groupId`, `seq`)",
    "CREATE INDEX IF NOT EXISTS `index_scan_row_groupId_discKey_numKey_nameOrder_seq` ON `scan_row` (`groupId`, `discKey`, `numKey`, `nameOrder`, `seq`)",
    "CREATE TABLE IF NOT EXISTS `scan_cluster` (`nameKey` TEXT NOT NULL, `groupId` INTEGER NOT NULL, `firstSeq` INTEGER NOT NULL DEFAULT 0, `files` INTEGER NOT NULL DEFAULT 0, `mbFiles` INTEGER NOT NULL DEFAULT 0, `byFiles` INTEGER NOT NULL DEFAULT 0, `mbFirst` TEXT, `byFirst` TEXT, `mbVaries` INTEGER NOT NULL DEFAULT 0, `byVaries` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`nameKey`))",
    "CREATE TABLE IF NOT EXISTS `scan_group` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nameKey` TEXT NOT NULL, `splitKey` TEXT NOT NULL, `clusterSeq` INTEGER NOT NULL, `firstSeq` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_group_nameKey_splitKey` ON `scan_group` (`nameKey`, `splitKey`)",
    "CREATE INDEX IF NOT EXISTS `index_scan_group_clusterSeq_firstSeq` ON `scan_group` (`clusterSeq`, `firstSeq`)",
    "CREATE TABLE IF NOT EXISTS `scan_tally` (`owner` TEXT NOT NULL, `value` TEXT NOT NULL, `n` INTEGER NOT NULL DEFAULT 0, `first` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`owner`, `value`))",
    "CREATE TABLE IF NOT EXISTS `scan_genre_name` (`genreKey` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`genreKey`))",
    "CREATE TABLE IF NOT EXISTS `scan_artist` (`artistId` TEXT NOT NULL, `firstSeq` INTEGER NOT NULL DEFAULT 0, `albums` INTEGER NOT NULL DEFAULT 0, `songs` INTEGER NOT NULL DEFAULT 0, `newestAt` INTEGER NOT NULL DEFAULT 0, `artwork` TEXT, `sortName` TEXT, PRIMARY KEY(`artistId`))",
    "CREATE INDEX IF NOT EXISTS `index_scan_artist_firstSeq` ON `scan_artist` (`firstSeq`)",
    "CREATE TABLE IF NOT EXISTS `phone_scan_state` (`id` INTEGER NOT NULL, `digest` TEXT, `tracks` INTEGER NOT NULL DEFAULT 0, `albums` INTEGER NOT NULL DEFAULT 0, `artists` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
    // the queue
    "CREATE TABLE IF NOT EXISTS `queue_entry` (`queueId` INTEGER NOT NULL, `entry` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `listId` INTEGER NOT NULL, `qk` INTEGER NOT NULL, `sk` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `skBefore` INTEGER, PRIMARY KEY(`queueId`, `entry`))",
    "CREATE INDEX IF NOT EXISTS `index_queue_entry_qk` ON `queue_entry` (`queueId`, `qk`, `entry`)",
    "CREATE INDEX IF NOT EXISTS `index_queue_entry_sk` ON `queue_entry` (`queueId`, `sk`, `entry`)",
    "CREATE INDEX IF NOT EXISTS `index_queue_entry_trackId` ON `queue_entry` (`trackId`)",
    "CREATE INDEX IF NOT EXISTS `index_queue_entry_skBefore` ON `queue_entry` (`queueId`, `skBefore`)",
    "CREATE TABLE IF NOT EXISTS `queue_list` (`listId` INTEGER NOT NULL, `queueId` INTEGER NOT NULL, `source` TEXT, `autoplay` INTEGER NOT NULL, `spec` TEXT, `seed` INTEGER NOT NULL, `fillState` INTEGER NOT NULL, `phase` INTEGER, `cursor` TEXT, `anchor` TEXT, `filled0` INTEGER NOT NULL DEFAULT 0, `filled1` INTEGER NOT NULL DEFAULT 0, `phase0End` INTEGER, `expected` INTEGER, `swapCount` INTEGER, `attempts` INTEGER NOT NULL DEFAULT 0, `qBase` INTEGER, `qStep` INTEGER, `sBase` INTEGER, `sStep` INTEGER, `copyOwner` TEXT, `songsOnce` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`listId`))",
    "CREATE INDEX IF NOT EXISTS `index_queue_list_queueId` ON `queue_list` (`queueId`)",
    "CREATE TABLE IF NOT EXISTS `queue_pending` (`listId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `op` TEXT NOT NULL, `body` TEXT NOT NULL, PRIMARY KEY(`listId`, `seq`))",
    "CREATE TABLE IF NOT EXISTS `queue_account` (`account` TEXT NOT NULL, `queueId` INTEGER NOT NULL, `currentEntry` INTEGER, `positionMs` INTEGER NOT NULL, `repeatMode` INTEGER NOT NULL, `shuffle` INTEGER NOT NULL, `floorQ` INTEGER, `floorS` INTEGER, `healedAt` INTEGER, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`account`))",
    "CREATE TABLE IF NOT EXISTS `queue_meta` (`id` INTEGER NOT NULL, `nextQueueId` INTEGER NOT NULL, `nextListId` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    // a list screen's or a queue fill's order rows
    "CREATE TABLE IF NOT EXISTS `lists_folder_order` (`owner` TEXT NOT NULL, `lead` BLOB NOT NULL, `ties` BLOB NOT NULL, `trackId` TEXT NOT NULL, PRIMARY KEY(`owner`, `trackId`))",
    "CREATE INDEX IF NOT EXISTS `index_lists_folder_order` ON `lists_folder_order` (`owner`, `lead`, `ties`)",
    "CREATE TABLE IF NOT EXISTS `lists_sort_copy` (`owner` TEXT NOT NULL, `segment` INTEGER NOT NULL, `lead` INTEGER NOT NULL, `leadKey` INTEGER NOT NULL, `liked` INTEGER NOT NULL, `tieKey` INTEGER NOT NULL, `sortKey` TEXT NOT NULL, `trackId` TEXT NOT NULL, `titleKey` BLOB NOT NULL, `sourceId` TEXT NOT NULL, `serverId` TEXT NOT NULL, PRIMARY KEY(`owner`, `segment`, `leadKey`, `tieKey`, `sortKey`, `titleKey`, `trackId`, `sourceId`, `serverId`))",
    "CREATE INDEX IF NOT EXISTS `index_lists_sort_copy_track` ON `lists_sort_copy` (`owner`, `trackId`)",
    "CREATE TABLE IF NOT EXISTS `lists_album_entry` (`owner` TEXT NOT NULL, `position` INTEGER NOT NULL, `songId` TEXT NOT NULL, `shownId` TEXT NOT NULL, `isFind` INTEGER NOT NULL, `albumOrder` INTEGER, `trackNo` INTEGER, `discNo` INTEGER, PRIMARY KEY(`owner`, `position`))",
    "CREATE INDEX IF NOT EXISTS `index_lists_album_entry_shown` ON `lists_album_entry` (`owner`, `shownId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_lists_album_entry_lib` ON `lists_album_entry` (`owner`, `albumOrder`, `shownId`)",
    "CREATE INDEX IF NOT EXISTS `index_lists_album_entry_shownId` ON `lists_album_entry` (`shownId`)",
    "CREATE TABLE IF NOT EXISTS `lists_server_dir` (`owner` TEXT NOT NULL, `position` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `coverArt` TEXT, `songCount` INTEGER, `trackId` TEXT, `lead` BLOB NOT NULL, `ties` BLOB NOT NULL, `relinkKey` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`owner`, `position`))",
    "CREATE INDEX IF NOT EXISTS `index_lists_server_dir_track` ON `lists_server_dir` (`owner`, `trackId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_lists_server_dir_order` ON `lists_server_dir` (`owner`, `kind`, `lead`, `ties`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_lists_server_dir_trackId` ON `lists_server_dir` (`trackId`)",
    // a server list's rows, by owner
    "CREATE TABLE IF NOT EXISTS `server_entry` (`owner` TEXT NOT NULL, $SERVER_ENTRY_COLUMNS, PRIMARY KEY(`owner`, `position`))",
    "CREATE INDEX IF NOT EXISTS `index_server_entry_owner_songId_position` ON `server_entry` (`owner`, `songId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_server_entry_owner_trackId_position` ON `server_entry` (`owner`, `trackId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_server_entry_owner_kind_songId_position` ON `server_entry` (`owner`, `kind`, `songId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_server_entry_trackId` ON `server_entry` (`trackId`)",
    "CREATE TABLE IF NOT EXISTS `server_key` (`owner` TEXT NOT NULL, `key` TEXT NOT NULL, PRIMARY KEY(`owner`, `key`))",
    "CREATE TABLE IF NOT EXISTS `server_match` (`id` INTEGER NOT NULL, `owner` TEXT NOT NULL, $SERVER_MATCH_COLUMNS, PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_server_match_owner_title_artist_lengthMs` ON `server_match` (`owner`, `title`, `artist`, `lengthMs`)",
    "CREATE INDEX IF NOT EXISTS `index_server_match_trackId` ON `server_match` (`trackId`)",
    "CREATE TABLE IF NOT EXISTS `server_lookup` (`owner` TEXT NOT NULL, `lookupKey` TEXT NOT NULL, `groupId` INTEGER NOT NULL, PRIMARY KEY(`owner`, `lookupKey`, `groupId`))",
    "CREATE TABLE IF NOT EXISTS `server_head` (`owner` TEXT NOT NULL, `kind` TEXT NOT NULL, `listId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`artist` TEXT, `artistId` TEXT, `year` INTEGER, `songCount` INTEGER, `cover` TEXT, `cut` INTEGER NOT NULL DEFAULT 0, " +
        "`complete` INTEGER NOT NULL DEFAULT 0, `readSeq` INTEGER NOT NULL, `swapCount` INTEGER NOT NULL, `currentAt` INTEGER, " +
        "`positionMs` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`owner`))",
    "CREATE INDEX IF NOT EXISTS `index_server_head_list` ON `server_head` (`kind`, `listId`, `readSeq`)",
    // a live list's picks, by owner
    "CREATE TABLE IF NOT EXISTS `live_song` (`owner` TEXT NOT NULL, `gen` INTEGER NOT NULL, `orderKey` BLOB NOT NULL, `trackId` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`owner`, `gen`, `orderKey`))",
    "CREATE INDEX IF NOT EXISTS `index_live_song_track` ON `live_song` (`owner`, `gen`, `trackId`)",
    "CREATE TABLE IF NOT EXISTS `live_state` (`owner` TEXT NOT NULL, `listId` TEXT, `gen` INTEGER NOT NULL, `queryHash` TEXT NOT NULL, `count` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `usedAt` INTEGER NOT NULL, `swapCount` INTEGER NOT NULL, PRIMARY KEY(`owner`))",
    // Library health's report and a fix's undo
    "CREATE TABLE IF NOT EXISTS `health_run` (`id` INTEGER NOT NULL, `gen` INTEGER NOT NULL, `building` INTEGER NOT NULL, `duplicates` INTEGER NOT NULL, `splitAlbums` INTEGER NOT NULL, `noLength` INTEGER NOT NULL, `noTrackNumber` INTEGER NOT NULL, `noAlbumArtist` INTEGER NOT NULL, `noCover` INTEGER NOT NULL, `noYear` INTEGER NOT NULL, `noGenre` INTEGER NOT NULL, `checked` INTEGER NOT NULL, `swapCount` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `health_song` (`owner` TEXT NOT NULL, `gen` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `mask` INTEGER NOT NULL, `k1` BLOB NOT NULL, `k2` BLOB NOT NULL, `albumId` TEXT NOT NULL, `disc` INTEGER NOT NULL, `trackKey` INTEGER NOT NULL, `k3` BLOB NOT NULL, PRIMARY KEY(`owner`, `gen`, `trackId`))",
    "CREATE INDEX IF NOT EXISTS `index_health_song_order` ON `health_song` (`owner`, `gen`, `k1`, `k2`, `albumId`, `disc`, `trackKey`, `k3`, `trackId`)",
    "CREATE TABLE IF NOT EXISTS `health_tag` (`gen` INTEGER NOT NULL, `key` TEXT NOT NULL, `firstNode` INTEGER NOT NULL, `firstSeconds` INTEGER NOT NULL, PRIMARY KEY(`gen`, `key`))",
    "CREATE TABLE IF NOT EXISTS `health_node` (`gen` INTEGER NOT NULL, `node` INTEGER NOT NULL, `parent` INTEGER NOT NULL, `size` INTEGER NOT NULL, `tagged` INTEGER NOT NULL, PRIMARY KEY(`gen`, `node`))",
    "CREATE TABLE IF NOT EXISTS `health_member` (`owner` TEXT NOT NULL, `gen` INTEGER NOT NULL, `root` INTEGER NOT NULL, `sound` INTEGER NOT NULL, `tags` INTEGER NOT NULL, `copyId` TEXT NOT NULL, `trackId` TEXT NOT NULL, PRIMARY KEY(`owner`, `gen`, `root`, `copyId`))",
    "CREATE INDEX IF NOT EXISTS `index_health_member_order` ON `health_member` (`owner` ASC, `gen` ASC, `root` ASC, `sound` DESC, `tags` DESC, `copyId` ASC)",
    "CREATE INDEX IF NOT EXISTS `index_health_member_track` ON `health_member` (`owner`, `gen`, `trackId`)",
    "CREATE TABLE IF NOT EXISTS `health_group` (`owner` TEXT NOT NULL, `gen` INTEGER NOT NULL, `k1` BLOB NOT NULL, `k2` BLOB NOT NULL, `bestId` TEXT NOT NULL, `root` INTEGER NOT NULL, `size` INTEGER NOT NULL, `basis` TEXT NOT NULL, `reason` TEXT NOT NULL, `songs` INTEGER NOT NULL, PRIMARY KEY(`owner`, `gen`, `root`))",
    "CREATE INDEX IF NOT EXISTS `index_health_group_order` ON `health_group` (`owner`, `gen`, `k1`, `k2`, `bestId`)",
    "CREATE TABLE IF NOT EXISTS `health_quality` (`gen` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `text` TEXT NOT NULL, PRIMARY KEY(`gen`, `trackId`))",
    "CREATE TABLE IF NOT EXISTS `health_part` (`gen` INTEGER NOT NULL, `albumId` TEXT NOT NULL, `bucket` TEXT NOT NULL, `firstKey` TEXT NOT NULL, `firstId` TEXT NOT NULL, `size` INTEGER NOT NULL, `artist` TEXT NOT NULL, `artistKey` TEXT NOT NULL, `albumArtists` TEXT NOT NULL, `years` TEXT NOT NULL, PRIMARY KEY(`gen`, `albumId`))",
    "CREATE INDEX IF NOT EXISTS `index_health_part_bucket` ON `health_part` (`gen`, `bucket`, `firstKey`, `firstId`)",
    "CREATE TABLE IF NOT EXISTS `health_code` (`gen` INTEGER NOT NULL, `albumId` TEXT NOT NULL, `kind` INTEGER NOT NULL, `code` TEXT NOT NULL, PRIMARY KEY(`gen`, `albumId`, `kind`, `code`))",
    "CREATE INDEX IF NOT EXISTS `index_health_code_code` ON `health_code` (`gen`, `kind`, `code`)",
    "CREATE TABLE IF NOT EXISTS `health_tally` (`gen` INTEGER NOT NULL, `albumId` TEXT NOT NULL, `value` TEXT NOT NULL, `n` INTEGER NOT NULL, PRIMARY KEY(`gen`, `albumId`, `value`))",
    "CREATE TABLE IF NOT EXISTS `health_split` (`owner` TEXT NOT NULL, `gen` INTEGER NOT NULL, `k1` BLOB NOT NULL, `k2` BLOB NOT NULL, `leadAlbum` TEXT NOT NULL, `setId` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `reasons` TEXT NOT NULL, `differences` TEXT NOT NULL, `linesBefore` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`owner`, `gen`, `setId`))",
    "CREATE INDEX IF NOT EXISTS `index_health_split_order` ON `health_split` (`owner`, `gen`, `k1`, `k2`, `leadAlbum`)",
    "CREATE INDEX IF NOT EXISTS `index_health_split_place` ON `health_split` (`owner`, `gen`, `linesBefore`)",
    "CREATE TABLE IF NOT EXISTS `health_split_song` (`owner` TEXT NOT NULL, `gen` INTEGER NOT NULL, `setId` INTEGER NOT NULL, `part` INTEGER NOT NULL, `disc` INTEGER NOT NULL, `trackKey` INTEGER NOT NULL, `titleKey` BLOB NOT NULL, `trackId` TEXT NOT NULL, PRIMARY KEY(`owner`, `gen`, `setId`, `part`, `disc`, `trackKey`, `titleKey`, `trackId`))",
    "CREATE INDEX IF NOT EXISTS `index_health_split_song_track` ON `health_split_song` (`owner`, `gen`, `trackId`)",
    "CREATE TABLE IF NOT EXISTS `fix_undo` (`owner` TEXT NOT NULL, `ord` INTEGER NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, `title` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`owner`, `ord`))",
    // a backup's or a playlist file's songs matched against the library
    "CREATE TABLE IF NOT EXISTS `match_query` (`owner` TEXT NOT NULL, `id` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `list` INTEGER NOT NULL, `value` INTEGER NOT NULL, `relinkKey` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `wholeKey` TEXT NOT NULL, `nameKey` TEXT NOT NULL, `parts` TEXT NOT NULL, `relinkId` TEXT, `wholeId` TEXT, `wholeDist` INTEGER, `titleId` TEXT, `titleDist` INTEGER, `pathId` TEXT, `pathEnd` INTEGER NOT NULL DEFAULT 0, `foundId` TEXT, `pos` INTEGER, `skip` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`owner`, `id`))",
    "CREATE INDEX IF NOT EXISTS `index_match_query_relinkKey` ON `match_query` (`owner`, `relinkKey`)",
    "CREATE INDEX IF NOT EXISTS `index_match_query_wholeKey` ON `match_query` (`owner`, `wholeKey`)",
    "CREATE INDEX IF NOT EXISTS `index_match_query_nameKey` ON `match_query` (`owner`, `nameKey`)",
    "CREATE INDEX IF NOT EXISTS `index_match_query_found` ON `match_query` (`owner`, `kind`, `foundId`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_match_query_list` ON `match_query` (`owner`, `kind`, `list`, `id`)",
    "CREATE TABLE IF NOT EXISTS `match_title` (`owner` TEXT NOT NULL, `query` INTEGER NOT NULL, `rank` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `lengthMs` INTEGER NOT NULL, `versions` TEXT NOT NULL, `hasArtist` INTEGER NOT NULL, `p1Id` TEXT, `p1Rank` INTEGER, `p2Id` TEXT, `p2Rank` INTEGER, `soleId` TEXT, `soleCount` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`owner`, `query`, `rank`))",
    "CREATE TABLE IF NOT EXISTS `match_lookup` (`owner` TEXT NOT NULL, `key` TEXT NOT NULL, `target` INTEGER NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`owner`, `key`, `target`))",
    // a save's find, its sets and groups, and the renames in force
    "CREATE TABLE IF NOT EXISTS `save_found` (`seq` INTEGER NOT NULL, `tbl` TEXT NOT NULL, `op` INTEGER NOT NULL, `id` TEXT NOT NULL, `oldId` TEXT, " +
        "`newId` TEXT, `seenRating` INTEGER, `newRating` INTEGER, `cost` INTEGER NOT NULL, `setId` INTEGER, `groupId` INTEGER, PRIMARY KEY(`seq`))",
    "CREATE INDEX IF NOT EXISTS `index_save_found_groupId_tbl_op_id` ON `save_found` (`groupId`, `tbl`, `op`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_save_found_setId` ON `save_found` (`setId`)",
    "CREATE INDEX IF NOT EXISTS `index_save_found_tbl_id` ON `save_found` (`tbl`, `id`)",
    "CREATE TABLE IF NOT EXISTS `save_ref` (`seq` INTEGER NOT NULL, `tbl` TEXT NOT NULL, `kind` TEXT NOT NULL, `keyText` TEXT NOT NULL, " +
        "`keyNum` INTEGER NOT NULL, `keyNum2` INTEGER NOT NULL, `oldId` TEXT NOT NULL, `relinkKey` TEXT NOT NULL, `target` TEXT, " +
        "`setId` INTEGER, `groupId` INTEGER, PRIMARY KEY(`seq`))",
    "CREATE INDEX IF NOT EXISTS `index_save_ref_groupId_tbl_seq` ON `save_ref` (`groupId`, `tbl`, `seq`)",
    "CREATE INDEX IF NOT EXISTS `index_save_ref_setId` ON `save_ref` (`setId`)",
    "CREATE TABLE IF NOT EXISTS `save_node` (`node` INTEGER NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, `parent` INTEGER NOT NULL, " +
        "`size` INTEGER NOT NULL, PRIMARY KEY(`node`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_save_node_kind_id` ON `save_node` (`kind`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_save_node_parent_node` ON `save_node` (`parent`, `node`)",
    "CREATE TABLE IF NOT EXISTS `save_set` (`setId` INTEGER NOT NULL, `cost` INTEGER NOT NULL, `groupId` INTEGER, PRIMARY KEY(`setId`))",
    "CREATE INDEX IF NOT EXISTS `index_save_set_groupId_setId` ON `save_set` (`groupId`, `setId`)",
    "CREATE TABLE IF NOT EXISTS `save_rename` (`kind` TEXT NOT NULL, `old` TEXT NOT NULL, `new` TEXT NOT NULL, `byKey` INTEGER NOT NULL, " +
        "`key` TEXT NOT NULL, PRIMARY KEY(`kind`, `old`))",
    "CREATE INDEX IF NOT EXISTS `index_save_rename_kind_new` ON `save_rename` (`kind`, `new`)",
)

// A server listing's song as a playlist sync read or a server list holds
// it, after the column that names the read or the owner.
private const val SERVER_ENTRY_COLUMNS =
    "`position` INTEGER NOT NULL, `songId` TEXT NOT NULL, `external` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
        "`artist` TEXT NOT NULL, `lengthMs` INTEGER NOT NULL, `album` TEXT NOT NULL, `albumId` TEXT, `artistId` TEXT, " +
        "`coverId` TEXT, `mimeType` TEXT, `bitrate` INTEGER, `explicit` INTEGER, `trackId` TEXT, " +
        "`kind` INTEGER NOT NULL DEFAULT 0, `groupId` INTEGER, `firstFind` INTEGER NOT NULL DEFAULT 0, " +
        "`held` INTEGER NOT NULL DEFAULT 0, `relinkKey` TEXT NOT NULL DEFAULT ''"

private const val SERVER_MATCH_COLUMNS =
    "`title` TEXT NOT NULL, `artist` TEXT NOT NULL, `lengthMs` INTEGER NOT NULL, `trackId` TEXT, `relinkKey` TEXT NOT NULL DEFAULT ''"
