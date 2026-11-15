package app.winters.octo.versions

import app.winters.octo.subsonic.MISSED_YOUTUBE
import app.winters.octo.subsonic.MISSED_YOUTUBE_MUSIC
import app.winters.octo.subsonic.PICKED_EARLIER
import app.winters.octo.subsonic.PICKED_PERSON
import app.winters.octo.subsonic.PLAYS_FROM_STREAM
import app.winters.octo.subsonic.STREAM_VERSION_AUTO
import app.winters.octo.subsonic.StreamVersion
import app.winters.octo.subsonic.StreamVersionPlaying
import app.winters.octo.subsonic.StreamVersions

// The song a Choose the version sheet was opened for: the queue entry
// playing it, its id as it streams, and what to call it.
data class VersionsSong(val entryId: String, val songId: String, val title: String, val artist: String)

// What the Choose the version sheet shows. It changes only through `on`, so
// every app's sheet behaves the same.
sealed interface StreamVersionsSheetState {
    // The song it is for; none once closed.
    val song: VersionsSong?

    data object Closed : StreamVersionsSheetState {
        override val song: VersionsSong? get() = null
    }

    // Asking the server for the list.
    data class Looking(override val song: VersionsSong) : StreamVersionsSheetState

    // No answer: the server could not be reached (`server`), or it could
    // not reach YouTube at all. Try again asks once more.
    data class CouldNotReach(override val song: VersionsSong, val server: Boolean) : StreamVersionsSheetState

    // The server said no in its own words, as for a song that can't be
    // given another version.
    data class Unavailable(override val song: VersionsSong, val message: String) : StreamVersionsSheetState

    // The server's answer. `checking` is the video a pick waits on
    // (STREAM_VERSION_AUTO while going back to Octo's pick); `refused` is the
    // one whose pick the server turned down, its words in `refusal`.
    data class Shown(
        override val song: VersionsSong,
        val answer: StreamVersions,
        val checking: String? = null,
        val refused: String? = null,
        val refusal: String? = null,
    ) : StreamVersionsSheetState {
        // Whether a row can be picked: the song streams for this listener
        // and no download of theirs is running.
        val picksOpen: Boolean get() = answer.playsFrom == PLAYS_FROM_STREAM && !answer.busy

        // The video playing, while the song streams for this listener.
        val playing: StreamVersionPlaying? get() = answer.playing.takeIf { answer.playsFrom == PLAYS_FROM_STREAM }

        // The other videos, while a row can be picked.
        val rows: List<StreamVersion> get() = if (picksOpen) answer.version else emptyList()

        // Whether Go back to Octo's pick is offered: a person's pick, or one
        // from before, stands.
        val canUndo: Boolean
            get() = answer.playsFrom == PLAYS_FROM_STREAM &&
                (answer.playing?.picked == PICKED_PERSON || answer.playing?.picked == PICKED_EARLIER)

        val notice: String? get() = StreamVersionWords.notice(answer)

        // The list's own line when it has no other version.
        val emptyText: String? get() = if (picksOpen && answer.version.isEmpty()) StreamVersionWords.NOTHING else null

        // Whether the player's current entry is still this sheet's song.
        fun playingHere(entryId: String?, songId: String?): Boolean = entryId == song.entryId && songId == song.songId
    }
}

// What happens to a sheet. A pick names the entry and song playing when it
// was tapped, so a pick for a song no longer playing is refused.
sealed interface StreamVersionsEvent {
    data class Open(val song: VersionsSong) : StreamVersionsEvent

    data class Listed(val answer: StreamVersions) : StreamVersionsEvent

    // The listing failed: `message` is the server's refusal, null when the
    // server could not be reached.
    data class ListFailed(val message: String?) : StreamVersionsEvent

    data object Retry : StreamVersionsEvent

    data class Pick(val videoId: String, val playingEntryId: String?, val playingSongId: String?) : StreamVersionsEvent

    data class Undo(val playingEntryId: String?, val playingSongId: String?) : StreamVersionsEvent

    data class Picked(val answer: StreamVersions) : StreamVersionsEvent

    data class PickFailed(val message: String) : StreamVersionsEvent

    // The player's current entry is now this one (null: nothing plays).
    data class EntryChanged(val entryId: String?) : StreamVersionsEvent

    data object Close : StreamVersionsEvent
}

// The sheet after `event`. An event that does not apply leaves the state as
// it was (the same object), so a caller can tell a refused pick from one
// that started.
fun StreamVersionsSheetState.on(event: StreamVersionsEvent): StreamVersionsSheetState = when (event) {
    is StreamVersionsEvent.Open -> StreamVersionsSheetState.Looking(event.song)
    StreamVersionsEvent.Close -> StreamVersionsSheetState.Closed
    is StreamVersionsEvent.EntryChanged -> {
        val current = song
        if (current == null || current.entryId == event.entryId) this else StreamVersionsSheetState.Closed
    }
    is StreamVersionsEvent.Listed ->
        if (this is StreamVersionsSheetState.Looking && event.answer.id == song.songId) listed(song, event.answer) else this
    is StreamVersionsEvent.ListFailed ->
        if (this is StreamVersionsSheetState.Looking) {
            val message = event.message
            if (message == null) {
                StreamVersionsSheetState.CouldNotReach(song, server = true)
            } else {
                StreamVersionsSheetState.Unavailable(song, message)
            }
        } else {
            this
        }
    StreamVersionsEvent.Retry ->
        if (this is StreamVersionsSheetState.CouldNotReach) StreamVersionsSheetState.Looking(song) else this
    is StreamVersionsEvent.Pick ->
        if (this is StreamVersionsSheetState.Shown &&
            checking == null &&
            playingHere(event.playingEntryId, event.playingSongId) &&
            rows.any { it.videoId == event.videoId }
        ) {
            copy(checking = event.videoId, refused = null, refusal = null)
        } else {
            this
        }
    is StreamVersionsEvent.Undo ->
        if (this is StreamVersionsSheetState.Shown &&
            checking == null &&
            playingHere(event.playingEntryId, event.playingSongId) &&
            canUndo
        ) {
            copy(checking = STREAM_VERSION_AUTO, refused = null, refusal = null)
        } else {
            this
        }
    is StreamVersionsEvent.Picked ->
        if (this is StreamVersionsSheetState.Shown && checking != null && event.answer.id == song.songId) {
            StreamVersionsSheetState.Shown(song, event.answer)
        } else {
            this
        }
    is StreamVersionsEvent.PickFailed ->
        if (this is StreamVersionsSheetState.Shown && checking != null) {
            copy(checking = null, refused = checking, refusal = event.message)
        } else {
            this
        }
}

// A listing that found no video at all because neither source answered is
// one that could not reach YouTube.
private fun listed(song: VersionsSong, answer: StreamVersions): StreamVersionsSheetState =
    if (answer.playsFrom == PLAYS_FROM_STREAM &&
        answer.version.isEmpty() &&
        MISSED_YOUTUBE_MUSIC in answer.missed &&
        MISSED_YOUTUBE in answer.missed
    ) {
        StreamVersionsSheetState.CouldNotReach(song, server = false)
    } else {
        StreamVersionsSheetState.Shown(song, answer)
    }
