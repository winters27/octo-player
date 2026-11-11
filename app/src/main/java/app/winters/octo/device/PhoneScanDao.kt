package app.winters.octo.device

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import app.winters.octo.catalog.FileTagsEntity
import app.winters.octo.catalog.PhoneScanStateEntity
import app.winters.octo.catalog.ScanArtistEntity
import app.winters.octo.catalog.ScanClusterEntity
import app.winters.octo.catalog.ScanFileEntity
import app.winters.octo.catalog.ScanGenreNameEntity
import app.winters.octo.catalog.ScanGroupEntity
import app.winters.octo.catalog.ScanRowEntity
import app.winters.octo.catalog.ScanTallyEntity
import app.winters.octo.catalog.StageAlbumEntity
import app.winters.octo.catalog.StageArtistEntity
import app.winters.octo.catalog.StageTrackEntity
import kotlinx.coroutines.flow.Flow

// What a phone scan writes and reads (PhoneScan), and where the phone's
// files sit for the screens that show them by folder or write them out.
// Every statement over file-sized tables runs on an index
// (PhoneScanQueryPlanTest), reads or writes a page at a time and binds at
// most 900 values.
@Dao
interface PhoneScanDao {
    // The files as listed. The insert answers each row's id, -1 for a file
    // listed twice, whose first listing is kept.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFiles(rows: List<ScanFileEntity>): List<Long>

    @Query(PhoneScanSql.FILE_PAGE)
    suspend fun filePage(after: Long, limit: Int): List<ScanFileEntity>

    @Query(PhoneScanSql.FILES_BY_ID)
    suspend fun filesById(ids: List<Long>): List<ScanFileEntity>

    @Query(PhoneScanSql.FILE_TAG_COUNT)
    suspend fun fileTagCount(): Long

    // Tags

    @Query(PhoneScanSql.STALE_PAGE)
    suspend fun stalePage(after: Long, version: Int, limit: Int): List<StaleFile>

    @Query(PhoneScanSql.GONE_PAGE)
    suspend fun gonePage(after: Long, limit: Int): List<Long>

    @Query(PhoneScanSql.READ_TAGS)
    suspend fun readTags(ids: List<Long>): List<FileTagsEntity>

    // Albums by name, and how they split

    @Query(PhoneScanSql.NEW_GROUP)
    suspend fun newGroup(nameKey: String, splitKey: String, clusterSeq: Long, firstSeq: Long)

    @Query(PhoneScanSql.GROUP_ID)
    suspend fun groupId(nameKey: String, splitKey: String): Long

    @Query(PhoneScanSql.NEW_CLUSTER)
    suspend fun newCluster(nameKey: String, groupId: Long, firstSeq: Long)

    @Query(PhoneScanSql.CLUSTERS)
    suspend fun clusters(keys: List<String>): List<ScanClusterEntity>

    @Update
    suspend fun updateClusters(rows: List<ScanClusterEntity>)

    @Query(PhoneScanSql.FIRST_CLUSTERS)
    suspend fun firstClusters(limit: Int): List<ScanClusterEntity>

    @Query(PhoneScanSql.CLUSTERS_AFTER)
    suspend fun clustersAfter(after: String, limit: Int): List<ScanClusterEntity>

    @Query(PhoneScanSql.SET_SPLIT)
    suspend fun setSplit(id: Long, splitKey: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRows(rows: List<ScanRowEntity>)

    @Query(PhoneScanSql.GROUP_ROWS)
    suspend fun groupRows(groupId: Long, after: Long, limit: Int): List<ScanRowEntity>

    @Query(PhoneScanSql.MOVE_ROWS)
    suspend fun moveRows(groupId: Long, ids: List<Long>)

    // How often each value comes up for an owner

    @Query(PhoneScanSql.NEW_TALLY)
    suspend fun newTally(owner: String, value: String, first: Long)

    @Query(PhoneScanSql.ADD_TALLY)
    suspend fun addTally(owner: String, value: String, n: Int)

    @Query(PhoneScanSql.FIRST_TALLIES)
    suspend fun firstTallies(owner: String, limit: Int): List<ScanTallyEntity>

    @Query(PhoneScanSql.TALLIES_AFTER)
    suspend fun talliesAfter(owner: String, after: String, limit: Int): List<ScanTallyEntity>

    @Query(PhoneScanSql.GENRE_NAMES)
    suspend fun genreNames(keys: List<String>): List<ScanGenreNameEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenreNames(rows: List<ScanGenreNameEntity>)

    // Albums

    @Query(PhoneScanSql.FIRST_GROUPS)
    suspend fun firstGroups(limit: Int): List<ScanGroupEntity>

    // PhoneScanSql.GROUPS_AFTER with clusterSeq, firstSeq and limit.
    @RawQuery
    suspend fun groupsAfter(query: SupportSQLiteQuery): List<ScanGroupEntity>

    @Query(PhoneScanSql.GROUP_SIZE)
    suspend fun groupSize(groupId: Long): Int

    // How many files the folder rules let in.
    @Query(PhoneScanSql.ROW_COUNT)
    suspend fun rowCount(): Long

    // Only asked of an album whose every file has a number.
    @Query(PhoneScanSql.FILE_NUMBERS)
    suspend fun fileNumbers(groupId: Long, limit: Int): List<Int>

    @Query(PhoneScanSql.NUMBER_BY_NAMES)
    suspend fun numberByNames(groupId: Long)

    @Query(PhoneScanSql.FIRST_ORDERED)
    suspend fun firstOrdered(groupId: Long, limit: Int): List<ScanRowEntity>

    // PhoneScanSql.ORDERED_AFTER with groupId, discKey, numKey, nameOrder, seq and limit.
    @RawQuery
    suspend fun orderedAfter(query: SupportSQLiteQuery): List<ScanRowEntity>

    @Query(PhoneScanSql.DELETE_ROWS)
    suspend fun deleteRows(ids: List<Long>)

    @Query(PhoneScanSql.DELETE_FILES)
    suspend fun deleteFiles(ids: List<Long>)

    @Insert
    suspend fun insertStageTracks(rows: List<StageTrackEntity>)

    // Two albums whose ids collide keep the later, as the source's rows do.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putStageAlbums(rows: List<StageAlbumEntity>)

    @Insert
    suspend fun insertStageArtists(rows: List<StageArtistEntity>)

    // Artists

    @Query(PhoneScanSql.NEW_ARTIST)
    suspend fun newArtist(artistId: String, firstSeq: Long, newestAt: Long, artwork: String?)

    @Query(PhoneScanSql.ADD_ARTIST)
    suspend fun addArtist(artistId: String, albums: Int, songs: Int, sortName: String?)

    @Query(PhoneScanSql.NEWER_ARTWORK)
    suspend fun newerArtwork(artistId: String, addedAt: Long, artwork: String?)

    @Query(PhoneScanSql.ARTIST_PAGE)
    suspend fun artistPage(after: Long, limit: Int): List<ScanArtistEntity>

    // The stage and the scan's record

    @Query(PhoneScanSql.MARK_BUILDING)
    suspend fun markBuilding(generation: Long)

    @Query(PhoneScanSql.END_UNCLAIMED)
    suspend fun endUnclaimed(generation: Long): Int

    @Query(PhoneScanSql.STAGE_COUNTS)
    suspend fun stageCounts(): StageCounts

    @Query(PhoneScanSql.LIVE_TRACK_COUNT)
    suspend fun liveTrackCount(): Long

    @Query(PhoneScanSql.STATE)
    suspend fun state(): PhoneScanStateEntity?

    @Query(PhoneScanSql.KEEP_STATE)
    suspend fun keepState()

    @Query(PhoneScanSql.FORGET_DIGEST)
    suspend fun forgetDigest()

    @Query(PhoneScanSql.SAVE_DIGEST)
    suspend fun saveDigest(digest: String, tracks: Int, albums: Int, artists: Int)

    // Emptied a chunk at a time; each answers the rows it took.

    @Query(PhoneScanSql.DRAIN_FILES)
    suspend fun drainFiles(): Int

    @Query(PhoneScanSql.DRAIN_FILES_REST)
    suspend fun drainFilesRest(): Int

    @Query(PhoneScanSql.DRAIN_ROWS)
    suspend fun drainRows(): Int

    @Query(PhoneScanSql.DRAIN_ROWS_REST)
    suspend fun drainRowsRest(): Int

    @Query(PhoneScanSql.DRAIN_CLUSTERS)
    suspend fun drainClusters(): Int

    @Query(PhoneScanSql.DRAIN_CLUSTERS_REST)
    suspend fun drainClustersRest(): Int

    @Query(PhoneScanSql.DRAIN_GROUPS)
    suspend fun drainGroups(): Int

    @Query(PhoneScanSql.DRAIN_GROUPS_REST)
    suspend fun drainGroupsRest(): Int

    @Query(PhoneScanSql.DRAIN_TALLIES)
    suspend fun drainTallies(): Int

    @Query(PhoneScanSql.DRAIN_TALLIES_REST)
    suspend fun drainTalliesRest(): Int

    @Query(PhoneScanSql.DRAIN_GENRE_NAMES)
    suspend fun drainGenreNames(): Int

    @Query(PhoneScanSql.DRAIN_GENRE_NAMES_REST)
    suspend fun drainGenreNamesRest(): Int

    @Query(PhoneScanSql.DRAIN_ARTISTS)
    suspend fun drainArtists(): Int

    @Query(PhoneScanSql.DRAIN_ARTISTS_REST)
    suspend fun drainArtistsRest(): Int

    @Query(PhoneScanSql.DRAIN_STAGE_TRACKS)
    suspend fun drainStageTracks(): Int

    @Query(PhoneScanSql.DRAIN_STAGE_TRACKS_REST)
    suspend fun drainStageTracksRest(): Int

    @Query(PhoneScanSql.DRAIN_STAGE_ALBUMS)
    suspend fun drainStageAlbums(): Int

    @Query(PhoneScanSql.DRAIN_STAGE_ALBUMS_REST)
    suspend fun drainStageAlbumsRest(): Int

    @Query(PhoneScanSql.DRAIN_STAGE_ARTISTS)
    suspend fun drainStageArtists(): Int

    @Query(PhoneScanSql.DRAIN_STAGE_ARTISTS_REST)
    suspend fun drainStageArtistsRest(): Int

    // Where the phone's files sit, from the library's own rows

    @Query(PhoneScanSql.PHONE_PLACES)
    suspend fun phonePlaces(afterSeq: Long, limit: Int): List<PhonePlace>

    @Query(PhoneScanSql.PHONE_VOLUMES)
    suspend fun phoneVolumes(): List<PhoneVolume>

    @Query(PhoneScanSql.PHONE_STAMP)
    fun phoneStamp(): Flow<Long>

    @Query(PhoneScanSql.PHONE_PATHS_OF)
    suspend fun phonePathsOf(ids: List<String>): List<PhonePath>
}

// A listed file whose tags are to be read again.
data class StaleFile(val mediaId: Long, val uri: String, val modifiedAt: Long, val sizeBytes: Long)

// How many rows the phone's stage holds.
data class StageCounts(val tracks: Int, val albums: Int, val artists: Int)

// One phone song in the source's order: its row, the library song it is,
// and the folder and storage its file is in.
data class PhonePlace(val id: String, val nativeId: String, val mergedId: String, val folder: String?, val storageVolume: String?, val seq: Long)

// A storage the phone's music is on; null for the main one when the media
// library named none.
data class PhoneVolume(val storageVolume: String?)

// Where a library song's phone copy is: its folder and file name, and the
// copy's place in the source's order.
data class PhonePath(val mergedId: String, val folder: String?, val fileName: String?, val seq: Long)

// A phone scan's statements, kept here so their query plans can be checked
// on the oldest SQLite the app runs on and their shapes checked by rule:
// every insert into a scan table names each column that has no default,
// and every delete takes a range or a page.
object PhoneScanSql {
    const val FILE_PAGE = "SELECT * FROM scan_file WHERE seq > :after ORDER BY seq LIMIT :limit"
    const val FILES_BY_ID = "SELECT * FROM scan_file WHERE mediaId IN (:ids)"
    const val FILE_TAG_COUNT = "SELECT COUNT(*) FROM file_tags"
    const val STALE_PAGE =
        "SELECT f.mediaId AS mediaId, f.uri AS uri, f.modifiedAt AS modifiedAt, f.sizeBytes AS sizeBytes " +
            "FROM scan_file f LEFT JOIN file_tags t ON t.mediaId = f.mediaId " +
            "WHERE f.mediaId > :after AND (t.mediaId IS NULL OR t.modifiedAt != f.modifiedAt OR t.size != f.sizeBytes OR t.tagsVersion != :version) " +
            "ORDER BY f.mediaId LIMIT :limit"
    const val GONE_PAGE =
        "SELECT t.mediaId FROM file_tags t WHERE t.mediaId > :after " +
            "AND NOT EXISTS (SELECT 1 FROM scan_file f WHERE f.mediaId = t.mediaId) ORDER BY t.mediaId LIMIT :limit"
    const val READ_TAGS = "SELECT * FROM file_tags WHERE mediaId IN (:ids) AND readOk = 1"
    const val NEW_GROUP =
        "INSERT OR IGNORE INTO scan_group (nameKey, splitKey, clusterSeq, firstSeq) VALUES (:nameKey, :splitKey, :clusterSeq, :firstSeq)"
    const val GROUP_ID = "SELECT id FROM scan_group WHERE nameKey = :nameKey AND splitKey = :splitKey"
    const val NEW_CLUSTER = "INSERT OR IGNORE INTO scan_cluster (nameKey, groupId, firstSeq) VALUES (:nameKey, :groupId, :firstSeq)"
    const val CLUSTERS = "SELECT * FROM scan_cluster WHERE nameKey IN (:keys)"
    const val FIRST_CLUSTERS = "SELECT * FROM scan_cluster ORDER BY nameKey LIMIT :limit"
    const val CLUSTERS_AFTER = "SELECT * FROM scan_cluster WHERE nameKey > :after ORDER BY nameKey LIMIT :limit"
    const val SET_SPLIT = "UPDATE scan_group SET splitKey = :splitKey WHERE id = :id"
    const val GROUP_ROWS = "SELECT * FROM scan_row WHERE groupId = :groupId AND seq > :after ORDER BY seq LIMIT :limit"
    const val MOVE_ROWS = "UPDATE scan_row SET groupId = :groupId WHERE mediaId IN (:ids)"
    const val NEW_TALLY = "INSERT OR IGNORE INTO scan_tally (owner, value, n, first) VALUES (:owner, :value, 0, :first)"
    const val ADD_TALLY = "UPDATE scan_tally SET n = n + :n WHERE owner = :owner AND value = :value"
    const val FIRST_TALLIES = "SELECT * FROM scan_tally WHERE owner = :owner ORDER BY value LIMIT :limit"
    const val TALLIES_AFTER = "SELECT * FROM scan_tally WHERE owner = :owner AND value > :after ORDER BY value LIMIT :limit"
    const val GENRE_NAMES = "SELECT * FROM scan_genre_name WHERE genreKey IN (:keys)"
    const val FIRST_GROUPS = "SELECT * FROM scan_group ORDER BY clusterSeq, firstSeq LIMIT :limit"
    const val GROUPS_AFTER = "SELECT * FROM scan_group WHERE (clusterSeq, firstSeq) > (?, ?) ORDER BY clusterSeq, firstSeq LIMIT ?"
    const val GROUP_SIZE = "SELECT COUNT(*) FROM scan_row WHERE groupId = :groupId"
    const val ROW_COUNT = "SELECT COUNT(*) FROM scan_row"
    const val FILE_NUMBERS = "SELECT fileNo FROM scan_row WHERE groupId = :groupId LIMIT :limit"
    const val NUMBER_BY_NAMES = "UPDATE scan_row SET numKey = fileNo WHERE groupId = :groupId AND track IS NULL"
    const val FIRST_ORDERED = "SELECT * FROM scan_row WHERE groupId = :groupId ORDER BY discKey, numKey, nameOrder, seq LIMIT :limit"
    const val ORDERED_AFTER =
        "SELECT * FROM scan_row WHERE groupId = ? AND (discKey, numKey, nameOrder, seq) > (?, ?, ?, ?) " +
            "ORDER BY discKey, numKey, nameOrder, seq LIMIT ?"
    const val DELETE_ROWS = "DELETE FROM scan_row WHERE mediaId IN (:ids)"
    const val DELETE_FILES = "DELETE FROM scan_file WHERE mediaId IN (:ids)"
    const val NEW_ARTIST =
        "INSERT OR IGNORE INTO scan_artist (artistId, firstSeq, albums, songs, newestAt, artwork) " +
            "VALUES (:artistId, :firstSeq, 0, 0, :newestAt, :artwork)"
    const val ADD_ARTIST =
        "UPDATE scan_artist SET albums = albums + :albums, songs = songs + :songs, sortName = COALESCE(:sortName, sortName) " +
            "WHERE artistId = :artistId"
    const val NEWER_ARTWORK = "UPDATE scan_artist SET newestAt = :addedAt, artwork = :artwork WHERE artistId = :artistId AND newestAt < :addedAt"
    const val ARTIST_PAGE = "SELECT * FROM scan_artist WHERE firstSeq > :after ORDER BY firstSeq LIMIT :limit"
    const val MARK_BUILDING =
        "UPDATE source_meta SET stage = 'building', generation = :generation, stageOwner = NULL, " +
            "stagedTracks = 0, stagedAlbums = 0, stagedArtists = 0, movedTracks = 0, movedAlbums = 0, movedArtists = 0 WHERE sourceId = 'device'"
    const val END_UNCLAIMED =
        "UPDATE source_meta SET stage = 'none', stageOwner = NULL, stagedTracks = 0, stagedAlbums = 0, stagedArtists = 0, " +
            "movedTracks = 0, movedAlbums = 0, movedArtists = 0 " +
            "WHERE sourceId = 'device' AND generation = :generation AND stage IN ('building', 'ready')"
    const val STAGE_COUNTS =
        "SELECT (SELECT COUNT(*) FROM stage_track WHERE sourceId = 'device') AS tracks, " +
            "(SELECT COUNT(*) FROM stage_album WHERE sourceId = 'device') AS albums, " +
            "(SELECT COUNT(*) FROM stage_artist WHERE sourceId = 'device') AS artists"
    const val LIVE_TRACK_COUNT = "SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"
    const val STATE = "SELECT * FROM phone_scan_state WHERE id = 0"
    const val KEEP_STATE = "INSERT OR IGNORE INTO phone_scan_state (id, digest, tracks, albums, artists) VALUES (0, NULL, 0, 0, 0)"
    const val FORGET_DIGEST = "UPDATE phone_scan_state SET digest = NULL WHERE id = 0"
    const val SAVE_DIGEST =
        "INSERT OR REPLACE INTO phone_scan_state (id, digest, tracks, albums, artists) VALUES (0, :digest, :tracks, :albums, :artists)"

    // Each table drains in chunks of 2,000 rows by its key, then the rest.
    const val DRAIN_FILES = "DELETE FROM scan_file WHERE mediaId <= (SELECT mediaId FROM scan_file ORDER BY mediaId LIMIT 1 OFFSET 1999)"
    const val DRAIN_FILES_REST = "DELETE FROM scan_file"
    const val DRAIN_ROWS = "DELETE FROM scan_row WHERE mediaId <= (SELECT mediaId FROM scan_row ORDER BY mediaId LIMIT 1 OFFSET 1999)"
    const val DRAIN_ROWS_REST = "DELETE FROM scan_row"
    const val DRAIN_CLUSTERS = "DELETE FROM scan_cluster WHERE nameKey <= (SELECT nameKey FROM scan_cluster ORDER BY nameKey LIMIT 1 OFFSET 1999)"
    const val DRAIN_CLUSTERS_REST = "DELETE FROM scan_cluster"
    const val DRAIN_GROUPS = "DELETE FROM scan_group WHERE id <= (SELECT id FROM scan_group ORDER BY id LIMIT 1 OFFSET 1999)"
    const val DRAIN_GROUPS_REST = "DELETE FROM scan_group"
    const val DRAIN_TALLIES = "DELETE FROM scan_tally WHERE rowid <= (SELECT rowid FROM scan_tally ORDER BY rowid LIMIT 1 OFFSET 1999)"
    const val DRAIN_TALLIES_REST = "DELETE FROM scan_tally"
    const val DRAIN_GENRE_NAMES =
        "DELETE FROM scan_genre_name WHERE genreKey <= (SELECT genreKey FROM scan_genre_name ORDER BY genreKey LIMIT 1 OFFSET 1999)"
    const val DRAIN_GENRE_NAMES_REST = "DELETE FROM scan_genre_name"
    const val DRAIN_ARTISTS = "DELETE FROM scan_artist WHERE artistId <= (SELECT artistId FROM scan_artist ORDER BY artistId LIMIT 1 OFFSET 1999)"
    const val DRAIN_ARTISTS_REST = "DELETE FROM scan_artist"
    const val DRAIN_STAGE_TRACKS =
        "DELETE FROM stage_track WHERE sourceId = 'device' " +
            "AND seq <= (SELECT seq FROM stage_track WHERE sourceId = 'device' ORDER BY seq LIMIT 1 OFFSET 1999)"
    const val DRAIN_STAGE_TRACKS_REST = "DELETE FROM stage_track WHERE sourceId = 'device'"
    const val DRAIN_STAGE_ALBUMS =
        "DELETE FROM stage_album WHERE sourceId = 'device' " +
            "AND seq <= (SELECT seq FROM stage_album WHERE sourceId = 'device' ORDER BY seq LIMIT 1 OFFSET 1999)"
    const val DRAIN_STAGE_ALBUMS_REST = "DELETE FROM stage_album WHERE sourceId = 'device'"
    const val DRAIN_STAGE_ARTISTS =
        "DELETE FROM stage_artist WHERE sourceId = 'device' " +
            "AND seq <= (SELECT seq FROM stage_artist WHERE sourceId = 'device' ORDER BY seq LIMIT 1 OFFSET 1999)"
    const val DRAIN_STAGE_ARTISTS_REST = "DELETE FROM stage_artist WHERE sourceId = 'device'"

    // The chunked delete of each table and the one that takes what is left.
    val DRAINS = listOf(
        DRAIN_FILES to DRAIN_FILES_REST, DRAIN_ROWS to DRAIN_ROWS_REST, DRAIN_CLUSTERS to DRAIN_CLUSTERS_REST,
        DRAIN_GROUPS to DRAIN_GROUPS_REST, DRAIN_TALLIES to DRAIN_TALLIES_REST, DRAIN_GENRE_NAMES to DRAIN_GENRE_NAMES_REST,
        DRAIN_ARTISTS to DRAIN_ARTISTS_REST, DRAIN_STAGE_TRACKS to DRAIN_STAGE_TRACKS_REST,
        DRAIN_STAGE_ALBUMS to DRAIN_STAGE_ALBUMS_REST, DRAIN_STAGE_ARTISTS to DRAIN_STAGE_ARTISTS_REST,
    )

    // Readers of where the phone's files are.
    const val PHONE_PLACES =
        "SELECT id, nativeId, mergedId, folder, storageVolume, seq FROM source_track WHERE sourceId = 'device' AND seq > :afterSeq " +
            "ORDER BY seq LIMIT :limit"
    const val PHONE_VOLUMES = "SELECT DISTINCT storageVolume FROM source_track WHERE sourceId = 'device'"
    const val PHONE_STAMP = "SELECT COALESCE((SELECT stamp FROM source_meta WHERE sourceId = 'device'), 0)"
    // Each song's phone copies are looked for by the song: `+` keeps the
    // planner off the indexes that start with the source, which would walk
    // every phone copy for each list of songs.
    const val PHONE_PATHS_OF = "SELECT mergedId, folder, fileName, seq FROM source_track WHERE mergedId IN (:ids) AND +sourceId = 'device'"
}
