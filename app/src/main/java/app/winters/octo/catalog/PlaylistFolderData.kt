package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import app.winters.octo.playlists.PlaylistFolderTree
import kotlinx.coroutines.flow.Flow

// Playlist folders on the phone, per account (`space`: the kept server's
// id, or "" with no server in use): the folders as the server last gave
// them with the changes made here on top, where each playlist sits by its
// placement key (the server's playlist id, or "local:<row id>" for a
// playlist only on the phone, which is never sent), and the folder changes
// waiting to reach the server, in the order they were made.

@Entity(
    tableName = "playlist_folder",
    primaryKeys = ["space", "id"],
    indices = [Index("space", "parent", name = "index_playlist_folder_space_parent")],
)
data class PlaylistFolderEntity(val space: String, val id: String, val name: String, val parent: String?, val created: String)

@Entity(
    tableName = "playlist_place",
    primaryKeys = ["space", "playlistKey"],
    indices = [Index("space", "folderId", name = "index_playlist_place_space_folderId")],
)
data class PlaylistPlaceEntity(
    val space: String,
    val playlistKey: String,
    val folderId: String,
    // A playlist only on the phone: never sent, and kept through a pull.
    @ColumnInfo(defaultValue = "0") val localOnly: Boolean = false,
)

// One folder change waiting to reach the server: a folder saved (made,
// renamed or moved) or deleted, or playlists placed (`folderId` null for
// the top level). `playlistKeys` holds the placed playlists' server ids,
// one a line.
@Entity(tableName = "folder_op", indices = [Index("space", "seq", name = "index_folder_op_space_seq")])
data class FolderOpEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val space: String,
    val kind: String,
    val folderId: String?,
    val name: String?,
    val parent: String?,
    val playlistKeys: String,
    val at: Long,
)

// A server whose playlists' bases were seeded once (PlaylistSteps.ensureBase).
@Entity(tableName = "playlist_base_mark")
data class PlaylistBaseMarkEntity(@PrimaryKey val sourceId: String, val at: Long)

const val FOLDER_OP_SAVE = "save"
const val FOLDER_OP_DELETE = "delete"
const val FOLDER_OP_PLACE = "place"

@Dao
abstract class PlaylistFolderDao {
    @Query("SELECT * FROM playlist_folder WHERE space = :space")
    abstract fun folders(space: String): Flow<List<PlaylistFolderEntity>>

    @Query("SELECT * FROM playlist_place WHERE space = :space")
    abstract fun places(space: String): Flow<List<PlaylistPlaceEntity>>

    @Query("SELECT * FROM playlist_folder WHERE space = :space")
    abstract suspend fun foldersNow(space: String): List<PlaylistFolderEntity>

    @Query("SELECT * FROM playlist_place WHERE space = :space")
    abstract suspend fun placesNow(space: String): List<PlaylistPlaceEntity>

    @Query("SELECT * FROM playlist_folder WHERE space = :space AND id = :id")
    abstract suspend fun folder(space: String, id: String): PlaylistFolderEntity?

    @Query("SELECT * FROM playlist_place WHERE space = :space AND playlistKey = :key")
    abstract suspend fun place(space: String, key: String): PlaylistPlaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putFolder(row: PlaylistFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putFolders(rows: List<PlaylistFolderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putPlace(row: PlaylistPlaceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putPlaces(rows: List<PlaylistPlaceEntity>)

    @Query("UPDATE playlist_folder SET name = :name WHERE space = :space AND id = :id")
    abstract suspend fun renameFolder(space: String, id: String, name: String): Int

    @Query("UPDATE playlist_folder SET parent = :parent WHERE space = :space AND id = :id")
    abstract suspend fun moveFolder(space: String, id: String, parent: String?): Int

    @Query("UPDATE playlist_folder SET parent = :to WHERE space = :space AND parent = :from")
    abstract suspend fun moveChildren(space: String, from: String, to: String?)

    @Query("UPDATE playlist_place SET folderId = :to WHERE space = :space AND folderId = :from")
    abstract suspend fun movePlaces(space: String, from: String, to: String)

    @Query("DELETE FROM playlist_place WHERE space = :space AND folderId = :folderId")
    abstract suspend fun unplaceAll(space: String, folderId: String)

    @Query("DELETE FROM playlist_folder WHERE space = :space AND id = :id")
    abstract suspend fun deleteFolder(space: String, id: String)

    @Query("DELETE FROM playlist_place WHERE space = :space AND playlistKey = :key")
    abstract suspend fun deletePlace(space: String, key: String)

    // A playlist deleted here: its placement goes from every account.
    @Query("DELETE FROM playlist_place WHERE playlistKey = :key")
    abstract suspend fun forgetPlaces(key: String)

    @Query("DELETE FROM playlist_folder WHERE space = :space")
    abstract suspend fun clearFolders(space: String)

    @Query("DELETE FROM playlist_place WHERE space = :space AND localOnly = 0")
    abstract suspend fun clearServerPlaces(space: String)

    // Placements whose folder is gone: the playlist shows at the top.
    @Query("DELETE FROM playlist_place WHERE space = :space AND folderId NOT IN (SELECT id FROM playlist_folder WHERE space = :space)")
    abstract suspend fun dropHomeless(space: String)

    @Insert
    abstract suspend fun insertOp(row: FolderOpEntity)

    @Query("SELECT * FROM folder_op WHERE space = :space ORDER BY seq LIMIT 1")
    abstract suspend fun firstOp(space: String): FolderOpEntity?

    @Query("SELECT * FROM folder_op WHERE space = :space ORDER BY seq")
    abstract suspend fun ops(space: String): List<FolderOpEntity>

    @Query("DELETE FROM folder_op WHERE seq = :seq")
    abstract suspend fun deleteOp(seq: Long)

    // A server playlist unlinked here: its placement becomes the phone-only
    // playlist's, in every account that had one.
    @Query("UPDATE OR REPLACE playlist_place SET playlistKey = :localKey, localOnly = 1 WHERE playlistKey = :serverId")
    abstract suspend fun turnLocal(serverId: String, localKey: String)

    // A phone-only playlist made on the server (or found to be one's
    // twin): its placement in `space` becomes one the server keeps, and a
    // place change is queued for it. Answers whether it had one.
    @Transaction
    open suspend fun placeOnServer(space: String, rowId: String, serverId: String, at: Long): Boolean {
        val localKey = PlaylistFolderTree.localKey(rowId)
        val local = place(space, localKey) ?: return false
        deletePlace(space, localKey)
        putPlace(PlaylistPlaceEntity(space, serverId, local.folderId, localOnly = false))
        if (space.isNotEmpty()) {
            insertOp(FolderOpEntity(space = space, kind = FOLDER_OP_PLACE, folderId = local.folderId, name = null, parent = null, playlistKeys = serverId, at = at))
        }
        return true
    }
}
