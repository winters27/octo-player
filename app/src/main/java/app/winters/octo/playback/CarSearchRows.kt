package app.winters.octo.playback

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.OnlineAlbum
import app.winters.octo.discovery.OnlineArtist
import app.winters.octo.discovery.SEARCH_TOP_SHOWN
import app.winters.octo.ui.search.LIBRARY_SONGS_SHOWN
import app.winters.octo.ui.search.ONLINE_SONGS_SHOWN

// How many of each kind a car's search shows before "See all". The car's
// rows run down the screen, about five to a view, where the phone's rows of
// artists and albums scroll sideways; songs and top songs show as on the
// phone.
const val CAR_SEARCH_ARTISTS = 5
const val CAR_SEARCH_ALBUMS = 6
const val CAR_SEARCH_PLAYLISTS = 3

// One thing a car's search lists.
sealed interface SearchEntry {
    data class LibraryArtist(val artist: ArtistEntity) : SearchEntry
    data class OutsideArtist(val artist: OnlineArtist) : SearchEntry
    data class Song(val track: TrackEntity, val list: CarNode) : SearchEntry
    data class LibraryAlbum(val album: AlbumEntity) : SearchEntry
    data class OutsideAlbum(val album: OnlineAlbum) : SearchEntry
    data class Playlist(val playlist: PlaylistSummary) : SearchEntry
    data class SeeAll(val node: CarNode) : SearchEntry
    data class Retry(val token: String) : SearchEntry
}

// A kind's run of a car's search, under its heading.
data class SearchRow(val title: String, val entries: List<SearchEntry>)

// A car's search as the phone's All lists it: artists (the library's, then
// those found outside it), songs (the library's best, then those found
// outside it, then "try again" when looking outside failed), the named
// artist's top songs, albums (the library's, those it holds part of, then
// the rest), playlists, and, when the library has no artist, the artists
// found outside it last. A song plays its kind's whole list from itself.
fun carSearchRows(answer: CarSearchAnswer): List<SearchRow> {
    val token = answer.token
    val outside = answer.outside
    val rows = mutableListOf<SearchRow>()
    fun seeAll(more: Boolean, node: CarNode) = if (more) listOf(SearchEntry.SeeAll(node)) else emptyList()

    val libraryArtists = answer.library.artists.map(SearchEntry::LibraryArtist)
    val outsideArtists = outside?.artists.orEmpty().map(SearchEntry::OutsideArtist)
    if (libraryArtists.isNotEmpty()) {
        val all = libraryArtists + outsideArtists
        rows += SearchRow("Artists", all.take(CAR_SEARCH_ARTISTS) + seeAll(all.size > CAR_SEARCH_ARTISTS, CarNode.Found(token, FoundKind.Artists)))
    }

    val songList = CarNode.Found(token, FoundKind.Songs)
    val librarySongs = answer.library.songs
    val outsideSongs = outside?.songs.orEmpty()
    val songs = librarySongs.take(LIBRARY_SONGS_SHOWN).map { SearchEntry.Song(it, songList) } +
        outsideSongs.take(ONLINE_SONGS_SHOWN).map { SearchEntry.Song(it, songList) } +
        seeAll(librarySongs.size > LIBRARY_SONGS_SHOWN || outsideSongs.size > ONLINE_SONGS_SHOWN, songList) +
        if (answer.outsideFailed) listOf(SearchEntry.Retry(token)) else emptyList()
    if (songs.isNotEmpty()) rows += SearchRow("Songs", songs)

    answer.top?.songs?.takeIf { it.isNotEmpty() }?.let { top ->
        val topList = CarNode.Top(token)
        rows += SearchRow("Top songs", top.take(SEARCH_TOP_SHOWN).map { SearchEntry.Song(it.track, topList) } + seeAll(top.size > SEARCH_TOP_SHOWN, topList))
    }

    val albums = answer.library.albums.map(SearchEntry::LibraryAlbum) +
        outside?.let { it.partAlbums + it.albums }.orEmpty().map(SearchEntry::OutsideAlbum)
    if (albums.isNotEmpty()) {
        rows += SearchRow("Albums", albums.take(CAR_SEARCH_ALBUMS) + seeAll(albums.size > CAR_SEARCH_ALBUMS, CarNode.Found(token, FoundKind.Albums)))
    }

    val playlists = answer.library.playlists
    if (playlists.isNotEmpty()) {
        rows += SearchRow(
            "Playlists",
            playlists.take(CAR_SEARCH_PLAYLISTS).map(SearchEntry::Playlist) + seeAll(playlists.size > CAR_SEARCH_PLAYLISTS, CarNode.Found(token, FoundKind.Playlists)),
        )
    }

    if (libraryArtists.isEmpty() && outsideArtists.isNotEmpty()) {
        rows += SearchRow(
            "Artists",
            outsideArtists.take(CAR_SEARCH_ARTISTS) + seeAll(outsideArtists.size > CAR_SEARCH_ARTISTS, CarNode.Found(token, FoundKind.Artists)),
        )
    }
    return rows
}
