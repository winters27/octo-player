package app.winters.octo.desktop.pages

import app.winters.octo.desktop.ui.PlaylistPicture
import app.winters.octo.desktop.ui.summary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.design.Corner
import app.winters.octo.design.CutTxt
import app.winters.octo.design.DesktopType
import app.winters.octo.design.GlassField
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.asPlaylist
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.filteredCount
import app.winters.octo.desktop.library.rememberFiltered
import app.winters.octo.desktop.loadPlaylist
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.playlistView
import app.winters.octo.desktop.playlists.movedPositions
import app.winters.octo.desktop.renamePlaylist
import app.winters.octo.desktop.reorderPlaylist
import app.winters.octo.desktop.setPlaylistComment
import app.winters.octo.desktop.ui.PageLoadingLine
import app.winters.octo.desktop.ui.FilterBar
import app.winters.octo.desktop.ui.Load
import app.winters.octo.desktop.ui.Loaded
import app.winters.octo.desktop.ui.NoMatches
import app.winters.octo.desktop.ui.PageSide
import app.winters.octo.desktop.ui.PlaylistMenu
import app.winters.octo.desktop.ui.RowDrop
import app.winters.octo.desktop.ui.SongPlace
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberShownFields
import app.winters.octo.desktop.ui.show
import app.winters.octo.query.LibraryQuery
import app.winters.octo.subsonic.Album

// A playlist: its songs in the playlist's own order, edited in place. The
// listener's own playlist is renamed by clicking its name and described by
// clicking the line under it; its songs move and come out from their menu.
// Changes show at once and go back if the server refuses them. The bar under
// the heading filters the songs; moves and removals still go by the
// playlist's own places.
@Composable
fun PlaylistPage(app: AppState, visit: Visit, id: String) {
    val connection = app.connection ?: return
    // Read again once the server's list says it changed (songs added from
    // a menu, a move saved), keeping the old one on screen meanwhile.
    val known = app.playlists.firstOrNull { it.id == id }
    var failed by remember(id) { mutableStateOf<String?>(null) }
    var round by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(connection, id, known?.songCount, known?.changed, round) { failed = app.loadPlaylist(id) }
    val view = app.playlistView(id)
    val state = when {
        view != null -> Load.Ready(view)
        failed != null -> Load.Failed(failed.orEmpty())
        else -> Load.Loading
    }
    val list = rememberListState(app.navigator, visit)
    val query = app.navigator.filterOf(visit)
    val filter: (LibraryQuery) -> Unit = { app.navigator.keepFilter(visit, it) }
    val fields = rememberShownFields(app)
    Loaded(state) { round++ }.show(Modifier.padding(horizontal = PageSide)) { playlist ->
        val all = playlist.entry
        val shown = rememberFiltered(all, query, fields) ?: return@show PageLoadingLine()
        val songs = shown.songs
        val menuPlaylist = known ?: playlist.asPlaylist()
        val owns = app.canEdit(menuPlaylist)
        SongTable(
            app,
            songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Favourite, SongColumn.Length),
            list,
            id = "playlist",
            place = { picked -> SongPlace.Playlist(id, picked.map { shown.placeOf(it.position) }) },
            // The number is the song's place in the playlist, filtered or not.
            number = { index, _ -> "${shown.placeOf(index) + 1}" },
            // In the listener's own playlist, its rows dragged onto another
            // row move there, above or below it.
            rowDrop = if (!owns) {
                null
            } else {
                RowDrop("Move here", takes = { (it as? SongPlace.Playlist)?.id == id }) { _, from, row, below ->
                    val picked = (from as? SongPlace.Playlist)?.positions.orEmpty()
                    val to = shown.placeOf(row.position) + if (below) 1 else 0
                    if (picked.isNotEmpty()) app.reorderPlaylist(id, movedPositions(all.indices.toList(), picked, to).map { all[it] })
                }
            },
            empty = {
                if (query.filters && all.isNotEmpty()) NoMatches { filter(query.cleared()) }
                else NothingHere("This playlist is empty", "Right-click songs anywhere and pick Add to playlist.")
            },
        ) {
            item(key = "head") {
                ListHeader(
                    "Playlist",
                    playlist.name,
                    playlist.coverArt,
                    if (query.filters) {
                        listOfNotNull(playlist.owner?.let { "By $it" }, filteredCount(songs.size, all.size, true)).joinToString(" · ")
                    } else {
                        playlistDetails(playlist.owner, all.size, all.sumOf { it.duration }, playlist.public)
                    },
                    { app.play(songs) },
                    { app.play(songs, shuffle = true) },
                    playable = songs.isNotEmpty(),
                    picture = { modifier -> PlaylistPicture(app, playlist.summary(), modifier, Corner.ArtLShape) },
                    titleContent = {
                        EditInPlace(playlist.name, "Playlist name", owns, "Rename", TitleFieldWidth, { app.renamePlaylist(id, it) }) { modifier ->
                            CutTxt(playlist.name, DesktopType.pageTitle, modifier = modifier, maxLines = 2)
                        }
                    },
                    subtitle = {
                        val comment = playlist.comment.orEmpty()
                        if (owns || comment.isNotBlank()) {
                            EditInPlace(comment, "Add a description", owns, "Edit the description", TitleFieldWidth, { app.setPlaylistComment(id, it) }) { modifier ->
                                if (comment.isBlank()) {
                                    Txt("Add a description", DesktopType.table, OctoColors.TextMuted, modifier)
                                } else {
                                    CutTxt(comment, DesktopType.table, OctoColors.TextSecondary, modifier, maxLines = 2)
                                }
                            }
                        }
                    },
                    extras = {
                        MoreButton(app, "More for this playlist") { close -> PlaylistMenu(app, menuPlaylist, close) }
                    },
                )
            }
            if (all.isNotEmpty()) item(key = "filters") { FilterBar(app, query, filter, all) }
        }
    }
}

// The line under a playlist's name: whose it is, how many songs and how
// long, and whether everyone on the server can see it.
fun playlistDetails(owner: String?, count: Int, seconds: Int, public: Boolean): String =
    listOfNotNull(owner?.let { "By $it" }, songsLine(count, seconds), if (public) "Public" else "Private").joinToString(" · ")

private val TitleFieldWidth = 420.dp

// Words that become a field when clicked, when `editable`: Enter or
// clicking away keeps what was typed, Escape leaves the words as they were.
// `shown` draws the words, with the modifier that makes them clickable.
@Composable
private fun EditInPlace(
    text: String,
    placeholder: String,
    editable: Boolean,
    hint: String,
    width: Dp,
    save: (String) -> Unit,
    shown: @Composable (Modifier) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    if (!editable) {
        shown(Modifier)
        return
    }
    if (!editing) {
        OctoTooltip(hint) {
            shown(Modifier.hoverLift(Corner.ControlShape).clickable(role = Role.Button) { editing = true }.padding(horizontal = Space.Xxs))
        }
        return
    }
    var value by remember(text) { mutableStateOf(text) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun done(keep: Boolean) {
        if (!editing) return
        editing = false
        if (keep && value.trim() != text.trim()) save(value)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    GlassField(
        value,
        { value = it },
        Modifier.width(width).onFocusChanged {
            if (focused && !it.hasFocus) done(keep = true)
            focused = it.hasFocus
        },
        placeholder = placeholder,
        focusRequester = focus,
        onSubmit = { done(keep = true) },
        onEscape = { done(keep = false) },
    )
}
