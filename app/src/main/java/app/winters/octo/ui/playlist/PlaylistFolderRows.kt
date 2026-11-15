package app.winters.octo.ui.playlist

import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.subsonic.upkeepRefusesFinds

// Whether playlist folders are offered, and the folders when they are.
data class FolderView(val on: Boolean, val tree: PlaylistFolderTree) {
    val shown: PlaylistFolderTree? get() = tree.takeIf { on }

    companion object {
        val Off = FolderView(false, PlaylistFolderTree.Empty)
    }
}

// A folder row: the folder, and its line ("3 playlists, 1 folder").
data class FolderLine(val folder: PlaylistFolder, val subtitle: String)

// What a level of the playlists shows: its folders A to Z, its playlists in
// the listener's order, and at the top level the library upkeep group with
// the note it shows while closed.
data class FolderPageRows(
    val folders: List<FolderLine>,
    val playlists: List<PlaylistSummary>,
    val upkeep: List<PlaylistSummary>,
    val upkeepNote: String?,
) {
    val empty: Boolean get() = folders.isEmpty() && playlists.isEmpty()
}

// A playlist's placement key: its server id once it is there, else its row's.
val PlaylistSummary.placeKey: String get() = serverId ?: PlaylistFolderTree.localKey(id)

// Whether it can sit in a folder: not an upkeep list (they keep to their
// own group) and not one of Octo's generated lists.
val PlaylistSummary.fileable: Boolean get() = !upkeep && PlaylistFolderTree.fileable(placeKey)

// Whether songs can be added to it here: not an imported playlist's copy,
// not a family playlist the listener may only hear.
val PlaylistSummary.takesSongs: Boolean get() = !octoImport && !(family && !familyMayAdd)

// An upkeep list that refuses songs found online: a library action list or
// a notice list, by the kind the server names (an upkeep list without one
// counts as an action list). The family's request lists take them (they
// turn an add into a request).
val PlaylistSummary.refusesFinds: Boolean get() = upkeepRefusesFinds(upkeep, upkeepKind)

// Whether it can be renamed or deleted here: never an imported playlist's
// copy (the server refuses both); a family playlist as its rights say.
val PlaylistSummary.renames: Boolean get() = !octoImport && (!family || familyMayRename)
val PlaylistSummary.deletes: Boolean get() = !octoImport && (!family || familyMayDelete)

val PlaylistEntity.renamesHere: Boolean get() = !octoImport && (!family || familyMayRename)
val PlaylistEntity.deletesHere: Boolean get() = !octoImport && (!family || familyMayDelete)

// A playlist's placement key from its row.
val PlaylistEntity.placeKey: String get() = serverId ?: PlaylistFolderTree.localKey(id)

// One level of the playlists (`folderId` null: the top): `sorted` already
// in the listener's order. Without folders the top level holds every
// playlist. Upkeep lists show only in their group at the top, never inside
// a folder.
fun folderPageRows(sorted: List<PlaylistSummary>, tree: PlaylistFolderTree?, folderId: String?): FolderPageRows {
    val (upkeep, own) = sorted.partition { it.upkeep }
    val top = folderId == null
    val group = if (top) upkeep else emptyList()
    val note = if (top) upkeepNoteOf(upkeep) else null
    if (tree == null) return FolderPageRows(emptyList(), if (top) own else emptyList(), group, note)
    val keys = own.map { it.placeKey }
    val folders = tree.children(folderId).map { folder ->
        FolderLine(folder, PlaylistFolderWords.folderSubtitle(tree.playlistsIn(folder.id, keys).size, tree.children(folder.id).size))
    }
    return FolderPageRows(folders, own.filter { tree.folderOf(it.placeKey) == folderId }, group, note)
}

// The upkeep group's note while closed: each notice list holding songs.
fun upkeepNoteOf(upkeep: List<PlaylistSummary>): String? =
    PlaylistFolderWords.upkeepNote(upkeep.filter { it.octoNotice != null }.map { it.name to it.songCount })
