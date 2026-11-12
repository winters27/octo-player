package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import app.winters.octo.ui.paging.SqlValue

// The queue's statements over song-sized rows, named so the plan test can
// check each is led by its index. Every window read walks one order's
// index from a key, honoring the floors Remove played left (Long.MIN_VALUE
// for none): the walked order's as the range start, the other's as a row
// filter.
const val QUEUE_AFTER_QK_SQL =
    "SELECT * FROM queue_entry WHERE queueId = ?1 AND (qk, entry) > (?2, ?3) AND qk >= ?4 AND sk >= ?5 ORDER BY qk, entry LIMIT ?6"
const val QUEUE_BEFORE_QK_SQL =
    "SELECT * FROM queue_entry WHERE queueId = ?1 AND (qk, entry) < (?2, ?3) AND qk >= ?4 AND sk >= ?5 ORDER BY qk DESC, entry DESC LIMIT ?6"
const val QUEUE_AFTER_SK_SQL =
    "SELECT * FROM queue_entry WHERE queueId = ?1 AND (sk, entry) > (?2, ?3) AND qk >= ?4 AND sk >= ?5 ORDER BY sk, entry LIMIT ?6"
const val QUEUE_BEFORE_SK_SQL =
    "SELECT * FROM queue_entry WHERE queueId = ?1 AND (sk, entry) < (?2, ?3) AND qk >= ?4 AND sk >= ?5 ORDER BY sk DESC, entry DESC LIMIT ?6"
const val QUEUE_COUNT_SQL = "SELECT COUNT(*) FROM queue_entry WHERE queueId = ?1 AND qk >= ?2 AND sk >= ?3"
const val QUEUE_COUNT_AFTER_QK_SQL = "SELECT COUNT(*) FROM queue_entry WHERE queueId = ?1 AND (qk, entry) > (?2, ?3) AND qk >= ?4 AND +sk >= ?5"
const val QUEUE_COUNT_AFTER_SK_SQL = "SELECT COUNT(*) FROM queue_entry WHERE queueId = ?1 AND (sk, entry) > (?2, ?3) AND +qk >= ?4 AND sk >= ?5"
const val QUEUE_LENGTH_AFTER_QK_SQL =
    "SELECT COALESCE(SUM(durationMs), 0) FROM queue_entry WHERE queueId = ?1 AND (qk, entry) > (?2, ?3) AND qk >= ?4 AND +sk >= ?5"
const val QUEUE_LENGTH_AFTER_SK_SQL =
    "SELECT COALESCE(SUM(durationMs), 0) FROM queue_entry WHERE queueId = ?1 AND (sk, entry) > (?2, ?3) AND +qk >= ?4 AND sk >= ?5"

// The keys around a place, one index seek each.
const val QUEUE_NEXT_QK_SQL = "SELECT MIN(qk) FROM queue_entry WHERE queueId = ?1 AND qk > ?2"
const val QUEUE_PREV_QK_SQL = "SELECT MAX(qk) FROM queue_entry WHERE queueId = ?1 AND qk < ?2"
const val QUEUE_NEXT_SK_SQL = "SELECT MIN(sk) FROM queue_entry WHERE queueId = ?1 AND sk > ?2"
const val QUEUE_PREV_SK_SQL = "SELECT MAX(sk) FROM queue_entry WHERE queueId = ?1 AND sk < ?2"
const val QUEUE_MAX_QK_SQL = "SELECT MAX(qk) FROM queue_entry WHERE queueId = ?1"
const val QUEUE_MAX_SK_SQL = "SELECT MAX(sk) FROM queue_entry WHERE queueId = ?1"
const val QUEUE_MIN_QK_SQL = "SELECT MIN(qk) FROM queue_entry WHERE queueId = ?1"
const val QUEUE_MIN_SK_SQL = "SELECT MIN(sk) FROM queue_entry WHERE queueId = ?1"

// The first key of a list's rows past a key, for Add to queue's place
// before the first Autoplay song still to come: a walk of the list's own
// range of numbers, kept off the key indexes.
// The list's own numbers are the range: named, since a current SQLite
// would otherwise walk the whole generation on the covering key index.
const val QUEUE_LIST_FIRST_QK_SQL =
    "SELECT MIN(qk + 0) FROM queue_entry INDEXED BY sqlite_autoindex_queue_entry_1 WHERE queueId = ?1 AND entry BETWEEN ?2 AND ?3 AND +qk > ?4"
const val QUEUE_LIST_FIRST_SK_SQL =
    "SELECT MIN(sk + 0) FROM queue_entry INDEXED BY sqlite_autoindex_queue_entry_1 WHERE queueId = ?1 AND entry BETWEEN ?2 AND ?3 AND +sk > ?4"

// A build writes each row by its number: changed in place when it is
// there with another song or key, else put in. No UPSERT: Android 10
// ships SQLite 3.22 and UPSERT needs 3.24.
const val QUEUE_BUILD_UPDATE_SQL =
    "UPDATE queue_entry SET trackId = ?3, listId = ?4, qk = ?5, sk = ?6, durationMs = ?7 " +
        "WHERE queueId = ?1 AND entry = ?2 AND (trackId IS NOT ?3 OR qk IS NOT ?5)"
const val QUEUE_BUILD_INSERT_SQL =
    "INSERT OR IGNORE INTO queue_entry (queueId, entry, trackId, listId, qk, sk, durationMs) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)"

// Whether the list already holds a song among the numbers this attempt
// wrote: one song's few entries, read by the song's index.
const val QUEUE_HOLDS_SONG_SQL =
    "SELECT COUNT(*) FROM queue_entry WHERE trackId = ?1 AND +queueId = ?2 AND (+entry BETWEEN ?3 AND ?4 OR +entry BETWEEN ?5 AND ?6)"

// The last number of a page of numbers past `after` and up to `last`, so
// a delete takes one bounded range of the primary key at a time.
const val QUEUE_PAGE_END_SQL = "SELECT entry FROM queue_entry WHERE queueId = ?1 AND entry > ?2 AND entry <= ?3 ORDER BY entry LIMIT 1 OFFSET ?4"
const val QUEUE_DELETE_RANGE_SQL = "DELETE FROM queue_entry WHERE queueId = ?1 AND entry > ?2 AND entry <= ?3"

// The heal pass: a page of a generation's rows by their primary key, and
// the library song a row's vanished song was merged into.
const val QUEUE_HEAL_PAGE_SQL = "SELECT * FROM queue_entry WHERE queueId = ?1 AND entry > ?2 ORDER BY entry LIMIT ?3"
const val QUEUE_FOLLOW_SQL =
    "SELECT s.mergedId FROM source_track s WHERE s.id = ?1 AND NOT EXISTS (SELECT 1 FROM track WHERE id = ?1) " +
        "AND EXISTS (SELECT 1 FROM track t WHERE t.id = s.mergedId)"

// A copied song as the library has it now: itself while the library or
// the finds hold it, else the library song it was merged into; nothing
// when it is gone for good.
// No row at all when gone, never a NULL one.
const val QUEUE_FOLLOW_OR_GONE_SQL =
    "SELECT followed FROM (SELECT CASE WHEN EXISTS (SELECT 1 FROM track WHERE id = ?1) OR EXISTS (SELECT 1 FROM online_song WHERE id = ?1) THEN ?1 " +
        "ELSE (SELECT s.mergedId FROM source_track s WHERE s.id = ?1 AND EXISTS (SELECT 1 FROM track t WHERE t.id = s.mergedId)) END AS followed) " +
        "WHERE followed IS NOT NULL"

// Which of `count` songs a generation holds, one seek of the songs' index
// each; the songs are the first `count` arguments, the generation the last.
fun queueHeldAmongSql(count: Int): String = (1..count).joinToString(" UNION ALL ") { i ->
    "SELECT ?$i WHERE EXISTS (SELECT 1 FROM queue_entry WHERE trackId = ?$i AND +queueId = ?${count + 1})"
}

// Most songs one held-among statement asks after, under SQLite's limit on
// a compound select's terms.
const val QUEUE_HELD_AMONG_CHUNK = 200

// The rows of one list that hold a song, by the songs' index.
const val QUEUE_LIST_HOLDS_SQL = "SELECT * FROM queue_entry WHERE trackId = ?1 AND +queueId = ?2 AND +listId = ?3"

// A generation's rows of a song, by the song's index.
const val QUEUE_HOLDING_SQL = "SELECT * FROM queue_entry WHERE trackId = ?1 AND +queueId = ?2"

// The rows of a generation holding the first shuffled key, by the index
// over the keys they had before (null on every other row).
const val QUEUE_FIRST_KEYED_SQL = "SELECT * FROM queue_entry WHERE queueId = ?1 AND skBefore >= ?2"

// The next generation after `?1` that Room holds rows or lists of, one
// seek each.
const val QUEUE_NEXT_GENERATION_SQL = "SELECT MIN(queueId) FROM queue_entry WHERE queueId > ?1"
const val QUEUE_NEXT_LISTED_SQL = "SELECT MIN(queueId) FROM queue_list WHERE queueId > ?1"

// A song's listed length.
data class SongLength(val id: String, val durationMs: Long)

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_meta WHERE id = 0")
    suspend fun meta(): QueueMetaEntity?

    // Ids up to these are taken; a later process starts past them.
    @Query("UPDATE queue_meta SET nextQueueId = MAX(nextQueueId, :queueId), nextListId = MAX(nextListId, :listId) WHERE id = 0")
    suspend fun reserve(queueId: Long, listId: Long)

    @Query("SELECT * FROM queue_account WHERE account = :account")
    suspend fun account(account: String): QueueAccountEntity?

    @Query("SELECT * FROM queue_account")
    suspend fun accounts(): List<QueueAccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAccount(row: QueueAccountEntity)

    // Where playback is, written only while the account still plays the
    // generation it was read from.
    @Query(
        """
        UPDATE queue_account SET currentEntry = :current, positionMs = :positionMs, repeatMode = :repeatMode, shuffle = :shuffle,
            savedAt = :savedAt
        WHERE account = :account AND queueId = :queueId
        """,
    )
    suspend fun saveState(account: String, queueId: Long, current: Long?, positionMs: Long, repeatMode: Int, shuffle: Boolean, savedAt: Long): Int

    @Query("UPDATE queue_account SET floorQ = :floorQ, floorS = :floorS WHERE account = :account AND queueId = :queueId")
    suspend fun setFloors(account: String, queueId: Long, floorQ: Long?, floorS: Long?): Int

    @Query("UPDATE queue_account SET healedAt = :healedAt WHERE queueId = :queueId")
    suspend fun setHealedAt(queueId: Long, healedAt: Long?)

    @Query("UPDATE queue_account SET account = :to WHERE account = :from")
    suspend fun renameAccount(from: String, to: String)

    @Query("DELETE FROM queue_account WHERE account = :account")
    suspend fun deleteAccount(account: String)

    @Query("SELECT COUNT(*) FROM queue_account WHERE queueId = :queueId")
    suspend fun accountsOn(queueId: Long): Int

    @Query("SELECT * FROM queue_account WHERE queueId = :queueId LIMIT 1")
    suspend fun accountOn(queueId: Long): QueueAccountEntity?

    @Query("SELECT * FROM queue_list WHERE listId = :listId")
    suspend fun list(listId: Long): QueueListEntity?

    // A generation's lists: one per play or add, never song-sized.
    @Query("SELECT * FROM queue_list WHERE queueId = :queueId ORDER BY listId")
    suspend fun listsOf(queueId: Long): List<QueueListEntity>

    @Query("SELECT * FROM queue_list WHERE fillState != 0 ORDER BY listId")
    suspend fun unjoined(): List<QueueListEntity>

    @Query("SELECT DISTINCT queueId FROM queue_list")
    suspend fun listedGenerations(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putList(row: QueueListEntity)

    @Query("UPDATE queue_list SET phase = :phase, cursor = :cursor, filled0 = :filled0, filled1 = :filled1, phase0End = :phase0End WHERE listId = :listId")
    suspend fun progress(listId: Long, phase: Int, cursor: String?, filled0: Long, filled1: Long, phase0End: Long?)

    // A swap ended the walk: it starts over from its anchor, from the
    // count it just read.
    @Query(
        """
        UPDATE queue_list SET swapCount = :swapCount, attempts = attempts + 1, phase = 0, cursor = anchor, filled0 = 0, filled1 = 0,
            phase0End = NULL
        WHERE listId = :listId
        """,
    )
    suspend fun restart(listId: Long, swapCount: Long)

    @Query("UPDATE queue_list SET swapCount = :swapCount WHERE listId = :listId")
    suspend fun setBaseline(listId: Long, swapCount: Long?)

    @Query("UPDATE queue_list SET expected = :expected WHERE listId = :listId")
    suspend fun setExpected(listId: Long, expected: Long?)

    @Query("UPDATE queue_list SET anchor = :anchor, cursor = CASE WHEN phase = 0 AND filled0 = 0 THEN :anchor ELSE cursor END WHERE listId = :listId")
    suspend fun setAnchor(listId: Long, anchor: String?)

    @Query("UPDATE queue_list SET copyOwner = :owner WHERE listId = :listId")
    suspend fun setCopyOwner(listId: Long, owner: String?)

    @Query("UPDATE queue_list SET songsOnce = :songsOnce WHERE listId = :listId")
    suspend fun setSongsOnce(listId: Long, songsOnce: Boolean)

    @Query("UPDATE queue_list SET fillState = 0, copyOwner = NULL WHERE listId = :listId")
    suspend fun joined(listId: Long)

    @Query("UPDATE queue_list SET fillState = :fillState, qBase = :qBase, qStep = :qStep, sBase = :sBase, sStep = :sStep WHERE listId = :listId")
    suspend fun placed(listId: Long, fillState: Int, qBase: Long?, qStep: Long?, sBase: Long?, sStep: Long?)

    @Query("UPDATE queue_list SET swapCount = :swapCount WHERE queueId = :queueId AND fillState = 0 AND listId NOT IN (:skipped)")
    suspend fun healedLists(queueId: Long, swapCount: Long, skipped: List<Long>)

    @Query("DELETE FROM queue_list WHERE listId = :listId")
    suspend fun deleteList(listId: Long)

    @Query("DELETE FROM queue_list WHERE queueId = :queueId")
    suspend fun deleteListsOf(queueId: Long)

    @Query("UPDATE queue_list SET source = :source WHERE listId = :listId")
    suspend fun setSource(listId: Long, source: String?)

    @Query("SELECT * FROM queue_entry WHERE queueId = :queueId AND entry = :entry")
    suspend fun entry(queueId: Long, entry: Long): QueueEntryEntity?

    @Query("SELECT * FROM queue_entry WHERE queueId = :queueId AND entry IN (:entries)")
    suspend fun entries(queueId: Long, entries: List<Long>): List<QueueEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEntries(rows: List<QueueEntryEntity>)

    // The rows numbered `from` to `to` head under `listId`: an old queue's
    // run of one source becomes its own list.
    @Query("UPDATE queue_entry SET listId = :listId WHERE queueId = :queueId AND entry BETWEEN :from AND :to")
    suspend fun setListOfRange(queueId: Long, from: Long, to: Long, listId: Long): Int

    @Query("DELETE FROM queue_entry WHERE queueId = :queueId AND entry IN (:entries)")
    suspend fun deleteEntries(queueId: Long, entries: List<Long>): Int

    @Query("UPDATE queue_entry SET qk = :qk WHERE queueId = :queueId AND entry = :entry")
    suspend fun setQk(queueId: Long, entry: Long, qk: Long)

    @Query("UPDATE queue_entry SET qk = :qk, sk = :sk WHERE queueId = :queueId AND entry = :entry")
    suspend fun setKeys(queueId: Long, entry: Long, qk: Long, sk: Long)

    @Query("UPDATE queue_entry SET trackId = :trackId WHERE queueId = :queueId AND entry = :entry")
    suspend fun setTrack(queueId: Long, entry: Long, trackId: String)

    // A row's shuffled key, and the one it had before taking the first.
    @Query("UPDATE queue_entry SET sk = :sk, skBefore = :skBefore WHERE queueId = :queueId AND entry = :entry")
    suspend fun setShuffled(queueId: Long, entry: Long, sk: Long, skBefore: Long?)

    @Query("UPDATE queue_entry SET queueId = :to WHERE queueId = :from AND entry IN (:entries)")
    suspend fun moveEntries(from: Long, to: Long, entries: List<Long>): Int

    // The rows of some songs in a generation, by the songs' index.
    @Query("SELECT * FROM queue_entry WHERE trackId IN (:trackIds) AND queueId = :queueId")
    suspend fun entriesOfSongs(queueId: Long, trackIds: List<String>): List<QueueEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun putPending(row: QueuePendingEntity)

    @Query("SELECT * FROM queue_pending WHERE listId = :listId ORDER BY seq")
    suspend fun pending(listId: Long): List<QueuePendingEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM queue_pending WHERE listId = :listId")
    suspend fun lastPending(listId: Long): Long

    @Query("DELETE FROM queue_pending WHERE listId = :listId")
    suspend fun deletePending(listId: Long)

    // An album's first songs in album order, and an artist's album by
    // album, for a radio's features: at most `limit`, by their indexes.
    @Query("SELECT * FROM track WHERE albumId = :albumId ORDER BY albumOrder, id LIMIT :limit")
    suspend fun albumFirst(albumId: String, limit: Int): List<TrackEntity>

    @Query("SELECT * FROM track WHERE artistId = :artistId ORDER BY albumId, albumOrder, id LIMIT :limit")
    suspend fun artistFirst(artistId: String, limit: Int): List<TrackEntity>

    // Which of these songs are on an album: at most 900 a call.
    @Query("SELECT id FROM track WHERE id IN (:ids) AND albumId = :albumId")
    suspend fun onAlbum(albumId: String, ids: List<String>): List<String>

    // The listed lengths of songs: at most 900 a call.
    @Query("SELECT id, durationMs FROM track WHERE id IN (:ids)")
    suspend fun lengths(ids: List<String>): List<SongLength>

    @Query("SELECT id, durationMs FROM online_song WHERE id IN (:ids)")
    suspend fun findLengths(ids: List<String>): List<SongLength>

    @Query("SELECT * FROM track WHERE id = :id")
    suspend fun track(id: String): TrackEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM track)")
    suspend fun anySong(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM liked_track)")
    suspend fun anyLiked(): Boolean

    @RawQuery
    suspend fun tracks(query: RoomRawQuery): List<TrackEntity>

    @RawQuery
    suspend fun rows(query: RoomRawQuery): List<QueueEntryEntity>

    @RawQuery
    suspend fun number(query: RoomRawQuery): Long?

    @RawQuery
    suspend fun numbers(query: RoomRawQuery): List<Long>

    @RawQuery
    suspend fun ids(query: RoomRawQuery): List<String>
}

// One statement with its arguments, for the queue's raw reads.
internal fun queueQuery(sql: String, vararg args: Any?): RoomRawQuery = rawQuery(
    sql,
    args.map {
        when (it) {
            null -> SqlValue.Null
            is Long -> SqlValue.Int64(it)
            is Int -> SqlValue.Int64(it.toLong())
            is Boolean -> SqlValue.Int64(if (it) 1 else 0)
            is String -> SqlValue.Text(it)
            else -> error("no queue argument of ${it.javaClass.simpleName}")
        }
    },
)
