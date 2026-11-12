package app.winters.octo.di

import android.content.Context
import androidx.room.Room
import app.winters.octo.catalog.CATALOG_FILE
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.DiskFreeSpace
import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.GuardedOpenHelperFactory
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.UpgradeGuard
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.WorkDao
import app.winters.octo.catalog.catalogSetup
import app.winters.octo.listening.ListeningDao
import app.winters.octo.offline.DownloadDao
import app.winters.octo.server.CopyDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CatalogModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context, guard: UpgradeGuard): CatalogDatabase =
        Room.databaseBuilder(context, CatalogDatabase::class.java, CATALOG_FILE)
            .openHelperFactory(GuardedOpenHelperFactory(guard))
            .catalogSetup()
            .build()

    @Provides
    fun freeSpace(disk: DiskFreeSpace): FreeSpace = disk

    @Provides
    fun workDao(db: CatalogDatabase): WorkDao = db.workDao()

    @Provides
    fun copyDao(db: CatalogDatabase): CopyDao = db.copyDao()

    @Provides
    fun listeningDao(db: CatalogDatabase): ListeningDao = db.listeningDao()

    @Provides
    fun dao(db: CatalogDatabase): CatalogDao = db.dao()

    @Provides
    fun userDao(db: CatalogDatabase): UserDao = db.userDao()

    @Provides
    fun favouritesDao(db: CatalogDatabase): FavouritesDao = db.favouritesDao()

    @Provides
    fun sourceDao(db: CatalogDatabase): SourceDao = db.sourceDao()

    @Provides
    fun onlineDao(db: CatalogDatabase): OnlineDao = db.onlineDao()

    @Provides
    fun downloadDao(db: CatalogDatabase): DownloadDao = db.downloadDao()
}
