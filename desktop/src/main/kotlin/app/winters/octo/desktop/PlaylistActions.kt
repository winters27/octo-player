package app.winters.octo.desktop

import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.playlists.PlaylistMove
import app.winters.octo.desktop.playlists.SongMove
import app.winters.octo.desktop.playlists.importPlaylist
import app.winters.octo.desktop.playlists.reapplied
import app.winters.octo.desktop.playlists.refusesFinds
import app.winters.octo.desktop.playlists.songMoveOf
import app.winters.octo.desktop.playlists.songsFor
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.desktop.playlists.m3uLinesOf
import app.winters.octo.desktop.playlists.missedDetail
import app.winters.octo.desktop.playlists.movedBy
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.desktop.system.JumpKind
import app.winters.octo.desktop.system.JumpTarget
import app.winters.octo.playlists.PLAYLIST_FILE_LIMIT
import app.winters.octo.playlists.playlistText
import app.winters.octo.playlists.writeM3u
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.PlaylistWithSongs
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.playlist.AddPlan
import app.winters.octo.ui.playlist.addAgainQuestion
import app.winters.octo.ui.playlist.addedMessage
import app.winters.octo.ui.playlist.copiedMessage
import app.winters.octo.ui.playlist.importSummary
import app.winters.octo.ui.playlist.planAdd
import app.winters.octo.ui.playlist.playlistCopyName
import app.winters.octo.ui.playlist.recentPlaylists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// What the listener does with playlists: adding songs (asking first about
// songs already there), moving them, renaming, copying, deleting, and
// playlist files in and out. Edits show at once on the playlist's page
// and go back, with one line saying why, when the server refuses them.

// A playlist as its page shows it, with the server it came from, so a
// playlist of the same id on another server is never shown for it.
class PlaylistView(val client: SubsonicClient, val playlist: PlaylistWithSongs)

// The playlist as last read or changed here, if it came from this server.
fun AppState.playlistView(id: String): PlaylistWithSongs? =
    playlistViews[id]?.takeIf { it.client === connection?.client }?.playlist

// Reads a playlist for its page. Null once read, or why it could not be
// (PLAYLIST_GONE for one the server no longer has, which leaves the
// sidebar). A read that lands while an edit is on its way is not shown.
suspend fun AppState.loadPlaylist(id: String): String? {
    val client = connection?.client ?: return null
    return try {
        val read = client.playlist(id)
        if ((playlistSaves[id] ?: 0) == 0) playlistViews[id] = PlaylistView(client, read)
        null
    } catch (e: SubsonicException.NotFound) {
        playlistGone(id)
        PLAYLIST_GONE
    } catch (e: SubsonicException) {
        e.userMessage()
    }
}

// Why a playlist's page closed by itself.
const val PLAYLIST_GONE = "That playlist isn't on the server any more, so it's gone from your list."

// Why a family playlist's songs were not moved.
const val FAMILY_KEEPS_ORDER = "A family playlist keeps the order songs were added in."

// Why songs were not taken out of a family playlist: the server showed
// fewer songs than it holds, so places in the list could point at others.
const val FAMILY_MISCOUNT = "Octo couldn't read every song in this family playlist just now, so nothing was taken out. Try again in a moment."

// Why songs were not taken out of a family playlist: it changed since its
// page read it, so places on the page could point at other songs.
const val FAMILY_CHANGED = "This family playlist changed since it was shown, so nothing was taken out. It's up to date now; try again."

// A refused playlist change in the server's own words when it says why
// (error 50, not allowed, or a playlist that changed since it was read),
// otherwise the usual line.
internal fun playlistFailure(e: SubsonicException): String {
    val words = e.message
    return if (e is SubsonicException.Server && (e.code == 50 || e.conflict) && !words.isNullOrBlank()) words else e.userMessage()
}

// A playlist the server says it does not have (deleted on another device,
// or one of Octo's own mixes or stations it has since made anew): out of
// the sidebar, its pin, its page and the jump list at once, and the list
// read again.
fun AppState.playlistGone(id: String) {
    dropPlaylist(id)
    if (isPinned(id)) setPinned(id, false)
    playlistViews.remove(id)
    jumpList?.forget(JumpTarget(JumpKind.Playlist, id, ""))
    refreshPlaylists()
}

// The playlist as a list of playlists has it, for its menus: the sidebar's
// entry, or one made from the page's own copy.
fun PlaylistWithSongs.asPlaylist(): Playlist =
    Playlist(id, name, comment, owner, public, songCount = entry.size, duration = entry.sumOf { it.duration }, coverArt = coverArt, changed = changed, readonly = readonly, octoList = octoList, octoNotice = octoNotice, octoCoverTitle = octoCoverTitle, octoFamily = octoFamily, octoFamilyCreatedBy = octoFamilyCreatedBy, octoFamilyMayRename = octoFamilyMayRename, octoFamilyMayDelete = octoFamilyMayDelete, octoUpkeep = octoUpkeep, octoImport = octoImport, octoUpkeepKind = octoUpkeepKind)

// Why a reorder was not saved: another device changed the playlist since
// its page read it, and a moved song is no longer in it.
const val PLAYLIST_CHANGED_TRY_AGAIN = "The playlist changed. Try again."

// Shows a change to a playlist's page at once, sends it, and puts the page
// back with a line saying why when the server refuses it. The sidebar is
// read again once the server has it.
private fun AppState.editPlaylist(
    id: String,
    failure: String,
    change: (PlaylistWithSongs) -> PlaylistWithSongs,
    send: suspend (SubsonicClient) -> Unit,
) {
    val client = connection?.client ?: return
    val before = playlistViews[id]?.takeIf { it.client === client }
    val shown = before?.let { PlaylistView(client, change(it.playlist)) }
    if (shown != null) playlistViews[id] = shown
    playlistSaves[id] = (playlistSaves[id] ?: 0) + 1
    scope.launch {
        try {
            send(client)
            refreshPlaylists()
        } catch (e: SubsonicException) {
            // Unless a later edit has already replaced this one.
            if (before != null && playlistViews[id] === shown) playlistViews[id] = before
            notice = "$failure: ${playlistFailure(e)}"
        } finally {
            val left = (playlistSaves[id] ?: 1) - 1
            if (left > 0) playlistSaves[id] = left else playlistSaves.remove(id)
        }
    }
}

// Moving songs.

// Makes a playlist's songs exactly these, in this order, as a drag that
// ends or a Move row does: shown at once, then saved in one replace. On a
// server listing octoPlaylistSongs the replace carries the changed time the
// page read (octoIfStamp) and octoOwnIds; when the playlist changed since,
// it is read again and `move` (the same move by song ids) is made over the
// new list, or the line says to try again when a moved song is gone.
fun AppState.reorderPlaylist(id: String, songs: List<Song>, move: SongMove? = null) {
    val family = playlists.firstOrNull { it.id == id }?.let { access(it).family }
        ?: playlistView(id)?.asPlaylist()?.let { access(it).family }
    if (family == true) {
        notice = FAMILY_KEEPS_ORDER
        return
    }
    val formPost = formPost
    val stamped = playlistSongs
    val stamp = playlistView(id)?.changed
    editPlaylist(id, "Couldn't move the songs", { it.copy(entry = songs) }) { client ->
        if (!stamped) {
            client.replacePlaylistSongs(id, songs.map { it.id }, formPost)
            return@editPlaylist
        }
        try {
            client.replacePlaylistSongs(id, songs.map { it.id }, formPost, ifStamp = stamp, ownIds = true)
        } catch (e: SubsonicException.Server) {
            if (!e.conflict) throw e
            val fresh = client.playlist(id)
            val order = move?.let { reapplied(it, fresh.entry.map(Song::id)) }
            if (order == null) {
                // The page shows the list as it is now.
                playlistViews[id] = PlaylistView(client, fresh)
                throw SubsonicException.Server(0, PLAYLIST_CHANGED_TRY_AGAIN, conflict = true)
            }
            client.replacePlaylistSongs(id, order.map { fresh.entry[it].id }, formPost, ifStamp = fresh.changed, ownIds = true)
        }
    }
}

// Moves the songs at these places (0-based, in the playlist's own order)
// up, down, to the top or to the bottom.
fun AppState.moveInPlaylist(id: String, positions: List<Int>, move: PlaylistMove) {
    val songs = playlistView(id)?.entry
    if (songs != null) {
        movedIn(id, songs, positions, move)
        return
    }
    scope.launch {
        val read = try {
            playlistSongs(id)
        } catch (e: SubsonicException) {
            notice = "Couldn't move the songs: ${e.userMessage()}"
            return@launch
        }
        movedIn(id, read, positions, move)
    }
}

private fun AppState.movedIn(id: String, songs: List<Song>, positions: List<Int>, move: PlaylistMove) {
    val order = movedBy(move, songs.size, positions)
    if (order == songs.indices.toList()) return
    // Where the picked songs landed, as the place of the first one after them.
    val places = positions.filter { it in songs.indices }.toSet()
    val landed = order.indexOfFirst { it in places }
    val to = order.drop(landed).firstOrNull { it !in places } ?: songs.size
    reorderPlaylist(id, order.map(songs::get), songMoveOf(songs.map(Song::id), places, to))
}

// A playlist's details.

fun AppState.renamePlaylist(id: String, name: String) {
    val clean = name.trim()
    if (clean.isEmpty()) return
    editPlaylist(id, "Couldn't rename the playlist", { it.copy(name = clean) }) { client ->
        client.updatePlaylist(id, name = clean, formPost = formPost)
    }
}

// The playlist's description; an empty one clears it.
fun AppState.setPlaylistComment(id: String, comment: String) {
    val clean = comment.trim()
    editPlaylist(id, "Couldn't change the description", { it.copy(comment = clean) }) { client ->
        client.updatePlaylist(id, comment = clean, formPost = formPost)
    }
}

// Public playlists are open to everyone else on the server.
fun AppState.setPlaylistPublic(id: String, public: Boolean) {
    editPlaylist(id, "Couldn't change who can see the playlist", { it.copy(public = public) }) { client ->
        client.updatePlaylist(id, public = public, formPost = formPost)
    }
}

// Copying and deleting.

// A new playlist of the listener's own with the same songs in the same order.
fun AppState.duplicatePlaylist(playlist: Playlist) {
    connection?.client ?: return
    scope.launch {
        val songs = try {
            playlistSongs(playlist.id)
        } catch (e: SubsonicException) {
            notice = "Couldn't copy the playlist: ${e.userMessage()}"
            return@launch
        }
        val name = playlistCopyName(playlist.name)
        createPlaylist(name, songs) { id -> if (id != null) notice = copiedMessage(name) }
    }
}

// Deletes one of the listener's playlists from the server. Its page, if
// open, goes back (or Home), and it leaves the pins and the recent list.
fun AppState.deletePlaylist(playlist: Playlist) {
    val client = connection?.client ?: return
    scope.launch {
        try {
            client.deletePlaylist(playlist.id)
        } catch (e: SubsonicException) {
            notice = "Couldn't delete the playlist: ${playlistFailure(e)}"
            return@launch
        }
        if (isPinned(playlist.id)) setPinned(playlist.id, false)
        settings.update { it.copy(recentPlaylists = it.recentPlaylists - playlist.id) }
        playlistViews.remove(playlist.id)
        if ((navigator.current.page as? Page.Playlist)?.id == playlist.id && !navigator.back()) navigator.go(Page.Home)
        refreshPlaylists()
    }
}

// Adding songs.

// Songs picked to go on a playlist, some of which are on it already. Nothing
// is added until the listener picks: all of them (`all`), only the ones not
// there yet (`fresh`), or none.
class AddQuestion(val playlist: Playlist, val plan: AddPlan, songs: List<Song>) {
    private val byId = songs.associateBy { it.id }
    val words: String get() = addAgainQuestion(playlist.name, plan)
    val all: List<Song> get() = plan.songs.mapNotNull(byId::get)
    val fresh: List<Song> get() = plan.fresh.mapNotNull(byId::get)
}

// The playlists the listener can add to that they added songs to lately,
// newest first.
fun AppState.lastPlaylists(): List<Playlist> {
    val own = playlists.filter { access(it).add }.associateBy { it.id }
    return settings.current.recentPlaylists.mapNotNull(own::get)
}

private fun AppState.usedPlaylist(id: String) =
    settings.update { it.copy(recentPlaylists = recentPlaylists(it.recentPlaylists, id)) }

// Checks which songs are on the playlist already. With none there, they
// go on at once and the answer is null; otherwise nothing is added and the
// question comes back to be asked. The playlist is read afresh, or taken
// from its page when the server cannot say.
// Songs found online go only where they can be kept (songsFor): a server
// listing octoPlaylistSongs, and never one of Octo's library upkeep lists
// that refuses them.
suspend fun AppState.checkAdd(playlist: Playlist, songs: List<Song>): AddQuestion? {
    val client = connection?.client ?: return null
    if (songs.isEmpty()) return null
    val access = access(playlist)
    if (!access.add) {
        notice = if (access.family) "Your plan lets you listen to family playlists, not change them." else "Only its owner can change ${playlist.name}"
        return null
    }
    val taken = songsFor(playlist, songs, playlistSongs)
    if (taken.isEmpty()) {
        if (refusesFinds(playlist)) notice = UPKEEP_REFUSES_FINDS
        return null
    }
    val onIt = try {
        client.playlist(playlist.id).entry
    } catch (e: SubsonicException) {
        playlistView(playlist.id)?.entry.orEmpty()
    }.mapTo(HashSet()) { it.id }
    val question = AddQuestion(playlist, planAdd(taken.map { it.id }, onIt), taken)
    if (question.plan.asks) return question
    addSongsToPlaylist(playlist, question.all)
    return null
}

// Why songs found online did not go in one of Octo's library upkeep lists.
const val UPKEEP_REFUSES_FINDS = "Songs found online can't go in Octo's library upkeep playlists."

// Adds songs to a playlist the way a drop or a menu does: `ask` is called
// only when some are on it already, with the question to show where the
// songs were dropped (AddAgainMenu). Answering it is addSongsToPlaylist
// with question.all or question.fresh.
fun AppState.addToPlaylistChecked(playlist: Playlist, songs: List<Song>, ask: (AddQuestion) -> Unit) {
    scope.launch { checkAdd(playlist, songs)?.let(ask) }
}

// Puts songs on the end of a playlist as they are, with one quiet line
// saying so, and remembers the playlist for "Add to last playlist".
fun AppState.addSongsToPlaylist(playlist: Playlist, songs: List<Song>) {
    val client = connection?.client ?: return
    val taken = songsFor(playlist, songs, playlistSongs)
    if (taken.isEmpty()) return
    val formPost = formPost
    usedPlaylist(playlist.id)
    scope.launch {
        try {
            val skipped = ArrayList<String>()
            client.updatePlaylist(playlist.id, songIdsToAdd = taken.map { it.id }, formPost = formPost, skipped = { skipped += it })
            // Songs the server could not keep (octoSkipped) are named once.
            notice = if (skipped.isEmpty()) addedMessage(playlist.name, taken.size) else PlaylistFolderWords.SKIPPED_SONGS
            refreshPlaylists()
        } catch (e: SubsonicException) {
            notice = "Couldn't add to the playlist: ${playlistFailure(e)}"
        }
    }
}

// A new playlist with these songs in it, which says where they went, as
// the phone does, and is remembered as the last one used.
fun AppState.newPlaylistWith(name: String, songs: List<Song>, then: (String?) -> Unit = {}) {
    createPlaylist(name, songs) { id ->
        if (id != null) {
            usedPlaylist(id)
            if (songs.isNotEmpty()) notice = addedMessage(name.trim(), songs.size)
        }
        then(id)
    }
}

// Playlist files.

// Writes a playlist to an M3U file: the server's path for each song when
// it says, "Artist - Title" when it does not.
fun AppState.exportPlaylist(playlist: Playlist, file: File) {
    val client = connection?.client ?: return
    scope.launch {
        try {
            val lines = coroutineScope {
                val songs = async { client.playlist(playlist.id).entry }
                val paths = async { runCatching { client.playlistPaths(playlist.id) }.getOrDefault(emptyList()) }
                m3uLinesOf(songs.await(), paths.await().mapNotNull { p -> p.path?.let { p.id to it } }.toMap())
            }
            withContext(Dispatchers.IO) { file.writeText(writeM3u(lines), Charsets.UTF_8) }
            notice = "Exported to ${file.name}"
        } catch (e: SubsonicException) {
            notice = "Couldn't export the playlist: ${e.userMessage()}"
        } catch (e: IOException) {
            notice = "Couldn't export the playlist: ${e.message ?: "the file couldn't be written"}"
        }
    }
}

// Makes a playlist from an M3U or M3U8 file, named after the file, with
// the songs the library has, and opens it. The notice line says how many
// were found, with the ones that were not behind Details.
fun AppState.importPlaylistFile(file: File) {
    connection ?: return
    val library = library?.index?.songs.orEmpty()
    scope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching { file.takeIf { it.length() <= PLAYLIST_FILE_LIMIT }?.readBytes()?.let(::playlistText) }.getOrNull()
        }
        if (text == null) {
            notice = "That file couldn't be read."
            return@launch
        }
        val found = withContext(Dispatchers.Default) { importPlaylist(text, file.name, library) }
        val summary = importSummary(found.report)
        if (found.songs.isEmpty()) {
            notice = summary
            noticeDetail = missedDetail(found.report.missed)
            return@launch
        }
        createPlaylist(found.report.name, found.songs) { id ->
            if (id != null) {
                notice = summary
                noticeDetail = missedDetail(found.report.missed)
                navigator.go(Page.Playlist(id))
            }
        }
    }
}
