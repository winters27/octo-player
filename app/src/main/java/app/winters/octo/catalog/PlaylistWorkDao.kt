package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

// Rows a playlist sync step works through: a server playlist as one read
// listed it, matched against the library, and the lists a write-back
// builds, so no step holds a playlist in memory. Only the step holding
// PlaylistSync's lock uses them; each step empties them as it starts and as
// it ends, and every open of the database empties them too.

// One song of a server playlist as one read listed it, in its order, with
// the library song it resolved to, found by that song's id too when the id
// moves.
@Entity(
    tableName = "playlist_entry",
    primaryKeys = ["read", "position"],
    indices = [
        Index("read", "songId", "position"), Index("read", "trackId", "position"), Index("read", "kind", "songId", "position"),
        Index("trackId"),
    ],
)
data class PlaylistEntryEntity(
    val read: Long,
    val position: Long,
    val songId: String,
    val external: Boolean,
    val title: String,
    val artist: String,
    val lengthMs: Long,
    val album: String,
    val albumId: String?,
    val artistId: String?,
    val coverId: String?,
    val mimeType: String?,
    val bitrate: Int?,
    val explicit: Boolean?,
    val trackId: String?,
    // 0 not resolved yet, 1 a library song, 2 waiting, 3 a find.
    @ColumnInfo(defaultValue = "0") val kind: Int = 0,
    val groupId: Long?,
    @ColumnInfo(defaultValue = "0") val firstFind: Boolean = false,
    @ColumnInfo(defaultValue = "0") val held: Boolean = false,
    @ColumnInfo(defaultValue = "") val relinkKey: String = "",
)

// The song keys a read looks up in the library.
@Entity(tableName = "playlist_key", primaryKeys = ["read", "key"])
data class PlaylistKeyEntity(val read: Long, val key: String)

// One title, artist and length a read asked the library about, and the
// song it matched.
@Entity(tableName = "playlist_match", indices = [Index("read", "title", "artist", "lengthMs", unique = true), Index("trackId")])
data class PlaylistMatchEntity(
    @PrimaryKey val id: Long,
    val read: Long,
    val title: String,
    val artist: String,
    val lengthMs: Long,
    val trackId: String?,
    @ColumnInfo(defaultValue = "") val relinkKey: String = "",
)

// Which matches a lookup key leads to, with each match's length (0 when
// not known), so a song is offered only the matches whose lengths can
// agree with its own.
@Entity(
    tableName = "playlist_lookup",
    primaryKeys = ["read", "lookupKey", "groupId"],
    indices = [Index("read", "lookupKey", "lengthMs", "groupId", name = "index_playlist_lookup_length")],
)
data class PlaylistLookupEntity(val read: Long, val lookupKey: String, val groupId: Long, @ColumnInfo(defaultValue = "0") val lengthMs: Long = 0)

// The phone's rows of a playlist as a write-back read them.
@Entity(
    tableName = "playlist_local",
    primaryKeys = ["itemPosition", "itemKey"],
    indices = [Index("anchor", "itemPosition", "itemKey"), Index("trackId", "itemPosition", "itemKey"), Index("trackId", "rank")],
)
data class PlaylistLocalEntity(
    val itemPosition: Long,
    val itemKey: Long,
    val trackId: String,
    val serverSongId: String?,
    val anchor: Long?,
    @ColumnInfo(defaultValue = "0") val rank: Int = 0,
    @ColumnInfo(defaultValue = "") val relinkKey: String = "",
    @ColumnInfo(defaultValue = "0") val taken: Boolean = false,
)

// The rows a write-back gives the playlist, in order, with the key each
// kept row keeps.
@Entity(tableName = "playlist_next", indices = [Index("trackId", "position")])
data class PlaylistNextEntity(
    @PrimaryKey val position: Long,
    val trackId: String,
    val serverSongId: String?,
    val relinkKey: String,
    @ColumnInfo(defaultValue = "0") val rank: Int = 0,
    val keptKey: Long?,
)

// The two lists a family write-back compares: what to send (slot 0) and
// the phone's (slot 1).
@Entity(tableName = "playlist_send", primaryKeys = ["slot", "position"], indices = [Index("slot", "songId", "position")])
data class PlaylistSendEntity(
    val slot: Int,
    val position: Long,
    val songId: String,
    @ColumnInfo(defaultValue = "0") val rank: Int = 0,
    @ColumnInfo(defaultValue = "0") val added: Boolean = false,
)

// The removals (kind 0) and adds (kind 1) a family write-back sends.
@Entity(tableName = "playlist_change", primaryKeys = ["kind", "position"], indices = [Index("kind", "songId", "position")])
data class PlaylistChangeEntity(
    val kind: Int,
    val position: Long,
    val songId: String,
    @ColumnInfo(defaultValue = "0") val copy: Int = 0,
    @ColumnInfo(defaultValue = "0") val done: Boolean = false,
)

// A walk of a playlist's songs that spans more than one transaction keeps
// the item set it started on here, one row per walker and playlist, so the
// set is not deleted under it.
@Entity(
    tableName = "playlist_set_pin",
    primaryKeys = ["owner", "playlistId"],
    indices = [Index("playlistId", "itemSet", name = "index_playlist_set_pin_set")],
)
data class PlaylistSetPinEntity(val owner: String, val playlistId: String, val itemSet: Long)

// Each move (op 0), removal (op 1) and putting back (op 2) of a row in a
// set some walk holds, with the row as it was just before, so a walk can
// read the set as it stood when the walk began.
@Entity(
    tableName = "playlist_edit_log",
    indices = [
        Index("playlistId", "itemSet", "itemKey", "seq", name = "index_playlist_edit_log_key"),
        Index("playlistId", "itemSet", "ord", "itemKey", "seq", name = "index_playlist_edit_log_ord"),
        Index("trackId", name = "index_playlist_edit_log_trackId"),
    ],
)
data class PlaylistEditLogEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val playlistId: String,
    val itemSet: Long,
    val op: Int,
    val itemKey: Long,
    val ord: Long,
    val trackId: String,
    val relinkKey: String,
    val serverSongId: String?,
)

// The slots of playlist_send: the songs to send, and the phone's songs as
// the server knows them.
const val SEND_SLOT = 0
const val PHONE_SLOT = 1

// The kinds of playlist_change: a song to take out, a song to add.
const val REMOVE_KIND = 0
const val ADD_KIND = 1

// An entry as the links step reads it.
data class EntryHead(val position: Long, val songId: String, val external: Boolean, val title: String, val artist: String, val lengthMs: Long)

// An entry as the steps after resolution read it.
data class EntryRow(val position: Long, val songId: String, val trackId: String?, val held: Boolean)

// An entry as a new playlist's rows are written from it.
data class EntryItem(val position: Long, val songId: String, val trackId: String, val relinkKey: String)

// A library song a pool page offers, under the pool key it was found by.
data class PoolTrack(val poolKey: String, val id: String, val title: String, val artist: String, val durationMs: Long, val relinkKey: String)

// A match group still open.
data class MatchGroup(val id: Long, val title: String, val artist: String, val lengthMs: Long)

// A song id at a place.
data class SongAt(val songId: String, val position: Long)

// A library song and its relink key.
data class TrackKey(val id: String, val relinkKey: String)

// A library song's copy on a server.
data class CopyOf(val mergedId: String, val nativeId: String)

// A local row as the walks read it.
data class LocalRow(val itemPosition: Long, val itemKey: Long, val trackId: String, val serverSongId: String?, val relinkKey: String)

// A phone-only local row with the entry it follows.
data class AnchoredRow(val anchor: Long, val itemPosition: Long, val itemKey: Long, val trackId: String, val serverSongId: String?)

// A local row's place.
data class LocalAt(val itemPosition: Long, val itemKey: Long)

// A next row's song at its place.
data class TrackAt(val trackId: String, val position: Long)

// A change row.
data class ChangeRow(val position: Long, val songId: String, val copy: Int, val done: Boolean)

// A song and how many times a list holds it.
data class SongCount(val songId: String, val n: Int)

// A base row.
data class BaseRow(val songId: String, val copies: Int)

// A phone playlist that may be a server playlist's twin.
data class TwinRow(val id: String, val updatedAt: Long)

// Every statement a playlist sync step runs on its work tables, and the
// library and base reads it makes between them. Each reads or writes at
// most one page.
@Dao
interface PlaylistWorkDao {
    // The stream

    @Insert
    suspend fun insertEntries(rows: List<PlaylistEntryEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_entry WHERE read = :read)")
    suspend fun hasEntries(read: Long): Boolean

    @Query("SELECT position, songId, external, title, artist, lengthMs FROM playlist_entry WHERE read = :read AND position >= :from ORDER BY position LIMIT :limit")
    suspend fun entryHeads(read: Long, from: Long, limit: Int): List<EntryHead>

    @Query("SELECT position, songId, trackId, held FROM playlist_entry WHERE read = :read AND position > :after ORDER BY position LIMIT :limit")
    suspend fun entryRows(read: Long, after: Long, limit: Int): List<EntryRow>

    @Query(ENTRY_ITEMS_SQL)
    suspend fun entryItems(read: Long, from: Long, limit: Int): List<EntryItem>

    @Query("SELECT songId FROM playlist_entry WHERE read = :read AND position > :after ORDER BY position LIMIT :limit")
    suspend fun entrySongs(read: Long, after: Long, limit: Int): List<String>

    @Query("DELETE FROM playlist_entry WHERE read = :read AND position IN (SELECT position FROM playlist_entry WHERE read = :read LIMIT :limit)")
    suspend fun deleteEntries(read: Long, limit: Int): Int

    // Resolution

    @Query("UPDATE playlist_entry SET kind = 1, trackId = :trackId, relinkKey = :relinkKey WHERE read = :read AND position = :position")
    suspend fun setLinked(read: Long, position: Long, trackId: String, relinkKey: String)

    @Query("UPDATE playlist_entry SET kind = 2, groupId = :groupId WHERE read = :read AND position = :position")
    suspend fun setWaiting(read: Long, position: Long, groupId: Long)

    @Query("SELECT id FROM playlist_match WHERE read = :read AND title = :title AND artist = :artist AND lengthMs = :lengthMs")
    suspend fun matchGroup(read: Long, title: String, artist: String, lengthMs: Long): Long?

    @Query("INSERT INTO playlist_match (read, title, artist, lengthMs, trackId, relinkKey) VALUES (:read, :title, :artist, :lengthMs, NULL, '')")
    suspend fun insertMatch(read: Long, title: String, artist: String, lengthMs: Long)

    @Query("INSERT OR IGNORE INTO playlist_lookup (read, lookupKey, groupId, lengthMs) VALUES (:read, :lookupKey, :groupId, :lengthMs)")
    suspend fun insertLookup(read: Long, lookupKey: String, groupId: Long, lengthMs: Long)

    @Query("INSERT OR IGNORE INTO playlist_key (read, `key`) VALUES (:read, :key)")
    suspend fun insertKey(read: Long, key: String)

    @Query(POOL_PAGE_SQL)
    suspend fun poolPage(read: Long, lastKey: String, lastId: String, limit: Int): List<PoolTrack>

    @Query(OPEN_GROUPS_NEAR_SQL)
    suspend fun openGroupsNear(read: Long, lookupKey: String, low: Long, high: Long, afterLength: Long, after: Long, limit: Int): List<MatchGroup>

    @Query("UPDATE playlist_match SET trackId = :trackId, relinkKey = :relinkKey WHERE id = :id AND trackId IS NULL")
    suspend fun placeGroup(id: Long, trackId: String, relinkKey: String): Int

    @Query("DELETE FROM playlist_lookup WHERE read = :read AND lookupKey = :lookupKey AND groupId = :groupId")
    suspend fun deleteLookup(read: Long, lookupKey: String, groupId: Long)

    @Query(SETTLE_SQL)
    suspend fun settle(read: Long, from: Long, to: Long)

    @Query(FINDS_BY_SONG_SQL)
    suspend fun findsBySong(read: Long, afterSong: String, afterPosition: Long, limit: Int): List<SongAt>

    @Query("UPDATE playlist_entry SET firstFind = 1 WHERE read = :read AND position IN (:positions)")
    suspend fun markFirstFinds(read: Long, positions: List<Long>)

    @Query("SELECT * FROM playlist_entry WHERE read = :read AND position > :after AND firstFind = 1 ORDER BY position LIMIT :limit")
    suspend fun firstFinds(read: Long, after: Long, limit: Int): List<PlaylistEntryEntity>

    // The library, a page at a time

    @Query("SELECT id, relinkKey FROM track WHERE id IN (:ids)")
    suspend fun trackKeys(ids: List<String>): List<TrackKey>

    @Query("SELECT EXISTS(SELECT 1 FROM track WHERE id = :id)")
    suspend fun trackExists(id: String): Boolean

    @Query(COPIES_ON_SQL)
    suspend fun copiesOn(sourceId: String, trackIds: List<String>): List<CopyOf>

    // The walks

    @Query("SELECT MIN(position) FROM playlist_entry WHERE read = :read AND trackId = :trackId")
    suspend fun firstPositionOf(read: Long, trackId: String): Long?

    @Query("SELECT position FROM playlist_entry WHERE read = :read AND songId = :songId ORDER BY position DESC LIMIT 1 OFFSET :skip")
    suspend fun positionFromEnd(read: Long, songId: String, skip: Int): Long?

    @Query(
        "UPDATE playlist_entry SET held = 1 WHERE read = :read AND position IN " +
            "(SELECT position FROM playlist_entry WHERE read = :read AND songId = :songId AND position >= :from AND held = 0 LIMIT :limit)",
    )
    suspend fun markHeld(read: Long, songId: String, from: Long, limit: Int): Int

    @Query("SELECT COUNT(*) FROM playlist_entry WHERE read = :read AND songId = :songId")
    suspend fun entryCountOf(read: Long, songId: String): Int

    @Query("SELECT COUNT(*) FROM playlist_entry WHERE read = :read AND songId = :songId AND position > :after AND position <= :upTo")
    suspend fun entriesBetween(read: Long, songId: String, after: Long, upTo: Long): Int

    @Query("SELECT position FROM playlist_entry WHERE read = :read AND songId = :songId AND position < :before ORDER BY position DESC LIMIT :limit")
    suspend fun lastPositionsBefore(read: Long, songId: String, before: Long, limit: Int): List<Long>

    @Insert
    suspend fun insertLocal(rows: List<PlaylistLocalEntity>)

    @Query(LOCAL_PAGE_SQL)
    suspend fun localPage(afterPosition: Long, afterKey: Long, limit: Int): List<LocalRow>

    @Query("UPDATE playlist_local SET anchor = :anchor WHERE itemPosition = :itemPosition AND itemKey = :itemKey")
    suspend fun setAnchor(itemPosition: Long, itemKey: Long, anchor: Long)

    @Query(ANCHORED_PAGE_SQL)
    suspend fun anchoredPage(afterAnchor: Long, afterPosition: Long, afterKey: Long, limit: Int): List<AnchoredRow>

    @Insert
    suspend fun insertNext(rows: List<PlaylistNextEntity>)

    @Query("SELECT * FROM playlist_next WHERE position >= :from ORDER BY position LIMIT :limit")
    suspend fun nextPage(from: Long, limit: Int): List<PlaylistNextEntity>

    @Query(NEXT_BY_TRACK_SQL)
    suspend fun nextByTrack(afterTrack: String, afterPosition: Long, limit: Int): List<TrackAt>

    @Query(NEXT_UNTAKEN_SQL)
    suspend fun nextUntaken(trackId: String, afterPosition: Long, afterKey: Long): LocalAt?

    @Query("UPDATE playlist_local SET taken = 1 WHERE itemPosition = :itemPosition AND itemKey = :itemKey")
    suspend fun take(itemPosition: Long, itemKey: Long)

    @Query("UPDATE playlist_next SET keptKey = :keptKey WHERE position = :position")
    suspend fun keep(position: Long, keptKey: Long)

    @Insert
    suspend fun insertSend(rows: List<PlaylistSendEntity>)

    @Query("SELECT songId FROM playlist_send WHERE slot = :slot AND position >= :from ORDER BY position LIMIT :limit")
    suspend fun sendPage(slot: Int, from: Long, limit: Int): List<String>

    // The same, for a request body being written on OkHttp's thread.
    @Query("SELECT songId FROM playlist_send WHERE slot = :slot AND position >= :from ORDER BY position LIMIT :limit")
    fun sendPageNow(slot: Int, from: Long, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM playlist_send WHERE slot = :slot")
    suspend fun sendCount(slot: Int): Int

    @Query("SELECT COUNT(*) FROM playlist_send WHERE slot = 1 AND songId = :songId")
    suspend fun phoneCountOf(songId: String): Int

    @Query(PHONE_BY_SONG_SQL)
    suspend fun phoneBySong(afterSong: String, afterPosition: Long, limit: Int): List<SongAt>

    @Query("UPDATE playlist_send SET added = 1 WHERE slot = 1 AND position IN (:positions)")
    suspend fun markAdded(positions: List<Long>)

    @Query("SELECT songId, position FROM playlist_send WHERE slot = 1 AND added = 1 AND position > :after ORDER BY position LIMIT :limit")
    suspend fun addedPage(after: Long, limit: Int): List<SongAt>

    @Query("SELECT songId, position FROM playlist_send WHERE slot = :slot AND position > :after AND instr(songId, char(10)) > 0 ORDER BY position LIMIT :limit")
    suspend fun oddSends(slot: Int, after: Long, limit: Int): List<SongAt>

    @Query("SELECT songId, position FROM playlist_entry WHERE read = :read AND position > :after AND instr(songId, char(10)) > 0 ORDER BY position LIMIT :limit")
    suspend fun oddEntries(read: Long, after: Long, limit: Int): List<SongAt>

    @Insert
    suspend fun insertChanges(rows: List<PlaylistChangeEntity>)

    @Query("SELECT COUNT(*) FROM playlist_change WHERE kind = :kind")
    suspend fun changeCount(kind: Int): Int

    @Query("SELECT position, songId, copy, done FROM playlist_change WHERE kind = :kind AND position > :after ORDER BY position LIMIT :limit")
    suspend fun changesUp(kind: Int, after: Long, limit: Int): List<ChangeRow>

    @Query("SELECT position, songId, copy, done FROM playlist_change WHERE kind = :kind AND position < :before ORDER BY position DESC LIMIT :limit")
    suspend fun changesDown(kind: Int, before: Long, limit: Int): List<ChangeRow>

    // The same as changesUp, for a request body being written on OkHttp's thread.
    @Query("SELECT position, songId, copy, done FROM playlist_change WHERE kind = :kind AND position > :after ORDER BY position LIMIT :limit")
    fun changesUpNow(kind: Int, after: Long, limit: Int): List<ChangeRow>

    @Query("SELECT songId FROM playlist_change WHERE kind = :kind AND position IN (:positions)")
    suspend fun changesAt(kind: Int, positions: List<Long>): List<String>

    @Query(CHANGES_BY_SONG_SQL)
    suspend fun changesBySong(kind: Int, afterSong: String, afterPosition: Long, limit: Int): List<SongAt>

    @Query("UPDATE playlist_change SET copy = :copy WHERE kind = :kind AND position = :position")
    suspend fun setCopy(kind: Int, position: Long, copy: Int)

    @Query("UPDATE playlist_change SET done = 1 WHERE kind = :kind AND position IN (:positions)")
    suspend fun markDone(kind: Int, positions: List<Long>)

    @Query("SELECT COUNT(*) FROM playlist_change WHERE kind = :kind AND songId = :songId AND done = 1")
    suspend fun doneCountOf(kind: Int, songId: String): Int

    @Query(CHANGE_GROUPS_SQL)
    suspend fun changeGroups(kind: Int, from: String, limit: Int): List<SongCount>

    @Query("DELETE FROM playlist_change WHERE kind = :kind AND position IN (SELECT position FROM playlist_change WHERE kind = :kind LIMIT :limit)")
    suspend fun deleteChanges(kind: Int, limit: Int): Int

    // The base

    @Query("SELECT songId, copies FROM playlist_synced_song WHERE playlistId = :playlistId AND baseSet = :baseSet AND songId >= :from ORDER BY songId LIMIT :limit")
    suspend fun basePage(playlistId: String, baseSet: Long, from: String, limit: Int): List<BaseRow>

    @Query("SELECT copies FROM playlist_synced_song WHERE playlistId = :playlistId AND baseSet = :baseSet AND songId = :songId")
    suspend fun baseCopies(playlistId: String, baseSet: Long, songId: String): Int?

    @Query("INSERT OR IGNORE INTO playlist_synced_song (playlistId, baseSet, songId, copies) VALUES (:playlistId, :baseSet, :songId, 0)")
    suspend fun ensureBase(playlistId: String, baseSet: Long, songId: String)

    @Query("UPDATE playlist_synced_song SET copies = copies + :n WHERE playlistId = :playlistId AND baseSet = :baseSet AND songId = :songId")
    suspend fun addBase(playlistId: String, baseSet: Long, songId: String, n: Int)

    @Query("DELETE FROM playlist_synced_song WHERE playlistId = :playlistId AND baseSet = :baseSet AND songId = :songId AND copies <= 0")
    suspend fun dropEmptyBase(playlistId: String, baseSet: Long, songId: String)

    @Query("SELECT MAX(songId) FROM playlist_synced_song WHERE playlistId = :playlistId AND baseSet = :baseSet")
    suspend fun lastBaseSong(playlistId: String, baseSet: Long): String?

    @Query(BASE_FROM_ENTRIES_SQL)
    suspend fun baseFromEntries(playlistId: String, baseSet: Long, read: Long, from: String, limit: Int)

    @Query(BASE_FROM_PHONE_SQL)
    suspend fun baseFromPhone(playlistId: String, baseSet: Long, from: String, limit: Int)

    @Query(BASE_COPY_SQL)
    suspend fun copyBase(playlistId: String, fromSet: Long, toSet: Long, from: String, limit: Int)

    // Twins

    @Query("SELECT id, updatedAt FROM playlist WHERE sourceId IS NULL AND name = :name AND itemSet >= 0 ORDER BY rowid")
    suspend fun phoneTwins(name: String): List<TwinRow>

    @Query("SELECT COUNT(*) FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet")
    suspend fun setCount(playlistId: String, itemSet: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_entry WHERE read = :read")
    suspend fun entryCount(read: Long): Int
}

// An entry with the relink key of the library song it resolved to, in
// order, for a new playlist's rows.
internal const val ENTRY_ITEMS_SQL = """
    SELECT e.position AS position, e.songId AS songId, e.trackId AS trackId,
        COALESCE((SELECT t.relinkKey FROM track t WHERE t.id = e.trackId), '') AS relinkKey
    FROM playlist_entry e WHERE e.read = :read AND e.position >= :from ORDER BY e.position LIMIT :limit
"""

// The pool: every library song filed under a key the read looks up, in
// key order, then by id, from just past (`lastKey`, `lastId`).
internal const val POOL_PAGE_SQL = """
    SELECT k.`key` AS poolKey, t.id AS id, t.title AS title, t.artist AS artist, t.durationMs AS durationMs, t.relinkKey AS relinkKey
    FROM playlist_key k CROSS JOIN track t
    WHERE k.read = :read AND k.`key` >= :lastKey AND t.searchKey = k.`key`
      AND t.id > CASE WHEN k.`key` = :lastKey THEN :lastId ELSE '' END
    ORDER BY k.`key`, t.id LIMIT :limit
"""

// A server's copies of these library songs, led by the songs: `+` keeps the
// planner off the indexes that start with the source, which would walk
// every copy the server has.
internal const val COPIES_ON_SQL = "SELECT mergedId, nativeId FROM source_track WHERE +sourceId = :sourceId AND mergedId IN (:trackIds)"

// The open match groups a lookup key leads to whose lengths lie from `low`
// to `high`, by length and then group, from just past (`afterLength`,
// `after`).
internal const val OPEN_GROUPS_NEAR_SQL = """
    SELECT g.id AS id, g.title AS title, g.artist AS artist, g.lengthMs AS lengthMs
    FROM playlist_lookup l INDEXED BY index_playlist_lookup_length CROSS JOIN playlist_match g ON g.id = l.groupId
    WHERE l.read = :read AND l.lookupKey = :lookupKey AND l.lengthMs BETWEEN :low AND :high
      AND (l.lengthMs, l.groupId) > (:afterLength, :after) AND g.trackId IS NULL
    ORDER BY l.lengthMs, l.groupId LIMIT :limit
"""

// Each waiting entry from `from` up to `to` takes its group's song, or
// becomes a find.
internal const val SETTLE_SQL = """
    UPDATE playlist_entry SET
        trackId = COALESCE((SELECT g.trackId FROM playlist_match g WHERE g.id = playlist_entry.groupId), 'find:' || songId),
        relinkKey = COALESCE((SELECT g.relinkKey FROM playlist_match g WHERE g.id = playlist_entry.groupId AND g.trackId IS NOT NULL), ''),
        kind = CASE WHEN (SELECT g.trackId FROM playlist_match g WHERE g.id = playlist_entry.groupId) IS NULL THEN 3 ELSE 1 END
    WHERE read = :read AND position >= :from AND position < :to AND kind = 2
"""

internal const val FINDS_BY_SONG_SQL = """
    SELECT songId, position FROM playlist_entry
    WHERE read = :read AND kind = 3 AND songId >= :afterSong AND CASE WHEN songId = :afterSong THEN position > :afterPosition ELSE 1 END
    ORDER BY songId, position LIMIT :limit
"""

internal const val LOCAL_PAGE_SQL = """
    SELECT itemPosition, itemKey, trackId, serverSongId, relinkKey FROM playlist_local
    WHERE itemPosition >= :afterPosition AND CASE WHEN itemPosition = :afterPosition THEN itemKey > :afterKey ELSE 1 END
    ORDER BY itemPosition, itemKey LIMIT :limit
"""

internal const val ANCHORED_PAGE_SQL = """
    SELECT anchor, itemPosition, itemKey, trackId, serverSongId FROM playlist_local
    WHERE anchor >= :afterAnchor AND CASE WHEN anchor = :afterAnchor
        THEN itemPosition > :afterPosition OR (itemPosition = :afterPosition AND itemKey > :afterKey) ELSE 1 END
    ORDER BY anchor, itemPosition, itemKey LIMIT :limit
"""

internal const val NEXT_BY_TRACK_SQL = """
    SELECT trackId, position FROM playlist_next
    WHERE trackId >= :afterTrack AND CASE WHEN trackId = :afterTrack THEN position > :afterPosition ELSE 1 END
    ORDER BY trackId, position LIMIT :limit
"""

// A song's first local row not yet kept by a new row, past a place.
internal const val NEXT_UNTAKEN_SQL = """
    SELECT itemPosition, itemKey FROM playlist_local
    WHERE trackId = :trackId AND itemPosition >= :afterPosition
      AND CASE WHEN itemPosition = :afterPosition THEN itemKey > :afterKey ELSE 1 END AND taken = 0
    ORDER BY itemPosition, itemKey LIMIT 1
"""

internal const val PHONE_BY_SONG_SQL = """
    SELECT songId, position FROM playlist_send
    WHERE slot = 1 AND songId >= :afterSong AND CASE WHEN songId = :afterSong THEN position > :afterPosition ELSE 1 END
    ORDER BY songId, position LIMIT :limit
"""

internal const val CHANGES_BY_SONG_SQL = """
    SELECT songId, position FROM playlist_change
    WHERE kind = :kind AND songId >= :afterSong AND CASE WHEN songId = :afterSong THEN position > :afterPosition ELSE 1 END
    ORDER BY songId, position LIMIT :limit
"""

// Each song of a kind of change from `from` on, with how many there are.
internal const val CHANGE_GROUPS_SQL = """
    SELECT songId, COUNT(*) AS n FROM playlist_change WHERE kind = :kind AND songId >= :from
    GROUP BY songId ORDER BY songId LIMIT :limit
"""

// A page of a read's songs, each once with how many times the read holds
// it, into a base set from `from` on. An empty id, or one holding a line
// break, is left to the caller (syncedSongsOf's split).
internal const val BASE_FROM_ENTRIES_SQL = """
    INSERT OR IGNORE INTO playlist_synced_song (playlistId, baseSet, songId, copies)
    SELECT :playlistId, :baseSet, songId, COUNT(*) FROM playlist_entry
    WHERE read = :read AND songId >= :from AND songId != '' AND instr(songId, char(10)) = 0
    GROUP BY songId ORDER BY songId LIMIT :limit
"""

internal const val BASE_FROM_PHONE_SQL = """
    INSERT OR IGNORE INTO playlist_synced_song (playlistId, baseSet, songId, copies)
    SELECT :playlistId, :baseSet, songId, COUNT(*) FROM playlist_send
    WHERE slot = 1 AND songId >= :from AND songId != '' AND instr(songId, char(10)) = 0
    GROUP BY songId ORDER BY songId LIMIT :limit
"""

internal const val BASE_COPY_SQL = """
    INSERT OR IGNORE INTO playlist_synced_song (playlistId, baseSet, songId, copies)
    SELECT playlistId, :toSet, songId, copies FROM playlist_synced_song
    WHERE playlistId = :playlistId AND baseSet = :fromSet AND songId >= :from ORDER BY songId LIMIT :limit
"""
