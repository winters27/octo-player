package app.winters.octo.desktop.home

import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.home.AlbumListening
import app.winters.octo.home.albumListening
import app.winters.octo.home.mostPlayedAlbums
import app.winters.octo.home.neverFinished
import app.winters.octo.home.neverPlayed
import app.winters.octo.home.notPlayedLately
import app.winters.octo.subsonic.Album

// The Home shelves that open a page of their own with See all, each worked
// out from the library the app holds: its name, which albums it holds and
// in what order (after the count, "12 albums, ..."), and what its page
// says when it is empty.
enum class AlbumShelf(val title: String, val detail: String, val emptyTitle: String, val emptyDetail: String) {
    MostPlayed(
        "Most played",
        "the most played first",
        "Nothing played yet",
        "Play some albums and the ones you come back to show here.",
    ),
    NotPlayedLately(
        "Not played in 6 months",
        "played before but not in six months, the most played first",
        "Nothing has gone quiet",
        "Albums you played before and haven't played in six months show here.",
    ),
    NeverFinished(
        "Albums you never finished",
        "some of their songs not played yet, the latest played first",
        "No albums left half played",
        "Albums you started and didn't play through show here.",
    ),
    NeverPlayed(
        "Unplayed albums",
        "none of their songs played yet, the newest first",
        "You've played every album",
        "New albums you haven't played yet show here.",
    ),
}

// Each album's listening in a library, for the shelves.
fun listeningOf(index: LibraryIndex): (Album) -> AlbumListening {
    val byId = albumListening(index.albums, index.songs)
    return { album -> byId[album.id] ?: AlbumListening(album.id, album.songCount, 0, album.playCount, null, null) }
}

// Every album on a shelf, in the shelf's order. Slow for a big library, so
// called away from the window's thread.
fun albumsOn(shelf: AlbumShelf, index: LibraryIndex, now: Long): List<Album> {
    val of = listeningOf(index)
    return when (shelf) {
        AlbumShelf.MostPlayed -> mostPlayedAlbums(index.albums, of)
        AlbumShelf.NotPlayedLately -> notPlayedLately(index.albums, of, now)
        AlbumShelf.NeverFinished -> neverFinished(index.albums, of)
        AlbumShelf.NeverPlayed -> neverPlayed(index.albums, of)
    }
}
