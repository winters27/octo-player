package app.winters.octo.listening

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.PlayedAt
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity

// The statements below are constants so the query plan test reads the
// same text Room runs. Each walk reads a page at a time in its key's
// order, led by an index, so none holds more than a page.

// The phone's plays of library songs, one row a song, in song id order.
internal const val PHONE_PLAY_PAGE_SQL = """
    SELECT p.trackId AS trackId, t.albumId AS albumId, t.artistId AS artistId, t.sortKey AS sortKey,
           t.artistSortKey AS artistSortKey, t.durationMs AS durationMs, COUNT(*) AS plays, MAX(p.startedAt) AS lastAt
    FROM play_event p CROSS JOIN track t ON t.id = p.trackId
    WHERE p.trackId > :after
    GROUP BY p.trackId ORDER BY p.trackId LIMIT :limit
"""

// The same, counting only plays from `since` on. The time is no index's
// lead, so the walk stays in song id order.
internal const val PHONE_PLAY_PAGE_SINCE_SQL = """
    SELECT p.trackId AS trackId, t.albumId AS albumId, t.artistId AS artistId, t.sortKey AS sortKey,
           t.artistSortKey AS artistSortKey, t.durationMs AS durationMs, COUNT(*) AS plays, MAX(p.startedAt) AS lastAt
    FROM play_event p CROSS JOIN track t ON t.id = p.trackId
    WHERE p.trackId > :after AND +p.startedAt >= :since
    GROUP BY p.trackId ORDER BY p.trackId LIMIT :limit
"""

internal const val PHONE_PLAYS_OF_SQL = """
    SELECT p.trackId AS trackId, t.albumId AS albumId, t.artistId AS artistId, t.sortKey AS sortKey,
           t.artistSortKey AS artistSortKey, t.durationMs AS durationMs, COUNT(*) AS plays, MAX(p.startedAt) AS lastAt
    FROM play_event p CROSS JOIN track t ON t.id = p.trackId
    WHERE p.trackId = :trackId
    GROUP BY p.trackId
"""

// What a server copy holds of the plays Octo sent the account in use:
// none without a sent row; otherwise the folded count and the newer sends
// that started no later than the server's last play, give or take the
// second it may round to (999 ms).
private const val HELD = """
    CASE WHEN sp.serverId IS NULL THEN 0 ELSE sp.folded + (
        SELECT COUNT(*) FROM sent_recent r
        WHERE r.account = :account AND r.serverId = s.nativeId
          AND s.lastPlayedAt IS NOT NULL AND r.startedAt <= s.lastPlayedAt + 999
    ) END
"""

private const val COPY_COLUMNS = """
    s.mergedId AS trackId, s.rowid AS copyRow, s.nativeId AS serverId, COALESCE(s.playCount, 0) AS plays,
    s.lastPlayedAt AS lastPlayedAt, $HELD AS held, t.albumId AS albumId, t.artistId AS artistId,
    t.sortKey AS sortKey, t.artistSortKey AS artistSortKey, t.durationMs AS durationMs
"""

// Server copies of library songs with a play record, in song id order and
// each song's copies in the order they were written. The tail of the key
// is a filter, so the index on mergedId leads the whole walk.
internal const val SERVER_COPY_PAGE_SQL = """
    SELECT $COPY_COLUMNS
    FROM source_track s CROSS JOIN track t ON t.id = s.mergedId
    LEFT JOIN sent_play sp ON sp.account = :account AND sp.serverId = s.nativeId
    WHERE s.mergedId >= :after AND CASE WHEN s.mergedId = :after THEN s.rowid > :afterRow ELSE 1 END
      AND s.sourceId LIKE 'server:%' AND (s.playCount > 0 OR s.lastPlayedAt IS NOT NULL)
    ORDER BY s.mergedId, s.rowid LIMIT :limit
"""

internal const val COPIES_OF_SQL = """
    SELECT $COPY_COLUMNS
    FROM source_track s CROSS JOIN track t ON t.id = s.mergedId
    LEFT JOIN sent_play sp ON sp.account = :account AND sp.serverId = s.nativeId
    WHERE s.mergedId = :trackId AND s.sourceId LIKE 'server:%' AND (s.playCount > 0 OR s.lastPlayedAt IS NOT NULL)
    ORDER BY s.rowid
"""

internal const val STORED_PAGE_SQL = "SELECT * FROM play_total WHERE trackId > :after ORDER BY trackId LIMIT :limit"

internal const val TOTAL_OF_SQL = "SELECT * FROM play_total WHERE trackId = :trackId"

internal const val DIFF_PAGE_SQL = "SELECT * FROM play_total_diff WHERE trackId > :after ORDER BY trackId LIMIT :limit"

internal const val DROP_DIFFS_SQL = "DELETE FROM play_total_diff WHERE trackId > :after AND trackId <= :upTo"

internal const val KNOWN_TRACKS_SQL = "SELECT id FROM track WHERE id IN (:ids)"

internal const val DELETE_TOTALS_SQL = "DELETE FROM play_total WHERE trackId IN (:ids)"

internal const val SWEEP_PAGE_SQL = "SELECT trackId FROM play_total WHERE trackId > :after ORDER BY trackId LIMIT :limit"

// Rows of a page whose song the library no longer has.
internal const val SWEEP_SQL = """
    DELETE FROM play_total WHERE trackId > :after AND trackId <= :upTo
      AND NOT EXISTS (SELECT 1 FROM track t WHERE t.id = play_total.trackId)
"""

internal const val STORED_COUNT_SQL = "SELECT COUNT(*) FROM play_total"

internal const val DIFF_COUNT_SQL = "SELECT COUNT(*) FROM play_total_diff"

// The most played songs, most first, then the one played last.
internal const val MOST_PLAYED_SQL = """
    SELECT t.*, p.plays AS plays, p.lastPlayedAt AS lastPlayedAt
    FROM play_total p CROSS JOIN track t ON t.id = p.trackId
    WHERE p.plays > 0
    ORDER BY p.plays DESC, p.lastPlayedAt DESC, p.trackId LIMIT :limit
"""

// Each album's listening, one row an album the library has, in album id
// order: its latest play for Recently played, how many of its songs were
// played, their plays together and the latest play of a played one.
internal const val ALBUM_PLAYS_PAGE_SQL = """
    SELECT p.albumId AS albumId, a.songCount AS songs, MAX(p.recentAt) AS recent, SUM(p.plays > 0) AS playedSongs,
           SUM(p.plays) AS plays, MAX(CASE WHEN p.plays > 0 THEN p.lastPlayedAt END) AS lastPlayed
    FROM play_total p CROSS JOIN album a ON a.id = p.albumId
    WHERE p.albumId > :after
    GROUP BY p.albumId ORDER BY p.albumId LIMIT :limit
"""

// Albums none of whose songs has a play, in album id order.
internal const val UNPLAYED_ALBUM_PAGE_SQL = """
    SELECT a.id AS id, a.addedAt AS addedAt FROM album a
    WHERE a.id > :after AND NOT EXISTS (SELECT 1 FROM play_total p WHERE p.albumId = a.id AND p.plays > 0)
    ORDER BY a.id LIMIT :limit
"""

internal const val ALBUMS_AMONG_SQL = "SELECT * FROM album WHERE id IN (:ids)"

internal const val TRACKS_AMONG_SQL = "SELECT * FROM track WHERE id IN (:ids)"

// The latest plays one by one, newest first, of songs the library has.
internal const val RECENT_PLAYS_SQL = """
    SELECT t.*, p.startedAt AS startedAt
    FROM play_event p CROSS JOIN track t ON t.id = p.trackId
    ORDER BY p.startedAt DESC LIMIT :limit
"""

internal const val PLAYS_AMONG_SQL = "SELECT trackId, plays, lastPlayedAt FROM play_total WHERE trackId IN (:ids) AND plays > 0"

internal const val ALBUM_TOP_SQL = """
    SELECT t.* FROM play_total p CROSS JOIN track t ON t.id = p.trackId
    WHERE p.albumId = :albumId AND p.plays > 0
    ORDER BY p.plays DESC, p.lastPlayedAt DESC, p.trackId LIMIT 1
"""

internal const val ARTIST_TOP_SQL = """
    SELECT t.* FROM play_total p CROSS JOIN track t ON t.id = p.trackId
    WHERE p.artistId = :artistId AND p.plays > 0
    ORDER BY p.plays DESC, p.lastPlayedAt DESC, p.trackId LIMIT 1
"""

internal const val RECENT_PHONE_SQL = """
    SELECT trackId FROM play_total WHERE phoneLastAt IS NOT NULL
    ORDER BY phoneLastAt DESC, trackId LIMIT :limit
"""

// One song's plays on the phone: how many and the latest start, with what
// the totals keep of the song.
data class PhonePlays(
    val trackId: String,
    val albumId: String,
    val artistId: String,
    val sortKey: String,
    val artistSortKey: String,
    val durationMs: Long,
    val plays: Int,
    val lastAt: Long,
)

// One server copy of a library song with a play record: the server's
// count and last play, how many of the plays Octo sent it that record
// holds, and what the totals keep of the song. `copyRow` orders a song's
// copies.
data class CopyPlays(
    val trackId: String,
    val copyRow: Long,
    val serverId: String,
    val plays: Int,
    val lastPlayedAt: Long?,
    val held: Int,
    val albumId: String,
    val artistId: String,
    val sortKey: String,
    val artistSortKey: String,
    val durationMs: Long,
)

// One album's listening, from the stored totals of its songs.
data class AlbumPlays(
    val albumId: String,
    val songs: Int,
    // The latest play that counts for Recently played, or null.
    val recent: Long?,
    val playedSongs: Int,
    val plays: Long,
    // The latest play of a played song, or null when none has one.
    val lastPlayed: Long?,
)

// An album with no played song, and when it came into the library.
data class UnplayedAlbum(val id: String, val addedAt: Long)

// A played song's stored plays and its latest play.
data class PlayCount(val trackId: String, val plays: Int, val lastPlayedAt: Long)

// The stored play totals: the walks that build them and the reads that
// answer from them. Only PlayTotals writes them.
@Dao
interface PlayTotalsDao {
    // The walks

    @Query(PHONE_PLAY_PAGE_SQL)
    suspend fun phonePlayPage(after: String, limit: Int): List<PhonePlays>

    @Query(PHONE_PLAY_PAGE_SINCE_SQL)
    suspend fun phonePlayPageSince(after: String, since: Long, limit: Int): List<PhonePlays>

    @Query(PHONE_PLAYS_OF_SQL)
    suspend fun phonePlaysOf(trackId: String): PhonePlays?

    @Query(SERVER_COPY_PAGE_SQL)
    suspend fun serverCopyPage(after: String, afterRow: Long, account: String?, limit: Int): List<CopyPlays>

    @Query(COPIES_OF_SQL)
    suspend fun copiesOf(trackId: String, account: String?): List<CopyPlays>

    @Query(STORED_PAGE_SQL)
    suspend fun storedPage(after: String, limit: Int): List<PlayTotalEntity>

    @Query(TOTAL_OF_SQL)
    suspend fun totalOf(trackId: String): PlayTotalEntity?

    @Query(STORED_COUNT_SQL)
    suspend fun storedCount(): Long

    // The diff a rebuild applies

    @Query("DELETE FROM play_total_diff")
    suspend fun clearDiffs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDiffs(rows: List<PlayTotalDiffEntity>)

    @Query(DIFF_PAGE_SQL)
    suspend fun diffPage(after: String, limit: Int): List<PlayTotalDiffEntity>

    @Query(DROP_DIFFS_SQL)
    suspend fun dropDiffs(after: String, upTo: String)

    @Query(DIFF_COUNT_SQL)
    suspend fun diffCount(): Long

    // The account a rebuild counts copies' plays for, where a save's groups
    // read it to count theirs.

    @Query("INSERT OR IGNORE INTO merge_state (id, skipKey, claimActive) VALUES (0, NULL, 0)")
    suspend fun ensureMergeState()

    @Query("UPDATE merge_state SET totalsAccount = :account WHERE id = 0")
    suspend fun setTotalsAccount(account: String?)

    @Query("SELECT totalsAccount FROM merge_state WHERE id = 0")
    suspend fun totalsAccount(): String?

    // Writes to the totals. At most 900 ids at a time.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTotals(rows: List<PlayTotalEntity>)

    @Query(DELETE_TOTALS_SQL)
    suspend fun deleteTotals(ids: List<String>): Int

    @Query(KNOWN_TRACKS_SQL)
    suspend fun knownTracks(ids: List<String>): List<String>

    @Query(SWEEP_PAGE_SQL)
    suspend fun sweepPage(after: String, limit: Int): List<String>

    @Query(SWEEP_SQL)
    suspend fun sweep(after: String, upTo: String): Int

    // The readers

    @Query(MOST_PLAYED_SQL)
    suspend fun mostPlayed(limit: Int): List<PlayedTrack>

    @Query(ALBUM_PLAYS_PAGE_SQL)
    suspend fun albumPlaysPage(after: String, limit: Int): List<AlbumPlays>

    @Query(UNPLAYED_ALBUM_PAGE_SQL)
    suspend fun unplayedAlbumPage(after: String, limit: Int): List<UnplayedAlbum>

    @Query(ALBUMS_AMONG_SQL)
    suspend fun albumsAmong(ids: List<String>): List<AlbumEntity>

    @Query(TRACKS_AMONG_SQL)
    suspend fun tracksAmong(ids: List<String>): List<TrackEntity>

    @Query(RECENT_PLAYS_SQL)
    suspend fun recentPlays(limit: Int): List<PlayedAt>

    @Query(PLAYS_AMONG_SQL)
    suspend fun playsAmong(ids: List<String>): List<PlayCount>

    // An album's or an artist's most played song, if any was played.
    @Query(ALBUM_TOP_SQL)
    suspend fun mostPlayedOfAlbum(albumId: String): TrackEntity?

    @Query(ARTIST_TOP_SQL)
    suspend fun mostPlayedOfArtist(artistId: String): TrackEntity?

    // The songs last played on the phone, the latest first.
    @Query(RECENT_PHONE_SQL)
    suspend fun recentPhoneIds(limit: Int): List<String>
}
