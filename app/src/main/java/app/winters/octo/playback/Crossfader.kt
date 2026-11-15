package app.winters.octo.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.winters.octo.sound.DeckMixer
import app.winters.octo.sound.DeckSound
import app.winters.octo.sound.DeckTransition
import app.winters.octo.sound.GateRecord
import app.winters.octo.sound.MixBlend
import app.winters.octo.sound.MixEvent
import app.winters.octo.sound.MixListener
import app.winters.octo.sound.SongLoudness
import app.winters.octo.sound.TAP_LEVEL_HEARD_MS
import app.winters.octo.sound.TapReading
import app.winters.octo.sound.chooseReplayGain
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

// The planner needs the end of the playing song and the start of the next
// once the song has played this long.
private const val SCOUT_AFTER_MS = 1_000L

// The song's body level from what has been heard counts once this much
// has been heard.
private const val LEVEL_HEARD_MS = TAP_LEVEL_HEARD_MS

// A little past that, so the second reading surely has it.
private const val REREAD_MARGIN_MS = 500L

// A tempo-matched song eases back to its own speed in steps this small.
private const val RATE_STEP = 0.0025

// The mixer's events, passed on to whoever listens. The mixer is made before
// the player that listens to it, so this stands between them.
class MixRelay : MixListener {
    @Volatile var to: MixListener? = null

    override fun onBlendEvent(event: MixEvent) {
        to?.onBlendEvent(event)
    }

    override fun onGateBound(record: GateRecord) {
        to?.onGateBound(record)
    }
}

// The planner's shape: the playing song and the next, their scouted end
// and start, the settings at the listener's pace, and where the player is.
typealias Planner = (FadeSong, FadeSong, SectionAnalysis?, SectionAnalysis?, AutomixSettings, TransitionContext) -> TransitionPlan

// A queue entry's pass as the mixer is told it when a blend is undone after
// the outgoing song read ahead: a number from the entry that plays next.
internal fun blendPassOf(entryId: String): Long = entryId.hashCode().toLong() and 0x7FFF_FFFFL

// Runs the transition between two decks. While a song plays it reads the
// end of it and the start of the next song, and plans where and how they
// meet (planTransition). Each comes from the server's transition profile of
// the song when the server has one (`profiles`), else from scouting it.
// Ahead of the blend it loads the spare deck with the same queue, the same
// shuffle order and the next song, parked just before the next song's
// entry. Both decks play into one mixer (`mixer`), which decides the blend
// on the frame: this registers the plan there (a generation, with its commit
// word), plays the parked spare a little before the blend so its sound is
// waiting, and follows what the mixer says. When the mixer proposes the
// start, this agrees by moving the commit word to ACKED and hands the player
// over to the spare in the same turn, so every edit made after that lands on
// the song now on screen; an edit before it cancels the word first, and the
// mixer undoes the start unheard. The old deck plays on until the mixer says
// the blend has ended.
@OptIn(UnstableApi::class)
internal class Crossfader(
    private val player: OctoPlayer,
    private var spare: ExoPlayer,
    private val soundOf: (ExoPlayer) -> DeckSound?,
    private val scout: Scout?,
    private val profiles: ProfileSource? = null,
    // Real lengths learned by playing, for a song whose listing was wrong.
    private val lengths: RealLengths = RealLengths(),
    // The one mix output both decks play into; with none, songs never blend.
    private val mixer: DeckMixer? = null,
    // Both decks, so each is listened to whatever it plays; a deck's place
    // here is its mixer slot.
    decks: List<ExoPlayer> = emptyList(),
    private val times: FadeTimes = FadeTimes(),
    // How a transition is planned (the shared planner; tests hand it plans).
    private val planner: Planner = ::planTransition,
) : Player.Listener, MixListener {
    // How transitions are planned; a longest blend of 0 turns them off. A
    // change before the handover ends the blend by the phase rule; after it,
    // it concerns only the next transition.
    var settings = AutomixSettings(maxOverlapMs = 0)
        set(value) {
            if (field == value) return
            field = value
            if (outgoing != null) return
            disarm(BlendDisarm.EDIT)
            followUp()
            dropPlan()
            unloadSpare()
            schedule(0)
        }

    // The longest blend, or 0 when crossfade is off (or there is no mixer).
    val fadeMs: Long get() = if (mixer == null) 0 else settings.maxOverlapMs

    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { step() }
    private val slots = decks

    // The queue position the spare is loaded with, if it is loaded, its
    // entry, and the plan it was loaded for.
    private var loadedFor = C.INDEX_UNSET
    private var loadedEntry: String? = null
    private var loadedPlan: TransitionPlan? = null

    // The next song whose spare failed for this transition: it is reached
    // by the playing deck's own advance instead.
    private val failed = BlendFailedSpare()

    // The playing song whose sound the mixer found ended before its planned
    // start: until the song changes or the person moves in it, it plays on
    // into its own next song with no blend planned.
    private var endedEarly: String? = null

    // The transition the mixer refused as too late to start, by what its
    // plan was made from and the playing song: until either changes, the
    // song plays on into its next with no blend instead of being armed
    // again with a plan made a moment later.
    private var refused: Pair<PlanKey, String?>? = null

    // The plan for the coming transition, and what it was made from.
    private var plan: TransitionPlan? = null
    private var planKey: PlanKey? = null

    // The silence gate the playing song was armed with, for an early fade.
    private var outgoingGate: Double? = null

    // The mixer's generations: the next to open, the one registered and not
    // yet handed over (with its plan and the entry the playing song reads
    // into next), and the one handed over whose blend has not ended.
    private var nextGeneration = 1L
    private var registered = -1L
    private var registeredPlan: TransitionPlan? = null
    private var registeredNext: String? = null
    private var registeredFrom: String? = null
    private var handedOver = -1L
    private var handedOverNext: String? = null
    private var leaving = false

    // An undone generation owed its follow-up, once the edit's own write has
    // applied.
    private var followUpFor = -1L
    private var followUpNext: String? = null

    // During the blend: the deck playing out the old song, and the plan.
    private var outgoing: ExoPlayer? = null
    private var blending: TransitionPlan? = null

    // A tempo-matched song easing back to its own speed.
    private var easing: Easing? = null

    // The scouted end of the playing song and start of the next.
    private var tail: Scouted? = null
    private var head: Scouted? = null
    private var reading: Reading? = null

    // Where the song was at the last check, to see it jump.
    private var lastPosition = -1L
    private var lastCheckAt = 0L

    // The app's own queue upkeep waiting for the blend to be over, and
    // whether its one message is on its way.
    private val upkeep = ArrayList<() -> Unit>()
    private var upkeepPosted = false
    private val runUpkeep = Runnable { applyUpkeep() }

    // Whether a blend is under way or being set up: a spare loaded, a
    // generation registered or handed over. The app's own queue upkeep
    // waits while it is.
    val busy: Boolean get() = loadedFor != C.INDEX_UNSET || registered >= 0 || handedOver >= 0

    // Whether a blend's start is registered and not yet handed over.
    internal val armed: Boolean get() = registered >= 0

    init {
        slots.forEach { it.addListener(DeckWatch(it)) }
    }

    private class Scouted(val entryId: String, var job: Scout.Job?) {
        var done = false
        var analysis: SectionAnalysis? = null

        // The server's profile of the song, when the analysis came from it.
        var profile: TransitionProfile? = null
    }

    private class Reading(val entryId: String, val job: Scout.Job?, val askedAtMs: Long) {
        var done = false
        var value: TapReading? = null

        // Read before its level counts, it is read again once the song has
        // played far enough for it to (`positionMs` in song time, at
        // `speed`), so the level gets used.
        fun wantsMore(positionMs: Long, speed: Float): Boolean =
            done && tapReadsAgain(value?.heardMs, askedAtMs, positionMs, speed, LEVEL_HEARD_MS + REREAD_MARGIN_MS)

        val levelHeard: Boolean get() = (value?.heardMs ?: 0L) >= LEVEL_HEARD_MS
    }

    // A tempo-matched song on its way back to its own speed, on its own song
    // time: matched until `easeFromMs` (B's song time since entry over the
    // rate, from the blend's end), then eased; `applied` is the rate last set
    // before the listener's pace.
    private class Easing(val deck: ExoPlayer, val entryId: String?, val plan: TransitionPlan, val rate: Double) {
        var easeFromMs = Double.POSITIVE_INFINITY
        var applied = rate

        fun sinceEntryMs(): Double = (deck.currentPosition - plan.entryMs) / rate
    }

    // Everything a plan depends on besides the clock. A change means a new
    // plan. Once a blend is registered, the scouts' readiness and the next
    // song's own tags being read never change it (`core`); the gain between
    // the two songs, to a tenth of a dB, does.
    private data class PlanKey(
        val current: String?,
        val next: String?,
        val currentMs: Long,
        val nextMs: Long,
        val settings: AutomixSettings,
        val repeatOne: Boolean,
        val stopAtEnd: Boolean,
        val speed: Float,
        val skipSilence: Boolean,
        val tail: Boolean,
        val head: Boolean,
        val reading: Boolean,
        val levelHeard: Boolean,
        val gainTenthsDb: Long,
        val nextTagsRead: Boolean,
    ) {
        val core: PlanKey get() = copy(tail = false, head = false, reading = false, levelHeard = false, nextTagsRead = false)
    }

    // The player's own events: the song on screen.

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // A pause reaches the blend through the player's pause, a deck's
        // own pause and a rebuffer through the mixer: neither is decided here.
        if (isPlaying) schedule(0)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        failed.songChanged()
        endedEarly = null
        refused = null
        if (outgoing != null) return
        // The song changed some other way (the playing deck moved on into
        // its own next song before the start): what the spare holds is stale.
        disarm(BlendDisarm.EDIT)
        // Inside an edit, the edit sends the follow-up once all of it applied.
        if (editing == 0) followUp()
        dropPlan()
        unloadSpare()
        schedule(0)
    }

    // How deep the player is inside its own edits.
    private var editing = 0

    // An edit of the player's begins: interrupt() by the phase rule; true
    // when the output was asked to hold, so the edit lifts it after its
    // operation. Each begin is closed by endEdit() once the edit's own write
    // has applied, which sends the follow-up.
    fun beginEdit(reason: BlendDisarm = BlendDisarm.EDIT, releasing: Boolean = false): Boolean {
        editing++
        return interrupt(reason, releasing)
    }

    fun endEdit() {
        editing = (editing - 1).coerceAtLeast(0)
        if (editing == 0) followUp()
    }

    // Called before anything that changes what plays or where: by the phase
    // rule, a start not yet handed over is undone and the spare unloaded; a
    // blend already handed over lets the old song leave and the change lands
    // on the song now on screen. A `releasing` change (a seek, a skip, new
    // items, a stop, Cast) first has the mixer hold the output when the
    // handed-over song's sound is in it. Every caller calls followUp() once
    // its own change has applied.
    fun interrupt(reason: BlendDisarm = BlendDisarm.EDIT, releasing: Boolean = false): Boolean {
        val mixer = mixer
        var held = false
        endedEarly = null
        refused = null
        if (releasing && mixer != null) {
            val g = if (handedOver >= 0) handedOver else registered
            if (g >= 0) {
                mixer.holdIfHandedOver(g)
                held = true
            }
        }
        disarm(reason)
        dropPlan()
        unloadSpare()
        schedule(0)
        return held
    }

    // What the playing song plays after its end, for an undone start whose
    // song had already read past its end: sent once the change that undid it
    // has applied, so a song put next by that change is the one heard.
    fun followUp() {
        val g = followUpFor
        if (g < 0) return
        followUpFor = -1
        mixer?.followUp(g, followUpPass(followUpNext))
        followUpNext = null
    }

    // The listener's speed and pitch changed: every deck plays at them,
    // except that a tempo-matched song (in its blend, or easing back after
    // it) plays at its rate times them, and a parked spare at its plan's.
    // During a blend the mixer hears of it.
    fun paceChanged(pace: PlaybackParameters) {
        val ease = easing?.takeIf { it.deck === player.deck }
        for (d in slots) {
            d.playbackParameters = when {
                d === outgoing -> pace
                ease != null && d === ease.deck -> PlaybackParameters((ease.applied * pace.speed).toFloat(), pace.pitch)
                d === spare && loadedFor != C.INDEX_UNSET -> pacedFor(loadedPlan)
                else -> pace
            }
        }
        if (outgoing != null && handedOver >= 0) mixer?.paceChanged(handedOver, pace.speed.toDouble())
    }

    // The person's "pause at the end of this song" set on the song on
    // screen: before the handover a change ends the blend by the phase rule;
    // after it, it is the new song's own.
    fun endOfSongChanged(write: () -> Unit) {
        if (registered >= 0) {
            disarm(BlendDisarm.EDIT)
            dropPlan()
            unloadSpare()
        }
        write()
        followUp()
        schedule(0)
    }

    // Runs `write` now, or, while a blend is under way, once it is over.
    // For the app's own queue upkeep only; each write checks its own guards
    // again when it runs.
    fun deferUpkeep(write: () -> Unit) {
        upkeep += write
        idle()
    }

    fun release() {
        handler.removeCallbacks(check)
        handler.removeCallbacks(runUpkeep)
        upkeep.clear()
        disarm(BlendDisarm.EDIT)
        finishFade()
        tail?.job?.cancel()
        head?.job?.cancel()
        reading?.job?.cancel()
        scout?.release()
        spare.release()
    }

    // The mixer's events, on main.

    override fun onBlendEvent(event: MixEvent) {
        val g = event.generation
        when (event.kind) {
            BlendEventKind.PROPOSED -> proposed(g)
            BlendEventKind.INCOMING_NOT_READY -> if (g == registered) {
                Log.i("Octo", "automix: the next song was not ready at the start (${event.cause.name.lowercase()})")
                if (event.cause == BlendCause.LATE) refused = planKey?.let { it.core to registeredFrom }
                forgetRegistration()
                // The spare stays parked; the rest of the song is planned
                // again from where the output is now.
                dropPlan()
                idle()
                schedule(0)
            }
            BlendEventKind.MOVED -> if (g == registered || g == handedOver) {
                Log.i("Octo", "automix: the blend moved to ${event.ms.toLong()} ms, ${event.ms2.toLong()} ms longer")
            }
            BlendEventKind.BLEND_END -> if (g == handedOver) finishFade(event.ms)
            BlendEventKind.CANCELLED -> canceled(g, event.cause)
            BlendEventKind.FOLLOW_UP_NEEDED -> {
                val next = if (g == followUpFor) followUpNext else handedOverNext ?: registeredNext
                if (g == followUpFor) followUpFor = -1
                mixer?.followUp(g, followUpPass(next))
            }
        }
    }

    override fun onGateBound(record: GateRecord) {
        Log.i("Octo", "mix: a start waited the gate's bound (${record.cause.name.lowercase()})")
    }

    private fun proposed(g: Long) {
        // A second proposal for a generation handed over does nothing.
        if (g == handedOver || g != registered) return
        val mixer = mixer ?: return
        if (mixer.commitWord.ack(g)) {
            handOver(g)
            mixer.acked(g)
        } else {
            // The mixer canceled the start itself (at its floor): the
            // playing song goes on and the rest of it is planned again.
            forgetRegistration()
            dropPlan()
            idle()
            schedule(0)
        }
    }

    private fun canceled(g: Long, cause: BlendCause) {
        when (g) {
            registered -> {
                Log.i("Octo", "automix: the blend was canceled (${cause.name.lowercase()})")
                val next = registeredNext
                val from = registeredFrom
                forgetRegistration()
                // The playing song's sound ended before the start: it goes on
                // into its own next song, and nothing more of it is planned.
                if (cause == BlendCause.A_MOVED_ON || cause == BlendCause.A_ENDED) endedEarly = from
                // A deck's own pause undid it: what the playing song plays
                // after its end is told now.
                if (cause == BlendCause.PAUSED) mixer?.followUp(g, followUpPass(next))
                dropPlan()
                unloadSpare()
                schedule(0)
            }
            handedOver -> finishFade()
        }
    }

    // Forgets the registered generation, its word left canceled (an undone
    // start the session decided keeps INITIAL otherwise).
    private fun forgetRegistration() {
        if (registered >= 0) mixer?.commitWord?.cancel(registered)
        registered = -1
        registeredPlan = null
        registeredNext = null
        registeredFrom = null
    }

    // The phase rule's main side: a registered start has its commit word
    // canceled before the disarm goes, so every edit handled before this
    // lands on the old song; a handed-over one has its old song leave.
    private fun disarm(reason: BlendDisarm) {
        val mixer = mixer ?: return
        val g = registered
        if (g >= 0) {
            mixer.commitWord.cancel(g)
            followUpFor = g
            followUpNext = registeredNext
            forgetRegistration()
            mixer.disarm(reason, g)
            return
        }
        if (handedOver >= 0 && !leaving) {
            leaving = true
            mixer.disarm(reason, handedOver)
        }
    }

    // What the outgoing deck plays after its end: nothing when it stops
    // there, the frames it already read when its next song is still the one
    // it read into, else the song next on it now.
    private fun followUpPass(heldNext: String?): Long {
        val deck = player.deck
        if (deck.pauseAtEndOfMediaItems) return BLEND_PASS_NONE
        val next = deck.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return BLEND_PASS_NONE
        val item = deck.getMediaItemAt(next)
        val entry = item.entryId ?: item.mediaId
        if (next != deck.currentMediaItemIndex && entry == heldNext) return BLEND_PASS_HELD
        return blendPassOf(if (next == deck.currentMediaItemIndex) "$entry#again" else entry)
    }

    // Each deck's own events, the spare's included, which the player does
    // not pass on.
    private inner class DeckWatch(private val deck: ExoPlayer) : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = deckFailed(deck)

        override fun onPlaybackStateChanged(playbackState: Int) {
            // The old song stopped or played out during its blend.
            if (deck === outgoing && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) finishFade()
        }
    }

    private fun deckFailed(deck: ExoPlayer) {
        val mixer = mixer
        when {
            deck === outgoing && handedOver >= 0 -> {
                // The old song failed mid-blend: the new one plays on alone.
                mixer?.failed(BlendDeck.A, handedOver, BLEND_NO_ANSWER)
            }
            deck === player.deck && handedOver >= 0 && !leaving -> {
                // The new song failed after the handover: the service hears it
                // as the song on screen, and the old one leaves at once.
                leaving = true
                mixer?.failed(BlendDeck.B, handedOver, BLEND_NO_ANSWER)
            }
            deck === spare && loadedFor != C.INDEX_UNSET -> {
                // The next song failed in the spare: the playing deck reaches
                // it by its own advance instead, once, and the spare is not
                // loaded with it again for this transition.
                val entry = loadedEntry ?: spare.currentMediaItem?.let { it.entryId ?: it.mediaId }
                entry?.let(failed::failed)
                Log.i("Octo", "automix: the next song could not start in the spare")
                val g = registered
                if (g >= 0 && mixer != null) {
                    mixer.commitWord.cancel(g)
                    val next = registeredNext
                    forgetRegistration()
                    mixer.failed(BlendDeck.B, g, followUpPass(next))
                }
                unloadSpare()
                schedule(0)
            }
            deck === player.deck && registered >= 0 && mixer != null -> {
                // The playing song failed before the handover: the start is
                // undone, and the service's own skip plays the next song once
                // on this deck.
                val g = registered
                mixer.commitWord.cancel(g)
                forgetRegistration()
                mixer.failed(BlendDeck.A, g, BLEND_NO_ANSWER)
                dropPlan()
                unloadSpare()
            }
        }
    }

    private fun step() {
        easeRate()
        if (outgoing != null) {
            // The blend is the mixer's until it says it has ended.
            schedule(times.farCheckMs)
            return
        }
        val deck = player.deck
        if (!deck.playWhenReady || deck.playbackState == Player.STATE_IDLE || deck.playbackState == Player.STATE_ENDED) {
            // Paused: nothing to do until playing starts again.
            lastPosition = -1
            return
        }
        if (!deck.isPlaying) {
            // Loading after a seek, or a rebuffer: keep watching.
            lastPosition = -1
            schedule(times.nearCheckMs)
            return
        }
        val next = deck.nextMediaItemIndex
        val current = deck.currentMediaItem
        if (fadeMs <= 0 || next == C.INDEX_UNSET || current == null || (current.entryId ?: current.mediaId) == endedEarly) {
            replanAway()
            schedule(times.farCheckMs)
            return
        }
        val position = deck.currentPosition
        // Where the mixer decides a start: the song at the output's head,
        // ahead of what is heard by the route's latency.
        val atHead = headPositionOf(deck, current) ?: position
        val pace = player.listenerPace
        val speed = pace.speed
        if (jumped(position, speed) && registered < 0) dropPlan()
        val songs = songsFor(deck, next) ?: run {
            schedule(times.farCheckMs)
            return
        }
        val (currentSong, nextSong) = songs
        val repeatOne = deck.repeatMode == Player.REPEAT_MODE_ONE
        val stopAtEnd = deck.pauseAtEndOfMediaItems
        // In real time: at 1.5x speed the song's last 6 seconds pass in 4.
        val remaining = ((currentSong.durationMs - position) / speed).toLong()
        val plain = crossfadeLength(currentSong, nextSong, fadeMs, repeatOne, stopAtEnd)
        val waiting = plain == 0L && waitsOnLength(currentSong, nextSong, fadeMs, repeatOne, stopAtEnd)
        val nextItem = deck.getMediaItemAt(next)
        if (waiting) {
            // The next song's length is only known once it has opened, so
            // open it early on the spare and decide once it says.
            replanAway(keepSpare = true)
            val lead = loadLeadMs(0, fadeMs, lengthKnown = false)
            if (loadedFor != next && remaining <= lead && mayLoad(nextItem)) load(next, null)
            schedule(if (remaining > lead + times.farCheckMs) times.farCheckMs else times.nearCheckMs)
            return
        }
        if (plain == 0L) {
            // Played gaplessly, by the usual rules: nothing to plan.
            replanAway()
            schedule(times.farCheckMs)
            return
        }
        val gain = incomingGain(deck, nextItem, next)
        val key = keyFor(current, nextItem, currentSong, nextSong, repeatOne, stopAtEnd, speed, deck.skipSilenceEnabled, gain)
        if (registered >= 0 && key.core != planKey?.core) {
            // What the blend was planned from changed before its handover
            // (a speed, Skip silence, a length, the gain between the songs):
            // undone by the phase rule, and planned again.
            disarm(BlendDisarm.EDIT)
            followUp()
            dropPlan()
        }
        if (registered < 0) {
            scoutAhead(deck, current, nextItem, position, currentSong.durationMs)
            val fresh = keyFor(current, nextItem, currentSong, nextSong, repeatOne, stopAtEnd, speed, deck.skipSilenceEnabled, gain)
            if (fresh != planKey && readyToPlan(fresh, remaining)) {
                makePlan(deck, fresh, currentSong, nextSong, position, atHead, repeatOne, stopAtEnd, pace, nextItem, gain.db)
            }
        }
        val p = plan
        if (p == null) {
            // Waiting on the scouts; they call back as soon as they are done.
            schedule(if (remaining > fadeMs + times.planLoadLeadMs * 2) times.farCheckMs else times.nearCheckMs)
            return
        }
        if (p.kind == TransitionKind.GAPLESS || (refused != null && refused == planKey?.let { it.core to (current.entryId ?: current.mediaId) })) {
            unloadSpare()
            schedule(times.farCheckMs)
            return
        }
        val toStart = ((p.startMs - position) / speed).toLong()
        if (loadedFor != next && toStart <= times.planLoadLeadMs && mayLoad(nextItem) && !upkeepPosted) load(next, p)
        if (registered < 0 && loadedFor == next && loadedPlan !== p) repark(p)
        if (registered < 0 && loadedFor == next && spare.playbackState != Player.STATE_IDLE) {
            val proposalLeadMs = mixer?.proposalLeadMs ?: 0.0
            if (atHead >= p.armAtMs(proposalLeadMs, speed.toDouble()) - times.nearCheckMs) arm(p, deck, nextItem)
        }
        schedule(if (registered >= 0 || toStart <= times.planLoadLeadMs + times.farCheckMs) times.nearCheckMs else times.farCheckMs)
    }

    // The playing song's time at the mixer's output head, run on to this
    // moment at the listener's speed, once the head is in this song; null
    // with no mixer or before then.
    private fun headPositionOf(deck: ExoPlayer, item: MediaItem): Long? {
        val mixer = mixer ?: return null
        val head = mixer.headSong(slotOf(deck)) ?: return null
        if (head.entry == null || head.entry != item.entryId) return null
        val sinceMs = (mixer.nanoTime() - head.nanos).coerceAtLeast(0) / 1e6
        return (head.ms + sinceMs * player.listenerPace.speed).toLong()
    }

    // Whether the next song may go into the spare: not one whose spare
    // failed for this transition.
    private fun mayLoad(item: MediaItem): Boolean = failed.mayLoad(item.entryId ?: item.mediaId)

    // No blend for the coming transition: a registered start is undone by
    // the phase rule, the plan forgotten and the spare unloaded.
    private fun replanAway(keepSpare: Boolean = false) {
        if (registered >= 0) {
            disarm(BlendDisarm.EDIT)
            followUp()
        }
        dropPlan()
        if (!keepSpare) unloadSpare()
    }

    // The playing song and the next one as the crossfade sees them, each by
    // its real length: what its deck measured once it holds the song, else
    // its listed length, corrected where playing it before found it wrong.
    // Null with nothing next.
    private fun songsFor(deck: ExoPlayer, next: Int): Pair<FadeSong, FadeSong>? {
        if (next == C.INDEX_UNSET) return null
        val current = deck.currentMediaItem ?: return null
        val item = deck.getMediaItemAt(next)
        return current.fadeSong(lengthOf(deck)) to item.fadeSong(nextLengthOf(item, next))
    }

    // How long the song is: what the deck measured, or the listed length
    // (corrected where playing it before found it wrong) for a stream that
    // has not said.
    private fun lengthOf(deck: ExoPlayer): Long {
        val item = deck.currentMediaItem ?: return 0
        return songLengthMs(lengths.lengthMs(item.mediaId, item.mediaMetadata.durationMs ?: 0), deck.duration)
    }

    // How long the next song is: what the spare measured once it holds that
    // song, else the listed length, corrected where it was found wrong.
    private fun nextLengthOf(item: MediaItem, index: Int): Long {
        val listed = lengths.lengthMs(item.mediaId, item.mediaMetadata.durationMs ?: 0)
        val spareMs = if (loadedFor == index && spare.currentMediaItem?.mediaId == item.mediaId) spare.duration else C.TIME_UNSET
        return nextSongLengthMs(listed, spareMs.takeIf { it != C.TIME_UNSET })
    }

    // Whether the song moved more than a plan can follow since the last check.
    private fun jumped(position: Long, speed: Float): Boolean {
        val now = SystemClock.elapsedRealtime()
        val expected = lastPosition + (now - lastCheckAt) * speed
        val moved = lastPosition >= 0 && abs(position - expected) > REPLAN_JUMP_MS
        lastPosition = position
        lastCheckAt = now
        return moved
    }

    // Reads the end of the playing song once it has played a second, and the
    // start of the next song as soon as it is known, each once per queue
    // entry: from the server's profile of the song when it has one, else by
    // scouting it.
    private fun scoutAhead(deck: ExoPlayer, current: MediaItem, next: MediaItem, position: Long, lengthMs: Long) {
        val scout = scout ?: return
        if (!settings.smart) return
        val currentEntry = current.entryId ?: current.mediaId
        if (tail?.entryId != currentEntry && position >= SCOUT_AFTER_MS && lengthMs >= SHORTEST_AUTOMIX_SONG_MS) {
            tail?.job?.cancel()
            val scouted = Scouted(currentEntry, null)
            tail = scouted
            withProfile(current, scouted, TransitionProfile::tailAnalysis) {
                val uri = current.localConfiguration?.uri
                if (uri == null) {
                    scouted.done = true
                } else {
                    lateinit var job: Scout.Job
                    job = scout.read(uri, max(0L, lengthMs - TAIL_SCOUT_MS), lengthMs, tailAnalyzer(current.mediaMetadata.tagBpm())) { analysis ->
                        if (tail === scouted && scouted.job === job) {
                            scouted.analysis = analysis
                            scouted.done = true
                            schedule(0)
                        }
                    }
                    scouted.job = job
                }
            }
        }
        val nextEntry = next.entryId ?: next.mediaId
        if (head?.entryId != nextEntry) {
            head?.job?.cancel()
            val scouted = Scouted(nextEntry, null)
            head = scouted
            withProfile(next, scouted, TransitionProfile::headAnalysis) {
                val uri = next.localConfiguration?.uri
                if (uri == null) {
                    scouted.done = true
                } else {
                    lateinit var job: Scout.Job
                    job = scout.read(uri, 0, AUTOMIX_HEAD_MS, headAnalyzer(next.mediaMetadata.tagBpm())) { analysis ->
                        if (head === scouted && scouted.job === job) {
                            scouted.analysis = analysis
                            scouted.done = true
                            schedule(0)
                        }
                    }
                    scouted.job = job
                }
            }
        }
        // Once both are in, what the deck has heard of this song; again
        // once its level has been heard long enough to count.
        val again = reading?.entryId == currentEntry && reading?.wantsMore(position, player.listenerPace.speed) == true
        if ((reading?.entryId != currentEntry || again) && tail?.done == true && head?.done == true) {
            val sound = soundOf(deck)
            val tagBpm = current.mediaMetadata.tagBpm()
            reading?.job?.cancel()
            lateinit var job: Scout.Job
            job = scout.run({ sound?.reading(currentEntry, tagBpm) }) { value ->
                if (reading?.job === job) {
                    reading?.value = value
                    reading?.done = true
                    schedule(0)
                }
            }
            reading = Reading(currentEntry, job, position)
        }
    }

    // Fills `scouted` from the server's profile of the song when it has one,
    // at once when it is already here; otherwise, or once the server says it
    // has none, runs `scoutIt`.
    private fun withProfile(item: MediaItem, scouted: Scouted, part: (TransitionProfile) -> SectionAnalysis, scoutIt: () -> Unit) {
        val source = profiles ?: return scoutIt()
        val fill = { profile: TransitionProfile ->
            scouted.profile = profile
            scouted.analysis = part(profile)
            scouted.done = true
        }
        val uri = item.localConfiguration?.uri?.toString()
        source.cached(uri)?.let { return fill(it) }
        if (!source.request(uri) { profile ->
                if (tail !== scouted && head !== scouted) return@request
                if (profile != null) fill(profile) else scoutIt()
                schedule(0)
            }
        ) {
            scoutIt()
        }
    }

    private fun keyFor(
        current: MediaItem,
        next: MediaItem,
        currentSong: FadeSong,
        nextSong: FadeSong,
        repeatOne: Boolean,
        stopAtEnd: Boolean,
        speed: Float,
        skipSilence: Boolean,
        gain: IncomingGain,
    ): PlanKey {
        val currentEntry = current.entryId ?: current.mediaId
        val nextEntry = next.entryId ?: next.mediaId
        return PlanKey(
            current = currentEntry,
            next = nextEntry,
            currentMs = currentSong.durationMs,
            nextMs = nextSong.durationMs,
            settings = settings,
            repeatOne = repeatOne,
            stopAtEnd = stopAtEnd,
            speed = speed,
            skipSilence = skipSilence,
            tail = tail?.entryId == currentEntry && tail?.done == true,
            head = head?.entryId == nextEntry && head?.done == true,
            reading = reading?.entryId == currentEntry && reading?.done == true,
            levelHeard = reading?.entryId == currentEntry && reading?.levelHeard == true,
            gainTenthsDb = (gain.db * 10).roundToLong(),
            nextTagsRead = gain.tagsRead,
        )
    }

    // The next song's gain less the playing one's, in dB, as the decks apply
    // them: ReplayGain under the settings and the EQ's preamp at each song's
    // sample rate. The playing song's is its deck's own; the next song's is
    // its deck's own choice once the spare has opened it (the sound's own
    // tags win there), else its stored loudness at 48 kHz. The same song
    // next keeps the playing one's album rule.
    private class IncomingGain(val db: Double, val tagsRead: Boolean)

    private fun incomingGain(deck: ExoPlayer, nextItem: MediaItem, next: Int): IncomingGain {
        val sound = soundOf(deck) ?: return IncomingGain(0.0, false)
        val current = deck.currentMediaItem
        val opener = if (loadedFor == next && spare.currentMediaItem?.entryId == nextItem.entryId) soundOf(spare) else null
        val opened = opener?.loudnessOf(nextItem.entryId)
        val stored = SongLoudness(
            chooseReplayGain(null, nextItem.mediaMetadata.storedLoudness()),
            followsSameAlbum = albumKey(nextItem) != null && albumKey(nextItem) == current?.let(::albumKey),
        )
        val sameSong = current != null && nextItem.mediaId == current.mediaId
        val view = (opened ?: stored).let { b ->
            if (sameSong) b.copy(followsSameAlbum = sound.loudnessOf(current?.entryId)?.followsSameAlbum == true) else b
        }
        val rate = opener?.takeIf { opened != null }?.sampleRate?.takeIf { it > 0 } ?: 48_000
        return IncomingGain(sound.replayGainDb(view, rate) - sound.replayGainDb(), opened != null)
    }

    // A plan is made once everything it could use is in, or when there is
    // no more time to wait; plain crossfades need nothing.
    private fun readyToPlan(key: PlanKey, remaining: Long): Boolean {
        if (!settings.smart || scout == null) return true
        if (key.tail && key.head && key.reading) return true
        return remaining <= fadeMs + times.planLoadLeadMs + PRE_ROLL_MS + times.farCheckMs
    }

    private fun makePlan(
        deck: ExoPlayer,
        key: PlanKey,
        currentSong: FadeSong,
        nextSong: FadeSong,
        position: Long,
        atHead: Long,
        repeatOne: Boolean,
        stopAtEnd: Boolean,
        pace: PlaybackParameters,
        nextItem: MediaItem,
        incomingGainDb: Double,
    ) {
        val speed = pace.speed
        val heard = reading?.takeIf { it.entryId == key.current }?.value
        val served = tail?.takeIf { it.entryId == key.current }?.profile
        val (level, tempoPrior) = heardFor(served, heard?.takeIf { it.heardMs >= LEVEL_HEARD_MS }?.bodyLevelDb, heard?.tempoPrior)
        // "Now" is where a start could still be decided: the song at the
        // output's head plus the mixer's proposal lead, and one check's
        // interval more for the head moving on while the arm reaches the
        // mixer.
        val mixer = mixer
        val now = if (mixer != null) {
            (blendReplanNowMs(atHead.toDouble(), mixer.proposalLead, mixer.mixRate, speed.toDouble()) + times.nearCheckMs * speed).toLong()
        } else {
            position
        }
        val context = TransitionContext(
            nowMs = now,
            playedMs = heard?.let { (it.heardMs * speed).toLong() } ?: position,
            repeatOne = repeatOne,
            stopAtEndOfSong = stopAtEnd,
            pace = speed.toDouble(),
            skipSilence = deck.skipSilenceEnabled,
            currentGenre = deck.currentMediaItem?.mediaMetadata?.genre?.toString(),
            nextGenre = nextItem.mediaMetadata.genre?.toString(),
            bodyLevelDb = level,
            tempoPrior = tempoPrior,
            incomingGainDb = incomingGainDb,
        )
        val tailAnalysis = tail?.takeIf { it.entryId == key.current }?.analysis
        val headAnalysis = head?.takeIf { it.entryId == key.next }?.analysis
        val made = planner(currentSong, nextSong, tailAnalysis, headAnalysis, settings.atSpeed(speed), context)
        planKey = key
        plan = made
        if (made.kind == TransitionKind.GAPLESS) return
        outgoingGate = tailAnalysis?.let { analysis -> level?.let(analysis::withBodyLevel) ?: analysis }?.features?.gateDb
            ?: level?.let { max(SILENCE_FLOOR_DB, it - SILENCE_BELOW_BODY_DB) }
    }

    // The spare gets the same songs in the same shuffle order, parked just
    // before where the next song comes in (its start when there is no plan
    // yet). Its slot is parked in the mixer first, so nothing of it is heard
    // until the mixer starts it.
    private fun load(next: Int, plan: TransitionPlan?) {
        val deck = player.deck
        val from = plan?.parkAtMs() ?: 0L
        val slot = slotOf(spare)
        mixer?.park(slot, true)
        mixer?.resetContinuation(slot)
        moving(spare) {
            spare.setMediaItems(List(deck.mediaItemCount, deck::getMediaItemAt), next, from)
            // The same kind of order too, so songs added after the blend
            // still land where they are asked for.
            spare.setShuffleOrder(QueueShuffleOrder(shuffleOrderOf(deck)))
            spare.shuffleModeEnabled = deck.shuffleModeEnabled
            spare.repeatMode = deck.repeatMode
            // The listener's pace, so the blend does not jump in speed or
            // pitch; a tempo-matched song gets its rate times it.
            spare.playbackParameters = pacedFor(plan)
            spare.skipSilenceEnabled = deck.skipSilenceEnabled
            spare.volume = 1f
            spare.playWhenReady = false
            spare.prepare()
        }
        loadedFor = next
        loadedEntry = deck.getMediaItemAt(next).let { it.entryId ?: it.mediaId }
        loadedPlan = plan
    }

    // A new plan for a song already loaded: parked again where it now comes
    // in, unless it is there already (a move would make it load again).
    private fun repark(plan: TransitionPlan) {
        spare.playbackParameters = pacedFor(plan)
        if (needsRepark(spare.currentPosition, plan.parkAtMs())) moving(spare) { spare.seekTo(plan.parkAtMs()) }
        loadedPlan = plan
    }

    private fun pacedFor(plan: TransitionPlan?): PlaybackParameters {
        val pace = player.listenerPace
        val rate = plan?.beatMatchRate ?: return pace
        return PlaybackParameters((rate * pace.speed).toFloat(), pace.pitch)
    }

    // Registers the blend with the mixer under a new generation, then plays
    // the parked spare so its sound waits there for the start.
    private fun arm(p: TransitionPlan, deck: ExoPlayer, nextItem: MediaItem) {
        val mixer = mixer ?: return
        val g = nextGeneration++
        val current = deck.currentMediaItem
        val arm = BlendArm(
            generation = g,
            startMs = p.startMs.toDouble(),
            overlapMs = p.overlapMs.toDouble(),
            latestEndMs = p.latestEndMs.toDouble(),
            outgoingEndMs = lengthOf(deck).toDouble(),
            lateMoveBars = p.lateMoveBars,
            barMs = p.barMs(),
            holdShare = p.holdShare,
            speed = player.listenerPace.speed.toDouble(),
            bothHeardStartMs = p.bothHeardStartMs.toDouble(),
            bothHeardEndMs = p.bothHeardEndMs.toDouble(),
            lockMs = p.lockMs,
            driftGap = p.driftGap,
        )
        val blend = MixBlend(
            arm = arm,
            outgoingSlot = slotOf(deck),
            incomingSlot = slotOf(spare),
            plan = p,
            outgoing = DeckTransition(current?.entryId, p, incoming = false, gateDb = outgoingGate),
            incoming = DeckTransition(nextItem.entryId, p, incoming = true),
        )
        registered = g
        registeredPlan = p
        registeredNext = nextItem.entryId ?: nextItem.mediaId
        registeredFrom = current?.let { it.entryId ?: it.mediaId }
        mixer.commitWord.open(g)
        mixer.arm(blend)
        spare.play()
    }

    // The mixer proposed the start and main agreed: the player hands over to
    // the spare now, so everything from here lands on the song on screen.
    // The handover is marked first, so a listener writing inside it (a sleep
    // timer's end of song) never undoes it.
    private fun handOver(g: Long) {
        handedOver = g
        handedOverNext = registeredNext
        leaving = false
        val p = registeredPlan
        forgetRegistration()
        val from = player.deck
        val into = spare
        outgoing = from
        blending = p
        // Once the blend is over, the old deck is the spare for next time.
        spare = from
        loadedFor = C.INDEX_UNSET
        loadedEntry = null
        loadedPlan = null
        this.plan = null
        planKey = null
        p?.let { Log.i("Octo", "automix: ${it.reason}") }
        p?.beatMatchRate?.let { easing = Easing(into, into.currentMediaItem?.entryId, p, it) }
        player.handOver(into, from)
    }

    // Eases a tempo-matched song back to its own speed on its own song time,
    // toward the listener's pace read at every step, until the deck is no
    // longer that song.
    private fun easeRate() {
        val ease = easing ?: return
        if (ease.deck !== player.deck) {
            easing = null
            return
        }
        val pace = player.listenerPace
        if (ease.deck.currentMediaItem?.entryId != ease.entryId) {
            ease.deck.playbackParameters = pace
            easing = null
            return
        }
        val rate = blendIncomingRate(ease.rate, ease.deck.currentPosition.toDouble(), ease.plan.entryMs.toDouble(), ease.easeFromMs)
        if (rate == 1.0) {
            ease.applied = 1.0
            ease.deck.playbackParameters = pace
            easing = null
            return
        }
        if (abs(rate - ease.applied) >= RATE_STEP) {
            ease.applied = rate
            ease.deck.playbackParameters = PlaybackParameters((rate * pace.speed).toFloat(), pace.pitch)
        }
    }

    // The blend is over (the mixer said so, or the old song stopped or
    // failed): the old deck stops and becomes the spare. `easeFromMs` is
    // where the new song's easing starts; with none, it starts now.
    private fun finishFade(easeFromMs: Double = Double.NaN) {
        val from = outgoing ?: return
        val g = handedOver
        val plan = blending
        outgoing = null
        blending = null
        handedOver = -1
        handedOverNext = null
        leaving = false
        easing?.let { it.easeFromMs = if (easeFromMs.isNaN()) it.sinceEntryMs() else easeFromMs }
        if (plan != null && plan.overlapMs > 0 && !easeFromMs.isNaN()) {
            val progress = easeFromMs * plan.incomingRate() / plan.overlapMs
            if (progress < 0.95) Log.i("Octo", "automix: blend cut short at ${(progress.coerceIn(0.0, 1.0) * 100).toInt()}%")
        }
        mixer?.resetContinuation(slotOf(from))
        moving(from) {
            from.stop()
            from.clearMediaItems()
        }
        if (g >= 0) mixer?.outgoingStopped(g)
        lastPosition = -1
        player.endFade()
        idle()
        schedule(0)
    }

    // Forgets the plan.
    private fun dropPlan() {
        plan = null
        planKey = null
    }

    // An entry played again as a new play (a version picked for its song):
    // what was scouted or read of it goes, and the plan, so its ends are
    // read again from the new file.
    fun forgetEntry(entryId: String) {
        if (tail?.entryId == entryId) {
            tail?.job?.cancel()
            tail = null
        }
        if (head?.entryId == entryId) {
            head?.job?.cancel()
            head = null
        }
        if (reading?.entryId == entryId) {
            reading?.job?.cancel()
            reading = null
        }
        dropPlan()
        schedule(0)
    }

    private fun unloadSpare() {
        if (loadedFor == C.INDEX_UNSET) return
        loadedFor = C.INDEX_UNSET
        loadedEntry = null
        loadedPlan = null
        mixer?.resetContinuation(slotOf(spare))
        moving(spare) {
            spare.stop()
            spare.clearMediaItems()
        }
        idle()
    }

    // The upkeep waiting for the blend goes once nothing is under way, in one
    // message, checked again then.
    private fun idle() {
        if (busy || upkeep.isEmpty() || upkeepPosted) return
        upkeepPosted = true
        handler.post(runUpkeep)
    }

    private fun applyUpkeep() {
        upkeepPosted = false
        if (busy) return
        val writes = upkeep.toList()
        upkeep.clear()
        writes.forEach { it() }
        schedule(0)
    }

    // A move this makes on a deck, told to the mixer on the deck's own
    // playback thread before it and after it, so the deck's next new output
    // plays what it is told rather than going on by song time.
    private fun moving(deck: ExoPlayer, move: () -> Unit) {
        val mixer = mixer
        if (mixer == null) {
            move()
            return
        }
        val slot = slotOf(deck)
        announceMove(deck, mixer, slot)
        move()
        moveDone(deck, mixer, slot)
    }

    private fun slotOf(deck: ExoPlayer): Int = slots.indexOf(deck).coerceAtLeast(0)

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(check)
        // A song easing back to its own speed is looked at often.
        handler.postDelayed(check, if (easing != null) minOf(delayMs, times.nearCheckMs * 5) else delayMs)
    }
}

// Tells the mixer, on `deck`'s playback thread, that the app is about to
// move the deck; and that the move is done.
@OptIn(UnstableApi::class)
internal fun announceMove(deck: ExoPlayer, mixer: DeckMixer, slot: Int) {
    deck.createMessage { _, _ -> mixer.announce(slot) }.setLooper(deck.playbackLooper).send()
}

@OptIn(UnstableApi::class)
internal fun moveDone(deck: ExoPlayer, mixer: DeckMixer, slot: Int) {
    deck.createMessage { _, _ -> mixer.moveDone(slot) }.setLooper(deck.playbackLooper).send()
}

// The play order the deck is using right now, shuffle included.
internal fun shuffleOrderOf(player: Player): IntArray {
    val timeline = player.currentTimeline
    return buildList {
        var i = timeline.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET) {
            add(i)
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
    }.toIntArray()
}

private fun MediaItem.fadeSong(durationMs: Long): FadeSong {
    val extras = mediaMetadata.extras
    return FadeSong(
        albumId = extras?.getString(EXTRA_ALBUM_ID),
        albumOrder = extras?.getInt(EXTRA_ALBUM_ORDER, -1)?.takeIf { it >= 0 },
        durationMs = durationMs,
    )
}
