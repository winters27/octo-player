package app.winters.octo.widget

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.playback.ListSpec
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import kotlinx.coroutines.flow.first
import javax.inject.Inject

// How many albums the Recently added and Album mix shortcuts take.
private const val RECENT_ALBUMS = 10
private const val MIX_ALBUMS = 8

// What each quick play shortcut plays.
class QuickPicks @Inject constructor(private val catalog: CatalogDao, db: CatalogDatabase) {
    private val queue = db.queueDao()

    // Whether the library has any song, and any liked one: one row each.
    suspend fun hasSongs(): Boolean = queue.anySong()

    suspend fun hasLiked(): Boolean = queue.anyLiked()

    // The list a shortcut plays, named by what it is, and whether to
    // shuffle it. Null for Resume, which plays the queue, and when there is
    // nothing to play.
    suspend fun specFor(pick: QuickPick): Pair<ListSpec, Boolean>? = when (pick) {
        QuickPick.Liked -> ListSpec.Songs(SongScope.Liked, SortList.Liked.default) to true
        // Newest album first, each in its own order.
        QuickPick.RecentlyAdded -> catalog.recentAlbums(RECENT_ALBUMS).first().map { it.id }.takeIf { it.isNotEmpty() }?.let { ListSpec.AlbumList(it) to false }
        QuickPick.AlbumMix -> catalog.randomAlbums(MIX_ALBUMS).map { it.id }.takeIf { it.isNotEmpty() }?.let { ListSpec.AlbumList(it) to true }
        QuickPick.Resume -> null
    }

    // Everything, for a play button with no queue to go back to.
    fun everything(): ListSpec = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false))
}
