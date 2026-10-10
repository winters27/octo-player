package app.winters.octo.discovery

import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.isFind
import app.winters.octo.playback.RealLengths
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

// A song's length as a row shows it: the real one once playing it found
// its listing wrong, else the listed one. Zero when neither is known.
fun shownLengthMs(listedMs: Long, learnedMs: Long?): Long = learnedMs ?: listedMs.coerceAtLeast(0)

// Lengths learned by playing songs. Half the finds come with no length,
// and a find's listed length can be another copy's; the phone's player
// knows the length of the sound once the song opens. A listing more than
// a second off is corrected in memory for every song, so rows and the
// queue already on screen show it, and stored with the find, so it shows
// next time too.
@Singleton
class FindLengths internal constructor(
    private val store: suspend (String, Long) -> Int,
    private val real: RealLengths,
) {
    @Inject constructor(online: OnlineDao, real: RealLengths) : this(online::setLength, real)

    // The corrected lengths, by song id.
    val learned: StateFlow<Map<String, Long>> = real.corrected

    // Finds already stored with their length, so the same song opening
    // again writes nothing.
    private val stored = HashMap<String, Long>()

    // Takes the player's length of a song listed at `listedMs`.
    suspend fun learn(trackId: String, listedMs: Long, playerMs: Long) {
        val ms = real.learn(trackId, listedMs, playerMs) ?: return
        if (!isFind(trackId)) return
        synchronized(stored) {
            if (stored[trackId] == ms) return
            stored[trackId] = ms
        }
        try {
            store(trackId, ms)
        } catch (e: Exception) {
            synchronized(stored) { stored.remove(trackId) }
            throw e
        }
    }
}
