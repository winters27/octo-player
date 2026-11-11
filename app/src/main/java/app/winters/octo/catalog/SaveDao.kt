package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Query

// What a found row does besides FOUND_NEW to FOUND_GONE: a library song
// whose rating alone changes (from seenRating to newRating).
const val FOUND_RATED = 4

// What else the find files in save_found's `tbl`, beside the source and
// library tables it names by their own names:
// - FOUND_MERGED_ID: a source row (`id`) of a source in the library whose
//   library song changes from `oldId` ("" for a row the save adds) to
//   `newId`;
// - FOUND_UNMERGE: a source row of a source out of the library that stops
//   being the copy of `oldId`;
// - FOUND_TAP: a rating tap whose server copy (`id`) the save takes away,
//   `oldId` that copy's song;
// - FOUND_TOTALS: a song (`id`) whose play total the save counts again.
//
// A source_track row holds its song in `oldId` (as it is) and `newId` (as
// the save leaves it), a song's row its ratings in `seenRating` (as the
// find saw it) and `newRating` (what the save writes).
const val FOUND_MERGED_ID = "merged_id"
const val FOUND_UNMERGE = "unmerge"
const val FOUND_TAP = "rating_tap"
const val FOUND_TOTALS = "play_total"

// A rename the save publishes, filed in save_ref under this table: `kind`
// and `oldId` its kind and old id (`keyText` the old id too, as
// save_rename keys it), `target` the id it becomes, `keyNum` 1 for a
// rename by key and 0 for one by follow, and `relinkKey` the key it was
// made for ("" by follow).
const val REF_RENAME = "save_rename"

// A reference whose `target` is its own `oldId` moves nothing: the row it
// names, on a song, album or artist the library keeps (or moves to), takes
// the key that one is filed under in place of `relinkKey`, the key the find
// saw it with (RenameTarget.keyFollows). No move's target is its own old
// id, since an id that moves is one the library will not have.
fun isRekey(oldId: String, target: String?): Boolean = target == oldId

internal const val FOUND_IN_SQL =
    "INSERT INTO save_found (tbl, op, id, oldId, newId, seenRating, newRating, cost) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)"

// A song to count again, unless it is found for that already.
internal const val TOTALS_IN_SQL =
    "INSERT INTO save_found (tbl, op, id, cost) SELECT ?1, ?2, ?3, ?4 WHERE NOT EXISTS (SELECT 1 FROM save_found WHERE tbl = ?1 AND id = ?3)"

internal const val REF_IN_SQL =
    "INSERT INTO save_ref (tbl, kind, keyText, keyNum, keyNum2, oldId, relinkKey, target) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)"

internal const val FOUND_RATED_SQL = "UPDATE save_found SET op = ?2, newRating = ?3, cost = ?4 WHERE seq = ?1"

internal const val FOUND_OUT_SQL = "DELETE FROM save_found WHERE seq = ?1"

// A song's found row, by the (tbl, id) index.
internal const val FOUND_SONG_SQL = "SELECT seq, op, seenRating, newRating, cost FROM save_found WHERE tbl = 'track' AND id = ?1"

// What the find found of a library album or artist, by the same index.
internal const val FOUND_OP_SQL = "SELECT op FROM save_found WHERE tbl = ?1 AND id = ?2"

// Each found row's set: the root of its id's node, by the id a row of its
// kind moves with (a source row's song, as it is, or as the save leaves
// it for a row the save adds), none for a source album or artist. Every
// column of the found row is named with its table: save_node has an `id`
// of its own, which a bare `id` would read.
internal const val FOUND_SET_SQL = """
    UPDATE save_found SET setId = (
        SELECT n.parent FROM save_node n
        WHERE n.kind = (CASE save_found.tbl WHEN 'album' THEN 'album' WHEN 'artist' THEN 'artist' ELSE 'track' END)
          AND n.id = (CASE save_found.tbl
              WHEN 'source_track' THEN COALESCE(NULLIF(save_found.oldId, ''), save_found.newId)
              WHEN 'merged_id' THEN COALESCE(NULLIF(save_found.oldId, ''), save_found.newId)
              WHEN 'unmerge' THEN save_found.oldId
              WHEN 'rating_tap' THEN NULLIF(save_found.oldId, '')
              WHEN 'source_album' THEN NULL
              WHEN 'source_artist' THEN NULL
              ELSE save_found.id END))
    WHERE seq > ?1 AND seq <= ?2
"""

internal const val REF_SET_SQL =
    "UPDATE save_ref SET setId = (SELECT n.parent FROM save_node n WHERE n.kind = save_ref.kind AND n.id = save_ref.oldId) WHERE seq > ?1 AND seq <= ?2"

// Each set's rows take its group.
internal const val FOUND_GROUP_SQL =
    "UPDATE save_found SET groupId = (SELECT s.groupId FROM save_set s WHERE s.setId = save_found.setId) WHERE seq > ?1 AND seq <= ?2 AND setId IS NOT NULL"

internal const val REF_GROUP_SQL =
    "UPDATE save_ref SET groupId = (SELECT s.groupId FROM save_set s WHERE s.setId = save_ref.setId) WHERE seq > ?1 AND seq <= ?2"

// Rows in no set, a run of them by seq, take a group.
internal const val LOOSE_GROUP_SQL = "UPDATE save_found SET groupId = ?1 WHERE setId IS NULL AND seq > ?2 AND seq <= ?3"

internal const val SET_IN_SQL = "INSERT INTO save_set (setId, cost) VALUES (?1, ?2)"

internal const val SET_GROUP_SQL = "UPDATE save_set SET groupId = ?2 WHERE setId = ?1"

// The save's own work rows: what the find found, its references, its sets
// and their nodes. The renames in force (save_rename) live on past a save.
@Dao
interface SaveDao {
    @Query("SELECT COUNT(*) FROM save_found")
    suspend fun foundCount(): Long

    @Query("SELECT COUNT(*) FROM save_ref")
    suspend fun refCount(): Long

    @Query("SELECT COUNT(*) FROM save_set")
    suspend fun setCount(): Long

    @Query("SELECT COUNT(*) FROM save_node")
    suspend fun nodeCount(): Long

    // How many groups the find planned, 0 before it planned any.
    @Query("SELECT COALESCE(MAX(groupId), 0) FROM save_set")
    suspend fun plannedSetGroups(): Long

    @Query("SELECT COALESCE(MAX(groupId), 0) FROM save_found")
    suspend fun plannedGroups(): Long

    // One group's found rows of one table and op, in id order, by the
    // group's key.
    @Query("SELECT * FROM save_found WHERE groupId = :group AND tbl = :tbl AND op = :op ORDER BY id")
    suspend fun foundIn(group: Long, tbl: String, op: Int): List<SaveFoundEntity>

    // One group's references of one table, in the order they were found.
    @Query("SELECT * FROM save_ref WHERE groupId = :group AND tbl = :tbl ORDER BY seq")
    suspend fun refsIn(group: Long, tbl: String): List<SaveRefEntity>

    // A set and the group it lands in.
    @Query("SELECT * FROM save_set WHERE setId = :setId")
    suspend fun set(setId: Long): SaveSetEntity?

    // What the find found of one row, by its table and key.
    @Query("SELECT * FROM save_found WHERE tbl = :tbl AND id = :id")
    suspend fun foundOf(tbl: String, id: String): List<SaveFoundEntity>
}
