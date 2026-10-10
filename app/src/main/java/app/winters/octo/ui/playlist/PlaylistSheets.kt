package app.winters.octo.ui.playlist

import app.winters.octo.covers.playlistGlyph
import app.winters.octo.ui.common.PlaylistArtwork
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.UserDao
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupPager
import app.winters.octo.design.PopupPages
import app.winters.octo.livelists.LiveListSongs
import app.winters.octo.livelists.LiveListStore
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playlists.PlaylistFiles
import app.winters.octo.playlists.RecentPlaylists
import app.winters.octo.playlists.playlistFileName
import app.winters.octo.ui.common.CloudMark
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.GlassMenuAction
import app.winters.octo.ui.common.GlassMenuBack
import app.winters.octo.ui.common.GlassMenuHeading
import app.winters.octo.ui.common.GlassMenuNote
import app.winters.octo.ui.common.GlassMenuPage
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.MenuWidth
import app.winters.octo.ui.common.PopupNameField
import app.winters.octo.ui.common.PopupNameForm
import app.winters.octo.ui.common.PopupQuestion
import app.winters.octo.ui.common.rememberOpenedBeside
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.livelists.liveCopyMessage
import app.winters.octo.ui.livelists.nothingToCopy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// The menus and forms for playlists. They pop up over everything, the bar
// included, so any page can open one.
sealed interface PlaylistSheet {
    // Choose a playlist to add songs to: many picked in a list, say.
    data class Pick(val trackIds: List<String>) : PlaylistSheet {
        constructor(trackId: String) : this(listOf(trackId))
    }

    // Name a new playlist, and put the songs in it if there are any.
    data class Create(val trackIds: List<String> = emptyList()) : PlaylistSheet

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

    // A live list's options: edit its rules, rename, copy, save what it
    // holds now as a playlist, delete (asked).
    data class LiveOptions(val id: String, val name: String) : PlaylistSheet
    data class LiveRename(val id: String, val name: String) : PlaylistSheet
    data class LiveDelete(val id: String, val name: String) : PlaylistSheet
}

class PlaylistSheets {
    var open by mutableStateOf<PlaylistSheet?>(null)
        private set

    // What the pop-up shows. One asked for while it is open becomes its next
    // page, with a way back.
    val pages = PopupPages<PlaylistSheet>(PlaylistSheet.Create())

    // Counts every showing, so a field starts fresh each time.
    var shown by mutableIntStateOf(0)
        private set

    fun show(sheet: PlaylistSheet) {
        if (open == null) pages.reset(sheet) else pages.open(sheet)
        open = sheet
        shown++
    }

    fun close() {
        open = null
    }

    // Opens a live list's editor, set by the screen that can navigate.
    var editLiveList: ((String) -> Unit)? = null
}

val LocalPlaylistSheets = staticCompositionLocalOf<PlaylistSheets> { error("No playlist sheets") }

@HiltViewModel
class PlaylistSheetsViewModel @Inject constructor(
    private val store: PlaylistStore,
    private val offline: OfflineDownloads,
    private val files: PlaylistFiles,
    private val userDao: UserDao,
    private val feedback: Feedback,
    private val recent: RecentPlaylists,
    private val liveLists: LiveListStore,
    private val liveSongs: LiveListSongs,
) : ViewModel() {
    val playlists: StateFlow<List<PlaylistSummary>> =
        store.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The playlists songs were added to lately, newest first, by id.
    val recentIds: StateFlow<List<String>> =
        recent.ids.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun used(id: String) {
        viewModelScope.launch { recent.used(id) }
    }

    // Which of the songs each playlist has already, by playlist.
    fun holdings(trackIds: List<String>): Flow<Map<String, Set<String>>> {
        val parts = trackIds.distinct().chunked(900).map { userDao.playlistHoldings(it) }
        if (parts.isEmpty()) return flowOf(emptyMap())
        return combine(parts) { rows -> holdingsByPlaylist(rows.flatMap { part -> part.map { it.playlistId to it.trackId } }) }
    }

    // A new playlist with songs in it says where they went, since nothing
    // on screen shows it.
    fun create(name: String, trackIds: List<String>) {
        val id = store.create(name, trackIds)
        if (trackIds.isNotEmpty()) {
            used(id)
            feedback.show(addedMessage(name.trim(), trackIds.size))
        }
    }

    fun rename(id: String, name: String) = store.rename(id, name)
    fun delete(id: String) = store.delete(id)

    // Adds the songs, with an Undo that takes them back out.
    fun add(playlist: PlaylistSummary, trackIds: List<String>) {
        used(playlist.id)
        store.add(playlist.id, trackIds) { rows ->
            feedback.undoable(addedMessage(playlist.name, rows.size)) { store.removeRows(playlist.id, rows) }
        }
    }

    fun saveToServer(id: String) = store.saveToServer(id)

    // Live lists.

    fun renameLive(id: String, name: String) {
        viewModelScope.launch { liveLists.rename(id, name) }
    }

    fun deleteLive(id: String) {
        viewModelScope.launch { liveLists.remove(id) }
    }

    fun duplicateLive(id: String) {
        viewModelScope.launch {
            val list = liveLists.byId(id) ?: return@launch
            feedback.show(copiedMessage(liveLists.duplicate(list).name))
        }
    }

    // A playlist of the songs the live list holds now: a copy, which does
    // not change with the rules.
    fun copyLive(id: String) {
        viewModelScope.launch {
            val list = liveLists.byId(id) ?: return@launch
            val songs = liveSongs.now(list)
            if (songs.isEmpty()) {
                feedback.show(nothingToCopy(list.name))
                return@launch
            }
            store.create(list.name, songs.map { it.id })
            feedback.show(liveCopyMessage(list.name, songs.size))
        }
    }

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

// The playlist pop-up, beside what opened it.
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

    val open = sheets.open != null
    GlassPopup(
        visible = open,
        anchor = rememberOpenedBeside(open),
        onDismiss = sheets::close,
        backdrop = LocalHaze.current,
        title = "Playlist",
        onBack = sheets.pages::back,
    ) {
        PopupPager(sheets.pages) { sheet, canGoBack ->
            val back: (() -> Unit)? = if (canGoBack) ({ sheets.pages.back() }) else null
            when (sheet) {
                is PlaylistSheet.Pick -> PlaylistPickerPage(sheet.trackIds, onBack = back, onDone = sheets::close, vm = vm)
                is PlaylistSheet.Create -> PopupNameForm("New playlist", "Playlist name", "Create", onBack = back, onDone = { name ->
                    vm.create(name, sheet.trackIds)
                    sheets.close()
                })
                is PlaylistSheet.Options -> GlassMenuPage(header = { GlassMenuHeading(sheet.name) }) {
                    GlassMenuAction(OctoIcons.Rename, "Rename", opensPage = true, onClick = { sheets.show(PlaylistSheet.Rename(sheet.id, sheet.name)) })
                    if (sheet.canSave) {
                        GlassMenuAction(OctoIcons.Cloud, "Save to server", onClick = {
                            vm.saveToServer(sheet.id)
                            sheets.close()
                        })
                    }
                    if (sheet.canDownload) {
                        GlassMenuAction(OctoIcons.Download, "Download", onClick = {
                            vm.download(sheet.id)
                            sheets.close()
                        })
                    }
                    GlassMenuAction(OctoIcons.Share, "Export as M3U", onClick = {
                        exporting = sheet.id
                        sheets.close()
                        exportTo.launch(playlistFileName(sheet.name))
                    })
                    GlassMenuAction(
                        OctoIcons.Delete,
                        "Delete",
                        opensPage = true,
                        destructive = true,
                        onClick = { sheets.show(PlaylistSheet.Delete(sheet.id, sheet.name, sheet.onServer)) },
                    )
                }
                is PlaylistSheet.Rename -> PopupNameForm("Rename playlist", "Playlist name", "Save", onBack = back, initial = sheet.name, onDone = { name ->
                    vm.rename(sheet.id, name)
                    sheets.close()
                })
                is PlaylistSheet.Delete -> ConfirmDelete(sheet.name, sheet.onServer, onCancel = back ?: sheets::close) {
                    vm.delete(sheet.id)
                    sheets.close()
                }
                is PlaylistSheet.LiveOptions -> GlassMenuPage(header = { GlassMenuHeading(sheet.name) }) {
                    sheets.editLiveList?.let { edit ->
                        GlassMenuAction(OctoIcons.Filter, "Edit rules", onClick = {
                            sheets.close()
                            edit(sheet.id)
                        })
                    }
                    GlassMenuAction(OctoIcons.Rename, "Rename", opensPage = true, onClick = { sheets.show(PlaylistSheet.LiveRename(sheet.id, sheet.name)) })
                    GlassMenuAction(OctoIcons.AddToPlaylist, "Duplicate", onClick = {
                        vm.duplicateLive(sheet.id)
                        sheets.close()
                    })
                    GlassMenuAction(OctoIcons.Playlists, "Save a copy as a playlist", onClick = {
                        vm.copyLive(sheet.id)
                        sheets.close()
                    })
                    GlassMenuAction(
                        OctoIcons.Delete,
                        "Delete",
                        opensPage = true,
                        destructive = true,
                        onClick = { sheets.show(PlaylistSheet.LiveDelete(sheet.id, sheet.name)) },
                    )
                }
                is PlaylistSheet.LiveRename -> PopupNameForm("Rename live list", "Live list name", "Save", onBack = back, initial = sheet.name, onDone = { name ->
                    vm.renameLive(sheet.id, name)
                    sheets.close()
                })
                is PlaylistSheet.LiveDelete -> ConfirmDelete(sheet.name, onServer = false, onCancel = back ?: sheets::close) {
                    vm.deleteLive(sheet.id)
                    sheets.close()
                }
            }
        }
    }
}

// The playlists to add songs to, as a page of a glass menu: "New playlist"
// first, which turns into a name field in place, then the playlists.
// Picking one adds the songs and closes; picking one that has some of them
// already asks first. The playlists added to lately come first. `onBack` is
// there when it was opened from a menu. With `start`, it adds to that
// playlist at once, as "Add to last playlist" does, asking first the same way.
@Composable
fun PlaylistPickerPage(
    trackIds: List<String>,
    onBack: (() -> Unit)?,
    onDone: () -> Unit,
    vm: PlaylistSheetsViewModel = hiltViewModel(),
    start: String? = null,
) {
    val all by vm.playlists.collectAsStateWithLifecycle()
    val recent by vm.recentIds.collectAsStateWithLifecycle()
    val playlists = remember(all, recent) { recentFirst(all, recent) { it.id } }
    val holdings by remember(trackIds) { vm.holdings(trackIds) }.collectAsStateWithLifecycle(null)
    var asking by remember { mutableStateOf<Pair<PlaylistSummary, AddPlan>?>(null) }
    var naming by remember { mutableStateOf(false) }
    // Whether the `start` playlist has been looked at yet.
    var started by remember { mutableStateOf(start == null) }
    val add = { playlist: PlaylistSummary, songs: List<String> ->
        vm.add(playlist, songs)
        onDone()
    }
    LaunchedEffect(start, all, holdings) {
        if (started) return@LaunchedEffect
        val playlist = all.firstOrNull { it.id == start } ?: return@LaunchedEffect
        val held = holdings ?: return@LaunchedEffect
        started = true
        val plan = planAdd(trackIds, held[playlist.id].orEmpty())
        if (plan.asks) asking = playlist to plan else add(playlist, plan.songs)
    }
    asking?.let { (playlist, plan) ->
        ConfirmAgain(
            addAgainQuestion(playlist.name, plan),
            plan,
            onCancel = { asking = null },
            onAddAll = { add(playlist, plan.songs) },
            onAddNew = { add(playlist, plan.fresh) },
        )
        return
    }
    if (!started) {
        GlassMenuPage { GlassMenuNote("Checking the playlist") }
        return
    }
    val title = if (trackIds.size == 1) "Add to playlist" else "Add ${songs(trackIds.size)} to a playlist"
    Column(Modifier.width(MenuWidth).padding(6.dp)) {
        if (onBack != null) GlassMenuBack(title, onBack) else GlassMenuHeading(title)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            item(key = "new") {
                if (naming) {
                    PopupNameField(
                        "Playlist name",
                        "Create",
                        onDone = { name ->
                            vm.create(name, trackIds.distinct())
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                } else {
                    GlassMenuAction(OctoIcons.AddToLibrary, "New playlist", onClick = { naming = true })
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                PickLine(playlist) {
                    val plan = planAdd(trackIds, holdings?.get(playlist.id).orEmpty())
                    if (plan.asks) asking = playlist to plan else add(playlist, plan.songs)
                }
            }
        }
    }
}

// One playlist to add to: its cover, name and size.
@Composable
private fun PickLine(playlist: PlaylistSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlaylistArtwork(playlist.id, playlist.name, playlist.covers, 36.dp, shape = RoundedCornerShape(6.dp), glyph = playlistGlyph(playlist.octoNotice, null)) {
            PlaylistCover(playlist.covers, 36.dp, shape = RoundedCornerShape(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(songs(playlist.songCount), style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1)
                if (playlist.onServer) CloudMark("On your server")
            }
        }
    }
}

// Asks before songs go on a playlist that has them already. With some new
// ones among them, the choice is all of them or only the new ones.
@Composable
private fun ConfirmAgain(question: String, plan: AddPlan, onCancel: () -> Unit, onAddAll: () -> Unit, onAddNew: () -> Unit) {
    if (plan.offersNewOnly) {
        Column(Modifier.width(MenuWidth).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)) {
            Text(question, style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentButton(ADD_NEW_ONES, onClick = onAddNew)
                GlazeButton(addAgainChoice(plan), onClick = onAddAll)
            }
        }
    } else {
        PopupQuestion(question, null, addAgainChoice(plan), onConfirm = onAddAll, onCancel = onCancel)
    }
}

// The new playlist name form on its own, for a playlist made from songs
// chosen somewhere else, such as the queue. `onCreate` gets the name.
@Composable
fun NewPlaylistForm(onCreate: (String) -> Unit) = PopupNameForm("New playlist", "Playlist name", "Create", onBack = null, onDone = onCreate)

// Asks before a playlist is deleted. Its songs stay in the library. One
// kept with the server goes from the server too.
@Composable
internal fun ConfirmDelete(name: String, onServer: Boolean, onCancel: () -> Unit, onDelete: () -> Unit) {
    PopupQuestion(
        "Delete \"$name\"?",
        if (onServer) "It is deleted from your server too. The songs stay in your library." else "The songs stay in your library.",
        "Delete",
        onConfirm = onDelete,
        onCancel = onCancel,
    )
}
