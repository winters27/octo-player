package app.winters.octo.playback

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.PlayedIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// The song a radio from an album or an artist starts with, played at once:
// its most played song as the stored totals have it now, read as they
// are with no wait for them to catch up, or with no stored play of its
// songs its first song (an album's first, an artist's first album's
// first); null when it has none.
@Singleton
class RadioSeeds @Inject constructor(private val db: CatalogDatabase) {
    private val totals = db.playTotalsDao()
    private val reader = db.readerDao()

    suspend fun of(where: PlayedIn): TrackEntity? = withContext(Dispatchers.IO) {
        when (where) {
            is PlayedIn.Album -> totals.mostPlayedOfAlbum(where.id) ?: reader.firstOfAlbum(where.id)
            is PlayedIn.Artist -> totals.mostPlayedOfArtist(where.id) ?: reader.firstAlbumOfArtist(where.id)?.let { reader.firstOfAlbum(it) }
        }
    }
}
