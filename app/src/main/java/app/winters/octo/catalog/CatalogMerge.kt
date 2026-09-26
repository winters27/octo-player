package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Rebuilds the library the screens read from what every source has, after
// any source changes. One rebuild at a time, the phone's files first so its
// songs keep their ids.
@Singleton
class CatalogMerge @Inject constructor(
    private val sources: SourceDao,
    private val catalog: CatalogDao,
    private val user: UserDao,
) {
    private val lock = Mutex()

    // Returns the merged library, so callers can tell how big it is.
    suspend fun rebuild(): MergedCatalog = lock.withLock {
        val all = sources.sourceIds()
            .sortedBy { if (it == DEVICE) "" else it }
            .map { id -> SourceCatalog(id, onPhone = id == DEVICE, sources.tracks(id), sources.albums(id), sources.artists(id)) }
        val merged = withContext(Dispatchers.Default) { mergeCatalogs(all) }
        catalog.replaceAll(merged.tracks, merged.albums, merged.artists)
        // Each copy remembers its library song, so playback can find them all.
        val copies = all.flatMap { it.tracks }.map { it.copy(mergedId = merged.mergedIds[it.id] ?: it.id) }
        copies.chunked(500).forEach { sources.insertTracks(it) }
        // Likes, plays and playlists follow songs whose ids changed.
        user.relinkAll()
        // Songs with no rating on a server show the one made on the phone.
        user.showPhoneRatings()
        merged
    }
}
