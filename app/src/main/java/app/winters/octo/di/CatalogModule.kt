package app.winters.octo.di

import android.content.Context
import androidx.room.Room
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.UserDao
import app.winters.octo.offline.DownloadDao
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
    fun database(@ApplicationContext context: Context): CatalogDatabase =
        Room.databaseBuilder(context, CatalogDatabase::class.java, "catalog.db").build()

    @Provides
    fun dao(db: CatalogDatabase): CatalogDao = db.dao()

    @Provides
    fun userDao(db: CatalogDatabase): UserDao = db.userDao()

    @Provides
    fun sourceDao(db: CatalogDatabase): SourceDao = db.sourceDao()

    @Provides
    fun onlineDao(db: CatalogDatabase): OnlineDao = db.onlineDao()

    @Provides
    fun downloadDao(db: CatalogDatabase): DownloadDao = db.downloadDao()
}
