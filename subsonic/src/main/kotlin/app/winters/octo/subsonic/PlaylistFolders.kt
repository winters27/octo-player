package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The extension a server lists when a playlist of the listener's own can
// hold songs found online: adds take outside ids, replaces take
// octoIfStamp and octoOwnIds, removals go by song (octoSongToRemove) on
// every owned playlist, and answers name the ids it skipped (octoSkipped).
const val PLAYLIST_SONGS_EXTENSION = "octoPlaylistSongs"

// The extension a server lists when it keeps the listener's playlist folders.
const val PLAYLIST_FOLDERS_EXTENSION = "octoPlaylistFolders"

// What one of Octo's library upkeep lists is for, as a server listing
// octoPlaylistSongs names it beside octoUpkeep (octoUpkeepKind): a library
// action list, a notice list (Review, Duplicates), or a family member's own
// request and remove lists.
const val UPKEEP_KIND_ACTION = "action"
const val UPKEEP_KIND_NOTICE = "notice"
const val UPKEEP_KIND_REQUEST = "request"

// Whether an upkeep list refuses songs found online: a library action list
// or a notice list. A family member's own request and remove lists take
// them (an add becomes a request). An upkeep list without a kind counts as
// an action list.
fun upkeepRefusesFinds(upkeep: Boolean, kind: String?): Boolean =
    upkeep && (kind ?: UPKEEP_KIND_ACTION).let { it == UPKEEP_KIND_ACTION || it == UPKEEP_KIND_NOTICE }

// One playlist folder: its id (made by the app that made it: "pf" and 20
// base62 characters), its name, the folder it sits in (null at the top),
// and when it was made.
@Serializable
data class PlaylistFolder(val id: String, val name: String, val parent: String? = null, val created: String = "")

// Which folder a playlist sits in, by the playlist's server id.
@Serializable
data class PlaylistPlace(val playlist: String, val folder: String)

// The listener's folders and where their playlists sit, as the server
// keeps them, and when they last changed.
data class PlaylistFolders(
    val changed: String = "",
    val folders: List<PlaylistFolder> = emptyList(),
    val places: List<PlaylistPlace> = emptyList(),
)

// The answer's own shape: one array of folders and one of places.
@Serializable
internal data class PlaylistFoldersAnswer(
    val changed: String = "",
    val folder: List<PlaylistFolder> = emptyList(),
    val place: List<PlaylistPlace> = emptyList(),
) {
    fun read() = PlaylistFolders(changed, folder, place)
}

private const val FOLDERS_KEY = "playlistFolders"

// The listener's folders. Every change answers the same, as it stands after.
suspend fun SubsonicClient.playlistFolders(): PlaylistFolders =
    getWith("getPlaylistFolders", emptyList(), FOLDERS_KEY, PlaylistFoldersAnswer.serializer()).read()

// Makes a folder, or renames or moves one; sent again it changes nothing
// more. A folder deleted before is refused (NotFound).
suspend fun SubsonicClient.savePlaylistFolder(id: String, name: String, parentId: String?): PlaylistFolders =
    getWith("savePlaylistFolder", saveFolderParams(id, name, parentId), FOLDERS_KEY, PlaylistFoldersAnswer.serializer()).read()

// Deletes a folder: its folders and playlists move to its parent.
suspend fun SubsonicClient.deletePlaylistFolder(id: String): PlaylistFolders =
    getWith("deletePlaylistFolder", listOf("id" to id), FOLDERS_KEY, PlaylistFoldersAnswer.serializer()).read()

// Puts playlists in a folder, or with no folder at the top level.
suspend fun SubsonicClient.setPlaylistFolder(playlistIds: List<String>, folderId: String?): PlaylistFolders =
    getWith("setPlaylistFolder", placeFolderParams(playlistIds, folderId), FOLDERS_KEY, PlaylistFoldersAnswer.serializer()).read()

internal fun saveFolderParams(id: String, name: String, parentId: String?): List<Pair<String, String>> =
    listOf("id" to id, "name" to name) + listOfNotNull(parentId?.let { "parentId" to it })

internal fun placeFolderParams(playlistIds: List<String>, folderId: String?): List<Pair<String, String>> =
    playlistIds.map { "playlistId" to it } + listOfNotNull(folderId?.let { "folderId" to it })
