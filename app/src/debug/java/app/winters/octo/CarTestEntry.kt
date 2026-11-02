package app.winters.octo

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.UserDao
import app.winters.octo.playback.CarLibrary
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.sort.SortSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// What the car's tests on an emulator reach in the debug app: the catalog
// and the listener's lists, to fill a library of their own. Debug builds
// only.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CarTestEntry {
    fun catalog(): CatalogDao
    fun user(): UserDao
    fun car(): CarLibrary
    fun playlists(): PlaylistStore
    fun sorting(): SortSettings
}
