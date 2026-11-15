package app.winters.octo.playback

import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.ui.playlist.placeKey

// One row of a car's playlist folder, or of the Playlists tab's own part:
// a folder (with its line), or a playlist.
sealed interface CarFolderChild {
    data class Folder(val folder: PlaylistFolder, val subtitle: String) : CarFolderChild

    data class Playlist(val playlist: PlaylistSummary) : CarFolderChild
}

// The playlists a car shows: never Octo's library upkeep lists, by the
// server's mark or, from an older server, by the action lists' leading 🛠.
fun carPlaylists(all: List<PlaylistSummary>): List<PlaylistSummary> = all.filterNot { it.upkeep || isToolPlaylist(it.name) }

// What a level holds in a car (`parent` null: the Playlists tab's own
// part), in the phone's order: its folders A to Z, then its playlists in
// `sorted`'s order. With no folders, the top level holds every playlist.
fun carFolderChildren(tree: PlaylistFolderTree?, parent: String?, sorted: List<PlaylistSummary>): List<CarFolderChild> {
    if (tree == null) return if (parent == null) sorted.map { CarFolderChild.Playlist(it) } else emptyList()
    val keys = sorted.map { it.placeKey }
    val folders = tree.children(parent).map { folder ->
        CarFolderChild.Folder(folder, PlaylistFolderWords.folderSubtitle(tree.playlistsIn(folder.id, keys).size, tree.children(folder.id).size))
    }
    return folders + sorted.filter { tree.folderOf(it.placeKey) == parent }.map { CarFolderChild.Playlist(it) }
}
