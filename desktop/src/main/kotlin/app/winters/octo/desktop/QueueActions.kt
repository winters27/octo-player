package app.winters.octo.desktop

import app.winters.octo.desktop.player.songsToSave
import app.winters.octo.subsonic.Song

// What the queue panel does to the queue, beyond playing: moving picked
// songs, taking them out, clearing what is to come, saving it as a
// playlist, and taking an edit back. An edit that takes songs out says so
// in the one notice line, with Undo; moves and songs put in are taken back
// with Ctrl+Z in the panel, quietly.

// A word the notice line offers beside `text` (the notice it came with),
// and what it does.
class NoticeAction(val text: String, val label: String, val run: () -> Unit)

// The action to show beside a notice: only the one that came with it.
fun AppState.actionFor(text: String): NoticeAction? = noticeAction?.takeIf { it.text == text }

// Says what happened to the queue in the notice line, with Undo.
fun AppState.queueEdited(words: String) {
    notice = words
    noticeDetail = null
    noticeAction = NoticeAction(words, "Undo") {
        if (undoQueue()) {
            notice = null
        } else {
            notice = "Can't undo that now: the queue has changed since"
            noticeAction = null
        }
    }
}

// Takes back the most recent queue edit. Answers whether it could. The
// notice's Undo goes with it.
fun AppState.undoQueue(): Boolean {
    val done = player.undo()
    if (done && notice != null && actionFor(notice!!) != null) {
        notice = null
        noticeAction = null
    }
    return done
}

// Takes picked songs out of the queue as one edit, with Undo.
fun AppState.removeQueued(keys: List<Long>) {
    val queue = player.state.value.queue
    val count = keys.distinct().count { key -> queue.any { it.key == key } }
    if (count == 0) return
    player.remove(keys)
    queueEdited(if (count == 1) "Removed from the queue" else "Removed $count songs from the queue")
}

// Takes out every song still to come; the one playing plays on.
fun AppState.clearUpcoming() {
    if (player.state.value.upcoming.isEmpty()) return
    player.clearUpcoming()
    queueEdited("Upcoming songs cleared")
}

// Takes out the songs already played, as the phone's Remove played does.
fun AppState.removePlayed() {
    if (player.state.value.played.isEmpty()) return
    player.removePlayed()
    queueEdited("Played songs removed")
}

// Moves picked songs to play right after the one playing.
fun AppState.moveToNext(keys: List<Long>) {
    val first = player.state.value.upcoming.firstOrNull { it.key !in keys }
    player.move(keys, first?.key)
}

// Moves picked songs to the end of the queue.
fun AppState.moveToEnd(keys: List<Long>) = player.move(keys, null)

// Songs dropped into the queue panel: they go in among the songs to come
// at `index` (0 plays them next, the number of songs to come or more puts
// them last), as the listener's own. For the drag and drop of songs from
// any list.
fun AppState.dropIntoQueue(songs: List<Song>, index: Int) {
    if (songs.isEmpty()) return
    player.insert(songs, player.state.value.upcoming.getOrNull(index.coerceAtLeast(0))?.key)
}

// Saves the queue as a new playlist, in play order and without the songs
// Autoplay added, as the phone does.
fun AppState.saveQueueAsPlaylist(name: String) {
    val songs = songsToSave(player.state.value)
    if (name.isBlank() || songs.isEmpty()) return
    createPlaylist(name, songs) { id -> if (id != null) notice = "Saved as ${name.trim()}" }
}

fun AppState.setAutoplay(on: Boolean) = settings.update { it.copy(playback = it.playback.copy(autoplay = on)) }
