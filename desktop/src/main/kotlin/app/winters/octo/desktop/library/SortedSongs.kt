package app.winters.octo.desktop.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Lists this short sort at once, as the page first draws.
private const val SORT_AT_ONCE = 5_000

// The songs in `order`, sorted off the window's thread so a big library
// never stalls it. While a new order is worked out the old one stays on
// screen. Null only the first time a long list is still being sorted.
@Composable
fun rememberSorted(songs: List<Song>, order: SortOrder?): List<Song>? {
    val sorted by produceState(
        initialValue = when {
            order == null -> songs
            songs.size <= SORT_AT_ONCE -> sortSongs(songs, order)
            else -> null
        },
        songs,
        order,
    ) {
        value = if (order == null) songs else withContext(Dispatchers.Default) { sortSongs(songs, order) }
    }
    return sorted
}
