package app.winters.octo.listening

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.winters.octo.catalog.RatingCopy
import app.winters.octo.catalog.ServerCopy
import app.winters.octo.catalog.ServerLink
import app.winters.octo.catalog.SyncStarredEntity
import kotlinx.coroutines.flow.Flow

// How many server copies one step of an after-sync walk reads.
internal const val AFTER_SYNC_CHUNK = 500

// The kind of the row that marks a generation of starred rows complete.
const val STARRED_END = "end"

// The statements below are constants so the query plan test reads the
// same text Room runs.

internal const val COPY_PAGE_SQL = """
    SELECT mergedId AS trackId, nativeId AS serverId, lastPlayedAt FROM source_track
    WHERE sourceId = :sourceId AND nativeId > :after AND mergedId != ''
    ORDER BY nativeId LIMIT :limit
"""

internal const val RATING_PAGE_SQL = """
    SELECT mergedId AS trackId, nativeId AS serverId, COALESCE(rating, 0) AS rating FROM source_track
    WHERE sourceId = :sourceId AND nativeId > :after AND mergedId != ''
    ORDER BY nativeId LIMIT :limit
"""

internal const val LINKS_AMONG_SQL = """
    SELECT nativeId AS serverId, mergedId AS trackId FROM source_track
    WHERE sourceId = :sourceId AND nativeId IN (:serverIds) AND mergedId != ''
"""

internal const val HAS_COPIES_SQL =
    "SELECT EXISTS(SELECT 1 FROM source_track WHERE sourceId = :sourceId AND mergedId != '')"

internal const val LIKED_AMONG_SQL = "SELECT trackId FROM liked_track WHERE trackId IN (:trackIds)"

internal const val PHONE_RATINGS_AMONG_SQL = "SELECT trackId, rating FROM track_rating WHERE trackId IN (:trackIds)"

internal const val STARRED_AMONG_SQL = """
    SELECT id, starredAt FROM sync_starred
    WHERE generation = :generation AND kind = 'song' AND id IN (:serverIds)
"""

internal const val SYNCED_STARS_AMONG_SQL =
    "SELECT serverId FROM synced_star WHERE account = :account AND serverId IN (:serverIds)"

internal const val SYNCED_RATINGS_AMONG_SQL =
    "SELECT * FROM synced_rating WHERE account = :account AND serverId IN (:serverIds)"

internal const val NEXT_GENERATION_SQL = "SELECT COALESCE((SELECT MAX(generation) FROM sync_starred), 0) + 1"

internal const val COMPLETE_STARRED_SQL = """
    SELECT generation FROM sync_starred
    WHERE kind = 'end' AND generation = (SELECT MAX(generation) FROM sync_starred)
"""

internal const val STARRED_OF_SQL = "SELECT id, starredAt FROM sync_starred WHERE generation = :generation AND kind = :kind"

// Sent plays of songs the server in use no longer has as a library song.
internal const val DROP_UNKNOWN_RECENT_SQL = """
    DELETE FROM sent_recent WHERE account = :account AND NOT EXISTS (
        SELECT 1 FROM source_track s
        WHERE s.sourceId = :sourceId AND s.nativeId = sent_recent.serverId AND s.mergedId != ''
    )
"""

internal const val DROP_UNKNOWN_SENT_SQL = """
    DELETE FROM sent_play WHERE account = :account AND NOT EXISTS (
        SELECT 1 FROM source_track s
        WHERE s.sourceId = :sourceId AND s.nativeId = sent_play.serverId AND s.mergedId != ''
    )
"""

// Plays the server's record holds: started no later than its last play,
// give or take the second it may round to (999 ms).
internal const val FOLD_HELD_SQL = """
    UPDATE sent_play SET folded = folded + (
        SELECT COUNT(*) FROM sent_recent r, source_track s
        WHERE r.account = sent_play.account AND r.serverId = sent_play.serverId
          AND s.sourceId = :sourceId AND s.nativeId = r.serverId
          AND s.lastPlayedAt IS NOT NULL AND r.startedAt <= s.lastPlayedAt + 999
    )
    WHERE account = :account AND EXISTS (
        SELECT 1 FROM sent_recent r, source_track s
        WHERE r.account = sent_play.account AND r.serverId = sent_play.serverId
          AND s.sourceId = :sourceId AND s.nativeId = r.serverId
          AND s.lastPlayedAt IS NOT NULL AND r.startedAt <= s.lastPlayedAt + 999
    )
"""

internal const val DROP_HELD_SQL = """
    DELETE FROM sent_recent WHERE account = :account AND EXISTS (
        SELECT 1 FROM source_track s
        WHERE s.sourceId = :sourceId AND s.nativeId = sent_recent.serverId
          AND s.lastPlayedAt IS NOT NULL AND sent_recent.startedAt <= s.lastPlayedAt + 999
    )
"""

internal const val SENT_ROWS_SQL = """
    SELECT p.serverId AS serverId, p.folded AS folded, r.startedAt AS startedAt
    FROM sent_play p LEFT JOIN sent_recent r ON r.account = p.account AND r.serverId = p.serverId
    WHERE p.account = :account
    ORDER BY p.serverId, r.startedAt
"""

internal const val PENDING_SQL = "SELECT * FROM pending_play WHERE account = :account ORDER BY startedAt, serverId"

// The oldest plays over the cap, oldest first.
internal const val TRIM_PENDING_SQL = """
    DELETE FROM pending_play WHERE rowid IN (
        SELECT rowid FROM pending_play WHERE account = :account ORDER BY startedAt, serverId
        LIMIT MAX(0, (SELECT COUNT(*) FROM pending_play WHERE account = :account) - :max)
    )
"""

// A server copy's starred time as getStarred2 listed it.
data class StarredRow(val id: String, val starredAt: String?)

// A rating made on the phone.
data class PhoneRating(val trackId: String, val rating: Int)

// One sent play row: a song's folded count and one newer start, if any.
data class SentRow(val serverId: String, val folded: Int, val startedAt: Long?)

// One step of the stars walk: server copies of library songs, and for
// them which songs are liked, which copies the server starred (with when),
// and which both sides last agreed were starred.
class StarChunk(
    val copies: List<ServerCopy>,
    val liked: Set<String>,
    val starred: Map<String, String?>,
    val synced: Set<String>,
)

// One step of the ratings walk: server copies with their rating there, the
// ratings made on the phone for their songs, the ratings both sides last
// agreed on, and the server songs with a tap the server has yet to confirm.
class RatingChunk(
    val copies: List<RatingCopy>,
    val local: Map<String, Int>,
    val synced: Map<String, Int>,
    val tapped: Set<String> = emptySet(),
)

// What an older version kept in its preferences file, as rows.
class ListeningImport(
    val stars: List<SyncedStarEntity>,
    val ratings: List<SyncedRatingEntity>,
    val pending: List<PendingPlayEntity>,
    val sentPlays: List<SentPlayEntity>,
    val sentRecent: List<SentRecentEntity>,
)

// Keeping in step with the signed-in server: the stars, ratings and plays
// ListeningStore keeps, the server's stars as last read, and the walks over
// a server's copies after each copy of its library, a chunk at a time by
// server id.
@Dao
interface ListeningDao {
    // Server copies of library songs

    @Query(COPY_PAGE_SQL)
    suspend fun copyPage(sourceId: String, after: String, limit: Int): List<ServerCopy>

    @Query(RATING_PAGE_SQL)
    suspend fun ratingPage(sourceId: String, after: String, limit: Int): List<RatingCopy>

    // The library song each of these server songs is now. At most 500 ids.
    @Query(LINKS_AMONG_SQL)
    suspend fun linksAmong(sourceId: String, serverIds: List<String>): List<ServerLink>

    @Query(HAS_COPIES_SQL)
    suspend fun hasServerCopies(sourceId: String): Boolean

    @Query(LIKED_AMONG_SQL)
    suspend fun likedAmong(trackIds: List<String>): List<String>

    @Query(PHONE_RATINGS_AMONG_SQL)
    suspend fun phoneRatingsAmong(trackIds: List<String>): List<PhoneRating>

    @Query(STARRED_AMONG_SQL)
    suspend fun starredAmong(generation: Long, serverIds: List<String>): List<StarredRow>

    @Query(SYNCED_STARS_AMONG_SQL)
    suspend fun syncedStarsAmong(account: String, serverIds: List<String>): List<String>

    @Query(SYNCED_RATINGS_AMONG_SQL)
    suspend fun syncedRatingsAmong(account: String, serverIds: List<String>): List<SyncedRatingEntity>

    // The next chunk of the stars walk after server id `after` ("" for the
    // first), read in one go so its lookups agree with its copies.
    @Transaction
    suspend fun starChunk(sourceId: String, account: String, generation: Long, after: String): StarChunk {
        val copies = copyPage(sourceId, after, AFTER_SYNC_CHUNK)
        if (copies.isEmpty()) return StarChunk(copies, emptySet(), emptyMap(), emptySet())
        val serverIds = copies.map { it.serverId }
        return StarChunk(
            copies,
            likedAmong(copies.map { it.trackId }.distinct()).toSet(),
            starredAmong(generation, serverIds).associate { it.id to it.starredAt },
            syncedStarsAmong(account, serverIds).toSet(),
        )
    }

    // The next chunk of the ratings walk, the same way.
    @Transaction
    suspend fun ratingChunk(sourceId: String, account: String, after: String): RatingChunk {
        val copies = ratingPage(sourceId, after, AFTER_SYNC_CHUNK)
        if (copies.isEmpty()) return RatingChunk(copies, emptyMap(), emptyMap())
        return RatingChunk(
            copies,
            phoneRatingsAmong(copies.map { it.trackId }.distinct()).associate { it.trackId to cleanRating(it.rating) },
            syncedRatingsAmong(account, copies.map { it.serverId }).associate { it.serverId to it.rating },
            tappedAmong(sourceId, copies.map { it.serverId }).toSet(),
        )
    }

    @Query(
        """
        SELECT s.nativeId FROM rating_tap t JOIN source_track s ON s.id = t.sourceRowId
        WHERE s.sourceId = :sourceId AND s.nativeId IN (:serverIds)
        """,
    )
    suspend fun tappedAmong(sourceId: String, serverIds: List<String>): List<String>

    // A send of `rating` landed: the taps it answers go. A tap made since,
    // with another rating, stays to be sent.
    @Query(
        """
        DELETE FROM rating_tap WHERE rating = :rating AND sourceRowId IN
            (SELECT id FROM source_track WHERE sourceId = :sourceId AND nativeId IN (:serverIds))
        """,
    )
    suspend fun clearTaps(sourceId: String, serverIds: List<String>, rating: Int)

    // The server's stars, as getStarred2 last listed them

    @Query(NEXT_GENERATION_SQL)
    suspend fun nextStarredGeneration(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStarred(rows: List<SyncStarredEntity>)

    @Query("DELETE FROM sync_starred WHERE generation < :generation")
    suspend fun dropStarredBefore(generation: Long)

    @Query("DELETE FROM sync_starred WHERE generation = :generation")
    suspend fun dropStarred(generation: Long)

    // The newest generation, when it was read whole; null otherwise.
    @Query(COMPLETE_STARRED_SQL)
    suspend fun completeStarred(): Long?

    // One kind of a generation's stars. Albums and artists only: their
    // number grows with the library's albums and artists, not its songs.
    @Query(STARRED_OF_SQL)
    suspend fun starredOf(generation: Long, kind: String): List<StarredRow>

    // Stars both sides last agreed on

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncedStars(rows: List<SyncedStarEntity>)

    @Query("DELETE FROM synced_star WHERE account = :account AND serverId IN (:serverIds)")
    suspend fun removeSyncedStars(account: String, serverIds: List<String>)

    // A chunk's record replaced by what the walk settled on.
    @Transaction
    suspend fun settleStars(account: String, chunk: List<String>, synced: List<SyncedStarEntity>) {
        removeSyncedStars(account, chunk)
        insertSyncedStars(synced)
    }

    // Ratings both sides last agreed on

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSyncedRatings(rows: List<SyncedRatingEntity>)

    @Query("DELETE FROM synced_rating WHERE account = :account AND serverId IN (:serverIds)")
    suspend fun clearSyncedRatings(account: String, serverIds: List<String>)

    @Transaction
    suspend fun settleRatings(account: String, chunk: List<String>, synced: List<SyncedRatingEntity>) {
        clearSyncedRatings(account, chunk)
        putSyncedRatings(synced)
    }

    // Plays waiting for the server, at most a cap per account

    @Query(PENDING_SQL)
    suspend fun pending(account: String): List<PendingPlayEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPending(rows: List<PendingPlayEntity>)

    @Query(TRIM_PENDING_SQL)
    suspend fun trimPending(account: String, max: Int)

    @Query("DELETE FROM pending_play WHERE account = :account AND serverId = :serverId AND startedAt = :startedAt")
    suspend fun removePending(account: String, serverId: String, startedAt: Long)

    // A play waits; past the cap the oldest are let go.
    @Transaction
    suspend fun addPending(play: PendingPlayEntity, max: Int) {
        insertPending(listOf(play))
        trimPending(play.account, max)
    }

    // A waiting play is done with: sent, or refused for good.
    @Transaction
    suspend fun finishPending(play: PendingPlayEntity, sent: Boolean) {
        removePending(play.account, play.serverId, play.startedAt)
        if (sent) {
            insertSentPlays(listOf(SentPlayEntity(play.account, play.serverId, 0)))
            insertSentRecent(listOf(SentRecentEntity(play.account, play.serverId, play.startedAt)))
        }
    }

    // Plays sent

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSentPlays(rows: List<SentPlayEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSentRecent(rows: List<SentRecentEntity>)

    @Query(SENT_ROWS_SQL)
    fun sentRows(account: String): Flow<List<SentRow>>

    @Query(DROP_UNKNOWN_RECENT_SQL)
    suspend fun dropUnknownRecent(account: String, sourceId: String)

    @Query(DROP_UNKNOWN_SENT_SQL)
    suspend fun dropUnknownSent(account: String, sourceId: String)

    @Query(FOLD_HELD_SQL)
    suspend fun foldHeld(account: String, sourceId: String)

    @Query(DROP_HELD_SQL)
    suspend fun dropHeld(account: String, sourceId: String)

    // After a sync: plays the server's record now holds become a plain
    // number, and songs the server no longer has are forgotten.
    @Transaction
    suspend fun foldSent(account: String, sourceId: String) {
        dropUnknownRecent(account, sourceId)
        dropUnknownSent(account, sourceId)
        foldHeld(account, sourceId)
        dropHeld(account, sourceId)
    }

    // An account taken off the list

    @Query("DELETE FROM synced_star WHERE account = :account")
    suspend fun forgetStars(account: String)

    @Query("DELETE FROM synced_rating WHERE account = :account")
    suspend fun forgetRatings(account: String)

    @Query("DELETE FROM pending_play WHERE account = :account")
    suspend fun forgetPending(account: String)

    @Query("DELETE FROM sent_play WHERE account = :account")
    suspend fun forgetSentPlays(account: String)

    @Query("DELETE FROM sent_recent WHERE account = :account")
    suspend fun forgetSentRecent(account: String)

    @Transaction
    suspend fun forget(account: String) {
        forgetStars(account)
        forgetRatings(account)
        forgetPending(account)
        forgetSentPlays(account)
        forgetSentRecent(account)
    }

    // Taking in an older version's values, once

    @Query("SELECT EXISTS(SELECT 1 FROM listening_import WHERE id = 0)")
    suspend fun imported(): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncedRatings(rows: List<SyncedRatingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markImported(row: ListeningImportEntity)

    // The rows and the mark that they are in commit together.
    @Transaction
    suspend fun importAll(rows: ListeningImport, doneAt: Long) {
        insertSyncedStars(rows.stars)
        insertSyncedRatings(rows.ratings)
        insertPending(rows.pending)
        insertSentPlays(rows.sentPlays)
        insertSentRecent(rows.sentRecent)
        markImported(ListeningImportEntity(0, doneAt))
    }
}
