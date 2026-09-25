package app.winters.octo.catalog

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, AlbumEntity::class, ArtistEntity::class, FileTagsEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun dao(): CatalogDao
}
