package app.winters.octo.playback

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// How many times each queue entry was played again from its start as a new
// play, for a version picked for its song: the second part of the play
// token its stream requests carry (see playTokenOf). Kept on the phone, so
// an entry that resumes after the app was closed is still the play it was.
// The newest PLAY_RESTARTS_KEPT entries are kept.
@Singleton
class PlayRestarts internal constructor(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences("play_restarts", Context.MODE_PRIVATE))

    // Oldest first, by when each was last read or written.
    private val counts = LinkedHashMap<String, Int>(16, 0.75f, true).apply {
        prefs.all.forEach { (entry, count) -> (count as? Int)?.let { put(entry, it) } }
    }

    fun of(entryId: String): Int = synchronized(counts) { counts[entryId] ?: 0 }

    // The entry plays again as a new play: its count goes up by one.
    fun restarted(entryId: String): Int = synchronized(counts) {
        val next = (counts[entryId] ?: 0) + 1
        counts[entryId] = next
        val edit = prefs.edit().putInt(entryId, next)
        while (counts.size > PLAY_RESTARTS_KEPT) {
            val oldest = counts.keys.first()
            counts.remove(oldest)
            edit.remove(oldest)
        }
        edit.apply()
        next
    }
}

internal const val PLAY_RESTARTS_KEPT = 500
