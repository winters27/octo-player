package app.winters.octo.playback

import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sound.GraphicBands
import app.winters.octo.sound.MixEvent
import app.winters.octo.sound.ReplayGainMode
import app.winters.octo.sound.SoundSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min

// The Crossfader's side of a blend in the one mixer: the handover on the
// mixer's proposal, the phase rule for edits, each deck's failures, the
// app's own queue upkeep waiting, writes that change nothing, the
// controller's volume and the listener's pace.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrossfaderMixTest {
    private val crossfade = AutomixSettings(maxOverlapMs = 1_500, smart = false)

    private fun twoSongs() = listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1), PcmSong("c", 2_500))

    @After
    fun close() = PlayerRig.closeAll()

    private fun handedOver(rig: PlayerRig, entry: String = "q:b", maxMs: Long = 10_000): Boolean =
        rig.runUntil(maxMs) { rig.transitions.lastOrNull() == entry }

    // Registered and the spare playing, not yet handed over.
    private fun armed(rig: PlayerRig): Boolean = rig.runUntil(6_000) { rig.decks[1].playWhenReady }

    private fun event(kind: app.winters.octo.playback.BlendEventKind, gen: Long) =
        MixEvent(kind, gen, 0, 0.0, 0.0, BlendCause.NONE)

    @Test
    fun theHandoverRunsOnceAndASleepTimerWritingInsideItTouchesOnlyTheNewSong() {
        val rig = PlayerRig(twoSongs())
        // The sleep timer's "after this song" for the new song, set as the
        // new song comes on screen.
        rig.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.entryId == "q:b") rig.player.pauseAtEndOfSong = true
            }
        })
        rig.play("a", "b", "c", settings = crossfade)
        assertTrue(handedOver(rig))
        assertTrue(rig.decks[1].pauseAtEndOfMediaItems)
        assertFalse(rig.decks[0].pauseAtEndOfMediaItems)
        // A second proposal for the generation handed over does nothing.
        rig.player.mixListener.onBlendEvent(event(BlendEventKind.PROPOSED, 1))
        assertTrue(rig.runUntil(6_000) { !rig.player.isBlending })
        assertEquals(listOf("q:a", "q:b"), rig.transitions)
        // The blend ran to its end: nothing cut or canceled it.
        val end = rig.events.first { it.kind == BlendEventKind.BLEND_END && it.generation == 1L }
        assertTrue("ended ${end.ms} ms in", end.ms > 1_000)
        assertFalse(rig.events.any { it.kind == BlendEventKind.CANCELLED })
        assertTrue(rig.runUntil(6_000) { !rig.player.playWhenReady })
        assertEquals("paused at the new song's end", Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM, rig.playReason)
    }

    @Test
    fun aProposalOfAGenerationAnEditUndidHandsNothingOver() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        assertTrue(armed(rig))
        rig.player.addMediaItems(listOf(rig.song("c").item()))
        assertEquals(CommitState.CANCELLED, rig.mix.mixer.commitWord.stateOf(1))
        assertEquals("the spare let go", Player.STATE_IDLE, rig.decks[1].playbackState)
        // A proposal already on its way to main.
        rig.player.mixListener.onBlendEvent(event(BlendEventKind.PROPOSED, 1))
        assertSame(rig.decks[0], rig.player.deck)
        assertEquals(listOf("q:a"), rig.transitions)
        // The edit landed on the song on screen.
        assertEquals(3, rig.decks[0].mediaItemCount)
    }

    @Test
    fun anEditInTheTurnBeforeTheProposalLandsOnTheOldSongAndAfterItOnTheNew() {
        // Before: the edit is handled first, so the start is undone.
        val before = PlayerRig(twoSongs())
        var edited = false
        before.mix.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED && !edited) {
                edited = true
                before.player.addMediaItems(listOf(before.song("c").item()))
            }
            before.player.mixListener.onBlendEvent(e)
        }
        before.play("a", "b", settings = crossfade)
        before.runUntil(6_000) { edited }
        before.runFor(20)
        assertEquals(3, before.decks[0].mediaItemCount)
        assertSame(before.decks[0], before.player.deck)
        assertFalse(before.events.any { it.kind == BlendEventKind.BLEND_END && it.generation == 1L })
        // After: the edit right after the handover lands on the new song.
        val after = PlayerRig(twoSongs())
        after.play("a", "b", settings = crossfade)
        assertTrue(handedOver(after))
        after.player.addMediaItems(listOf(after.song("c").item()))
        assertEquals(3, after.decks[1].mediaItemCount)
        assertSame(after.decks[1], after.player.deck)
    }

    @Test
    fun aNextSongThatFailsInTheSpareIsLoadedOnceAndReachedByThePlayingSongsOwnAdvance() {
        // The next song cannot be decoded (as an expired link fails).
        val rig = PlayerRig(listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1, failAtMs = 0)))
        // Made once for the playing deck's queue and once for the spare's
        // one load: never loaded into the spare again.
        rig.sources.most["b"] = 2
        rig.play("a", "b", settings = crossfade)
        assertTrue(rig.runUntil(9_000) { rig.decks[0].playerError != null })
        assertEquals(2, rig.sources.made["b"])
        assertFalse(rig.events.any { it.kind == BlendEventKind.PROPOSED })
        assertEquals(emptyList<String>(), rig.breaksOf(1))
        assertFalse(rig.anyOf(-1))
    }

    @Test
    fun thePlayingSongFailingBeforeTheHandoverUndoesTheStartAndNothingOfTheNextIsHeard() {
        // Its decoder fails about 2 s ahead of what is heard: inside the
        // run-up, before the start is proposed.
        val rig = PlayerRig(listOf(PcmSong("a", 6_000, failAtMs = 5_000), PcmSong("b", 3_000, sign = -1)))
        rig.play("a", "b", settings = crossfade)
        assertTrue(rig.runUntil(7_000) { rig.decks[0].playerError != null })
        rig.runFor(200)
        // A blend was registered when it failed, and its start was undone.
        val word = rig.mix.mixer.commitWord.get()
        assertTrue("${rig.told()} at ${rig.decks[0].currentPosition}", commitGenerationOf(word) >= 1)
        assertEquals(rig.told(), CommitState.CANCELLED, commitStateOf(word))
        assertEquals(listOf("q:a"), rig.transitions)
        assertSame(rig.decks[0], rig.player.deck)
        assertFalse(rig.anyOf(-1))
        assertEquals(0, rig.mixedCount())
    }

    @Test
    fun theAppsOwnUpkeepWaitsForTheBlendAndRunsOnceInOneGo() {
        val rig = PlayerRig(twoSongs())
        var ran = 0
        rig.player.upkeep { ran++ }
        assertEquals("nothing under way: at once", 1, ran)
        rig.play("a", "b", settings = crossfade)
        assertTrue(rig.runUntil(4_000) { rig.decks[1].playbackState != Player.STATE_IDLE })
        // A refill landing with the spare loaded adds a song at the end.
        rig.player.upkeep {
            ran++
            rig.player.addMediaItems(listOf(rig.song("c").item()))
        }
        assertEquals(1, ran)
        assertTrue(handedOver(rig))
        assertEquals("still waiting through the blend", 1, ran)
        assertTrue(rig.runUntil(6_000) { !rig.player.isBlending })
        rig.runFor(5)
        assertEquals(2, ran)
        // The blend was kept, and the song went in after it.
        assertTrue(rig.mixedCount() > 0)
        assertFalse(rig.events.any { it.kind == BlendEventKind.CANCELLED })
        assertEquals(3, rig.player.mediaItemCount)
    }

    // A blend set up and armed, then `write`: the blend still hands over and
    // runs to its end when the write changes nothing.
    private fun keepsTheBlend(write: (PlayerRig) -> Unit) {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        assertTrue(armed(rig))
        write(rig)
        assertEquals(CommitState.INITIAL, rig.mix.mixer.commitWord.stateOf(1))
        assertTrue(handedOver(rig))
        assertTrue(rig.runUntil(6_000) { !rig.player.isBlending })
        assertFalse(rig.events.any { it.kind == BlendEventKind.CANCELLED })
    }

    @Test
    fun repeatAndShuffleSetToWhatTheyAreKeepTheBlend() = keepsTheBlend {
        it.player.repeatMode = Player.REPEAT_MODE_OFF
        it.player.shuffleModeEnabled = false
    }

    @Test
    fun anEndOfSongWriteThatChangesNothingKeepsTheBlend() = keepsTheBlend {
        // The sleep timer's tick writes the end of song it already has.
        it.player.pauseAtEndOfSong = false
    }

    @Test
    fun theDecksOwnPlayOrderKeepsTheBlendAndAChangedOneUndoesIt() {
        keepsTheBlend { it.player.setPlayOrder(shuffleOrderOf(it.decks[0])) }
        // A changed play order is an edit: the run-up is undone.
        val again = PlayerRig(twoSongs())
        again.play("a", "b", settings = crossfade)
        assertTrue(armed(again))
        again.player.setPlayOrder(shuffleOrderOf(again.decks[0]).reversedArray())
        assertEquals(CommitState.CANCELLED, again.mix.mixer.commitWord.stateOf(1))
        assertEquals(Player.STATE_IDLE, again.decks[1].playbackState)
    }

    @Test
    fun aControllersVolumeIsHeardAtOnceAndReadsBackWithoutTheDuck() {
        val rig = PlayerRig(listOf(PcmSong("a", 4_000)))
        rig.play("a")
        rig.runFor(600)
        rig.player.volume = 0.5f
        rig.runFor(1)
        assertEquals(0.5f, rig.mix.lastTrack.volume, 1e-6f)
        val audio = rig.context.getSystemService(AudioManager::class.java)
        shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        rig.runFor(1)
        assertEquals(0.1f, rig.mix.lastTrack.volume, 1e-6f)
        // A controller reads its own level and writes it back: still ducked.
        assertEquals(0.5f, rig.player.volume, 1e-6f)
        rig.player.volume = rig.player.volume
        rig.runFor(1)
        assertEquals(0.1f, rig.mix.lastTrack.volume, 1e-6f)
        shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        rig.runFor(1)
        assertEquals(0.5f, rig.mix.lastTrack.volume, 1e-6f)
        rig.player.mute()
        rig.runFor(1)
        assertEquals(0f, rig.mix.lastTrack.volume, 1e-6f)
    }

    // Songs whose second one is long enough to ease back (5 s) after the blend.
    private fun longNext() = listOf(PcmSong("a", 5_000), PcmSong("b", 8_000, sign = -1), PcmSong("c", 2_500))

    // A tempo-matched blend over the last 1.5 s, at rate 0.97.
    private val matched: Planner = { current, _, _, _, _, _ ->
        TransitionPlan(
            current.durationMs - 1_500, 0, 1_500, TransitionKind.BLEND, BLEND_CURVE, 0.0, 0.97, null, null, false, 0.0, "rig",
            latestEndMs = current.durationMs, lateMoveBars = LATE_MOVE_UNBOUNDED,
        )
    }

    @Test
    fun aSpeedChangeMidBlendSetsTheOldSongToThePaceAndTheNewToItsRateTimesIt() {
        val contexts = ArrayList<TransitionContext>()
        val rig = PlayerRig(longNext(), planner = { a, b, t, h, s, c -> contexts += c; matched(a, b, t, h, s, c) })
        rig.play("a", "b", settings = AutomixSettings(maxOverlapMs = 1_500))
        assertTrue(handedOver(rig))
        assertEquals(0.97f, rig.decks[1].playbackParameters.speed, 1e-6f)
        rig.player.playbackParameters = PlaybackParameters(1.25f)
        assertEquals(1.25f, rig.decks[0].playbackParameters.speed, 1e-6f)
        assertEquals(0.97f * 1.25f, rig.decks[1].playbackParameters.speed, 1e-5f)
        assertEquals(1.25f, rig.player.listenerPace.speed, 1e-6f)
        // The new song eases back to the listener's pace, never compounding
        // its rate.
        assertTrue(
            "${rig.told()} at ${rig.decks[1].playbackParameters.speed}, blending ${rig.player.isBlending}",
            rig.runUntil(9_000) { rig.decks[1].playbackParameters.speed == 1.25f },
        )
        // The next plan is made at the listener's pace.
        val planned = contexts.size
        rig.player.addMediaItems(listOf(rig.song("c").item()))
        assertTrue(rig.runUntil(3_000) { contexts.size > planned })
        assertEquals(1.25, contexts.last().pace, 1e-9)
    }

    @Test
    fun aMatchedSongKeepsItsRateOnItsOwnSongTimeUntilTheBlendsEnd() {
        val rig = PlayerRig(longNext(), planner = matched)
        rig.play("a", "b", settings = AutomixSettings(maxOverlapMs = 1_500))
        assertTrue(handedOver(rig))
        // Paused mid-blend, the start already heard, for a while: the song's
        // own time stands still, so its rate does not move.
        rig.runFor(900)
        rig.player.pause()
        rig.runFor(2_000)
        assertEquals(0.97f, rig.decks[1].playbackParameters.speed, 1e-6f)
        rig.player.play()
        // Just after the resume it has eased only for the song time heard
        // since, nothing for the paused stretch.
        rig.runFor(100)
        assertEquals(0.97f, rig.decks[1].playbackParameters.speed, 0.004f)
        assertTrue(
            "${rig.told()} at ${rig.decks[1].playbackParameters.speed}, blending ${rig.player.isBlending}, " +
                "${rig.decks[1].currentPosition} ms, playing ${rig.decks[1].isPlaying}",
            rig.runUntil(7_000) { rig.decks[1].playbackParameters.speed == 1f },
        )
    }

    // A blend over 3 s, ending 2 s before the old song does, held in step by
    // a lock at its start with a 10% drift gap: at a new pace the drift
    // passes its limit 40 ms times the pace over the gap into the blend
    // (500 ms at 1.25), and the blend is cut there.
    private val locked: Planner = { current, _, _, _, _, _ ->
        TransitionPlan(
            current.durationMs - 5_000, 0, 3_000, TransitionKind.BLEND, BLEND_CURVE, 0.0, null, null, null, false, 0.0, "rig",
            latestEndMs = current.durationMs, lateMoveBars = LATE_MOVE_UNBOUNDED,
            bothHeardStartMs = 0, bothHeardEndMs = 3_000, lockMs = 0.0, driftGap = 0.1,
        )
    }

    // How long the blend lasts on the output, from the handover to its end,
    // with the listener's speed set to `speed` at the handover (left as it
    // is for none).
    private fun blendOutputMs(speed: Float?): Long {
        val rig = PlayerRig(listOf(PcmSong("a", 9_000), PcmSong("b", 6_000, sign = -1)), planner = locked)
        var endAt = -1L
        rig.mix.onEvent = { e ->
            val end = e.kind == BlendEventKind.BLEND_END || e.kind == BlendEventKind.CANCELLED
            if (end && endAt < 0) endAt = rig.mix.sched.now
            rig.player.mixListener.onBlendEvent(e)
        }
        rig.play("a", "b", settings = AutomixSettings(maxOverlapMs = 3_000))
        assertTrue(handedOver(rig))
        val from = rig.mix.sched.now
        speed?.let { rig.player.playbackParameters = PlaybackParameters(it) }
        assertTrue(rig.told(), rig.runUntil(6_000) { endAt >= 0 })
        return (endAt - from) / 1_000_000
    }

    @Test
    fun aSpeedChangeMidALockedBlendReachesTheMixerWhichCutsItShort() {
        val left = blendOutputMs(null)
        val faster = blendOutputMs(1.25f)
        // Cut once the new pace is heard: over a second sooner, where playing
        // the rest of it at 1.25 saves only a fifth of what is left.
        assertTrue("$faster ms at 1.25 for $left ms left alone", faster < left - 1_000)
    }

    @Test
    fun aFixedCrossfadeRefusedAsLateIsNotArmedAgainAndTheSongPlaysOnIntoItsNext() {
        // Every plan starts 300 ms inside the mixer's proposal lead and may
        // not move: too late to start, and planned a moment later it is the
        // same transition again.
        val tooLate: Planner = { a, b, t, h, s, c -> planTransition(a, b, t, h, s, c).let { it.copy(startMs = c.nowMs - 300, lateMoveBars = 0) } }
        val rig = PlayerRig(twoSongs(), planner = tooLate)
        rig.play("a", settings = crossfade)
        assertTrue(rig.runUntil(5_000) { rig.player.currentPosition >= 3_500 })
        rig.player.addMediaItems(listOf(rig.song("b").item()))
        assertTrue(rig.runUntil(2_000) { rig.events.any { it.kind == BlendEventKind.INCOMING_NOT_READY } })
        assertEquals(BlendCause.LATE, rig.events.first { it.kind == BlendEventKind.INCOMING_NOT_READY }.cause)
        rig.runFor(200)
        assertEquals("armed once, never again: ${rig.told()}", null, rig.mix.mixer.commitWord.stateOf(2))
        assertEquals("the spare let go", Player.STATE_IDLE, rig.decks[1].playbackState)
        // The song plays on into its next, gapless.
        assertTrue(rig.runUntil(4_000) { rig.transitions.lastOrNull() == "q:b" })
        assertEquals(0, rig.mixedCount())
    }

    @Test
    fun withHalfASecondOfOutputLatencyANextSongAddedLateIsPlannedFromTheHeadAndBlends() {
        // The fake presents each cycle 13 cycles (520 ms) after it takes it.
        // A planner that starts no earlier than the now it is given, the
        // blend shortened to end by the latest end.
        val made = ArrayList<String>()
        val fromNow: Planner = { a, b, t, h, s, c ->
            val p = planTransition(a, b, t, h, s, c)
            val start = max(p.startMs, c.nowMs)
            p.copy(startMs = start, overlapMs = min(p.overlapMs, p.latestEndMs - start)).also { made += "now ${c.nowMs} start ${it.startMs} for ${it.overlapMs}" }
        }
        val rig = PlayerRig(twoSongs(), latencyCycles = 13, planner = fromNow)
        rig.play("a", settings = crossfade)
        // Heard 2.5 s in, the output's head is half a second further on: a
        // start planned from the heard position would already be late there.
        assertTrue(rig.runUntil(5_000) { rig.player.currentPosition >= 2_500 })
        rig.player.addMediaItems(listOf(rig.song("b").item()))
        assertTrue("${rig.told()} ${rig.notes}", rig.runUntil(4_000) { rig.transitions.lastOrNull() == "q:b" })
        assertTrue("none refused as late: ${rig.events.map { "${it.kind}/${it.cause}@${it.frame}" }}, plans $made", rig.events.none { it.cause == BlendCause.LATE })
        // Heard half a second after the head: the blend itself.
        rig.runFor(2_000)
        assertTrue("blended: ${rig.told()}", rig.mixedCount() > 0)
    }

    @Test
    fun anUntaggedNextSongAfterOneTagged8DbDownIsPlanned8DbUpWithReplayGainOnTrackAndLevelWithItOff() {
        for ((mode, want) in listOf(ReplayGainMode.Track to 8.0, ReplayGainMode.Off to 0.0)) {
            val contexts = ArrayList<TransitionContext>()
            val rig = PlayerRig(
                listOf(PcmSong("a", 5_000, trackGainDb = -8f), PcmSong("b", 2_500, sign = -1)),
                planner = { a, b, t, h, s, c ->
                    contexts += c
                    planTransition(a, b, t, h, s, c)
                },
                sound = { SoundSettings(replayGain = mode) },
            )
            rig.play("a", settings = crossfade)
            // Planned once the playing song's loudness is taken up.
            rig.runFor(1_000)
            rig.player.addMediaItems(listOf(rig.song("b").item()))
            assertTrue("$mode: planned", rig.runUntil(1_000) { contexts.isNotEmpty() })
            assertEquals("$mode", want, contexts.last().incomingGainDb, 1e-3)
            rig.close()
        }
    }

    // Plays `songs[0]`, adds the rest a second in, and returns every plan's
    // context once the spare has opened the next song.
    private fun gainsPlanned(songs: List<PcmSong>, next: List<MediaItem>, settings: () -> SoundSettings, during: (PlayerRig) -> Unit = {}): List<Double> {
        val contexts = ArrayList<TransitionContext>()
        val rig = PlayerRig(
            songs,
            planner = { a, b, t, h, s, c ->
                contexts += c
                planTransition(a, b, t, h, s, c)
            },
            sound = settings,
        )
        rig.play(songs[0].id, settings = crossfade)
        rig.runFor(1_000)
        rig.player.addMediaItems(next)
        assertTrue("planned", rig.runUntil(1_000) { contexts.isNotEmpty() })
        during(rig)
        rig.runFor(400)
        return contexts.map { it.incomingGainDb }
    }

    @Test
    fun theGainBetweenTheSongsIsAsTheirDecksApplyIt() {
        // Each song's own sound is tagged, nothing stored: A's level is what
        // its deck applies, B's its stored one (none) until the spare opens
        // it, then its own tag.
        val songs = listOf(PcmSong("a", 5_000, streamGainDb = -8f), PcmSong("b", 2_500, sign = -1, streamGainDb = -3f))
        val gains = gainsPlanned(songs, listOf(songs[1].item()), { SoundSettings(replayGain = ReplayGainMode.Track) })
        assertEquals("before the spare opens it: $gains", 8.0, gains.first(), 1e-3)
        assertEquals("planned again once it has: $gains", 5.0, gains.last(), 1e-3)
    }

    @Test
    fun theSameSongNextKeepsThePlayingOnesAlbumRule() {
        // Smart: the song first in its album plays its track gain, and played
        // again next it is still not following its album.
        val a = PcmSong("a", 5_000, albumId = "x", trackGainDb = -8f, albumGainDb = -2f)
        val gains = gainsPlanned(listOf(a), listOf(a.item(entry = "q:a2")), { SoundSettings(replayGain = ReplayGainMode.Smart) })
        assertEquals("$gains", 0.0, gains.first(), 1e-3)
        assertEquals("$gains", 0.0, gains.last(), 1e-3)
    }

    @Test
    fun theEqPreampIsTakenAtEachSongsOwnRate() {
        // A 6 dB boost at 16 kHz: above a 22.05 kHz song's highest frequency,
        // so its preamp takes back less than a 48 kHz song's.
        val eq = SoundSettings(eqEnabled = true, graphicGains = List(GraphicBands.size) { if (it == GraphicBands.lastIndex) 6f else 0f })
        val want = (eq.effectivePreampDb(48_000) - eq.effectivePreampDb(22_050)).toDouble()
        assertTrue("the preamps differ: $want", kotlin.math.abs(want) > 0.5)
        val songs = listOf(PcmSong("a", 5_000, rate = 22_050), PcmSong("b", 2_500, sign = -1))
        val gains = gainsPlanned(songs, listOf(songs[1].item()), { eq })
        assertEquals("$gains", want, gains.last(), 0.01)
    }

    @Test
    fun aReplayGainChangePlansTheBlendAgainWithTheNewGain() {
        var mode = ReplayGainMode.Off
        val songs = listOf(PcmSong("a", 5_000, trackGainDb = -8f), PcmSong("b", 2_500, sign = -1))
        val gains = gainsPlanned(songs, listOf(songs[1].item()), { SoundSettings(replayGain = mode) }) { rig ->
            // Registered, the next song's tags read: only the setting changes.
            assertTrue(rig.runUntil(1_000) { rig.decks[1].playWhenReady })
            rig.runFor(300)
            mode = ReplayGainMode.Track
        }
        assertEquals("$gains", 0.0, gains.first(), 1e-3)
        assertEquals("planned again at the new gain: $gains", 8.0, gains.last(), 1e-3)
    }

    @Test
    fun aStartTheMixerCouldNotMakeIsPlannedAgainWithTheSpareKept() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        assertTrue(armed(rig))
        val made = rig.sources.made["b"]
        rig.player.mixListener.onBlendEvent(MixEvent(BlendEventKind.INCOMING_NOT_READY, 1, 0, 0.0, 0.0, BlendCause.NOT_READY))
        assertTrue("armed again", rig.runUntil(2_000) { rig.mix.mixer.commitWord.stateOf(2) != null })
        assertEquals("the spare stayed loaded", made, rig.sources.made["b"])
        assertTrue(handedOver(rig))
        assertNotNull(rig.events.firstOrNull { it.kind == BlendEventKind.BLEND_END && it.generation == 2L } ?: run { rig.runUntil(5_000) { rig.events.any { it.kind == BlendEventKind.BLEND_END } }; rig.events.firstOrNull { it.kind == BlendEventKind.BLEND_END } })
    }
}
