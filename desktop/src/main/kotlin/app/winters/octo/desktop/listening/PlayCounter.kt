package app.winters.octo.desktop.listening

import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.playback.countsAsPlay
import app.winters.octo.subsonic.Song

// A play that counted: the song, when it began (ms since 1970), how long
// it was actually heard and how long it is.
data class HeardPlay(val song: Song, val startedAt: Long, val heardMs: Long, val durationMs: Long)

// Counts how long each song is actually heard, pauses and waits for the
// network left out, and hands on a play when the song moves on, if enough
// of it was heard (the phone's rule). Also says when each song first
// starts to be heard. Each time round a song on repeat is its own play.
// Fed every player state; not thread safe, so one thread feeds it.
class PlayCounter(
    private val onStarted: (Song) -> Unit,
    private val onCounted: (HeardPlay) -> Unit,
    // Milliseconds from any fixed point, for how long a song was heard.
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    // Milliseconds since 1970, for when a play began.
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private var play: Pair<Long, Int>? = null
    private var song: Song? = null
    private var durationMs = 0L
    private var startedAt = 0L
    private var heardMs = 0L
    private var hearingSince: Long? = null
    private var announced = false

    fun update(state: PlayerState) {
        val current = state.current
        val id = current?.let { it.key to state.rounds }
        if (id != play) {
            finish()
            play = id
            song = current?.song
            startedAt = wallClock()
            heardMs = 0
            announced = false
        }
        if (current == null) return
        val length = current.song.duration * 1000L
        durationMs = if (length > 0) length else state.durationMs
        val hearing = state.playing && !state.buffering
        if (hearing && hearingSince == null) {
            hearingSince = clock()
            if (!announced) {
                announced = true
                onStarted(current.song)
            }
        } else if (!hearing) {
            stopClock()
        }
    }

    // Hands on the song playing now if it counts, and starts over: for
    // quitting, or signing out.
    fun flush() {
        finish()
        play = null
        song = null
    }

    private fun finish() {
        stopClock()
        val heard = song ?: return
        if (countsAsPlay(heardMs, durationMs)) onCounted(HeardPlay(heard, startedAt, heardMs, durationMs))
        song = null
        heardMs = 0
    }

    private fun stopClock() {
        hearingSince?.let { heardMs += clock() - it }
        hearingSince = null
    }
}
