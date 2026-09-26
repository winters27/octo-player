package app.winters.octo.ui.playlist

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.UserDao
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassInput
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playlists.PlaylistFiles
import app.winters.octo.playlists.playlistFileName
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.menu.MenuRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// The sheets for playlists. They are drawn over everything, the bar
// included, so any page or the song menu can open one.
sealed interface PlaylistSheet {
    // Choose a playlist to add a song to.
    data class Pick(val trackId: String) : PlaylistSheet

    // Name a new playlist, and put the song in it if there is one.
    data class Create(val trackId: String? = null) : PlaylistSheet

    // Rename or delete a playlist, or save one only on the phone to the
    // server when `canSave`. `onServer` is one kept with the server.
    // `canDownload` when some of its songs are only on a server.
    data class Options(
        val id: String,
        val name: String,
        val onServer: Boolean = false,
        val canSave: Boolean = false,
        val canDownload: Boolean = false,
    ) : PlaylistSheet
    data class Rename(val id: String, val name: String) : PlaylistSheet
    data class Delete(val id: String, val name: String, val onServer: Boolean = false) : PlaylistSheet
}

class PlaylistSheets {
    var open by mutableStateOf<PlaylistSheet?>(null)
        private set

    // The last sheet shown, kept after closing so it can slide away.
    var last by mutableStateOf<PlaylistSheet?>(null)
        private set

    // Counts every showing, so a field starts fresh each time.
    var shown by mutableIntStateOf(0)
        private set

    fun show(sheet: PlaylistSheet) {
        open = sheet
        last = sheet
        shown++
    }

    fun close() {
        open = null
    }
}

val LocalPlaylistSheets = staticCompositionLocalOf<PlaylistSheets> { error("No playlist sheets") }

@HiltViewModel
class PlaylistSheetsViewModel @Inject constructor(
    private val store: PlaylistStore,
    private val offline: OfflineDownloads,
    private val files: PlaylistFiles,
    private val userDao: UserDao,
    private val feedback: Feedback,
) : ViewModel() {
    val playlists: StateFlow<List<PlaylistSummary>> =
        store.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The playlists a song is on already. Null until first read.
    fun playlistsWith(trackId: String): Flow<List<String>?> = userDao.playlistsWith(trackId)

    // A new playlist with the song in it says where the song went, since
    // nothing on screen shows it.
    fun create(name: String, trackId: String?) {
        store.create(name, listOfNotNull(trackId))
        if (trackId != null) feedback.show("Added to ${name.trim()}")
    }

    fun rename(id: String, name: String) = store.rename(id, name)
    fun delete(id: String) = store.delete(id)

    // Adds the song, with an Undo that takes it back out.
    fun add(playlist: PlaylistSummary, trackId: String) {
        store.add(playlist.id, listOf(trackId)) { rows ->
            feedback.undoable("Added to ${playlist.name}") { store.removeRows(playlist.id, rows) }
        }
    }

    fun saveToServer(id: String) = store.saveToServer(id)

    // Downloads the playlist's songs that are only on a server, once.
    fun download(id: String) = offline.downloadPlaylist(id)

    fun export(id: String, uri: Uri) {
        viewModelScope.launch {
            if (!files.export(id, uri)) {
                Log.w("Octo", "playlist export: could not write the file")
                feedback.show("Could not export the playlist")
            }
        }
    }
}

@Composable
fun PlaylistSheetsHost(sheets: PlaylistSheets, vm: PlaylistSheetsViewModel = hiltViewModel()) {
    // Closing puts the keyboard away with it.
    val focus = LocalFocusManager.current
    LaunchedEffect(sheets.open) { if (sheets.open == null) focus.clearFocus() }
    // The playlist being exported while the file picker is open.
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val id = exporting
        exporting = null
        if (uri != null && id != null) vm.export(id, uri)
    }

    GlassSheet(visible = sheets.open != null, onDismiss = sheets::close) {
        val sheet = sheets.last ?: return@GlassSheet
        key(sheets.shown) {
            when (sheet) {
                is PlaylistSheet.Pick -> Picker(sheets, vm, sheet.trackId)
                is PlaylistSheet.Create -> NameForm("New playlist", "", "Create") { name ->
                    vm.create(name, sheet.trackId)
                    sheets.close()
                }
                is PlaylistSheet.Options -> {
                    SheetTitle(sheet.name, Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(8.dp))
                    MenuRow(OctoIcons.Rename, "Rename") { sheets.show(PlaylistSheet.Rename(sheet.id, sheet.name)) }
                    if (sheet.canSave) {
                        MenuRow(OctoIcons.Cloud, "Save to server") {
                            vm.saveToServer(sheet.id)
                            sheets.close()
                        }
                    }
                    if (sheet.canDownload) {
                        MenuRow(OctoIcons.Download, "Download") {
                            vm.download(sheet.id)
                            sheets.close()
                        }
                    }
                    MenuRow(OctoIcons.Share, "Export as M3U") {
                        exporting = sheet.id
                        sheets.close()
                        exportTo.launch(playlistFileName(sheet.name))
                    }
                    MenuRow(OctoIcons.Delete, "Delete") { sheets.show(PlaylistSheet.Delete(sheet.id, sheet.name, sheet.onServer)) }
                    Spacer(Modifier.height(12.dp))
                }
                is PlaylistSheet.Rename -> NameForm("Rename playlist", sheet.name, "Save") { name ->
                    vm.rename(sheet.id, name)
                    sheets.close()
                }
                is PlaylistSheet.Delete -> ConfirmDelete(sheet.name, sheet.onServer, onCancel = sheets::close) {
                    vm.delete(sheet.id)
                    sheets.close()
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = OctoType.section, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

// "New playlist" first, then the playlists. Picking one adds the song and
// closes. Picking one the song is on already asks first.
@Composable
private fun ColumnScope.Picker(sheets: PlaylistSheets, vm: PlaylistSheetsViewModel, trackId: String) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val holding by remember(trackId) { vm.playlistsWith(trackId) }.collectAsStateWithLifecycle(null)
    var again by remember { mutableStateOf<PlaylistSummary?>(null) }
    val add = { playlist: PlaylistSummary ->
        vm.add(playlist, trackId)
        sheets.close()
    }
    again?.let { playlist ->
        ConfirmAgain(playlist.name, onCancel = { again = null }, onAdd = { add(playlist) })
        return
    }
    SheetTitle("Add to playlist", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
        item(key = "new") { NewPlaylistLine { sheets.show(PlaylistSheet.Create(trackId)) } }
        items(playlists, key = { it.id }) { playlist ->
            PlaylistLine(playlist.name, songs(playlist.songCount), onClick = {
                if (holding.orEmpty().contains(playlist.id)) again = playlist else add(playlist)
            }, onServer = playlist.onServer) {
                PlaylistCover(playlist.covers, 56.dp)
            }
        }
    }
}

// A name field with its button. The button waits for a name that is not blank.
@Composable
private fun NameForm(title: String, initial: String, action: String, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val ready = name.isNotBlank()
    val focus = remember { FocusRequester() }
    // Opens the keyboard as the field appears.
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
    ) {
        SheetTitle(title)
        Spacer(Modifier.height(16.dp))
        Row {
            GlassInput(
                value = name,
                onValueChange = { name = it },
                placeholder = "Playlist name",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ready) onDone(name) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            Spacer(Modifier.width(10.dp))
            AccentButton(action, onClick = { onDone(name) }, enabled = ready)
        }
    }
}

// Asks before a song goes on a playlist it is on already.
@Composable
private fun ConfirmAgain(name: String, onCancel: () -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        Text("Already in $name. Add again?", style = OctoType.section, color = OctoColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentButton("Add", onClick = onAdd)
            GlazeButton("Cancel", onClick = onCancel)
        }
    }
}

// Asks before a playlist is deleted. Its songs stay in the library. One
// kept with the server goes from the server too.
@Composable
private fun ConfirmDelete(name: String, onServer: Boolean, onCancel: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        SheetTitle("Delete \"$name\"?")
        Text(
            if (onServer) "It is deleted from your server too. The songs stay in your library." else "The songs stay in your library.",
            style = OctoType.bodySmall,
            color = OctoColors.TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentButton("Delete", onClick = onDelete)
            GlazeButton("Cancel", onClick = onCancel)
        }
    }
}
