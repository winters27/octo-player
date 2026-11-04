package app.winters.octo.playback

import androidx.annotation.VisibleForTesting
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
            user.noteTap(trackId, stars, relinkKey, System.currentTimeMillis())
            listening.ratingChanged(trackId)
        }
    }

    // A rating brought back from a backup. The phone keeps it as a tap
    // would, but one the song's server copies already show is not sent,
    // so restoring a big library sends only the ratings that differ.
    fun restore(trackId: String, rating: Int) {
        scope.launch { restoreNow(trackId, rating) }
    }

    // Answers whether the restored rating goes to the server.
    @VisibleForTesting
    internal suspend fun restoreNow(trackId: String, rating: Int): Boolean {
        if (isFind(trackId)) return false
        val relinkKey = user.relinkKeyOf(trackId) ?: return false
        val sent = user.noteRestored(trackId, cleanRating(rating), relinkKey, System.currentTimeMillis())
        if (sent) listening.ratingChanged(trackId)
        return sent
    }
}
