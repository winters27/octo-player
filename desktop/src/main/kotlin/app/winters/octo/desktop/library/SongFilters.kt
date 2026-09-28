package app.winters.octo.desktop.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.SongFields
import app.winters.octo.query.SubsonicSongFields
import app.winters.octo.query.places
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A list as its filters show it: the songs, and where each row is in the
// whole list, so a filtered playlist still removes the right entries.
class Filtered(val songs: List<Song>, private val kept: List<Int>?) {
    // The place in the whole list of the row at `row`.
    fun placeOf(row: Int): Int = kept?.getOrNull(row) ?: row

    companion object {
        fun of(songs: List<Song>, query: LibraryQuery, fields: SongFields<Song>, now: Long): Filtered {
            if (!query.filters) return Filtered(songs, null)
            val kept = query.copy(sort = null, limit = null).places(songs, now, fields)
            return Filtered(kept.map(songs::get), kept)
        }
    }
}

// Lists this short filter at once, as the page first draws.
private const val FILTER_AT_ONCE = 5_000

// The songs `query` lets through, in the order they came, worked out off
// the window's thread as sorting is. While new filters are worked out the
// songs shown before stay on screen. Null while `songs` is, or the first
// time a long list is still being filtered.
@Composable
fun rememberFiltered(songs: List<Song>?, query: LibraryQuery, fields: SongFields<Song>): Filtered? {
    val filtered by produceState(
        initialValue = when {
            songs == null -> null
            !query.filters || songs.size <= FILTER_AT_ONCE -> Filtered.of(songs, query, fields, System.currentTimeMillis())
            else -> null
        },
        songs,
        query,
    ) {
        value = when {
            songs == null -> null
            !query.filters -> Filtered(songs, null)
            else -> withContext(Dispatchers.Default) { Filtered.of(songs, query, fields, System.currentTimeMillis()) }
        }
    }
    // With nothing to filter the whole list shows at once.
    if (songs != null && !query.filters) return Filtered(songs, null)
    return filtered
}

// Songs as the window shows them: a heart or a rating changed a moment ago
// counts before the server's lists catch up.
class ShownSongFields(
    private val starred: (Song) -> Boolean,
    private val rated: (Song) -> Int,
) : SubsonicSongFields() {
    override fun favourite(song: Song) = starred(song)
    override fun rating(song: Song) = rated(song)
}

// "120 of 2,835 songs" while filtered, else "2,835 songs".
fun filteredCount(shown: Int, all: Int, filtered: Boolean): String {
    val noun = if (all == 1) "song" else "songs"
    return if (filtered) "%,d of %,d %s".format(shown, all, noun) else "%,d %s".format(all, noun)
}
