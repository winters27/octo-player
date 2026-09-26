package app.winters.octo.playback

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.TrackRatingEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.isFind
import app.winters.octo.listening.ListeningSync
import app.winters.octo.listening.cleanRating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Rates library songs, 1 to 5 stars or 0 for none. The phone keeps the
// rating, the library shows it at once, and the song's server copies follow.
// A song found online cannot be rated.
@Singleton
class RatingStore @Inject constructor(
    private val user: UserDao,
    private val catalog: CatalogDao,
    private val sources: SourceDao,
    private val listening: ListeningSync,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun rate(trackId: String, rating: Int) {
        if (isFind(trackId)) return
        val stars = cleanRating(rating)
        scope.launch {
            val track = catalog.track(trackId) ?: return@launch
            if (stars == 0) user.unrate(trackId) else user.rate(TrackRatingEntity(trackId, track.relinkKey, stars, System.currentTimeMillis()))
            user.showRating(trackId, stars)
            // The server copies are noted as rated too, so a rebuild before
            // the next sync still shows this rating.
            sources.setServerRating(trackId, stars)
            listening.ratingChanged(trackId)
        }
    }
}
