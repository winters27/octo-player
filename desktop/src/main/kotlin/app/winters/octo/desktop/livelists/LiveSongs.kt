package app.winters.octo.desktop.livelists

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.liveListName
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.SongFields
import app.winters.octo.query.select
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A live list as the editor holds it until saved: the name typed and the
// rules picked.
data class LiveListDraft(val name: String, val query: LibraryQuery) {
    // The name it is saved under: the one typed, else one from its rules.
    val savedName: String get() = name.trim().ifEmpty { liveListName(query) }

    // Whether saving would change `list`.
    fun changes(list: LiveList): Boolean = savedName != list.name || query != list.query

    companion object {
        fun of(list: LiveList) = LiveListDraft(list.name, list.query)
    }
}

// Up to four covers for a live list's picture, from its first songs, one
// per album; a single cover when there are fewer than four.
fun liveListCovers(songs: List<Song>): List<String> {
    val covers = LinkedHashSet<String>()
    for (song in songs) {
        val cover = song.coverArt?.takeIf(String::isNotEmpty) ?: continue
        covers += cover
        if (covers.size == 4) break
    }
    return if (covers.size >= 4) covers.toList() else covers.take(1)
}

// Lists this long are picked at once, as the page first draws.
private const val PICK_AT_ONCE = 5_000

// The songs `query` picks from `library`, in its order, worked out off the
// window's thread as the filters are, so the editor's preview keeps up as
// rules change. The songs shown before stay on screen meanwhile. Null only
// the first time a big library is still being worked out.
@Composable
fun rememberLiveSongs(library: List<Song>, query: LibraryQuery, fields: SongFields<Song>): List<Song>? {
    // Worked out once, for the first frame only.
    val first = remember { if (library.size <= PICK_AT_ONCE) query.select(library, System.currentTimeMillis(), fields) else null }
    val picked by produceState(
        initialValue = first,
        library,
        query,
    ) {
        value = withContext(Dispatchers.Default) { query.select(library, System.currentTimeMillis(), fields) }
    }
    return picked
}
