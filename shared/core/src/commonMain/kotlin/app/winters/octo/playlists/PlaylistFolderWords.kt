package app.winters.octo.playlists

import java.util.Locale

// The words playlist folders, the upkeep group and playlists holding songs
// found online show, the same on every app.
object PlaylistFolderWords {
    const val PLAYLIST_FOLDER = "Playlist folder"
    const val FOLDER = "Folder"
    const val NEW_FOLDER = "New folder"
    const val FOLDER_NAME = "Folder name"
    const val MOVE_TO_FOLDER = "Move to folder"
    const val NOT_IN_A_FOLDER = "Not in a folder"
    const val ADD_PLAYLISTS = "Add playlists"
    const val RENAME = "Rename"
    const val RENAME_FOLDER = "Rename folder"
    const val DELETE_FOLDER = "Delete folder"
    const val CANCEL = "Cancel"
    const val NEW_PLAYLIST_HERE = "New playlist here"
    const val NEW_FOLDER_HERE = "New folder here"
    const val NOTHING_HERE_YET = "Nothing here yet"
    const val EMPTY_FOLDER_NOTE = "Use Move to folder on any playlist, or Add playlists."
    const val INTO_ITSELF = "A folder can't go inside itself."
    const val TOO_DEEP = "Folders can go 4 deep."
    const val LIBRARY_UPKEEP = "Library upkeep"
    const val SKIPPED_SONGS = "Some songs couldn't be added. Search for them again."
    const val SAVED_FOR_FINDS = "Saved to your server so its songs found online keep playing."
    const val FOLDER_CHANGE_FAILED = "A folder change couldn't be saved to your server."

    fun moveHere(count: Int): String = "Move here ($count)"

    fun deleteTitle(name: String): String = "Delete \"$name\"?"

    // Where a deleted folder's playlists go: its parent, or the top level.
    fun deleteBody(parentName: String?): String = "Its playlists move to ${parentName ?: "the top level"}. No playlist is deleted."

    fun fromThisPhone(name: String): String = "$name (from this phone)"

    fun bothChanged(name: String): String =
        "This playlist changed on your server and on this phone. Your phone's version was saved as \"${fromThisPhone(name)}\"."

    // The upkeep group's note while it is closed: each notice list holding
    // songs, "Review has 3 songs, Duplicates has 1 song"; null when none does.
    fun upkeepNote(notices: List<Pair<String, Int>>): String? =
        notices.filter { it.second > 0 }.takeIf { it.isNotEmpty() }?.joinToString(", ") { (name, n) -> "$name has ${counted(n, "song")}" }

    // A folder row's line: "3 playlists", "1 playlist, 2 folders".
    fun folderSubtitle(playlists: Int, folders: Int): String =
        counted(playlists, "playlist") + if (folders > 0) ", " + counted(folders, "folder") else ""

    private fun counted(n: Int, one: String): String = if (n == 1) "1 $one" else "%,d %ss".format(Locale.ENGLISH, n, one)
}
