package app.winters.octo.lyrics.engine

import kotlin.math.abs
import kotlin.math.max

// When the projection and the player disagree by more than this, the
// player wins.
const val CLOCK_DRIFT_S = 0.15

// A jump bigger than this from one frame to the next is a seek.
const val CLOCK_SEEK_S = 0.6

// This many frames in a row with the player's position standing still
// means it is paused or waiting, whatever it says.
const val CLOCK_STILL_FRAMES = 6

// After a tap to seek, the target shows until the player is this close to
// it, or this long has passed.
const val SEEK_HOLD_NEAR_S = 0.5
const val SEEK_HOLD_MAX_S = 2.5

// The step the springs are given each frame is kept within this.
const val MAX_FRAME_STEP_S = 0.05

// Where in the song the lyrics are, smooth from frame to frame. The
// player's reported position moves in coarse steps, so the clock takes it
// as an anchor and runs on from there with the phone's own steady clock,
// at the playback speed. It snaps back to the player when the two drift
// apart, and tells a seek from ordinary time passing.
//
// `latency` is how long sound takes to be heard after the player reports
// it, taken off the time. All times are in seconds, clock times in
// nanoseconds.
class LyricClock(private val latency: Double = 0.0) {
    private var anchorAt = 0.0
    private var anchorNanos = 0L
    private var anchorRate = 1.0
    private var anchored = false
    private var anchorWanted = false

    private var lastReported: Double? = null
    private var stillFrames = 0
    private var lastTime: Double? = null
    private var lastNanos: Long? = null

    private var holdTarget: Double? = null
    private var holdSince = 0L

    // The time shown this frame.
    var time: Double = 0.0
        private set

    // Whether this frame jumped, as a seek does.
    var seeked: Boolean = false
        private set

    // The time since the last frame, kept small enough for the springs.
    var step: Double = 0.0
        private set

    // Starts over, as when the view comes back: the next frame anchors
    // fresh and is not taken for a seek.
    fun reset() {
        anchored = false
        lastReported = null
        stillFrames = 0
        lastTime = null
        lastNanos = null
    }

    // Takes a new anchor from the player next frame: it played, paused,
    // seeked, changed song or said the time changed.
    fun requestAnchor() {
        anchorWanted = true
    }

    // After a tap to seek: shows `target` until the player gets there.
    fun holdSeek(target: Double, nowNanos: Long) {
        holdTarget = target
        holdSince = nowNanos
    }

    // Works out this frame's time from the player's reported position,
    // playback speed and whether it says it is playing.
    fun frame(nowNanos: Long, reported: Double, rate: Double, playing: Boolean): Double {
        step = lastNanos?.let { ((nowNanos - it) / 1e9).coerceIn(0.0, MAX_FRAME_STEP_S) } ?: 0.0
        lastNanos = nowNanos

        stillFrames = if (lastReported == reported) stillFrames + 1 else 0
        lastReported = reported
        val moving = playing && stillFrames < CLOCK_STILL_FRAMES

        var shown: Double
        if (!moving) {
            // Paused: the player's own position, not projected.
            shown = reported - latency
            anchored = false
        } else {
            when {
                !anchored || anchorWanted -> anchor(reported, nowNanos, rate)
                // A new speed carries on from where the old one had got to.
                rate != anchorRate -> anchor(projected(nowNanos), nowNanos, rate)
            }
            var at = projected(nowNanos)
            if (abs(at - reported) > CLOCK_DRIFT_S) {
                anchor(reported, nowNanos, rate)
                at = reported
            }
            shown = at - latency
        }
        anchorWanted = false
        shown = max(0.0, shown)

        holdTarget?.let { target ->
            if (abs(reported - target) <= SEEK_HOLD_NEAR_S || (nowNanos - holdSince) / 1e9 > SEEK_HOLD_MAX_S) {
                holdTarget = null
            } else {
                shown = target
            }
        }

        seeked = lastTime?.let { abs(shown - it) > CLOCK_SEEK_S } ?: false
        lastTime = shown
        time = shown
        return shown
    }

    private fun anchor(at: Double, nowNanos: Long, rate: Double) {
        anchorAt = at
        anchorNanos = nowNanos
        anchorRate = rate
        anchored = true
    }

    private fun projected(nowNanos: Long): Double = anchorAt + (nowNanos - anchorNanos) / 1e9 * anchorRate
}
