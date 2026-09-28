package app.winters.octo.desktop

import app.winters.octo.desktop.library.ShownSongFields
import app.winters.octo.desktop.nav.Page
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListStarter
import app.winters.octo.query.LibraryQuery
import app.winters.octo.subsonic.Song
import app.winters.octo.ui.playlist.copiedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// What the window does with live lists: make, change, copy and delete them,
// and save what one holds now to the server as an ordinary playlist. The
// lists themselves are in AppState.liveLists.

// Songs as this window shows them, a heart or rating changed a moment ago
// included, for a live list to pick from.
fun AppState.shownFields() = ShownSongFields(::isStarred) { ratingOf(it) }

// The library songs a live list picks now, or null while the library is
// still being read.
fun AppState.liveListSongs(list: LiveList, now: Long = System.currentTimeMillis()): List<Song>? {
    val index = library?.index ?: return null
    return list.songsOf(index.songs, now, shownFields())
}

// Makes a live list and shows it in place of its editor.
fun AppState.createLiveList(name: String, query: LibraryQuery): LiveList {
    val made = liveLists.save(LiveList.new(name, query, System.currentTimeMillis()))
    if (navigator.current.page is Page.NewLiveList) navigator.replace(Page.LiveList(made.id)) else navigator.go(Page.LiveList(made.id))
    return made
}

// A starter the listener picked, made as it is.
fun AppState.createStarter(starter: LiveListStarter): LiveList = createLiveList(starter.name, starter.query)

// Keeps a live list's new name and rules.
fun AppState.updateLiveList(list: LiveList, name: String, query: LibraryQuery) {
    liveLists.save(list.copy(name = name.ifBlank { list.name }, query = query))
}

fun AppState.renameLiveList(id: String, name: String) = liveLists.rename(id, name)

fun AppState.duplicateLiveList(list: LiveList) {
    val copy = liveLists.duplicate(list)
    notice = copiedMessage(copy.name)
}

// Deletes a live list; its page, if open, goes back (or Home). The songs
// stay in the library.
fun AppState.deleteLiveList(list: LiveList) {
    liveLists.remove(list.id)
    if ((navigator.current.page as? Page.LiveList)?.id == list.id && !navigator.back()) navigator.go(Page.Home)
}

// Saves the songs the list holds now to the server as an ordinary playlist
// of the same name: a snapshot, which does not change with the rules.
fun AppState.saveLiveListToServer(list: LiveList) {
    if (connection == null) return
    scope.launch {
        val songs = withContext(Dispatchers.Default) { liveListSongs(list) }
        when {
            songs == null -> notice = "Your library is still being read. Try again in a moment."
            songs.isEmpty() -> notice = "\"${list.name}\" has no songs right now, so there is nothing to save."
            else -> createPlaylist(list.name, songs) { id ->
                if (id != null) notice = savedCopyMessage(list.name, songs.size)
            }
        }
    }
}

// "Saved a copy of Late night to the server, 42 songs".
fun savedCopyMessage(name: String, count: Int): String =
    "Saved a copy of $name to the server, ${if (count == 1) "1 song" else "%,d songs".format(count)}"
