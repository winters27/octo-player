package app.winters.octo.discovery

import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.isFind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

// The shortest and longest a length from the player is believed, so a
// stream that reports nonsense is not kept.
private const val SHORTEST_MS = 1_000L
private const val LONGEST_MS = 3 * 60 * 60_000L

// The length to keep for a find the player has opened: only when none is
// known yet, and only one that looks like a song's.
fun lengthToLearn(knownMs: Long, playerMs: Long): Long? = when {
    knownMs > 0 -> null
    playerMs < SHORTEST_MS || playerMs > LONGEST_MS -> null
    else -> playerMs
}

// A song's length as a row shows it: the one it came with, or else one the
// player learned since the list was loaded. Zero when neither is known.
fun shownLengthMs(knownMs: Long, learnedMs: Long?): Long = if (knownMs > 0) knownMs else learnedMs ?: 0

// Lengths of songs found online, learned by playing them. Half the finds
// come with no length, and the player knows it once the song opens. It is
// stored with the find, and kept here too so a list already on screen
// shows it without being loaded again.
@Singleton
class FindLengths internal constructor(private val fill: suspend (String, Long) -> Int) {
    @Inject constructor(online: OnlineDao) : this(online::fillLength)

    private val _learned = MutableStateFlow<Map<String, Long>>(emptyMap())
    val learned: StateFlow<Map<String, Long>> = _learned

    // Finds already answered, so the same song opening again asks nothing.
    private val settled = HashSet<String>()

    suspend fun learn(trackId: String, playerMs: Long) {
        if (!isFind(trackId)) return
        val ms = lengthToLearn(0, playerMs) ?: return
        synchronized(settled) { if (!settled.add(trackId)) return }
        // A find that already had a length keeps it, here as in storage.
        if (fill(trackId, ms) > 0) _learned.update { it + (trackId to ms) }
    }
}
