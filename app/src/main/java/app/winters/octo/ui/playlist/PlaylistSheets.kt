package app.winters.octo.ui.playlist

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
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassInput
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.menu.MenuRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
    data class Options(val id: String, val name: String, val onServer: Boolean = false, val canSave: Boolean = false) : PlaylistSheet
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
class PlaylistSheetsViewModel @Inject constructor(private val store: PlaylistStore) : ViewModel() {
    val playlists: StateFlow<List<PlaylistSummary>> =
        store.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, trackId: String?) = store.create(name, listOfNotNull(trackId))
    fun rename(id: String, name: String) = store.rename(id, name)
    fun delete(id: String) = store.delete(id)
    fun add(id: String, trackId: String) = store.add(id, listOf(trackId))
    fun saveToServer(id: String) = store.saveToServer(id)
}

@Composable
fun PlaylistSheetsHost(sheets: PlaylistSheets, vm: PlaylistSheetsViewModel = hiltViewModel()) {
    // Closing puts the keyboard away with it.
    val focus = LocalFocusManager.current
    LaunchedEffect(sheets.open) { if (sheets.open == null) focus.clearFocus() }

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

// "New playlist" first, then the playlists. Picking one adds the song and closes.
@Composable
private fun ColumnScope.Picker(sheets: PlaylistSheets, vm: PlaylistSheetsViewModel, trackId: String) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    SheetTitle("Add to playlist", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
        item(key = "new") { NewPlaylistLine { sheets.show(PlaylistSheet.Create(trackId)) } }
        items(playlists, key = { it.id }) { playlist ->
            PlaylistLine(playlist.name, songs(playlist.songCount), onClick = {
                vm.add(playlist.id, trackId)
                sheets.close()
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
