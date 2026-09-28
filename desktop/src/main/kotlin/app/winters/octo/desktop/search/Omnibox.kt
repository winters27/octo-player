package app.winters.octo.desktop.search

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.Song

// One line in the search box's list.
sealed interface OmniItem {
    // A past search, to search again.
    data class Recent(val text: String) : OmniItem

    // Something to do.
    class Run(val command: Command) : OmniItem

    // A song, with the list it came in, so playing it plays on from there.
    data class SongHit(val song: Song, val list: List<Song>, val index: Int, val outside: Boolean) : OmniItem

    data class AlbumHit(val album: Album, val outside: Boolean) : OmniItem

    data class ArtistHit(val artist: Artist, val outside: Boolean) : OmniItem

    data class PlaylistHit(val playlist: Playlist) : OmniItem

    // Every result, on the Search page.
    data class SeeAll(val text: String) : OmniItem
}

// A heading and its lines.
data class OmniSection(val title: String, val items: List<OmniItem>)

// How many of each kind the box shows; See all shows the rest.
private const val SONGS = 6
private const val ARTISTS = 3
private const val ALBUMS = 4
private const val PLAYLISTS = 3
private const val OUTSIDE = 3
private const val COMMANDS_WITH_RESULTS = 3

// Typed first, this asks for commands only.
const val COMMAND_MARK = ">"

// What the box lists for what is typed: with ">" first, the commands that
// match; with nothing typed, the recent searches; otherwise a few commands
// that match, then the library's songs, artists, albums and playlists, what
// the server found online, and a way to see everything. `found` is the
// last answer to the search, kept while a newer one is on its way.
fun omniSections(text: String, found: SearchFound?, commands: List<Command>, recent: List<String>): List<OmniSection> {
    val typed = text.trim()
    if (typed.startsWith(COMMAND_MARK)) {
        val matched = matchCommands(commands, typed.removePrefix(COMMAND_MARK))
        return matched.groupBy { it.group }.map { (group, list) -> OmniSection(group, list.map(OmniItem::Run)) }
    }
    if (typed.isEmpty()) {
        return if (recent.isEmpty()) emptyList() else listOf(OmniSection("Recent searches", recent.map(OmniItem::Recent)))
    }
    return buildList {
        if (typed.length >= 2) {
            val matched = matchCommands(commands, typed).take(COMMANDS_WITH_RESULTS)
            if (matched.isNotEmpty()) add(OmniSection("Commands", matched.map(OmniItem::Run)))
        }
        val library = found?.library
        if (library != null) {
            val songs = library.songs
            if (songs.isNotEmpty()) add(OmniSection("Songs", songs.take(SONGS).mapIndexed { i, song -> OmniItem.SongHit(song, songs, i, outside = false) }))
            if (library.artists.isNotEmpty()) add(OmniSection("Artists", library.artists.take(ARTISTS).map { OmniItem.ArtistHit(it, outside = false) }))
            if (library.albums.isNotEmpty()) add(OmniSection("Albums", library.albums.take(ALBUMS).map { OmniItem.AlbumHit(it, outside = false) }))
            if (library.playlists.isNotEmpty()) add(OmniSection("Playlists", library.playlists.take(PLAYLISTS).map(OmniItem::PlaylistHit)))
        }
        val outside = found?.outside
        if (outside != null && !outside.isEmpty) {
            val songs = outside.songs
            add(
                OmniSection(
                    "Not in your library",
                    songs.take(OUTSIDE).mapIndexed { i, song -> OmniItem.SongHit(song, songs, i, outside = true) } +
                        outside.albums.take(1).map { OmniItem.AlbumHit(it, outside = true) },
                ),
            )
        }
        if (found != null) add(OmniSection("", listOf(OmniItem.SeeAll(typed))))
    }
}

// Whether the box is open, and which line the keyboard is on (counted
// down the whole list, headings left out).
@Stable
class OmniboxState {
    var open by mutableStateOf(false)
    var highlight by mutableIntStateOf(0)

    // The last full answer, shown while a newer search is on its way.
    var lastFound by mutableStateOf<SearchFound?>(null)

    // Where the field and the list are in the window, so a click anywhere
    // else closes the list.
    var field by mutableStateOf(IntRect.Zero)
    var panel by mutableStateOf(IntRect.Zero)

    fun holds(x: Int, y: Int) = field.contains(IntOffset(x, y)) || panel.contains(IntOffset(x, y))

    fun move(step: Int, count: Int) {
        if (count == 0) return
        highlight = (highlight + step).mod(count)
    }
}
