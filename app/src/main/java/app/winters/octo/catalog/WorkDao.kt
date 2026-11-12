package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction

// The merge's working rows and records (WorkTables).
@Dao
interface WorkDao {
    @Query("SELECT * FROM merge_state WHERE id = 0")
    suspend fun mergeState(): MergeStateEntity?

    // The merge: which sources have rows

    @Query("SELECT sourceId FROM source_track WHERE sourceId > :after ORDER BY sourceId LIMIT 1")
    suspend fun nextTrackSource(after: String): String?

    @Query("SELECT sourceId FROM source_album WHERE sourceId > :after ORDER BY sourceId LIMIT 1")
    suspend fun nextAlbumSource(after: String): String?

    // Pages of a source's rows, built by trackPageQuery and seqPageQuery.

    @RawQuery
    suspend fun trackPage(query: RoomRawQuery): List<SourceTrackEntity>

    @RawQuery
    suspend fun albumPage(query: RoomRawQuery): List<SourceAlbumEntity>

    @RawQuery
    suspend fun artistPage(query: RoomRawQuery): List<SourceArtistEntity>

    // Song keys made by an older way of reading them

    @Query("SELECT keysVersion FROM source_track WHERE keysVersion < :version LIMIT 1")
    suspend fun staleKeysVersion(version: Int): Int?

    @Query("SELECT id, artist, title FROM source_track WHERE keysVersion = :old AND id > :after ORDER BY id LIMIT :limit")
    suspend fun keyRows(old: Int, after: String, limit: Int): List<KeyRow>

    @Query("UPDATE source_track SET titleKey = :titleKey, matchKey = :matchKey, keysVersion = :version WHERE id = :id AND keysVersion = :old")
    suspend fun setKeys(id: String, titleKey: String, matchKey: String, version: Int, old: Int)

    // What is known of each source, and the copies built for them

    @Query("SELECT * FROM source_meta")
    suspend fun metas(): List<SourceMetaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(row: SourceMetaEntity)

    @Query("UPDATE source_meta SET stage = :stage WHERE sourceId = :sourceId")
    suspend fun setStage(sourceId: String, stage: String)

    @Query(
        """
        UPDATE source_meta SET stagedTracks = stagedTracks + :tracks, stagedAlbums = stagedAlbums + :albums,
            stagedArtists = stagedArtists + :artists
        WHERE sourceId = :sourceId
        """,
    )
    suspend fun addStaged(sourceId: String, tracks: Int, albums: Int, artists: Int)

    // Rows of the stage placed other than where their source rows are, by
    // table, written with the rows they count.
    @Query(
        """
        UPDATE source_meta SET movedTracks = movedTracks + :tracks, movedAlbums = movedAlbums + :albums,
            movedArtists = movedArtists + :artists
        WHERE sourceId = :sourceId
        """,
    )
    suspend fun addMoved(sourceId: String, tracks: Int, albums: Int, artists: Int)

    @Query(
        """
        UPDATE source_meta SET stage = 'none', stageOwner = NULL, stagedTracks = 0, stagedAlbums = 0,
            stagedArtists = 0, movedTracks = 0, movedAlbums = 0, movedArtists = 0, stamp = stamp + 1
        WHERE sourceId = :sourceId
        """,
    )
    suspend fun stageApplied(sourceId: String)

    @Query(
        """
        UPDATE source_meta SET stage = 'none', stageOwner = NULL, stagedTracks = 0, stagedAlbums = 0, stagedArtists = 0,
            movedTracks = 0, movedAlbums = 0, movedArtists = 0
        WHERE sourceId = :sourceId AND generation = :generation
        """,
    )
    suspend fun stageDropped(sourceId: String, generation: Long)

    // A copy being swapped in: its account's steps after a copy are owed
    // again, and the rows are that account's copy from now on.
    @Query("UPDATE account_sync SET afterSyncDone = 0 WHERE account = (SELECT stageOwner FROM source_meta WHERE sourceId = :sourceId)")
    suspend fun owedSteps(sourceId: String)

    @Query("UPDATE source_meta SET copyOf = stageOwner WHERE sourceId = :sourceId")
    suspend fun takeStageOwner(sourceId: String)

    // Whether a source's copy swapped in last is still leaving the stage;
    // null for a source with no record.

    @Query("SELECT stageClearing FROM source_meta WHERE sourceId = :sourceId")
    suspend fun stageClearing(sourceId: String): Boolean?

    @Query("UPDATE source_meta SET stageClearing = 1 WHERE sourceId = :sourceId")
    suspend fun markStageClearing(sourceId: String)

    @Query("UPDATE source_meta SET stageClearing = 0 WHERE sourceId = :sourceId")
    suspend fun stageCleared(sourceId: String)

    @Query("SELECT COUNT(*) FROM stage_track WHERE sourceId = :sourceId")
    suspend fun stageTrackCount(sourceId: String): Int

    @Query("SELECT COUNT(*) FROM stage_album WHERE sourceId = :sourceId")
    suspend fun stageAlbumCount(sourceId: String): Int

    @Query("SELECT COUNT(*) FROM stage_artist WHERE sourceId = :sourceId")
    suspend fun stageArtistCount(sourceId: String): Int

    @Query("SELECT MAX(seq) FROM stage_track WHERE sourceId = :sourceId")
    suspend fun lastStageTrackSeq(sourceId: String): Long?

    @Query("SELECT MAX(seq) FROM stage_album WHERE sourceId = :sourceId")
    suspend fun lastStageAlbumSeq(sourceId: String): Long?

    @Query("SELECT MAX(seq) FROM stage_artist WHERE sourceId = :sourceId")
    suspend fun lastStageArtistSeq(sourceId: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStageTracks(rows: List<StageTrackEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStageAlbums(rows: List<StageAlbumEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStageArtists(rows: List<StageArtistEntity>): List<Long>

    @Query("DELETE FROM stage_track WHERE sourceId = :sourceId")
    suspend fun deleteStageTracks(sourceId: String)

    @Query("DELETE FROM stage_album WHERE sourceId = :sourceId")
    suspend fun deleteStageAlbums(sourceId: String)

    @Query("DELETE FROM stage_artist WHERE sourceId = :sourceId")
    suspend fun deleteStageArtists(sourceId: String)

    @Transaction
    suspend fun clearStage(sourceId: String) {
        deleteStageTracks(sourceId)
        deleteStageAlbums(sourceId)
        deleteStageArtists(sourceId)
    }

    // Up to `limit` of a source's stage rows deleted, in one transaction of
    // their own; answers how many went.

    @Query("DELETE FROM stage_track WHERE rowid IN (SELECT rowid FROM stage_track WHERE sourceId = :sourceId LIMIT :limit)")
    suspend fun deleteStageTrackPage(sourceId: String, limit: Int): Int

    @Query("DELETE FROM stage_album WHERE rowid IN (SELECT rowid FROM stage_album WHERE sourceId = :sourceId LIMIT :limit)")
    suspend fun deleteStageAlbumPage(sourceId: String, limit: Int): Int

    @Query("DELETE FROM stage_artist WHERE rowid IN (SELECT rowid FROM stage_artist WHERE sourceId = :sourceId LIMIT :limit)")
    suspend fun deleteStageArtistPage(sourceId: String, limit: Int): Int

    @Query("SELECT id FROM take_in_log WHERE generation = :generation AND kind = :kind")
    suspend fun loggedIds(generation: Long, kind: String): List<String>

    @Query("DELETE FROM take_in_log WHERE generation = :generation")
    suspend fun clearTakeInLog(generation: Long)

    @Query("SELECT * FROM source_track WHERE id IN (:ids)")
    suspend fun liveTracks(ids: List<String>): List<SourceTrackEntity>

    @Query("SELECT * FROM source_album WHERE id IN (:ids)")
    suspend fun liveAlbums(ids: List<String>): List<SourceAlbumEntity>

    @Query("SELECT * FROM source_artist WHERE id IN (:ids)")
    suspend fun liveArtists(ids: List<String>): List<SourceArtistEntity>

    // Whether a source has a row no merge has placed yet.
    @Query("SELECT EXISTS(SELECT 1 FROM source_track WHERE sourceId = :sourceId AND mergedId = '')")
    suspend fun hasUnmerged(sourceId: String): Boolean

    // The merge's own record

    @Query("INSERT OR IGNORE INTO merge_state (id, skipKey, claimActive) VALUES (0, NULL, 0)")
    suspend fun ensureMergeState()

    @Query("SELECT skipKey FROM merge_state WHERE id = 0")
    suspend fun skipKey(): String?

    @Query("UPDATE merge_state SET skipKey = :key WHERE id = 0")
    suspend fun writeSkipKey(key: String?)

    @Query("UPDATE merge_state SET claimActive = :active WHERE id = 0")
    suspend fun writeClaimActive(active: Boolean)

    @Transaction
    suspend fun setSkipKey(key: String?) {
        ensureMergeState()
        writeSkipKey(key)
    }

    @Transaction
    suspend fun setClaimActive(active: Boolean) {
        ensureMergeState()
        writeClaimActive(active)
    }

    // How many swaps have changed the library's rows; it only grows. A walk
    // that reads it before its first read and again inside each write knows
    // whether song ids moved in between.
    @Query("SELECT COALESCE((SELECT swapCount FROM merge_state WHERE id = 0), 0)")
    suspend fun swapCount(): Long

    @Query("UPDATE merge_state SET swapCount = swapCount + 1 WHERE id = 0")
    suspend fun writeSwapCountRaised()

    @Transaction
    suspend fun raiseSwapCount() {
        ensureMergeState()
        writeSwapCountRaised()
    }

    @Query("SELECT server FROM merge_state WHERE id = 0")
    suspend fun mergedServer(): String?

    @Query("UPDATE merge_state SET server = :server WHERE id = 0")
    suspend fun writeMergedServer(server: String)

    @Transaction
    suspend fun setMergedServer(server: String) {
        ensureMergeState()
        writeMergedServer(server)
    }

    @Query("SELECT * FROM rating_note")
    suspend fun ratingNotes(): List<RatingNoteEntity>

    @Query("DELETE FROM rating_note")
    suspend fun clearRatingNotes()

    @Query("UPDATE track SET rating = :rating WHERE id = (SELECT mergedId FROM source_track WHERE id = :sourceRowId)")
    suspend fun showCopyRating(sourceRowId: String, rating: Int)

    // The merge's output

    @Insert
    suspend fun insertAlbumNext(rows: List<AlbumNextEntity>)

    @Insert
    suspend fun insertArtistNext(rows: List<ArtistNextEntity>)

    @Query("DELETE FROM merge_candidate")
    suspend fun deleteCandidates()

    @Query("DELETE FROM merged_id_next")
    suspend fun deleteMergedIdNext()

    @Query("DELETE FROM album_next")
    suspend fun deleteAlbumNext()

    @Query("DELETE FROM artist_next")
    suspend fun deleteArtistNext()

    // The merge's work rows a page at a time, for the clear after a merge.

    @Query("DELETE FROM merge_candidate WHERE rowid IN (SELECT rowid FROM merge_candidate LIMIT :limit)")
    suspend fun deleteCandidatePage(limit: Int): Int

    @Query("DELETE FROM merged_id_next WHERE rowid IN (SELECT rowid FROM merged_id_next LIMIT :limit)")
    suspend fun deleteMergedIdNextPage(limit: Int): Int

    @Query("DELETE FROM album_next WHERE rowid IN (SELECT rowid FROM album_next LIMIT :limit)")
    suspend fun deleteAlbumNextPage(limit: Int): Int

    @Query("DELETE FROM artist_next WHERE rowid IN (SELECT rowid FROM artist_next LIMIT :limit)")
    suspend fun deleteArtistNextPage(limit: Int): Int

    // Whether any of the merge's work rows are left for clearWork: its
    // output's and a save's find's (save_found, save_ref, save_node,
    // save_set), the take-in log of copies no longer being built or waiting,
    // and the rating notes once no server copy is; the renames in force are
    // not work rows.
    @Query(
        "SELECT EXISTS (SELECT 1 FROM merge_candidate) OR EXISTS (SELECT 1 FROM merged_id_next) " +
            "OR EXISTS (SELECT 1 FROM album_next) OR EXISTS (SELECT 1 FROM artist_next) " +
            "OR EXISTS (SELECT 1 FROM save_found) OR EXISTS (SELECT 1 FROM save_ref) " +
            "OR EXISTS (SELECT 1 FROM save_node) OR EXISTS (SELECT 1 FROM save_set) " +
            "OR EXISTS (SELECT 1 FROM take_in_log WHERE $STALE_TAKE_IN) " +
            "OR ($NO_SERVER_COPY AND EXISTS (SELECT 1 FROM rating_note))",
    )
    suspend fun hasMergeWork(): Boolean

    // Up to `limit` rows of the take-in log of copies no longer being built
    // or waiting, and of the rating notes once no server copy is; each
    // answers how many went.
    @Query("DELETE FROM take_in_log WHERE rowid IN (SELECT rowid FROM take_in_log WHERE $STALE_TAKE_IN LIMIT :limit)")
    suspend fun deleteStaleTakeInPage(limit: Int): Int

    @Query("DELETE FROM rating_note WHERE $NO_SERVER_COPY AND sourceRowId IN (SELECT sourceRowId FROM rating_note ORDER BY sourceRowId LIMIT :limit)")
    suspend fun deleteStaleNotePage(limit: Int): Int

    // A save part applied: its stop's words, kept with its record; and the
    // record gone, once the save's last transaction ends it.
    @Query("UPDATE merge_state SET applyStopped = :words WHERE id = 0 AND applyClaim IS NOT NULL")
    suspend fun setApplyStopped(words: String)

    @Query(
        "UPDATE merge_state SET applyClaim = NULL, applySkipKey = NULL, applyAccount = NULL, applyFirstCopy = 0, applyScan = NULL, " +
            "applyStopped = NULL WHERE id = 0",
    )
    suspend fun endApplyRecord()

    // Whether a save is part applied: its record stands.
    @Query("SELECT EXISTS (SELECT 1 FROM merge_state WHERE id = 0 AND applyClaim IS NOT NULL)")
    suspend fun applying(): Boolean

    @Transaction
    suspend fun clearMergeWork() {
        deleteCandidates()
        deleteMergedIdNext()
        deleteAlbumNext()
        deleteArtistNext()
    }

    @Query("SELECT COUNT(*) FROM track")
    suspend fun trackTotal(): Int

    @Query("SELECT COUNT(*) FROM album")
    suspend fun albumTotal(): Int

    @Query("SELECT COUNT(*) FROM artist")
    suspend fun artistTotal(): Int
}

// A source song's id with what its song keys are read from.
data class KeyRow(val id: String, val artist: String, val title: String)

// A take-in log row (`take_in_log`) of no copy being built, waiting or
// claimed: the copy it was noted for is over.
internal const val STALE_TAKE_IN =
    "generation NOT IN (SELECT generation FROM source_meta WHERE stage IN ('$STAGE_BUILDING', '$STAGE_READY', '$STAGE_CLAIMED'))"

// No server copy is being built, waiting or claimed, so no rating note is
// kept for one.
internal const val NO_SERVER_COPY = "NOT EXISTS (SELECT 1 FROM source_meta WHERE sourceId != 'device' AND stage != '$STAGE_NONE')"
