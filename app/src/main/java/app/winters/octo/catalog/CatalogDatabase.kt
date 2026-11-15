package app.winters.octo.catalog

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import app.winters.octo.device.PhoneScanDao
import app.winters.octo.listening.ListeningDao
import app.winters.octo.listening.ListeningImportEntity
import app.winters.octo.listening.PendingPlayEntity
import app.winters.octo.listening.PlayTotalDiffEntity
import app.winters.octo.listening.PlayTotalEntity
import app.winters.octo.listening.PlayTotalsDao
import app.winters.octo.listening.SentPlayEntity
import app.winters.octo.listening.SentRecentEntity
import app.winters.octo.listening.SyncedRatingEntity
import app.winters.octo.listening.SyncedStarEntity
import app.winters.octo.offline.DownloadDao
import app.winters.octo.offline.DownloadEntity
import app.winters.octo.server.CopyDao

// The version the code expects. 18 to 19 and 19 to 20 are hand-written
// migrations (Migration18To19.kt, Migration19To20.kt), so they are not in
// autoMigrations.
const val CATALOG_VERSION = 20

// The file the library lives in.
const val CATALOG_FILE = "catalog.db"

@Database(
    entities = [
        TrackEntity::class, AlbumEntity::class, ArtistEntity::class, FileTagsEntity::class,
        LikedTrackEntity::class, PlayEventEntity::class, PlaylistEntity::class, PlaylistItemEntity::class,
        SourceTrackEntity::class, SourceAlbumEntity::class, SourceArtistEntity::class,
        OnlineSongEntity::class, TrackRatingEntity::class, DownloadEntity::class,
        LikedAlbumEntity::class, LikedArtistEntity::class, PinnedItemEntity::class,
        StageTrackEntity::class, StageAlbumEntity::class, StageArtistEntity::class,
        SyncSongEntity::class, SyncAlbumEntity::class, SyncArtistEntity::class, SyncOrderEntity::class,
        SyncStarredEntity::class, MergeCandidateEntity::class, MergedIdNextEntity::class,
        AlbumNextEntity::class, ArtistNextEntity::class, TakeInLogEntity::class, RatingNoteEntity::class, RatingTapEntity::class,
        SourceMetaEntity::class, AccountSyncEntity::class, MergeStateEntity::class,
        SyncedStarEntity::class, SyncedRatingEntity::class, PendingPlayEntity::class,
        SentPlayEntity::class, SentRecentEntity::class, ListeningImportEntity::class,
        PlayTotalEntity::class, PlayTotalDiffEntity::class,
        PlaylistSyncedSongEntity::class, PlaylistMetaEntity::class, PlaylistSetPinEntity::class, PlaylistEditLogEntity::class,
        PlaylistEntryEntity::class, PlaylistKeyEntity::class, PlaylistMatchEntity::class, PlaylistLookupEntity::class,
        PlaylistLocalEntity::class, PlaylistNextEntity::class, PlaylistSendEntity::class, PlaylistChangeEntity::class,
        ScanFileEntity::class, ScanRowEntity::class, ScanClusterEntity::class, ScanGroupEntity::class,
        ScanTallyEntity::class, ScanGenreNameEntity::class, ScanArtistEntity::class, PhoneScanStateEntity::class,
        QueueEntryEntity::class, QueueListEntity::class, QueuePendingEntity::class, QueueAccountEntity::class, QueueMetaEntity::class,
        ListsFolderOrderEntity::class, ListsSortCopyEntity::class, ListsAlbumEntryEntity::class, ListsServerDirEntity::class,
        ServerEntryEntity::class, ServerKeyEntity::class, ServerMatchEntity::class, ServerLookupEntity::class, ServerHeadEntity::class,
        LiveSongEntity::class, LiveStateEntity::class,
        HealthRunEntity::class, HealthSongEntity::class, HealthTagEntity::class, HealthNodeEntity::class, HealthMemberEntity::class,
        HealthGroupEntity::class, HealthQualityEntity::class, HealthPartEntity::class, HealthCodeEntity::class, HealthTallyEntity::class,
        HealthSplitEntity::class, HealthSplitSongEntity::class, FixUndoEntity::class,
        MatchQueryEntity::class, MatchTitleEntity::class, MatchLookupEntity::class,
        SaveFoundEntity::class, SaveRefEntity::class, SaveNodeEntity::class, SaveSetEntity::class, SaveRenameEntity::class,
        PlaylistFolderEntity::class, PlaylistPlaceEntity::class, FolderOpEntity::class, PlaylistBaseMarkEntity::class,
    ],
    version = CATALOG_VERSION,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14), AutoMigration(from = 14, to = 15), AutoMigration(from = 15, to = 16), AutoMigration(from = 16, to = 17), AutoMigration(from = 17, to = 18)],
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun dao(): CatalogDao

    abstract fun userDao(): UserDao

    abstract fun favouritesDao(): FavouritesDao

    abstract fun sourceDao(): SourceDao

    abstract fun onlineDao(): OnlineDao

    abstract fun downloadDao(): DownloadDao

    abstract fun workDao(): WorkDao

    abstract fun copyDao(): CopyDao

    abstract fun listeningDao(): ListeningDao

    abstract fun playTotalsDao(): PlayTotalsDao

    abstract fun phoneScanDao(): PhoneScanDao

    abstract fun playlistSetDao(): PlaylistSetDao

    abstract fun playlistWorkDao(): PlaylistWorkDao

    abstract fun listsDao(): ListsDao

    abstract fun playlistEditDao(): PlaylistEditDao

    abstract fun readerDao(): ReaderDao

    abstract fun queueDao(): QueueDao

    abstract fun saveDao(): SaveDao

    abstract fun playlistFolderDao(): PlaylistFolderDao
}
