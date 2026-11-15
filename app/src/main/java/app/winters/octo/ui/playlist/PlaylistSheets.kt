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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.data.spaceWords
import app.winters.octo.design.AccentButton
import app.winters.octo.playback.ListSpec
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
import app.winters.octo.playlists.PlaylistFolderStore
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.playlists.RecentPlaylists
import app.winters.octo.catalog.isFind
import app.winters.octo.ui.common.GlassMenuSeparator
import app.winters.octo.ui.common.GlassMenuTitle
import kotlinx.coroutines.flow.combine
import app.winters.octo.playlists.playlistFileName
import app.winters.octo.ui.common.CloudMark
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.GlassMenuAction
import app.winters.octo.ui.common.GlassMenuSwitch
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// The menus and forms for playlists. They pop up over everything, the bar
// included, so any page can open one.
sealed interface PlaylistSheet {
    // Choose a playlist to add songs to: many picked in a list, say, or a
    // whole album, artist or playlist.
    data class Pick(val source: AddSource) : PlaylistSheet {
        constructor(trackIds: List<String>) : this(AddSource.Ids(trackIds))
        constructor(trackId: String) : this(listOf(trackId))
    }

    // Name a new playlist, and put the songs in it if there are any; made
    // from inside a folder (`folderId`), it lands there.
    data class Create(val trackIds: List<String> = emptyList(), val folderId: String? = null) : PlaylistSheet

    // Rename or delete a playlist, or save one only on the phone to the
    // server when `canSave`. `onServer` is one kept with the server.
    // `canDownload` when some of its songs are only on a server. `keep`
    // offers its Keep downloaded switch, from its page. A family playlist
    // (`family`) is renamed and deleted only by a manager (`canRename`,
    // `canDelete`).
    data class Options(
        val id: String,
        val name: String,
        val onServer: Boolean = false,
        val canSave: Boolean = false,
        val canDownload: Boolean = false,
        val keep: Boolean = false,
        val family: Boolean = false,
        val canRename: Boolean = true,
        val canDelete: Boolean = true,
        // Its placement key while it can be filed and folders are offered.
        val placeKey: String? = null,
    ) : PlaylistSheet
    data class Rename(val id: String, val name: String) : PlaylistSheet
    data class Delete(val id: String, val name: String, val onServer: Boolean = false, val family: Boolean = false) : PlaylistSheet

    // A live list's options: edit its rules, rename, copy, save what it
    // holds now as a playlist, delete (asked).
    data class LiveOptions(val id: String, val name: String) : PlaylistSheet
    data class LiveRename(val id: String, val name: String) : PlaylistSheet
    data class LiveDelete(val id: String, val name: String) : PlaylistSheet

    // Playlist folders: name a new one in `parent` (null: the top level),
    // placing `placing` playlists or `moving` a folder into it once made;
    // rename, delete (asked) or move one; move playlists; fill one from a
    // checklist; a folder's options.
    data class NewFolder(val parent: String? = null, val placing: List<String> = emptyList(), val moving: String? = null) : PlaylistSheet
    data class RenameFolder(val id: String, val name: String) : PlaylistSheet
    data class DeleteFolder(val id: String, val name: String) : PlaylistSheet
    data class MoveToFolder(val target: FolderMoveTarget) : PlaylistSheet
    data class AddPlaylists(val folderId: String, val name: String) : PlaylistSheet
    data class FolderOptions(val id: String, val name: String) : PlaylistSheet
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
    private val feedback: Feedback,
    private val recent: RecentPlaylists,
    private val liveLists: LiveListStore,
    private val liveSongs: LiveListSongs,
    private val sources: AddSources,
    private val folders: PlaylistFolderStore,
) : ViewModel() {
    val playlists: StateFlow<List<PlaylistSummary>> =
        store.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Whether folders are offered, and the folders.
    val folderView: StateFlow<FolderView> =
        combine(folders.offered, folders.tree) { on, tree -> FolderView(on, tree) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderView.Off)

    // Whether songs found online can go in a playlist on this server.
    val findsOpen: Boolean get() = store.findsOpen

    // Whether what an add brings holds a song found online that can go in:
    // then the picker leaves out the upkeep lists that refuse one.
    suspend fun holdsFind(source: AddSource): Boolean {
        if (!store.findsOpen) return false
        return when (source) {
            is AddSource.Ids -> source.ids.any(::isFind)
            is AddSource.Spec -> when (val spec = source.spec) {
                is ListSpec.Album -> spec.withFinds
                is ListSpec.Playlist -> store.holdsFind(spec.playlistId)
                else -> false
            }
        }
    }

    // A folder made here, with playlists placed in it or a folder moved in.
    fun newFolder(name: String, parent: String?, placing: List<String>, moving: String?) {
        viewModelScope.launch {
            val id = folders.create(name, parent) ?: return@launch
            if (placing.isNotEmpty()) folders.place(placing, id)
            moving?.let { folders.move(it, id)?.let { refusal -> feedback.show(refusal.words) } }
        }
    }

    fun renameFolder(id: String, name: String) {
        viewModelScope.launch { folders.rename(id, name) }
    }

    fun deleteFolder(id: String) {
        viewModelScope.launch { folders.delete(id) }
    }

    fun place(keys: List<String>, folderId: String?) {
        viewModelScope.launch { folders.place(keys, folderId) }
    }

    fun moveFolder(id: String, parent: String?) {
        viewModelScope.launch { folders.move(id, parent)?.let { feedback.show(it.words) } }
    }

    // The playlists songs were added to lately, newest first, by id.
    val recentIds: StateFlow<List<String>> =
        recent.ids.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun used(id: String) {
        viewModelScope.launch { recent.used(id) }
    }

    // How adding `source` to a playlist would go, counted chunk by chunk
    // against the playlist's songs.
    suspend fun check(playlist: PlaylistSummary, source: AddSource): Pair<AddCounts, AddPlan?> = sources.check(playlist.id, source)

    // A new playlist with songs in it says where they went, since nothing
    // on screen shows it.
    fun create(name: String, trackIds: List<String>, folderId: String? = null) {
        val id = store.create(name, trackIds)
        if (folderId != null) viewModelScope.launch { folders.place(listOf(PlaylistFolderTree.localKey(id)), folderId) }
        if (trackIds.isNotEmpty()) {
            used(id)
            feedback.show(addedMessage(name.trim(), trackIds.size))
        }
    }

    // A new playlist of what `source` brings, each song once.
    fun create(name: String, source: AddSource) {
        if (source is AddSource.Ids) return create(name, source.ids.distinct())
        viewModelScope.launch {
            val (id, count) = store.createDistinct(name) { emit -> sources.pages(source, emit, store.findsOpen) }
            used(id)
            if (count > 0) feedback.show(addedMessage(name.trim(), count))
        }
    }

    fun rename(id: String, name: String) = store.rename(id, name)
    fun delete(id: String) = store.delete(id)

    // Adds the songs, with an Undo that takes them back out.
    fun add(playlist: PlaylistSummary, trackIds: List<String>) {
        used(playlist.id)
        store.add(playlist.id, if (playlist.refusesFinds) trackIds.filterNot(::isFind) else trackIds) { rows ->
            feedback.undoable(addedMessage(playlist.name, rows.count)) { store.undoAdd(playlist.id, rows) }
        }
    }

    // Adds what `source` brings a page at a time, each song once (with
    // `newOnly` only those the playlist does not hold yet), with an Undo.
    fun add(playlist: PlaylistSummary, source: AddSource, newOnly: Boolean, plan: AddPlan?) {
        if (source is AddSource.Ids && plan != null) return add(playlist, if (newOnly) plan.fresh else plan.songs)
        used(playlist.id)
        viewModelScope.launch {
            val finds = store.findsOpen && !playlist.refusesFinds
            val rows = store.addPages(playlist.id, newOnly) { emit -> sources.pages(source, emit, finds) } ?: return@launch
            feedback.undoable(addedMessage(playlist.name, rows.count)) { store.undoAdd(playlist.id, rows) }
        }
    }

    fun saveToServer(id: String) = store.saveToServer(id)

    // How many songs a list holds, for the picker's title.
    suspend fun countOf(spec: ListSpec): Int? = sources.count(spec)

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
    // not change with the rules, written a page at a time.
    fun copyLive(id: String) {
        viewModelScope.launch {
            val list = liveLists.byId(id) ?: return@launch
            val count = try {
                liveSongs.copy(list) { pages -> store.createFrom(list.name, pages) }
            } catch (e: NotEnoughSpace) {
                feedback.show(spaceWords(e.doing, e.neededBytes))
                return@launch
            }
            feedback.show(if (count == 0) nothingToCopy(list.name) else liveCopyMessage(list.name, count))
        }
    }

    // Downloads the playlist's songs that are only on a server, once.
    fun download(id: String) = offline.downloadPlaylist(id)

    fun export(id: String, uri: Uri) {
        viewModelScope.launch {
            files.export(id, uri)?.let { problem ->
                Log.w("Octo", "playlist export: could not write the file")
                feedback.show(problem)
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
                is PlaylistSheet.Pick -> PlaylistPickerPage(sheet.source, onBack = back, onDone = sheets::close, vm = vm)
                is PlaylistSheet.Create -> PopupNameForm("New playlist", "Playlist name", "Create", onBack = back, onDone = { name ->
                    vm.create(name, sheet.trackIds, sheet.folderId)
                    sheets.close()
                })
                is PlaylistSheet.Options -> GlassMenuPage(header = { GlassMenuHeading(sheet.name) }) {
                    if (sheet.canRename) {
                        GlassMenuAction(OctoIcons.Rename, "Rename", opensPage = true, onClick = { sheets.show(PlaylistSheet.Rename(sheet.id, sheet.name)) })
                    }
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
                    if (sheet.keep) keepPlaylistItem(sheet.id)?.let { GlassMenuSwitch(it) }
                    sheet.placeKey?.let { key ->
                        GlassMenuAction(OctoIcons.PlaylistFolder, PlaylistFolderWords.MOVE_TO_FOLDER, opensPage = true, onClick = {
                            sheets.show(PlaylistSheet.MoveToFolder(FolderMoveTarget.Playlists(listOf(key), sheet.name)))
                        })
                    }
                    GlassMenuAction(OctoIcons.Share, "Export as M3U", onClick = {
                        exporting = sheet.id
                        sheets.close()
                        exportTo.launch(playlistFileName(sheet.name))
                    })
                    if (sheet.canDelete) {
                        GlassMenuAction(
                            OctoIcons.Delete,
                            "Delete",
                            opensPage = true,
                            destructive = true,
                            onClick = { sheets.show(PlaylistSheet.Delete(sheet.id, sheet.name, sheet.onServer, sheet.family)) },
                        )
                    }
                }
                is PlaylistSheet.Rename -> PopupNameForm("Rename playlist", "Playlist name", "Save", onBack = back, initial = sheet.name, onDone = { name ->
                    vm.rename(sheet.id, name)
                    sheets.close()
                })
                is PlaylistSheet.Delete -> ConfirmDelete(sheet.name, sheet.onServer, family = sheet.family, onCancel = back ?: sheets::close) {
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
                is PlaylistSheet.NewFolder -> PopupNameForm(
                    PlaylistFolderWords.NEW_FOLDER,
                    PlaylistFolderWords.FOLDER_NAME,
                    "Create",
                    onBack = back,
                    ready = { PlaylistFolderTree.cleanName(it) != null },
                    onDone = { name ->
                        vm.newFolder(name, sheet.parent, sheet.placing, sheet.moving)
                        sheets.close()
                    },
                )
                is PlaylistSheet.RenameFolder -> PopupNameForm(
                    PlaylistFolderWords.RENAME_FOLDER,
                    PlaylistFolderWords.FOLDER_NAME,
                    "Save",
                    onBack = back,
                    initial = sheet.name,
                    ready = { PlaylistFolderTree.cleanName(it) != null },
                    onDone = { name ->
                        vm.renameFolder(sheet.id, name)
                        sheets.close()
                    },
                )
                is PlaylistSheet.DeleteFolder -> {
                    val folders by vm.folderView.collectAsStateWithLifecycle()
                    val parentName = folders.tree.parentOf(sheet.id)?.let { folders.tree.folder(it)?.name }
                    ConfirmDeleteFolder(sheet.name, parentName, onCancel = back ?: sheets::close) {
                        vm.deleteFolder(sheet.id)
                        sheets.close()
                    }
                }
                is PlaylistSheet.MoveToFolder -> MoveToFolderPage(sheet.target, onBack = back, onDone = sheets::close, vm = vm)
                is PlaylistSheet.AddPlaylists -> AddPlaylistsPage(sheet.folderId, onBack = back, onDone = sheets::close, vm = vm)
                is PlaylistSheet.FolderOptions -> GlassMenuPage(header = { GlassMenuHeading(sheet.name) }) {
                    GlassMenuAction(OctoIcons.Rename, PlaylistFolderWords.RENAME, opensPage = true, onClick = {
                        sheets.show(PlaylistSheet.RenameFolder(sheet.id, sheet.name))
                    })
                    GlassMenuAction(OctoIcons.PlaylistFolder, PlaylistFolderWords.MOVE_TO_FOLDER, opensPage = true, onClick = {
                        sheets.show(PlaylistSheet.MoveToFolder(FolderMoveTarget.Folder(sheet.id, sheet.name)))
                    })
                    GlassMenuAction(OctoIcons.Delete, PlaylistFolderWords.DELETE_FOLDER, opensPage = true, destructive = true, onClick = {
                        sheets.show(PlaylistSheet.DeleteFolder(sheet.id, sheet.name))
                    })
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
) = PlaylistPickerPage(AddSource.Ids(trackIds), onBack, onDone, vm, start)

// The same for songs named by what they are, an album or a playlist say:
// the title counts them, and a tapped playlist is checked against them a
// chunk at a time, saying "Checking the playlist" meanwhile.
@Composable
fun PlaylistPickerPage(
    source: AddSource,
    onBack: (() -> Unit)?,
    onDone: () -> Unit,
    vm: PlaylistSheetsViewModel = hiltViewModel(),
    start: String? = null,
) {
    val all by vm.playlists.collectAsStateWithLifecycle()
    val recent by vm.recentIds.collectAsStateWithLifecycle()
    val folders by vm.folderView.collectAsStateWithLifecycle()
    // Songs found online among what the add brings: the upkeep lists that
    // refuse them are left out.
    val forFind by produceState(vm.findsOpen && (source as? AddSource.Ids)?.ids?.any(::isFind) == true, source) { value = vm.holdsFind(source) }
    // A playlist the listener may only listen to, or an imported playlist's copy, takes no songs.
    val offered = remember(all, forFind) { all.filter { it.takesSongs && !(forFind && it.refusesFinds) } }
    val groups = remember(offered, recent, folders, forFind) {
        PickerGroups.build(
            offered,
            id = { it.id },
            placeKey = { it.placeKey },
            name = { it.name },
            upkeep = { it.upkeep },
            refusesFind = { it.refusesFinds },
            recentIds = recent,
            tree = folders.shown,
            forFind = forFind,
        )
    }
    var asking by remember { mutableStateOf<Triple<PlaylistSummary, AddCounts, AddPlan?>?>(null) }
    var naming by remember { mutableStateOf(false) }
    // A playlist being checked, and the `start` playlist once looked at.
    var checking by remember { mutableStateOf<PlaylistSummary?>(null) }
    var started by remember { mutableStateOf(start == null) }
    val scope = rememberCoroutineScope()
    val add = { playlist: PlaylistSummary, newOnly: Boolean, plan: AddPlan? ->
        vm.add(playlist, source, newOnly, plan)
        onDone()
    }
    val tap = { playlist: PlaylistSummary ->
        checking = playlist
        scope.launch {
            val (counts, plan) = vm.check(playlist, source)
            checking = null
            if (counts.asks) asking = Triple(playlist, counts, plan) else add(playlist, false, plan)
        }
        Unit
    }
    LaunchedEffect(start, offered) {
        if (started) return@LaunchedEffect
        val playlist = offered.firstOrNull { it.id == start } ?: return@LaunchedEffect
        started = true
        tap(playlist)
    }
    asking?.let { (playlist, counts, plan) ->
        ConfirmAgain(
            addAgainQuestion(playlist.name, counts),
            counts,
            onCancel = { asking = null },
            onAddAll = { add(playlist, false, plan) },
            onAddNew = { add(playlist, true, plan) },
        )
        return
    }
    if (!started || checking != null) {
        GlassMenuPage { GlassMenuNote("Checking the playlist") }
        return
    }
    val size by produceState<Int?>((source as? AddSource.Ids)?.ids?.distinct()?.size, source) {
        if (source is AddSource.Spec) value = vm.countOf(source.spec)
    }
    val title = when (val n = size) {
        1 -> "Add to playlist"
        null -> "Add to a playlist"
        else -> "Add ${songs(n)} to a playlist"
    }
    Column(Modifier.width(MenuWidth).padding(6.dp)) {
        if (onBack != null) GlassMenuBack(title, onBack) else GlassMenuHeading(title)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            item(key = "new") {
                if (naming) {
                    PopupNameField(
                        "Playlist name",
                        "Create",
                        onDone = { name ->
                            vm.create(name, source)
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                } else {
                    GlassMenuAction(OctoIcons.AddToLibrary, "New playlist", onClick = { naming = true })
                }
            }
            groups.forEachIndexed { index, group ->
                item(key = "head:${group.kind}:${group.title.orEmpty()}") {
                    val title = group.title
                    when {
                        title != null -> GlassMenuTitle(title)
                        index > 0 -> GlassMenuSeparator()
                    }
                }
                items(group.items, key = { "${group.kind}:${group.title.orEmpty()}:${it.id}" }) { playlist ->
                    PickLine(playlist) { tap(playlist) }
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
                Text(if (playlist.family) "By Family · ${songs(playlist.songCount)}" else songs(playlist.songCount), style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1)
                if (playlist.onServer) CloudMark("On your server")
            }
        }
    }
}

// Asks before songs go on a playlist that has them already. With some new
// ones among them, the choice is all of them or only the new ones.
@Composable
private fun ConfirmAgain(question: String, counts: AddCounts, onCancel: () -> Unit, onAddAll: () -> Unit, onAddNew: () -> Unit) {
    if (counts.offersNewOnly) {
        Column(Modifier.width(MenuWidth).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)) {
            Text(question, style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentButton(ADD_NEW_ONES, onClick = onAddNew)
                GlazeButton(addAgainChoice(counts), onClick = onAddAll)
            }
        }
    } else {
        PopupQuestion(question, null, addAgainChoice(counts), onConfirm = onAddAll, onCancel = onCancel)
    }
}

// The new playlist name form on its own, for a playlist made from songs
// chosen somewhere else, such as the queue. `onCreate` gets the name.
@Composable
fun NewPlaylistForm(onCreate: (String) -> Unit) = PopupNameForm("New playlist", "Playlist name", "Create", onBack = null, onDone = onCreate)

// Asks before a playlist is deleted. Its songs stay in the library. One kept
// with the server goes from the server too; a family playlist goes for the
// whole family.
@Composable
internal fun ConfirmDelete(name: String, onServer: Boolean, family: Boolean = false, onCancel: () -> Unit, onDelete: () -> Unit) {
    PopupQuestion(
        "Delete \"$name\"?",
        when {
            family -> "It's deleted for everyone in the family. The songs stay in your library."
            onServer -> "It is deleted from your server too. The songs stay in your library."
            else -> "The songs stay in your library."
        },
        "Delete",
        onConfirm = onDelete,
        onCancel = onCancel,
    )
}
