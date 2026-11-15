package app.winters.octo.playback

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlaybackLooperProvider
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import app.winters.octo.sound.DeckMixer
import app.winters.octo.sound.DeckSound
import app.winters.octo.sound.MixListener
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// One engine that plays audio. Crossfade runs two of these; audio focus is
// handled above them, so each deck ignores it. `sources` opens both phone
// files and streams, `renderers` puts Octo's sound shaping in the audio
// path, and both decks play in one audio session, so an equalizer app
// attached to it hears both. `loads` decides when a stream has enough to
// start. A deck that plays into Octo's mixer may hold its sound for a long
// while (parked before a blend), and the mixer has its own check on a deck
// that stops taking sound, so the player's own check is off.
@OptIn(UnstableApi::class)
fun buildDeck(
    context: Context,
    sources: MediaSource.Factory,
    renderers: RenderersFactory,
    audioSessionId: Int,
    loads: OctoLoadControl,
    // Where the deck's playback runs (its own thread unless a test says).
    playbackLooper: PlaybackLooperProvider = PlaybackLooperProvider(),
    // How long a release waits for that thread to let go. A deck playing
    // on its caller's own thread waits none: the release runs there next.
    releaseTimeoutMs: Long = ExoPlayer.DEFAULT_RELEASE_TIMEOUT_MS,
): ExoPlayer =
    ExoPlayer.Builder(context, renderers, sources)
        .setPlaybackLooperProvider(playbackLooper)
        .setReleaseTimeoutMs(releaseTimeoutMs)
        .setLoadControl(loads)
        .setAudioAttributes(deckAudioAttributes(), /* handleAudioFocus = */ false)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
        .build()
        .also { if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) it.audioSessionId = audioSessionId }

// What the decks play as, and so the mix output too: music.
fun deckAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

// Asks the phone for the right to play, and hears when a call, a
// navigation prompt or another app wants it back.
class AudioFocus(context: Context, private val onChange: (Int) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            PlatformAudioAttributes.Builder()
                .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener({ onChange(it) }, Handler(Looper.getMainLooper()))
        .build()
    private var held = false

    fun request(): Boolean {
        if (!held) held = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    fun abandon() {
        if (held) audio.abandonAudioFocusRequest(request)
        held = false
    }
}

private const val DUCKED_VOLUME = 0.2f

// A mark of this run of the app, made once per process, in the queue entry
// ids the player gives out (see OctoPlayer.stamped).
internal val QUEUE_RUN: String = java.util.UUID.randomUUID().toString().substring(0, 8)

// Whether a replacement keeps every item (by id and queue entry) where it
// was, so only its words change.
internal fun wordsOnly(before: List<Pair<String, String?>>, after: List<Pair<String, String?>>): Boolean =
    before.isNotEmpty() && before == after && after.all { it.second != null }

// Whether the person still wants music: playing or about to (a rebuffer
// included), and not at the end of the queue or stopped.
internal fun wantedMusic(playWhenReady: Boolean, playbackState: Int): Boolean =
    playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE

// The one player the rest of the phone sees: the lock screen, the
// notification, headphones and the app all talk to this. It forwards to the
// deck that is playing, and hands over to the other deck for a crossfade
// without anything outside noticing. It also owns audio focus.
//
// `soundOf` gives each deck's sound processor, which reads what the song
// sounds like; `scout` reads ahead in songs to plan transitions, and
// `profiles` gives the server's transition profiles of songs in its place.
// With `mixer`, both decks play into Octo's one mix output (the first deck
// on its slot 0, the spare on slot 1), which runs the blends, and this
// tells it the player's own pauses, holds, moves and volume.
@OptIn(UnstableApi::class)
class OctoPlayer(
    context: Context,
    initial: ExoPlayer,
    spare: ExoPlayer,
    soundOf: (ExoPlayer) -> DeckSound? = { null },
    scout: Scout? = null,
    profiles: ProfileSource? = null,
    lengths: RealLengths = RealLengths(),
    private val mixer: DeckMixer? = null,
    times: FadeTimes = FadeTimes(),
    planner: Planner = ::planTransition,
) : ForwardingSimpleBasePlayer(initial), SleepTarget, EditableQueue {
    var deck: ExoPlayer = initial
        private set

    private var ducked = false

    // During a crossfade: the deck playing out the old song.
    private var outgoing: ExoPlayer? = null

    // Both decks, whichever is playing, so speed and skipping silence can be
    // set on each and a crossfade hands over at the same pace. A deck's
    // place here is its slot in the mixer.
    private val decks = listOf(initial, spare)

    private val fader = Crossfader(this, spare, soundOf, scout, profiles, lengths, mixer, decks, times, planner).also(::addListener)

    // What the mixer's events go to.
    val mixListener: MixListener get() = fader

    // The next queue entry id to give out.
    private var nextEntry = 0L

    init {
        // Songs put into a shuffled queue land where they were asked for,
        // never at random.
        decks.forEach { it.setShuffleOrder(QueueShuffleOrder(IntArray(0))) }
    }

    // Whether a crossfade is under way, so a song change now is the song
    // ending on its own.
    override val isBlending: Boolean get() = outgoing != null

    override val currentKey: String? get() = currentMediaItem?.entryId

    override fun hasEntry(key: String): Boolean = (0 until mediaItemCount).any { getMediaItemAt(it).entryId == key }

    // The app's own queue upkeep waits while a blend is under way or being
    // set up, then runs once in one go.
    override fun upkeep(write: () -> Unit) {
        if (fader.busy) fader.deferUpkeep(write) else write()
    }

    // How transitions between songs are made: the longest blend (0 for
    // none) and how it is planned.
    var automix: AutomixSettings
        get() = fader.settings
        set(value) {
            fader.settings = value
        }

    // The longest blend, or 0 for none.
    val crossfadeMs: Long get() = fader.fadeMs

    // The listener's own speed and pitch, whatever a deck plays at (a
    // tempo-matched song easing back plays at its rate times this).
    var listenerPace: PlaybackParameters = PlaybackParameters.DEFAULT
        private set

    // Skips quiet stretches inside songs, on both decks.
    var skipSilence: Boolean
        get() = deck.skipSilenceEnabled
        set(value) {
            decks.forEach { it.skipSilenceEnabled = value }
        }

    override var sleepFade = 1f
        set(value) {
            field = value
            applyVolume()
        }

    // The level a media controller set (and mute), reported back as the
    // player's volume.
    private var controllerVolume = 1f

    // The person's "pause at the end of this song", on the song on screen.
    // A write that changes nothing (the sleep timer's tick) is skipped.
    override var pauseAtEndOfSong: Boolean
        get() = deck.pauseAtEndOfMediaItems
        set(value) {
            if (value == deck.pauseAtEndOfMediaItems) return
            fader.endOfSongChanged { deck.pauseAtEndOfMediaItems = value }
        }

    private var resumeWhenFocusReturns = false
    private val focus: AudioFocus = AudioFocus(context) { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app is playing now; stay paused.
                resumeWhenFocusReturns = false
                pause()
                releaseFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // A call or a short interruption: pause, and come back after,
                // when the music was still wanted (a rebuffer included).
                resumeWhenFocusReturns = wantedMusic(deck.playWhenReady, deck.playbackState) || resumeWhenFocusReturns
                pausing()
                deck.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                duck(false)
                if (resumeWhenFocusReturns) {
                    // A station comes back live, not from where it paused.
                    if (onStation) rejoin()
                    mixer?.wantPlaying(true)
                    deck.play()
                }
                resumeWhenFocusReturns = false
            }
        }
    }

    // The one pause path, for the person's pause and a focus loss alike:
    // the mixer's output goes quiet at once, then any blend ends by the
    // phase rule, so whatever it clears is cleared unheard.
    private fun pausing() {
        mixer?.wantPlaying(false)
        mixer?.pause(false)
        fader.interrupt(BlendDisarm.PAUSE)
        fader.followUp()
    }

    // Playing needs focus; if the phone says no (a call is on), stay paused.
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && !focus.request()) return Futures.immediateVoidFuture()
        if (!playWhenReady) {
            resumeWhenFocusReturns = false
            pausing()
        } else {
            mixer?.wantPlaying(true)
        }
        if (onStation) {
            // A station has no pause: it stops, and lets the stream go.
            if (!playWhenReady) {
                moving(deck) {
                    deck.pause()
                    deck.stop()
                }
                mixer?.resetContinuation(slotOf(deck))
                releaseFocus()
                return Futures.immediateVoidFuture()
            }
            // Play joins what is on air now. Audio held from before a pause
            // (headphones pulled out, say) or a stopped stream is let go and
            // the stream opened afresh; one just opened plays as it is.
            if (!deck.playWhenReady && deck.playbackState != Player.STATE_BUFFERING) rejoin()
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    // A stopped station opens at the live edge, never where it stopped. A
    // deck opened from idle plays what it is told.
    override fun handlePrepare(): ListenableFuture<*> {
        if (reopening || deck.playbackState != Player.STATE_IDLE) return super.handlePrepare()
        if (onStation) deck.seekToDefaultPosition()
        mixer?.resetContinuation(slotOf(deck))
        return moving(deck) { super.handlePrepare() }
    }

    // Opens the deck again on the song it is on, for a stream the server
    // began sending anew: the sound goes on by song time, nothing repeated.
    private var reopening = false

    fun reopen() {
        reopening = true
        try {
            prepare()
        } finally {
            reopening = false
        }
    }

    // Plays the entry on again from its start as a new play, for a version
    // picked for its song. Inside one releasing edit: a blend ends, a spare
    // holding a song is let go, the deck stops (its readers, buffer and
    // decoder go), `fence` runs while nothing reads the song (it lets its
    // saved copies go once more), what was scouted and planned of the entry
    // is forgotten, and the deck opens the song again at 0; then it plays.
    // False, changing nothing, when another entry is on.
    fun restartFresh(entryId: String, fence: () -> Unit): Boolean {
        if (currentMediaItem?.entryId != entryId) return false
        val index = deck.currentMediaItemIndex
        edit(releasing = true) {
            mixer?.resetContinuation(slotOf(deck))
            moving(deck) {
                deck.stop()
                fence()
                fader.forgetEntry(entryId)
                deck.seekTo(index, 0)
                deck.prepare()
            }
        }
        play()
        return true
    }

    // The songs whose sound may be playing: the one on, and the one a blend
    // plays out. On the player's thread.
    fun playingItems(): List<MediaItem> = listOfNotNull(deck.currentMediaItem, outgoing?.currentMediaItem)

    // Told when a blend begins or ends, which changes what plays.
    var onPlayingChanged: () -> Unit = {}

    // Whether a live station is on.
    private val onStation: Boolean get() = deck.currentMediaItem?.mediaId?.let(::isRadio) == true

    // Opens the station's stream afresh, at the live edge.
    private fun rejoin() {
        mixer?.resetContinuation(slotOf(deck))
        moving(deck) {
            if (deck.playbackState != Player.STATE_IDLE) deck.stop()
            deck.seekToDefaultPosition()
            deck.prepare()
        }
    }

    // Anything that changes what plays or where ends a blend first, so the
    // old song never keeps sounding under the new choice; one that lets the
    // deck's output go (a seek, new songs, a stop) has the mixer hold its
    // output first while a handed-over blend's sound is in it.
    private fun <T> edit(releasing: Boolean = false, write: () -> T): T {
        val held = fader.beginEdit(BlendDisarm.EDIT, releasing)
        val on = deck
        try {
            return write()
        } finally {
            if (held) holdDone(on)
            fader.endEdit()
        }
    }

    // A seek the player would skip as changing nothing: predicted only
    // while paused, where the position read here is the playback thread's.
    // Tests can force the guess.
    internal var noOpSeekGuess: Boolean? = null

    private fun noOpSeek(mediaItemIndex: Int, positionMs: Long): Boolean =
        noOpSeekGuess ?: (!deck.playWhenReady && mediaItemIndex == deck.currentMediaItemIndex && positionMs == deck.currentPosition)

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val releasing = !noOpSeek(mediaItemIndex, positionMs)
        return edit(releasing) { moving(deck) { super.handleSeek(mediaItemIndex, positionMs, seekCommand) } }
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> =
        edit(releasing = true) {
            mixer?.resetContinuation(slotOf(deck))
            moving(deck) { super.handleSetMediaItems(stamped(mediaItems), startIndex, startPositionMs) }
        }

    // Songs the listener adds at the end go before any Autoplay songs still
    // to come, since those only fill in once the listener's own run out.
    // Under shuffle, added songs take their place in the play order by the
    // rules in ShuffleQueue.kt instead of a random one.
    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = edit {
        val size = deck.mediaItemCount
        if (size == 0 || mediaItems.isEmpty()) return@edit super.handleAddMediaItems(index, stamped(mediaItems))
        val order = shuffleOrderOf(deck)
        val current = deck.currentMediaItemIndex
        val autoplay = List(size) { deck.getMediaItemAt(it).isAutoplay }
        val adding = mediaItems.all { it.isAutoplay }
        val next = playingNext
        val at = if (adding || next) index else ownSongsAt(index, upcoming(deck), autoplay)
        val beforeAutoplay = !adding && autoplay.getOrElse(at) { false }
        val rank = shuffleRankFor(order, at, size, current, playNext = next, beforeAutoplay = beforeAutoplay)
        val result = super.handleAddMediaItems(at, stamped(mediaItems))
        if (deck.mediaItemCount == size + mediaItems.size) {
            deck.setShuffleOrder(QueueShuffleOrder(insertIntoShuffle(order, at, mediaItems.size, rank)))
        }
        result
    }

    // "Play next": the songs go right after the song that is on, in the
    // queue and, under shuffle, in the play order too. Told apart from "Add
    // to queue", since both add at the end while the last song is on.
    private var playingNext = false

    override fun addNext(items: List<MediaItem>) {
        if (items.isEmpty()) return
        playingNext = true
        try {
            addMediaItems((currentMediaItemIndex + 1).coerceAtMost(mediaItemCount), items)
        } finally {
            playingNext = false
        }
    }

    // Sets the play order for shuffle, as saved or as it was before an edit.
    // An order that is already the deck's changes nothing and is skipped.
    override fun setPlayOrder(order: IntArray) {
        if (order.size != deck.mediaItemCount || order.contentEquals(shuffleOrderOf(deck))) return
        edit { deck.setShuffleOrder(QueueShuffleOrder(order)) }
    }

    // Puts songs back where they were, exactly, entry ids and all, for an
    // undo. `runs` are queue positions and the songs that go there, lowest
    // first; `order` is the play order once they are all back.
    override fun putBack(runs: List<Pair<Int, List<MediaItem>>>, order: IntArray) = edit {
        runs.forEach { (at, items) -> deck.addMediaItems(at, items) }
        setPlayOrder(order)
    }

    // Takes out runs of songs, last run first. The rest keep their play order.
    override fun removeRuns(runs: List<IntRange>) {
        val current = deck.currentMediaItemIndex
        edit(releasing = runs.any { current in it }) { runs.forEach { deck.removeMediaItems(it.first, it.last + 1) } }
    }

    // Each song gets a queue entry id as it goes in, unless it has one, and
    // a server song carries it in its address too, so every request of its
    // play names the play (see Streams). An id given out here carries this
    // run's own mark: the server's record of plays and PlayRestarts both
    // outlive the app, so a number counted again from 0 next run must never
    // name an old play.
    private fun stamped(items: List<MediaItem>): List<MediaItem> =
        items.map { (if (it.entryId != null) it else it.withEntry("q:$QUEUE_RUN:${nextEntry++}")).withPlayEntry() }

    // Taking out the song on screen lets the deck's output go; clearing the
    // queue also means nothing on the deck goes on by song time.
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val current = deck.currentMediaItemIndex
        val all = fromIndex <= 0 && toIndex >= deck.mediaItemCount
        return edit(releasing = current in fromIndex until toIndex) {
            if (all) mixer?.resetContinuation(slotOf(deck))
            if (current in fromIndex until toIndex) moving(deck) { super.handleRemoveMediaItems(fromIndex, toIndex) } else super.handleRemoveMediaItems(fromIndex, toIndex)
        }
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        edit { super.handleMoveMediaItems(fromIndex, toIndex, newIndex) }

    // Items put back with new words only (a station's song title, say) are
    // the same items in the same places: a blend under way goes on.
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val before = (fromIndex until toIndex.coerceAtMost(deck.mediaItemCount)).map { deck.getMediaItemAt(it).let { item -> item.mediaId to item.entryId } }
        if (wordsOnly(before, mediaItems.map { it.mediaId to it.entryId })) return super.handleReplaceMediaItems(fromIndex, toIndex, stamped(mediaItems))
        val current = deck.currentMediaItemIndex
        return edit(releasing = current in fromIndex until toIndex) { super.handleReplaceMediaItems(fromIndex, toIndex, stamped(mediaItems)) }
    }

    // Shuffle and repeat set to what they already are change nothing: a
    // controller sending them again keeps the blend and the queue's order.
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled == deck.shuffleModeEnabled) return Futures.immediateVoidFuture()
        return edit { super.handleSetShuffleModeEnabled(shuffleModeEnabled) }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        if (repeatMode == deck.repeatMode) return Futures.immediateVoidFuture()
        return edit { super.handleSetRepeatMode(repeatMode) }
    }

    // Speed and pitch are the listener's: both decks play at them, except
    // that a tempo-matched song keeps its rate times them.
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        listenerPace = playbackParameters
        fader.paceChanged(playbackParameters)
        return Futures.immediateVoidFuture()
    }

    // A media controller's level (and mute): one level for the player,
    // with ducking and the sleep timer's fade on top.
    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        controllerVolume = if (volume.isFinite()) volume.coerceIn(0f, 1f) else controllerVolume
        applyVolume()
        return Futures.immediateVoidFuture()
    }

    // The player's volume is the controller's own level, never the deck's
    // product with ducking and the sleep fade, so a controller that reads
    // it and writes it back keeps no duck.
    override fun getState(): State = super.getState().buildUpon().setVolume(controllerVolume).build()

    override fun handleStop(): ListenableFuture<*> = edit(releasing = true) {
        releaseFocus()
        mixer?.resetContinuation(slotOf(deck))
        mixer?.stop()
        moving(deck) { super.handleStop() }
    }

    override fun handleRelease(): ListenableFuture<*> {
        fader.release()
        releaseFocus()
        return super.handleRelease()
    }

    // The blend begins: the incoming deck, already playing in the mixer,
    // becomes the one everything talks to, taking its play state as it is,
    // while the outgoing one keeps sounding there.
    internal fun handOver(into: ExoPlayer, from: ExoPlayer) {
        outgoing = from
        deck = into
        applyVolume()
        setPlayer(into)
        onPlayingChanged()
    }

    internal fun endFade() {
        outgoing = null
        applyVolume()
        onPlayingChanged()
    }

    private fun duck(on: Boolean) {
        ducked = on
        applyVolume()
    }

    // The one place the player's volume is set, so the controller's level,
    // ducking and the sleep timer's fade multiply instead of undoing each
    // other. With the mixer it is one level for the one output, so both
    // decks always change together; each deck stays at full.
    private fun applyVolume() {
        val level = controllerVolume * (if (ducked) DUCKED_VOLUME else 1f) * sleepFade
        if (mixer != null) {
            mixer.setVolume(level)
            return
        }
        deck.volume = level
        outgoing?.volume = level
    }

    // A move the player makes on a deck, told to the mixer on the deck's own
    // playback thread before and after it.
    private fun <T> moving(on: ExoPlayer, move: () -> T): T {
        val mixer = mixer ?: return move()
        val slot = slotOf(on)
        announceMove(on, mixer, slot)
        try {
            return move()
        } finally {
            moveDone(on, mixer, slot)
        }
    }

    // A hold the mixer was asked for ends once the deck's operation behind
    // it has run, on the deck's playback thread after it.
    private fun holdDone(on: ExoPlayer) {
        val mixer = mixer ?: return
        on.createMessage { _, _ -> mixer.holdDone() }.setLooper(on.playbackLooper).send()
    }

    private fun slotOf(on: ExoPlayer): Int = decks.indexOf(on).coerceAtLeast(0)

    private fun releaseFocus(): Unit = focus.abandon()
}
