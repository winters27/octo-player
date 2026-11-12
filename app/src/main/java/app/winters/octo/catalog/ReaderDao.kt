package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

// An album's first song in its own order.
internal const val ALBUM_FIRST_SQL = "SELECT * FROM track WHERE albumId = :albumId ORDER BY albumOrder, id LIMIT 1"

// An artist's first album that has songs, newest first, as their page
// lists them.
internal const val ARTIST_FIRST_ALBUM_SQL =
    "SELECT a.id FROM album a WHERE a.artistId = :artistId AND EXISTS (SELECT 1 FROM track t WHERE t.albumId = a.id) " +
        "ORDER BY a.year DESC, a.sortKey, a.id LIMIT 1"

// The library in library order, `(sortKey, id)`, a page after a song.
internal const val TRACK_ORDER_PAGE_SQL = "SELECT * FROM track WHERE (sortKey, id) > (?, ?) ORDER BY sortKey, id LIMIT ?"

// A fix run's undo steps before `before`, the last done first.
internal const val FIX_UNDO_PAGE_SQL = "SELECT * FROM fix_undo WHERE owner = :owner AND ord < :before ORDER BY ord DESC LIMIT :limit"

// What the library's other readers read of songs by statements of their
// own: the radio, live lists, Library health and the matching of files.
@Dao
interface ReaderDao {
    // Songs a statement over `track` answers.
    @RawQuery
    suspend fun tracks(query: SupportSQLiteQuery): List<TrackEntity>

    // The same, on the calling thread, for a read inside a check that does
    // not suspend.
    @RawQuery
    fun tracksNow(query: SupportSQLiteQuery): List<TrackEntity>

    // Copies a statement over `source_track` answers, each with its row
    // number as `node`.
    @RawQuery
    fun copyRows(query: SupportSQLiteQuery): List<CopyRow>

    @Query(ALBUM_FIRST_SQL)
    suspend fun firstOfAlbum(albumId: String): TrackEntity?

    @Query(ARTIST_FIRST_ALBUM_SQL)
    suspend fun firstAlbumOfArtist(artistId: String): String?

    // A matching job's staged entries.

    @Insert
    suspend fun putQueries(rows: List<MatchQueryEntity>)

    @Insert
    suspend fun putTitles(rows: List<MatchTitleEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putLookups(rows: List<MatchLookupEntity>)

    // What puts a fix run back, written as its steps are done.

    @Insert
    suspend fun putUndo(rows: List<FixUndoEntity>)

    @Query(FIX_UNDO_PAGE_SQL)
    suspend fun undoPage(owner: String, before: Long, limit: Int): List<FixUndoEntity>
}

// A source's copy of a song and its row number.
data class CopyRow(val node: Long, @Embedded val copy: SourceTrackEntity)

// A page of the library in library order after the song at `(sortKey,
// id)`; from the top for two empty strings.
internal suspend fun ReaderDao.trackOrderPage(afterSortKey: String, afterId: String, limit: Int): List<TrackEntity> =
    tracks(SimpleSQLiteQuery(TRACK_ORDER_PAGE_SQL, arrayOf<Any>(afterSortKey, afterId, limit)))
