package app.winters.octo.discovery

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

// Each find now in the library, by the library song it became.
fun adoptionsOf(rows: List<OnlineSongEntity>): Map<String, String> =
    rows.filter { it.adoptedId.isNotEmpty() }.associate { it.id to it.adoptedId }

// How many library songs AdoptedSongs remembers: more than the rows of any
// screen, so a row scrolled back to still shows its song at once.
internal const val ADOPTED_SEEN_KEPT = 500

// The library songs finds became, followed one at a time for the rows that
// show one. The last seen of the ADOPTED_SEEN_KEPT drawn most recently is
// kept, so a row drawn again shows it straight away instead of the find
// first.
@Singleton
class AdoptedSongs @Inject constructor(private val catalog: CatalogDao) {
    private val seen = RecentRows<TrackEntity>(ADOPTED_SEEN_KEPT)

    fun lastSeen(id: String): TrackEntity? = seen[id]

    fun song(id: String): Flow<TrackEntity?> = catalog.trackFlow(id).onEach { track ->
        if (track != null) seen[id] = track else seen.remove(id)
    }
}

// Rows by id, the `kept` used most recently: past that, the one used
// longest ago goes. Safe across threads.
internal class RecentRows<V : Any>(private val kept: Int) {
    private val rows = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > kept
    }

    operator fun get(id: String): V? = synchronized(rows) { rows[id] }

    operator fun set(id: String, row: V) {
        synchronized(rows) { rows[id] = row }
    }

    fun remove(id: String) {
        synchronized(rows) { rows.remove(id) }
    }

    val size: Int get() = synchronized(rows) { rows.size }
}
