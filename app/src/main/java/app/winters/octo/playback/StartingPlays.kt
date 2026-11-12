package app.winters.octo.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

// The words a play shows while it starts, and when it could not.
const val STARTING_WORDS = "Starting"
const val START_FAILED_WORDS = "Couldn't start that. Try again."

// A play just asked for, shown at once with what the screen already knew
// of it, until its first song is the player's: its token (the play's list
// id), title, artist and cover.
data class StartingPlay(
    val token: Long,
    val title: String?,
    val artist: String?,
    val artwork: String?,
) {
    // The start entry's id the player's item carries once it plays.
    val startKey: String get() = entryKey(entryOf(token, 0))

    val words: String get() = STARTING_WORDS
}

// The play the app is starting, for the whole process: a tap sets it before
// anything is read, the player's item showing its start entry ends it, and
// so does a refusal, a command that fails, or the service going away. A
// newer tap takes its place; whatever ends an older one changes nothing.
@Singleton
class StartingPlays @Inject constructor() {
    private val _starting = MutableStateFlow<StartingPlay?>(null)
    val starting: StateFlow<StartingPlay?> = _starting

    fun start(play: StartingPlay) {
        _starting.value = play
    }

    // Fills in what the screen did not know when the start song's read
    // landed.
    fun learned(token: Long, title: String?, artist: String?, artwork: String?) {
        _starting.update { if (it?.token == token) it.copy(title = title ?: it.title, artist = artist ?: it.artist, artwork = artwork ?: it.artwork) else it }
    }

    // Ends `token`'s play; answers whether it was the current one.
    fun end(token: Long): Boolean {
        var was = false
        _starting.update {
            if (it?.token == token) {
                was = true
                null
            } else {
                it
            }
        }
        return was
    }

    // The player now plays this item: a play whose start entry it is ends.
    fun playing(entryId: String?) {
        val now = _starting.value ?: return
        if (entryId != null && entryId == now.startKey) end(now.token)
    }

    fun clear() {
        _starting.value = null
    }
}
