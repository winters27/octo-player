package app.winters.octo.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// What looking up a song's lyrics came to.
sealed interface LyricsAnswer {
    data class Found(val lyrics: Lyrics) : LyricsAnswer

    // Every source answered, and none had lyrics (or the listener hid them).
    data object None : LyricsAnswer

    // The lookup could not finish: it was cut off, a source could not be
    // reached, or something went wrong. Nothing is known, so nothing is
    // saved, and it is worth trying again.
    data object Failed : LyricsAnswer
}

// Each song's saved lyrics answer, in memory in front of the cache folder,
// and the rule for what a new search may save: lyrics are kept, and "none"
// only when every source answered. A search that could not finish keeps
// nothing, so an older answer stays as it was and the next look asks again.
class LyricsAnswers(private val cache: LyricsCache, private val clock: () -> Long = System::currentTimeMillis) {
    private val recent = object : LinkedHashMap<String, CachedLyrics>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>) = size > 32
    }

    // The song's answer: the saved one while it stands, or else what
    // `search` finds. `resumed` is set when the lyrics have just come back
    // on screen, so a "none" found while they were away is asked again.
    // `away` says whether the lyrics are off screen right now, and is
    // noted with what is saved.
    suspend fun answer(
        songId: String,
        picked: String?,
        onlineAllowed: Boolean,
        resumed: Boolean,
        away: () -> Boolean,
        search: suspend () -> LyricsSearch,
    ): LyricsAnswer {
        val known = saved(songId)
        if (known != null && known.standsFor(picked, clock(), onlineAllowed) && !(resumed && known.recheckOnReturn())) {
            synchronized(recent) { recent[songId] = known }
            return known.lyrics?.let(LyricsAnswer::Found) ?: LyricsAnswer.None
        }

        val found = try {
            search()
        } catch (e: CancellationException) {
            // Stopped from outside: nothing is saved, and the stop goes on.
            // Cut off by a time limit of its own: it failed.
            currentCoroutineContext().ensureActive()
            return LyricsAnswer.Failed
        } catch (_: Exception) {
            return LyricsAnswer.Failed
        }
        val lyrics = found.lyrics
        if (lyrics == null && !found.complete) return LyricsAnswer.Failed

        val entry = CachedLyrics(
            savedAt = clock(),
            lyrics = lyrics,
            askedOnline = found.askedOnline,
            lookupVersion = ONLINE_LOOKUP_VERSION,
            pick = found.pick,
            unsure = lyrics == null && found.unsure,
            away = away(),
        )
        synchronized(recent) { recent[songId] = entry }
        // Lyrics found while another source could not be reached are shown,
        // but only kept in memory, so that source is asked next time.
        if (found.complete) withContext(Dispatchers.IO) { cache.write(songId, entry) }
        return lyrics?.let(LyricsAnswer::Found) ?: LyricsAnswer.None
    }

    // The song's saved answer, from memory or the cache folder.
    suspend fun saved(songId: String): CachedLyrics? =
        synchronized(recent) { recent[songId] } ?: withContext(Dispatchers.IO) { cache.read(songId) }

    // Keeps an answer in memory and in the cache folder.
    suspend fun keep(songId: String, entry: CachedLyrics) {
        synchronized(recent) { recent[songId] = entry }
        withContext(Dispatchers.IO) { cache.write(songId, entry) }
    }

    // Forgets a song's answer, in memory and in the cache folder.
    suspend fun forget(songId: String) {
        synchronized(recent) { recent.remove(songId) }
        withContext(Dispatchers.IO) { cache.drop(songId) }
    }
}
