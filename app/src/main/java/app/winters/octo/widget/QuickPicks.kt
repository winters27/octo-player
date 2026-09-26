package app.winters.octo.widget

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.UserDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject

// How many albums the Recently added and Album mix shortcuts take.
private const val RECENT_ALBUMS = 10
private const val MIX_ALBUMS = 8

// The songs behind each quick play shortcut.
class QuickPicks @Inject constructor(
    private val catalog: CatalogDao,
    private val user: UserDao,
) {
    // The songs a shortcut plays, in order, and whether to shuffle them.
    // Empty when there are none, and for Resume, which plays the queue.
    suspend fun songsFor(pick: QuickPick): Pair<List<String>, Boolean> = when (pick) {
        QuickPick.Liked -> user.likedTracks().first().map { it.id } to true
        // Newest album first, each in its own order.
        QuickPick.RecentlyAdded -> catalog.recentAlbums(RECENT_ALBUMS).first().flatMap { catalog.albumTrackIds(it.id) } to false
        QuickPick.AlbumMix -> catalog.randomAlbums(MIX_ALBUMS).flatMap { catalog.albumTrackIds(it.id) } to true
        QuickPick.Resume -> emptyList<String>() to false
    }

    // Everything, for a play button with no queue to go back to.
    suspend fun everything(): List<String> = catalog.allTrackIds()
}
