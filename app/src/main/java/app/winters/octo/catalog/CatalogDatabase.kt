package app.winters.octo.catalog

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class, AlbumEntity::class, ArtistEntity::class, FileTagsEntity::class,
        LikedTrackEntity::class, PlayEventEntity::class, PlaylistEntity::class, PlaylistItemEntity::class,
        QueueItemEntity::class, QueueStateEntity::class,
        SourceTrackEntity::class, SourceAlbumEntity::class, SourceArtistEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun dao(): CatalogDao

    abstract fun userDao(): UserDao

    abstract fun sourceDao(): SourceDao
}
