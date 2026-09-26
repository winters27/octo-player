package app.winters.octo.catalog

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import app.winters.octo.offline.DownloadDao
import app.winters.octo.offline.DownloadEntity

@Database(
    entities = [
        TrackEntity::class, AlbumEntity::class, ArtistEntity::class, FileTagsEntity::class,
        LikedTrackEntity::class, PlayEventEntity::class, PlaylistEntity::class, PlaylistItemEntity::class,
        QueueItemEntity::class, QueueStateEntity::class,
        SourceTrackEntity::class, SourceAlbumEntity::class, SourceArtistEntity::class,
        OnlineSongEntity::class, TrackRatingEntity::class, DownloadEntity::class,
    ],
    version = 10,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10)],
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun dao(): CatalogDao

    abstract fun userDao(): UserDao

    abstract fun sourceDao(): SourceDao

    abstract fun onlineDao(): OnlineDao

    abstract fun downloadDao(): DownloadDao
}
