package app.winters.octo.desktop

import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.subsonic.PlaylistFolders
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.deletePlaylistFolder
import app.winters.octo.subsonic.savePlaylistFolder
import app.winters.octo.subsonic.setPlaylistFolder
import kotlinx.coroutines.launch

// The listener's playlist folders, kept by the server in use (one listing
// octoPlaylistFolders) and read with the playlists. Every change is one call
// whose answer is the folders as they stand after it; a refused one says why
// on the notice line and changes nothing here. Folder ids are made here.

// A new folder in `parent` (null: the top level). `then` hears its id once
// the server has it; the folder it went in opens.
fun AppState.createFolder(name: String, parent: String?, then: (String) -> Unit = {}) {
    val clean = PlaylistFolderTree.cleanName(name) ?: return
    if (!folderTree.canHoldFolder(parent)) {
        notice = PlaylistFolderWords.TOO_DEEP
        return
    }
    val id = PlaylistFolderTree.newId()
    folderChange("Couldn't make the folder", { it.savePlaylistFolder(id, clean, parent) }) {
        if (parent != null) openFolder(parent)
        then(id)
    }
}

fun AppState.renameFolder(id: String, name: String) {
    val clean = PlaylistFolderTree.cleanName(name) ?: return
    val folder = folderTree.folder(id) ?: return
    if (clean == folder.name) return
    folderChange("Couldn't rename the folder", { it.savePlaylistFolder(id, clean, folder.parent) })
}

// Deletes a folder: its folders and playlists move to its parent, or to the
// top level. No playlist is deleted.
fun AppState.deleteFolder(id: String) {
    folderTree.folder(id) ?: return
    folderChange("Couldn't delete the folder", { it.deletePlaylistFolder(id) }) {
        updateFrame { it.copy(openFolders = it.openFolders - id) }
    }
}

// Puts playlists in a folder, or with none at the top level; the folder
// opens so they show where they went. Octo's own lists are never filed.
fun AppState.placePlaylists(ids: List<String>, folderId: String?) {
    val filed = ids.distinct().filter(PlaylistFolderTree::fileable)
    if (filed.isEmpty() || filed.all { folderTree.folderOf(it) == folderId }) return
    folderChange("Couldn't move the playlists", { it.setPlaylistFolder(filed, folderId) }) {
        if (folderId != null) openFolder(folderId)
    }
}

// Opens a folder in the sidebar, with every folder above it.
fun AppState.openFolder(id: String) {
    val path = folderTree.path(id).map { it.id }
    if (path.isEmpty()) return
    updateFrame { it.copy(openFolders = it.openFolders + path) }
}

// Sends one folder change and takes the folders it answers, unless the
// server in use changed meanwhile.
private fun AppState.folderChange(failure: String, call: suspend (SubsonicClient) -> PlaylistFolders, then: () -> Unit = {}) {
    val client = connection?.client ?: return
    scope.launch {
        try {
            val answer = call(client)
            if (connection?.client !== client) return@launch
            takeFolders(answer)
            then()
        } catch (e: SubsonicException) {
            notice = "$failure: ${playlistFailure(e)}"
        }
    }
}
