package app.winters.octo.sound

import androidx.media3.common.util.UnstableApi

// A released output's unmixed frames, from `at` on, still to be played
// until the slot's new output takes over.
@UnstableApi
internal class Tail(val out: MixAudioOutput, var at: Long)

// A Sonic restart's join: the old ring's next frames from `at` fade out
// while the new output's fade in, over `frames`.
@UnstableApi
internal class Join(val out: MixAudioOutput, var at: Long, val frames: Int) {
    var done = 0
}

// One slot's mixing state on the mix thread. Each block keeps a copy of
// both slots' states at its start, so a re-mix can start from there.
@UnstableApi
internal class DeckState(val slot: Int) {
    var out: MixAudioOutput? = null

    // The next ring frame of `out` to mix.
    var mixIdx = 0L

    // Its part in the registered blend: none, A (out) or B (in).
    var role = 0

    // A spare loaded and waiting for its start.
    var parked = false

    // Whether it contributes at all (a deck that left or ended does not).
    var on = true

    // Dry-out: normal, fading its last frames, or dry.
    var dry = 0
    var fadeLeft = 0
    var fadeTotal = 0

    // A rejoin's or a cut's ramp in, and a leave's ramp out.
    var rampLeft = 0
    var rampTotal = 1
    var leaveLeft = 0
    var leaveTotal = 0

    // Its share of the output's volume, ramped when the decks' volumes change.
    var share = 1f
    var shareFrom = 1f
    var shareTo = 1f
    var shareLeft = 0

    // The blend stage and the transition it runs; the blend time is the
    // song time less `offsetMs`, or stands at `frozenMs` while frozen.
    private var ownShaper: TransitionShaper? = null
    var shaper: TransitionShaper? = null
    var transition: DeckTransition? = null
    var frozen = false
    var frozenMs = 0.0
    var offsetMs = 0.0
    var blendMsNow = 0.0

    // B: held for placement; its start's mix frame and first ring frame.
    var held = false
    var startAt = -1L
    var startRing = -1L
    var started = false
    var readyState = -1
    var readyPosted = -1L

    // A: its next pass held at the boundary; what plays after it; its own
    // song's frames dropped after the blend's end.
    var holdNext = false
    var follow = Long.MIN_VALUE
    var endDrop = false
    var endPosted = false
    var boundaryPosted = -1L

    // The pass being mixed: its entry and the last frame's song time.
    var passEntry: String? = null
    var passLive = false
    var passNo = 0L
    var lastSongMs = Double.NaN
    var lastSongEndMs = Double.NaN
    var lastMsPerFrame = Double.NaN

    // A new output that plays on in the pass by song time.
    var continuing = false

    // The player released the slot's playing output on its own (a restart,
    // a format change): its next output is coming, and the sound plays on.
    var restarting = false
    var announced = false
    var announcedPending = false
    var tail: Tail? = null
    var join: Join? = null

    var watchNanos = 0L

    // When the ring last grew, as the mixer last looked: the output and
    // its written count then.
    var grewAt = 0L
    var seenOut: MixAudioOutput? = null
    var seenWritten = 0L

    fun arm(t: DeckTransition, rate: Int) {
        if (ownShaper == null) ownShaper = TransitionShaper(rate, 2)
        shaper = ownShaper
        transition = t
        frozen = false
        offsetMs = 0.0
        held = false
        startAt = -1
        startRing = -1
        started = false
        holdNext = false
        follow = Long.MIN_VALUE
        endDrop = false
        endPosted = false
        readyState = -1
        // A leave left from an earlier blend never ramps this one out.
        leaveLeft = 0
        leaveTotal = 0
    }

    // The slot's part in a blend is over: from here on its own sound, at
    // its own level.
    fun dropBlend() {
        transition = null
        shaper?.disarm(0)
        frozen = false
        offsetMs = 0.0
        held = false
        startAt = -1
        holdNext = false
        endDrop = false
        leaveTotal = 0
        leaveLeft = 0
        rampLeft = 0
    }

    fun leave(frames: Int) {
        if (!on) return
        if (frames <= 0) {
            stop()
            return
        }
        leaveTotal = frames
        leaveLeft = frames
    }

    // Stops the deck where it is: a leave not yet ramped out ends here.
    fun stop() {
        on = false
        leaveLeft = 0
        leaveTotal = 0
    }

    // No leave, dry-out fade or ramp of an earlier part carries over: the
    // deck's own level, its share where the volumes put it.
    fun clearLevel() {
        leaveLeft = 0
        leaveTotal = 0
        fadeLeft = 0
        fadeTotal = 0
        rampLeft = 0
        dry = 0
        share = shareTo
        shareFrom = shareTo
        shareLeft = 0
    }

    // The pass before this one, and the mix frame this one began at: a
    // player's own restart of the earlier pass removes this one.
    var prevEntry: String? = null
    var prevLastSongMs = Double.NaN
    var passStartMix = -1L

    fun newPass(entry: String?, live: Boolean, atMix: Long = -1L) {
        prevEntry = passEntry
        prevLastSongMs = lastSongMs
        passStartMix = atMix
        passEntry = entry
        passLive = live
        passNo++
        lastSongMs = Double.NaN
        lastSongEndMs = Double.NaN
        holdNext = false
    }

    fun resetPass() {
        passEntry = null
        prevEntry = null
        passStartMix = -1
        passLive = false
        lastSongMs = Double.NaN
        lastSongEndMs = Double.NaN
        continuing = false
        restarting = false
        tail = null
        join = null
    }

    fun blendMs(songMs: Double): Double = if (frozen) frozenMs else songMs - offsetMs

    fun copyFrom(o: DeckState) {
        out = o.out
        mixIdx = o.mixIdx
        role = o.role
        parked = o.parked
        on = o.on
        dry = o.dry
        fadeLeft = o.fadeLeft
        fadeTotal = o.fadeTotal
        rampLeft = o.rampLeft
        rampTotal = o.rampTotal
        leaveLeft = o.leaveLeft
        leaveTotal = o.leaveTotal
        share = o.share
        shareFrom = o.shareFrom
        shareTo = o.shareTo
        shareLeft = o.shareLeft
        val s = o.shaper
        if (s == null) {
            shaper = null
        } else {
            val mine = ownShaper?.takeIf { it.sampleRate == s.sampleRate } ?: TransitionShaper(s.sampleRate, s.channels).also { ownShaper = it }
            mine.copyFrom(s)
            shaper = mine
        }
        transition = o.transition
        frozen = o.frozen
        frozenMs = o.frozenMs
        offsetMs = o.offsetMs
        blendMsNow = o.blendMsNow
        held = o.held
        startAt = o.startAt
        startRing = o.startRing
        started = o.started
        readyState = o.readyState
        readyPosted = o.readyPosted
        holdNext = o.holdNext
        follow = o.follow
        endDrop = o.endDrop
        endPosted = o.endPosted
        boundaryPosted = o.boundaryPosted
        passEntry = o.passEntry
        passLive = o.passLive
        passNo = o.passNo
        prevEntry = o.prevEntry
        prevLastSongMs = o.prevLastSongMs
        passStartMix = o.passStartMix
        lastSongMs = o.lastSongMs
        lastSongEndMs = o.lastSongEndMs
        lastMsPerFrame = o.lastMsPerFrame
        continuing = o.continuing
        restarting = o.restarting
        announced = o.announced
        announcedPending = o.announcedPending
        tail = o.tail?.let { Tail(it.out, it.at) }
        join = o.join?.let { j -> Join(j.out, j.at, j.frames).also { it.done = j.done } }
        watchNanos = o.watchNanos
    }

    // A recycled snapshot lets go of the outputs it referred to.
    fun clearRefs() {
        out = null
        transition = null
        tail = null
        join = null
        seenOut = null
    }
}

// A stretch of a deck output's ring the mixer used: ring frames
// [ringStart, ringEnd) went into mix frames [mixAt, mixEnd). A drop has
// mixEnd == mixAt: its frames count as heard once mix frame mixAt is.
internal class Run(val mixAt: Long, val ringStart: Long, var ringEnd: Long, var mixEnd: Long)
