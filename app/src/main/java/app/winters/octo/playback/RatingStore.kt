package app.winters.octo.playback

import app.winters.octo.catalog.SongIds
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
// A song found online cannot be rated. A rating is written on the song its
// id names now (SongIds), its key read and its rows written between groups
// of a save, and the server's copies follow that id.
@Singleton
class RatingStore @Inject constructor(
    private val user: UserDao,
    private val listening: ListeningSync,
    private val ids: SongIds,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun rate(trackId: String, rating: Int) {
        if (isFind(trackId)) return
        val stars = cleanRating(rating)
        scope.launch {
            // The server copies are noted as rated too, so a merge before
            // the next sync still shows this rating.
            val id = ids.write {
                val id = ids.song(trackId)
                val relinkKey = user.relinkKeyOf(id) ?: return@write null
                user.noteTap(id, stars, relinkKey, System.currentTimeMillis())
                id
            } ?: return@launch
            listening.ratingChanged(id)
        }
    }

    // A rating brought back from a backup, written before it returns so a
    // restore writes its ratings a page at a time. The phone keeps it as a
    // tap would, but one the song's server copies already show is not sent,
    // so restoring a big library sends only the ratings that differ.
    // Answers whether the restored rating goes to the server.
    internal suspend fun restoreNow(trackId: String, rating: Int): Boolean {
        if (isFind(trackId)) return false
        val (id, sent) = ids.write {
            val id = ids.song(trackId)
            val relinkKey = user.relinkKeyOf(id) ?: return@write null
            id to user.noteRestored(id, cleanRating(rating), relinkKey, System.currentTimeMillis())
        } ?: return false
        if (sent) listening.ratingChanged(id)
        return sent
    }
}
