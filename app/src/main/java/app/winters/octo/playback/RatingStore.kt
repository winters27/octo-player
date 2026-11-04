package app.winters.octo.playback

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
    private val listening: ListeningSync,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun rate(trackId: String, rating: Int) {
        if (isFind(trackId)) return
        val stars = cleanRating(rating)
        scope.launch {
            // The server copies are noted as rated too, so a merge before
            // the next sync still shows this rating.
            val relinkKey = user.relinkKeyOf(trackId) ?: return@launch
            user.noteRating(trackId, stars, relinkKey, System.currentTimeMillis(), copies = true)
            listening.ratingChanged(trackId)
        }
    }
}
