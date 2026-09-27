package app.winters.octo.discovery

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// Each find now in the library, by the library song it became.
fun adoptionsOf(rows: List<OnlineSongEntity>): Map<String, String> =
    rows.filter { it.adoptedId.isNotEmpty() }.associate { it.id to it.adoptedId }

// The library songs finds became, followed one at a time for the rows that
// show one. The last seen of each is kept, so a row drawn again shows it
// straight away instead of the find first.
@Singleton
class AdoptedSongs @Inject constructor(private val catalog: CatalogDao) {
    private val seen = ConcurrentHashMap<String, TrackEntity>()

    fun lastSeen(id: String): TrackEntity? = seen[id]

    fun song(id: String): Flow<TrackEntity?> = catalog.trackFlow(id).onEach { track ->
        if (track != null) seen[id] = track else seen.remove(id)
    }
}
