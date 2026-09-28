package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.nav.Page
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.launch
import java.util.Locale

private enum class MenuPage { Main, Playlists }

// The menu for songs: one, or everything picked in a table. Songs found
// online (`outside`) are not in the library, so they have no favourite
// heart (on Octo a star would fetch them) and no album or artist to open.
@Composable
fun ColumnScope.SongMenu(app: AppState, songs: List<Song>, close: () -> Unit, outside: Boolean = false) {
    var page by remember { mutableStateOf(MenuPage.Main) }
    val one = songs.singleOrNull()
    when (page) {
        MenuPage.Main -> {
            MenuTitle(one?.title ?: "${songs.size} songs")
            MenuRow("Play", { app.play(songs); close() }, OctoIcons.Play)
            MenuRow("Play next", { app.playNext(songs); close() }, OctoIcons.PlayNext)
            MenuRow("Add to queue", { app.addToQueue(songs); close() }, OctoIcons.AddToQueue)
            MenuRow("Add to playlist", { page = MenuPage.Playlists }, OctoIcons.AddToPlaylist, more = true)
            if (!outside) {
                MenuSeparator()
                if (one != null) {
                    MenuRow("Go to album", { one.albumId?.let { app.navigator.go(Page.Album(it)) }; close() }, OctoIcons.Album, enabled = !one.albumId.isNullOrEmpty())
                    MenuRow("Go to artist", { one.artistId?.let { app.navigator.go(Page.Artist(it, one.artist.orEmpty())) }; close() }, OctoIcons.Artist, enabled = !one.artistId.isNullOrEmpty())
                }
                val all = songs.all(app::isStarred)
                MenuRow(
                    if (all) "Remove from favourites" else "Add to favourites",
                    { app.setStarred(songs, !all); close() },
                    if (all) OctoIcons.Liked else OctoIcons.Like,
                )
            }
            if (one != null) MenuRow("Song details", { app.showInfo(one); close() }, OctoIcons.Info)
        }
        MenuPage.Playlists -> PlaylistChooser(app, { songs }, close) { page = MenuPage.Main }
    }
}

// The menu for an album card: play it, queue it, or keep it.
@Composable
fun ColumnScope.AlbumMenu(app: AppState, album: Album, close: () -> Unit, outside: Boolean = false) {
    var choosing by remember { mutableStateOf(false) }
    fun withSongs(action: (List<Song>) -> Unit) {
        close()
        app.scope.launch { runCatching { app.albumSongs(album.id) }.getOrNull()?.let(action) }
    }
    if (choosing) {
        PlaylistChooser(app, { app.albumSongs(album.id) }, close) { choosing = false }
        return
    }
    MenuTitle(album.name)
    MenuRow("Play", { withSongs { app.play(it) } }, OctoIcons.Play)
    MenuRow("Shuffle", { withSongs { app.play(it, shuffle = true) } }, OctoIcons.Shuffle)
    MenuRow("Play next", { withSongs { app.playNext(it) } }, OctoIcons.PlayNext)
    MenuRow("Add to queue", { withSongs { app.addToQueue(it) } }, OctoIcons.AddToQueue)
    MenuRow("Add to playlist", { choosing = true }, OctoIcons.AddToPlaylist, more = true)
    if (!outside) {
        MenuSeparator()
        val starred = app.isAlbumStarred(album.id, album.starred)
        MenuRow(if (starred) "Remove from favourites" else "Add to favourites", { app.setAlbumStarred(album.id, !starred); close() }, if (starred) OctoIcons.Liked else OctoIcons.Like)
        MenuRow("Go to artist", { album.artistId?.let { app.navigator.go(Page.Artist(it, album.artist)) }; close() }, OctoIcons.Artist, enabled = !album.artistId.isNullOrEmpty())
    }
}

// Picks a playlist to add songs to, or makes a new one with them. The
// songs are gathered only once a playlist is chosen.
@Composable
fun ColumnScope.PlaylistChooser(app: AppState, songs: suspend () -> List<Song>, close: () -> Unit, back: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    MenuRow("Back", back, OctoIcons.Back)
    MenuSeparator()
    if (naming) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val create = {
                if (name.isNotBlank()) {
                    close()
                    app.scope.launch { app.createPlaylist(name, runCatching { songs() }.getOrDefault(emptyList())) }
                }
            }
            GlassField(name, { name = it }, Modifier.weight(1f), placeholder = "Playlist name", onSubmit = create)
            GlazeCapsule(null, "Create", create, lit = true, enabled = name.isNotBlank())
        }
    } else {
        MenuRow("New playlist", { naming = true }, OctoIcons.AddToLibrary)
    }
    val own = app.playlists.filter { !it.readonly && (it.owner == null || it.owner == app.connection?.client?.username) }
    if (own.isNotEmpty()) MenuSeparator()
    own.forEach { playlist ->
        MenuRow(playlist.name, {
            close()
            app.scope.launch { app.addToPlaylist(playlist.id, runCatching { songs() }.getOrDefault(emptyList())) }
        }, OctoIcons.Playlists, detail = "${playlist.songCount}")
    }
}
