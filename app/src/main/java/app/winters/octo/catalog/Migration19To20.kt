package app.winters.octo.catalog

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Version 20: playlist folders (each account's folders, where its
// playlists sit, and the folder changes waiting to reach the server), a
// mark per server that its playlists' bases were seeded, and three playlist
// columns: one of Octo's library upkeep lists, which kind of upkeep list it
// is, and an imported playlist's copy. Tables and columns only: nothing is
// copied or seeded here, so the upgrade asks for little room (UpgradeGuard).
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `playlist` ADD COLUMN `upkeep` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `playlist` ADD COLUMN `upkeepKind` TEXT")
        db.execSQL("ALTER TABLE `playlist` ADD COLUMN `octoImport` INTEGER NOT NULL DEFAULT 0")
        V20_TABLES.forEach(db::execSQL)
    }
}

internal val V20_TABLES = listOf(
    "CREATE TABLE IF NOT EXISTS `playlist_folder` (`space` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
        "`parent` TEXT, `created` TEXT NOT NULL, PRIMARY KEY(`space`, `id`))",
    "CREATE INDEX IF NOT EXISTS `index_playlist_folder_space_parent` ON `playlist_folder` (`space`, `parent`)",
    "CREATE TABLE IF NOT EXISTS `playlist_place` (`space` TEXT NOT NULL, `playlistKey` TEXT NOT NULL, `folderId` TEXT NOT NULL, " +
        "`localOnly` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`space`, `playlistKey`))",
    "CREATE INDEX IF NOT EXISTS `index_playlist_place_space_folderId` ON `playlist_place` (`space`, `folderId`)",
    "CREATE TABLE IF NOT EXISTS `folder_op` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `space` TEXT NOT NULL, " +
        "`kind` TEXT NOT NULL, `folderId` TEXT, `name` TEXT, `parent` TEXT, `playlistKeys` TEXT NOT NULL, `at` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_folder_op_space_seq` ON `folder_op` (`space`, `seq`)",
    "CREATE TABLE IF NOT EXISTS `playlist_base_mark` (`sourceId` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`sourceId`))",
)
