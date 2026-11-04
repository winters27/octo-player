package app.winters.octo.server

import androidx.annotation.VisibleForTesting
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import app.winters.octo.catalog.AccountSyncEntity
import app.winters.octo.catalog.SourceAlbumEntity
import app.winters.octo.catalog.SourceArtistEntity
import app.winters.octo.catalog.SourceMetaEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.StageAlbumEntity
import app.winters.octo.catalog.StageArtistEntity
import app.winters.octo.catalog.StageTrackEntity
import app.winters.octo.catalog.SyncAlbumEntity
import app.winters.octo.catalog.SyncArtistEntity
import app.winters.octo.catalog.SyncOrderEntity
import app.winters.octo.catalog.SyncSongEntity
import app.winters.octo.catalog.TakeInLogEntity
import kotlinx.coroutines.flow.Flow

// What a copy of a server's library writes and reads: the server's pages as
// they came, the stage built from them and its state, music taken in ahead
// of a copy, and each account's record of its last copy. Every statement
// over song-sized tables runs on an index (CopyQueryPlanTest).
@Dao
interface CopyDao {
    // The server's pages

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncSongs(rows: List<SyncSongEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncAlbums(rows: List<SyncAlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncArtists(rows: List<SyncArtistEntity>)

    @Query("SELECT COUNT(*) FROM sync_song")
    suspend fun syncSongCount(): Int

    @Query(CopySql.SONG_ALBUM_KEYS)
    suspend fun songAlbumKeys(after: Long, limit: Int): List<SongAlbumKey>

    @Query(CopySql.ALBUM_SONG_COUNT)
    suspend fun albumSongCount(key: String): Int

    @Query(CopySql.ALBUM_SONGS)
    suspend fun albumSongs(key: String): List<SyncSongEntity>

    @Query(CopySql.ALBUM_SONG_PAGE)
    suspend fun albumSongPage(key: String, after: Long, limit: Int): List<SyncSongEntity>

    @Query(CopySql.SONGS_BY_IDS)
    suspend fun syncSongsByIds(ids: List<String>): List<SyncSongEntity>

    @Query(CopySql.DELETE_ALBUM_SONGS)
    suspend fun deleteAlbumSongs(keys: List<String>)

    @Query(CopySql.SYNC_ALBUM)
    suspend fun syncAlbum(id: String): SyncAlbumEntity?

    @Query(CopySql.SYNC_ALBUM_PAGE)
    suspend fun syncAlbumPage(after: Long, limit: Int): List<SyncAlbumEntity>

    @Query("SELECT MAX(seq) FROM sync_album")
    suspend fun maxSyncAlbumSeq(): Long?

    @Query(CopySql.SYNC_ARTIST_PAGE)
    suspend fun syncArtistPage(after: Long, limit: Int): List<SyncArtistEntity>

    // A giant album's order

    @Insert
    suspend fun insertSyncOrder(rows: List<SyncOrderEntity>)

    @Query(CopySql.FIRST_ORDERED)
    suspend fun firstOrdered(owner: String, limit: Int): List<SyncOrderEntity>

    // CopySql.ORDERED_AFTER with owner, discKey, trackKey, sortKeyHex, seq and limit.
    @RawQuery
    suspend fun orderedAfter(query: SupportSQLiteQuery): List<SyncOrderEntity>

    @Query("DELETE FROM sync_order WHERE owner = :owner")
    suspend fun deleteSyncOrder(owner: String)

    @Query(CopySql.DELETE_SYNC_ORDER_OF)
    suspend fun deleteSyncOrderOf(prefix: String)

    @Query("DELETE FROM sync_song")
    suspend fun deleteSyncSongs()

    @Query("DELETE FROM sync_album")
    suspend fun deleteSyncAlbums()

    @Query("DELETE FROM sync_artist")
    suspend fun deleteSyncArtists()

    // Empties the copy's pages and this source's ordering rows.
    @Transaction
    suspend fun clearSync(sourceId: String) {
        deleteSyncSongs()
        deleteSyncAlbums()
        deleteSyncArtists()
        deleteSyncOrderOf("$sourceId#")
    }

    // The stage

    @Insert
    suspend fun insertStageTracks(rows: List<StageTrackEntity>)

    @Insert
    suspend fun insertStageAlbums(rows: List<StageAlbumEntity>)

    @Insert
    suspend fun insertStageArtists(rows: List<StageArtistEntity>)

    @Query(CopySql.CLEAR_STAGE_TRACKS)
    suspend fun clearStageTracks(sourceId: String)

    @Query(CopySql.CLEAR_STAGE_ALBUMS)
    suspend fun clearStageAlbums(sourceId: String)

    @Query(CopySql.CLEAR_STAGE_ARTISTS)
    suspend fun clearStageArtists(sourceId: String)

    @Transaction
    suspend fun clearStage(sourceId: String) {
        clearStageTracks(sourceId)
        clearStageAlbums(sourceId)
        clearStageArtists(sourceId)
    }

    // A whole stage in order, for checks only.
    @VisibleForTesting
    @Query("SELECT * FROM stage_track WHERE sourceId = :sourceId ORDER BY seq")
    suspend fun stageTracks(sourceId: String): List<StageTrackEntity>

    @VisibleForTesting
    @Query("SELECT * FROM stage_album WHERE sourceId = :sourceId ORDER BY seq")
    suspend fun stageAlbums(sourceId: String): List<StageAlbumEntity>

    @VisibleForTesting
    @Query("SELECT * FROM stage_artist WHERE sourceId = :sourceId ORDER BY seq")
    suspend fun stageArtists(sourceId: String): List<StageArtistEntity>

    // Each source's state

    @Query("SELECT * FROM source_meta WHERE sourceId = :sourceId")
    suspend fun meta(sourceId: String): SourceMetaEntity?

    @Query(
        """
        INSERT OR IGNORE INTO source_meta
            (sourceId, copyOf, stamp, stage, generation, stagedTracks, stagedAlbums, stagedArtists, stageOwner, readFrom)
        VALUES (:sourceId, NULL, 0, 'none', 0, 0, 0, 0, NULL, 0)
        """,
    )
    suspend fun ensureMeta(sourceId: String)

    @Query(CopySql.LAST_GENERATION)
    suspend fun lastGeneration(): Long?

    @Query(
        """
        UPDATE source_meta SET stage = 'building', generation = :generation, stageOwner = :owner,
            stagedTracks = 0, stagedAlbums = 0, stagedArtists = 0, readFrom = :readFrom
        WHERE sourceId = :sourceId
        """,
    )
    suspend fun markBuilding(sourceId: String, generation: Long, owner: String, readFrom: Long)

    // Answers how many rows changed: 1, unless the stage is no longer this
    // generation's and being built.
    @Query(
        """
        UPDATE source_meta SET stage = 'ready', stagedTracks = :tracks, stagedAlbums = :albums, stagedArtists = :artists
        WHERE sourceId = :sourceId AND generation = :generation AND stage = 'building'
        """,
    )
    suspend fun markReady(sourceId: String, generation: Long, tracks: Int, albums: Int, artists: Int): Int

    @Query(
        """
        UPDATE source_meta SET stage = 'none', stageOwner = NULL, stagedTracks = 0, stagedAlbums = 0, stagedArtists = 0
        WHERE sourceId = :sourceId
        """,
    )
    suspend fun markNone(sourceId: String)

    @Query("UPDATE source_meta SET stamp = stamp + 1 WHERE sourceId = :sourceId")
    suspend fun bumpStamp(sourceId: String)

    @Query("SELECT copyOf FROM source_meta WHERE sourceId = :sourceId")
    suspend fun copyOf(sourceId: String): String?

    @Query("UPDATE source_meta SET copyOf = :account WHERE sourceId = :sourceId")
    suspend fun setCopyOf(sourceId: String, account: String)

    // Music taken in ahead of a copy

    @Query(CopySql.KNOWN_TRACKS)
    suspend fun knownTracks(sourceId: String, ids: List<String>): List<String>

    @Query(CopySql.KNOWN_ALBUMS)
    suspend fun knownAlbums(sourceId: String, ids: List<String>): List<String>

    @Query("SELECT id FROM source_artist WHERE id IN (:ids)")
    suspend fun knownArtists(ids: List<String>): List<String>

    @Query("SELECT id, seq FROM source_album WHERE id IN (:ids)")
    suspend fun albumSeqs(ids: List<String>): List<RowSeq>

    @Query(CopySql.MAX_TRACK_SEQ)
    suspend fun maxTrackSeq(sourceId: String): Long?

    @Query(CopySql.MAX_ALBUM_SEQ)
    suspend fun maxAlbumSeq(sourceId: String): Long?

    @Query(CopySql.MAX_ARTIST_SEQ)
    suspend fun maxArtistSeq(sourceId: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceTracks(rows: List<SourceTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceArtists(rows: List<SourceArtistEntity>)

    @Upsert
    suspend fun upsertSourceAlbums(rows: List<SourceAlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun logTakeIns(rows: List<TakeInLogEntity>)

    @Query(CopySql.LIVE_TRACKS)
    suspend fun liveTrackCount(sourceId: String): Int

    @Query(CopySql.LIVE_ALBUMS)
    suspend fun liveAlbumCount(sourceId: String): Int

    // Each account's last copy

    @Query("SELECT * FROM account_sync WHERE account = :account")
    suspend fun accountSync(account: String): AccountSyncEntity?

    @Query("SELECT * FROM account_sync WHERE account = :account")
    fun accountSyncFlow(account: String): Flow<AccountSyncEntity?>

    // A record for an account's first copy, before its stage goes to the
    // merge: never copied (time 0) until the steps after it are done.
    @Query(
        """
        INSERT OR IGNORE INTO account_sync (account, sourceId, syncedAt, songs, albums, rowsVersion, lastScan, afterSyncDone)
        VALUES (:account, :sourceId, 0, 0, 0, :rowsVersion, NULL, 1)
        """,
    )
    suspend fun ensureAccountSync(account: String, sourceId: String, rowsVersion: Int)

    @Query("UPDATE account_sync SET rowsVersion = :rowsVersion, afterSyncDone = 0 WHERE account = :account")
    suspend fun markSwapped(account: String, rowsVersion: Int)

    @Query(
        """
        UPDATE account_sync SET sourceId = :sourceId, syncedAt = :at, songs = :songs, albums = :albums, afterSyncDone = 1
        WHERE account = :account
        """,
    )
    suspend fun markDone(account: String, sourceId: String, at: Long, songs: Int, albums: Int)

    @Query("UPDATE account_sync SET lastScan = :scan WHERE account = :account")
    suspend fun setLastScan(account: String, scan: String?)

    // The steps after a copy are done: the record takes the copy's time and
    // counts, and the server's last scan unless only the steps ran again.
    @Transaction
    suspend fun finish(account: String, sourceId: String, at: Long, songs: Int, albums: Int, scan: String?, keepScan: Boolean) {
        markDone(account, sourceId, at, songs, albums)
        if (!keepScan) setLastScan(account, scan)
    }

    @Query("DELETE FROM account_sync WHERE account = :account")
    suspend fun forgetAccount(account: String)

    @Upsert
    suspend fun upsertAccountSyncs(rows: List<AccountSyncEntity>)

    // Records an older version kept as preferences: each account's last copy,
    // and which account each address's rows are (source id, account).
    @Transaction
    suspend fun moveRecords(rows: List<AccountSyncEntity>, owners: List<Pair<String, String>>) {
        upsertAccountSyncs(rows)
        owners.forEach { (sourceId, account) ->
            ensureMeta(sourceId)
            setCopyOf(sourceId, account)
        }
    }
}

// One song's place in the read, and the key of the album it is filed under.
data class SongAlbumKey(val seq: Long, val albumKey: String)

// A row's id and its place in its source's order.
data class RowSeq(val id: String, val seq: Long)

// The copy's statements over song-sized tables, kept here so their query
// plans can be checked on the oldest SQLite the app runs on.
object CopySql {
    const val SONG_ALBUM_KEYS = "SELECT seq, albumKey FROM sync_song WHERE seq > :after ORDER BY seq LIMIT :limit"
    const val ALBUM_SONG_COUNT = "SELECT COUNT(*) FROM sync_song WHERE albumKey = :key"
    const val ALBUM_SONGS = "SELECT * FROM sync_song WHERE albumKey = :key ORDER BY seq"
    const val ALBUM_SONG_PAGE = "SELECT * FROM sync_song WHERE albumKey = :key AND seq > :after ORDER BY seq LIMIT :limit"
    const val SONGS_BY_IDS = "SELECT * FROM sync_song WHERE songId IN (:ids)"
    const val DELETE_ALBUM_SONGS = "DELETE FROM sync_song WHERE albumKey IN (:keys)"
    const val SYNC_ALBUM = "SELECT * FROM sync_album WHERE id = :id"
    const val SYNC_ALBUM_PAGE = "SELECT * FROM sync_album WHERE seq > :after ORDER BY seq LIMIT :limit"
    const val SYNC_ARTIST_PAGE = "SELECT * FROM sync_artist WHERE seq > :after ORDER BY seq LIMIT :limit"
    const val FIRST_ORDERED = "SELECT * FROM sync_order WHERE owner = :owner ORDER BY discKey, trackKey, sortKeyHex, seq LIMIT :limit"
    const val ORDERED_AFTER =
        "SELECT * FROM sync_order WHERE owner = ? AND (discKey, trackKey, sortKeyHex, seq) > (?, ?, ?, ?) " +
            "ORDER BY discKey, trackKey, sortKeyHex, seq LIMIT ?"
    const val DELETE_SYNC_ORDER_OF = "DELETE FROM sync_order WHERE substr(owner, 1, length(:prefix)) = :prefix"
    const val CLEAR_STAGE_TRACKS = "DELETE FROM stage_track WHERE sourceId = :sourceId"
    const val CLEAR_STAGE_ALBUMS = "DELETE FROM stage_album WHERE sourceId = :sourceId"
    const val CLEAR_STAGE_ARTISTS = "DELETE FROM stage_artist WHERE sourceId = :sourceId"
    const val LAST_GENERATION =
        "SELECT MAX(g) FROM (SELECT MAX(generation) AS g FROM source_meta UNION ALL SELECT MAX(generation) AS g FROM take_in_log)"
    const val KNOWN_TRACKS = "SELECT nativeId FROM source_track WHERE sourceId = :sourceId AND nativeId IN (:ids)"
    const val KNOWN_ALBUMS = "SELECT nativeId FROM source_album WHERE sourceId = :sourceId AND nativeId IN (:ids)"
    const val MAX_TRACK_SEQ = "SELECT MAX(seq) FROM source_track WHERE sourceId = :sourceId"
    const val MAX_ALBUM_SEQ = "SELECT MAX(seq) FROM source_album WHERE sourceId = :sourceId"
    const val MAX_ARTIST_SEQ = "SELECT MAX(seq) FROM source_artist WHERE sourceId = :sourceId"
    const val LIVE_TRACKS = "SELECT COUNT(*) FROM source_track WHERE sourceId = :sourceId"
    const val LIVE_ALBUMS = "SELECT COUNT(*) FROM source_album WHERE sourceId = :sourceId"
}
