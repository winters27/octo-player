package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.sortAlbums
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.LinkText
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberLoad
import app.winters.octo.desktop.ui.show
import app.winters.octo.design.IconAction
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import app.winters.octo.sort.SortList
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// An album: its cover and names, Play and Shuffle, the heart, and its songs
// in album order, with a heading for each disc when there are several.
@Composable
fun AlbumPage(app: AppState, visit: Visit, id: String) {
    val connection = app.connection ?: return
    val loaded = rememberLoad(connection, id) { connection.client.album(id) }
    val list = rememberListState(app.navigator, visit)
    loaded.show(Modifier.padding(horizontal = 28.dp)) { album ->
        val songs = album.song
        val discs = songs.mapNotNull { it.discNumber }.distinct()
        // An album found online is not in the library: no heart, since on
        // Octo a star would fetch the whole album.
        val inLibrary = app.library?.index?.hasAlbum(album.id) ?: true
        SongTable(
            app,
            songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Plays, SongColumn.Favourite, SongColumn.Length),
            list,
            id = "album",
            covers = false,
            number = { index, song -> song.track?.toString() ?: "${index + 1}" },
            groupTitle = { index ->
                val disc = songs[index].discNumber
                if (discs.size > 1 && disc != null && (index == 0 || songs[index - 1].discNumber != disc)) "Disc $disc" else null
            },
        ) {
            item(key = "head") {
                ListHeader(
                    "Album",
                    album.name,
                    album.coverArt,
                    listOfNotNull(album.year?.takeIf { it > 0 }?.toString(), album.genre, songsLine(songs.size, songs.sumOf { it.duration })).joinToString(" · "),
                    { app.play(songs) },
                    { app.play(songs, shuffle = true) },
                    subtitle = { LinkText(album.displayArtist ?: album.artist, album.artistId) { app.navigator.go(Page.Artist(it, album.artist)) } },
                    online = !inLibrary,
                    extras = {
                        if (inLibrary) {
                            val starred = app.isAlbumStarred(album.id, app.library?.index?.albums?.firstOrNull { it.id == album.id }?.starred)
                            IconAction(if (starred) OctoIcons.Liked else OctoIcons.Like, if (starred) "Remove from favourites" else "Add to favourites", { app.setAlbumStarred(album.id, !starred) })
                        }
                    },
                    art = 220.dp,
                )
            }
        }
    }
}

// What an artist page shows: the artist, their albums, their most played
// songs and artists like them.
class ArtistView(
    val name: String,
    val coverArt: String?,
    val albums: List<Album>,
    val top: List<Song>,
    val similar: List<app.winters.octo.subsonic.Artist>,
    val picture: String?,
)

// An artist: Play (their most played songs) and Shuffle, then their
// albums newest first, their top songs and similar artists.
@Composable
fun ArtistPage(app: AppState, visit: Visit, id: String, name: String) {
    val connection = app.connection ?: return
    val loaded = rememberLoad(connection, id) {
        coroutineScope {
            val artist = connection.client.artist(id)
            val info = async { runCatching { connection.client.artistInfo(id, similar = 12) }.getOrNull() }
            val byId = connection.supports("topSongsByArtistId")
            val top = async {
                try {
                    connection.client.topSongs(artist.name, 10, if (byId) id else null)
                } catch (e: SubsonicException) {
                    emptyList()
                }
            }
            val found = info.await()
            ArtistView(
                artist.name,
                artist.coverArt,
                sortAlbums(artist.album, SortList.ArtistAlbums.default),
                top.await(),
                found?.similarArtist.orEmpty(),
                found?.largeImageUrl,
            )
        }
    }
    val list = rememberListState(app.navigator, visit)
    loaded.show(Modifier.padding(horizontal = 28.dp)) { artist ->
        SongTable(
            app,
            artist.top,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Album, SongColumn.Plays, SongColumn.Favourite, SongColumn.Length),
            list,
            id = "artist",
        ) {
            item(key = "head") {
                ListHeader(
                    "Artist",
                    artist.name.ifEmpty { name },
                    artist.coverArt,
                    if (artist.albums.size == 1) "1 album" else "${artist.albums.size} albums",
                    { app.play(artist.top) },
                    { app.play(artist.top, shuffle = true) },
                    round = true,
                    playable = artist.top.isNotEmpty(),
                    extras = {
                        val starred = app.isArtistStarred(id, app.library?.index?.artists?.firstOrNull { it.id == id }?.starred)
                        IconAction(if (starred) OctoIcons.Liked else OctoIcons.Like, if (starred) "Remove from favourites" else "Add to favourites", { app.setArtistStarred(id, !starred) })
                    },
                )
            }
            item(key = "albums") { Shelf("Albums", artist.albums, { it.id }) { AlbumCard(app, it) } }
            item(key = "similar") { Shelf("Similar artists", artist.similar, { it.id }) { ArtistCard(app, it) } }
            if (artist.top.isNotEmpty()) item(key = "top-title") { Txt("Top songs", OctoType.headline, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)) }
        }
    }
}

// A playlist: its songs in the playlist's own order.
@Composable
fun PlaylistPage(app: AppState, visit: Visit, id: String) {
    val connection = app.connection ?: return
    // Opened again after songs are added from a menu, so it shows them.
    val known = app.playlists.firstOrNull { it.id == id }
    val loaded = rememberLoad(connection, id, known?.songCount, known?.changed) { connection.client.playlist(id) }
    val list = rememberListState(app.navigator, visit)
    loaded.show(Modifier.padding(horizontal = 28.dp)) { playlist ->
        val songs = playlist.entry
        SongTable(
            app,
            songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Favourite, SongColumn.Length),
            list,
            id = "playlist",
            empty = { NothingHere("This playlist is empty", "Right-click songs anywhere and pick Add to playlist.") },
        ) {
            item(key = "head") {
                ListHeader(
                    "Playlist",
                    playlist.name,
                    playlist.coverArt,
                    listOfNotNull(playlist.owner?.let { "By $it" }, songsLine(songs.size, songs.sumOf { it.duration })).joinToString(" · "),
                    { app.play(songs) },
                    { app.play(songs, shuffle = true) },
                    playable = songs.isNotEmpty(),
                    subtitle = playlist.comment?.takeIf(String::isNotBlank)?.let { { Txt(it, OctoType.bodySmall, OctoColors.TextSecondary, maxLines = 2) } },
                )
            }
        }
    }
}
