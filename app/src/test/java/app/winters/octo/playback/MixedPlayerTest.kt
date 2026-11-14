package app.winters.octo.playback

import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// The phone's player with both decks playing into the one mixer, through
// the app's own deck builder and renderers: plain playback, gapless albums,
// Crossfade, pause, seek, focus, an unplug, the end of a song, Cast and a
// route change each play as 1.12.0 played them, every frame once and in
// order. A new deck output starts with Media3's own 20 ms ramp-up and a
// resumed output with the mixer's 20 ms onset ramp, so a frame at a ramp's
// very start is heard at no level.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MixedPlayerTest {
    private val crossfade = AutomixSettings(maxOverlapMs = 1_500, smart = false)

    private fun twoSongs() = listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1))

    private fun threeSongs() = twoSongs() + PcmSong("c", 2_500, flip = true)

    // Every plan starts 300 ms inside the mixer's proposal lead and may not
    // move: the mixer refuses it as late.
    private val tooLate: Planner = { a, b, t, h, s, c -> planTransition(a, b, t, h, s, c).let { it.copy(startMs = c.nowMs - 300, lateMoveBars = 0) } }

    // The most frames of the old place a move may still play: what the
    // output already took (its pause cycles and latency).
    private val moveFrames = 9_600

    @After
    fun close() = PlayerRig.closeAll()

    // Every frame of the song with `sign` heard, from its first to its last,
    // none twice and none skipped.
    private fun assertWhole(rig: PlayerRig, sign: Int, song: PcmSong) {
        assertEquals("breaks, ${rig.notes}", emptyList<String>(), rig.breaksOf(sign))
        val frames = rig.framesOf(sign)
        assertTrue("first ${frames.first()}", frames.first() <= 1)
        assertEquals(((song.frames - 1) % SAW).toInt(), frames.last())
    }

    // Runs until the player has handed over to `entry`; the heard frames at
    // that moment.
    private fun untilHandover(rig: PlayerRig, entry: String = "q:b", maxMs: Long = 10_000): Int {
        assertTrue("handed over", rig.runUntil(maxMs) { rig.transitions.lastOrNull() == entry })
        return rig.heardCount
    }

    // Within 20 ms of a saw frame, either way (a seek lands in its 10 ms
    // piece).
    private fun near(frame: Int, target: Int): Boolean = minOf(Math.floorMod(frame - target, SAW), Math.floorMod(target - frame, SAW)) <= 960

    private fun focusChange(rig: PlayerRig, change: Int) {
        val audio = rig.context.getSystemService(AudioManager::class.java)
        shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(change)
        rig.runFor(1)
    }

    @Test
    fun aSongPlaysEveryFrameInOrderToItsEnd() {
        val rig = PlayerRig(listOf(PcmSong("a", 2_000)))
        rig.play("a")
        assertTrue("ended", rig.runUntil(5_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertWhole(rig, 1, rig.song("a"))
        assertEquals(listOf(Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED), rig.states)
        assertEquals(0, rig.mixedCount())
    }

    @Test
    fun aGaplessAlbumGoesFromMonoToStereoWithNoGapAndNoOverlap() {
        val a = PcmSong("a", 2_000, channels = 1, albumId = "x", albumOrder = 1)
        val b = PcmSong("b", 2_000, sign = -1, albumId = "x", albumOrder = 2)
        val rig = PlayerRig(listOf(a, b))
        rig.play("a", "b", settings = crossfade)
        assertTrue("ended", rig.runUntil(8_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertEquals(listOf("q:a", "q:b"), rig.transitions)
        val songs = rig.heardSongs()
        // The mono song is read at full level, so from past its first
        // output's 20 ms ramp-up.
        val firstMono = songs.indexOfFirst { it.first == MONO }
        assertEquals(emptyList<String>(), rig.breaksOf(MONO, from = firstMono + 960))
        assertEquals(((a.frames - 1) % SAW).toInt(), rig.framesOf(MONO).last())
        // The second song plays on the same output: no ramp, every frame.
        assertEquals(emptyList<String>(), rig.breaksOf(-1))
        assertEquals(0, rig.framesOf(-1).first())
        assertEquals(((b.frames - 1) % SAW).toInt(), rig.framesOf(-1).last())
        // Its first frame is the very next frame heard.
        val lastA = songs.indexOfLast { it.first == MONO }
        assertEquals(lastA + 1, songs.indexOfFirst { it.first == -1 })
        assertEquals(0, rig.mixedCount())
        assertEquals("no events of a blend", 0, rig.events.size)
    }

    @Test
    fun eachFixedCrossfadeHandsOverOnceAndTheNextSongPlaysOnWhole() {
        for (curve in FadeCurve.entries) {
            val rig = PlayerRig(twoSongs())
            rig.play("a", "b", settings = crossfade.copy(fadeCurve = curve))
            val atHandover = untilHandover(rig)
            assertTrue("ended", rig.runUntil(10_000) { rig.player.playbackState == Player.STATE_ENDED })
            assertEquals(curve.name, listOf("q:a", "q:b"), rig.transitions)
            val songs = rig.heardSongs()
            // Up to the handover only the playing song was heard: nothing of
            // the next one reached the output before main agreed to it.
            assertTrue(curve.name, songs.take(atHandover).all { it.first == 1 || it.first == 0 })
            val first = rig.firstMixed()
            val last = rig.lastMixed()
            assertTrue("$curve blended", first in atHandover..last)
            // The old song alone and whole up to the blend, the new one alone
            // from its end to the new song's last frame.
            assertEquals("$curve ${rig.notes}", emptyList<String>(), rig.breaksOf(1, to = first))
            assertTrue(rig.framesOf(1, to = first).first() <= 1)
            assertEquals(emptyList<String>(), rig.breaksOf(-1, from = last + 1))
            assertEquals(((rig.song("b").frames - 1) % SAW).toInt(), rig.framesOf(-1).last())
            assertFalse(rig.framesOf(1, from = last + 1).any())
            assertFalse(rig.player.isBlending)
            assertEquals(Player.STATE_IDLE, rig.decks[0].playbackState)
            // No plan touched either deck's own pause at the end.
            assertFalse(rig.decks.any { it.pauseAtEndOfMediaItems })
            rig.close()
        }
    }

    @Test
    fun aPauseMidSongPlaysEveryFrameOnceAfterTheResume() {
        val rig = PlayerRig(listOf(PcmSong("a", 3_000)))
        rig.play("a")
        rig.runFor(1_000)
        rig.player.pause()
        rig.runFor(200)
        val paused = rig.heardCount
        rig.runFor(800)
        assertEquals("nothing heard while paused", paused, rig.heardCount)
        rig.player.play()
        assertTrue(rig.runUntil(5_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertWhole(rig, 1, rig.song("a"))
    }

    @Test
    fun aPauseInTheRunUpUndoesTheBlendAndTheSongPlaysOnToALaterOne() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        // Registered and the spare playing, the start not yet proposed.
        assertTrue(rig.runUntil(6_000) { rig.decks[1].playWhenReady })
        rig.player.pause()
        assertEquals("the spare let go", Player.STATE_IDLE, rig.decks[1].playbackState)
        assertEquals(CommitState.CANCELLED, rig.mix.mixer.commitWord.stateOf(1))
        rig.runFor(300)
        rig.player.play()
        untilHandover(rig)
        assertTrue(rig.runUntil(10_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertEquals(listOf("q:a", "q:b"), rig.transitions)
        assertEquals(rig.notes.toString(), emptyList<String>(), rig.breaksOf(1, to = rig.firstMixed()))
        assertEquals(emptyList<String>(), rig.breaksOf(-1, from = rig.lastMixed() + 1))
    }

    @Test
    fun aPauseAfterTheHandoverEndsTheBlendAndTheResumePlaysTheNewSongAlone() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        untilHandover(rig)
        rig.runFor(700)
        rig.player.pause()
        rig.runFor(600)
        val atResume = rig.heardCount
        rig.player.play()
        assertTrue(rig.runUntil(8_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertTrue("the old song is never heard again", rig.framesOf(1, from = atResume).isEmpty())
        assertEquals(rig.notes.toString(), emptyList<String>(), rig.breaksOf(-1, from = atResume))
        assertEquals(((rig.song("b").frames - 1) % SAW).toInt(), rig.framesOf(-1).last())
        assertFalse(rig.player.isBlending)
    }

    @Test
    fun aPauseJustAfterTheHandoverPlaysTheOldSongToTheStartThenTheNewOneOnResume() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        untilHandover(rig)
        // The start is still ahead of the output's head.
        rig.runFor(100)
        rig.player.pause()
        rig.runFor(600)
        val atResume = rig.heardCount
        rig.player.play()
        assertTrue("${rig.told()} ${rig.notes}", rig.runUntil(6_000) { rig.player.playbackState == Player.STATE_ENDED })
        // The old song plays on to the start, every frame once, and the new
        // one from there to its end.
        assertEquals(rig.notes.toString(), emptyList<String>(), rig.breaksOf(1))
        // A to the start, then the cut: at most its 20 ms of both (where a
        // frame of the two can read as some frame of one), then the new
        // song alone from where the cut left it, its entry at the start.
        val s = rig.heardSongs()
        val mixedAfter = (atResume until rig.heardCount).filter { s[it].first == MIXED }
        assertTrue("from $atResume: mixed ${mixedAfter.size} (${mixedAfter.firstOrNull()} to ${mixedAfter.lastOrNull()}): ${rig.notes}", mixedAfter.size in 1..960)
        val afterCut = mixedAfter.last() + 1
        assertEquals("from $afterCut: ${rig.notes}", emptyList<String>(), rig.breaksOf(-1, from = afterCut))
        val cut = afterCut - mixedAfter.first()
        val firstB = rig.framesOf(-1, from = afterCut).first()
        assertTrue("the new song from its start: $firstB after a cut of $cut", firstB in cut - 2..cut + 2)
        assertEquals(((rig.song("b").frames - 1) % SAW).toInt(), rig.framesOf(-1).last())
        assertFalse(rig.player.isBlending)
    }

    @Test
    fun aSeekPlaysTheNewPlaceAtOnceAndNothingOfTheOldAfterIt() {
        val rig = PlayerRig(listOf(PcmSong("a", 5_000)))
        rig.play("a")
        rig.runFor(1_000)
        rig.player.seekTo(3_000)
        assertTrue(rig.runUntil(6_000) { rig.player.playbackState == Player.STATE_ENDED })
        // One jump, to the new place, and nothing of the old place after it.
        val breaks = rig.breakFrames(1)
        assertEquals("$breaks", 1, breaks.size)
        val target = (3_000 * 48 % SAW)
        assertTrue("landed at ${breaks[0].second} for $target", near(breaks[0].second, target))
        assertEquals(((rig.song("a").frames - 1) % SAW).toInt(), rig.framesOf(1).last())
    }

    @Test
    fun aSeekWhilePausedPlaysTheNewPlaceOnTheResume() {
        val rig = PlayerRig(listOf(PcmSong("a", 5_000)))
        rig.play("a")
        rig.runFor(1_000)
        rig.player.pause()
        rig.runFor(300)
        rig.player.seekTo(3_000)
        rig.runFor(300)
        val atResume = rig.heardCount
        rig.player.play()
        assertTrue(rig.runUntil(6_000) { rig.player.playbackState == Player.STATE_ENDED })
        val after = rig.framesOf(1, from = atResume)
        val target = (3_000 * 48 % SAW)
        assertTrue("first heard ${after.first()} for $target: ${rig.notes}", near(after.first(), target))
        assertEquals(emptyList<String>(), rig.breaksOf(1, from = atResume))
    }

    @Test
    fun aSeekToWhereThePausedSongStandsChangesNothingHeard() {
        val rig = PlayerRig(listOf(PcmSong("a", 3_000)))
        rig.play("a")
        rig.runFor(1_000)
        rig.player.pause()
        rig.runFor(300)
        // Media3 skips it; nothing is held or cleared for it.
        rig.player.seekTo(rig.player.currentPosition)
        rig.runFor(200)
        rig.player.play()
        assertTrue(rig.runUntil(4_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertWhole(rig, 1, rig.song("a"))
    }

    @Test
    fun aSeekThatDropsAPlannedBlendUnloadsTheSpareAndTheBlendComesLater() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        assertTrue("spare loaded", rig.runUntil(4_000) { rig.decks[1].playbackState != Player.STATE_IDLE })
        val loads = rig.sources.made["b"]
        rig.player.seekTo(200)
        assertEquals("spare unloaded", Player.STATE_IDLE, rig.decks[1].playbackState)
        untilHandover(rig, maxMs = 12_000)
        assertTrue(rig.runUntil(10_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertEquals(listOf("q:a", "q:b"), rig.transitions)
        assertEquals("loaded again once, for the blend", (loads ?: 0) + 1, rig.sources.made["b"])
        assertTrue(rig.mixedCount() > 0)
    }

    @Test
    fun aCallPausesAndItsEndPlaysOnWithNothingLostAndADuckIsAtOnce() {
        val rig = PlayerRig(listOf(PcmSong("a", 4_000)))
        rig.play("a")
        rig.runFor(800)
        focusChange(rig, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(rig.player.playWhenReady)
        rig.runFor(500)
        focusChange(rig, AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(rig.player.playWhenReady)
        rig.runFor(500)
        focusChange(rig, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0.2f, rig.mix.lastTrack.volume, 1e-6f)
        focusChange(rig, AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f, rig.mix.lastTrack.volume, 1e-6f)
        assertTrue(rig.runUntil(6_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertWhole(rig, 1, rig.song("a"))
    }

    @Test
    fun anUnplugMidSongStopsTheSoundWithinACycleAndTheResumeGoesOn() {
        val rig = PlayerRig(listOf(PcmSong("a", 4_000)))
        rig.play("a")
        rig.runFor(1_000)
        rig.context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        rig.runFor(150)
        assertFalse(rig.player.playWhenReady)
        assertEquals(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY, rig.playReason)
        val stopped = rig.heardCount
        rig.runFor(500)
        assertEquals(stopped, rig.heardCount)
        rig.player.play()
        assertTrue(rig.runUntil(5_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertWhole(rig, 1, rig.song("a"))
    }

    @Test
    fun anUnplugMidBlendEndsItAndTheResumePlaysTheNewSongAlone() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        untilHandover(rig)
        rig.runFor(600)
        rig.context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        rig.runFor(300)
        assertFalse(rig.player.playWhenReady)
        val atResume = rig.heardCount
        rig.player.play()
        assertTrue(rig.runUntil(8_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertTrue(rig.framesOf(1, from = atResume).isEmpty())
        assertEquals(rig.notes.toString(), emptyList<String>(), rig.breaksOf(-1, from = atResume))
        assertFalse(rig.player.isBlending)
    }

    @Test
    fun anUnplugInTheRunUpUndoesTheBlendAndTheResumePlaysTheSongOn() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        assertTrue(rig.runUntil(6_000) { rig.decks[1].playWhenReady })
        // Both decks pause by their own noisy handling.
        rig.context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        rig.runFor(300)
        assertFalse(rig.player.playWhenReady)
        assertEquals(rig.told(), CommitState.CANCELLED, rig.mix.mixer.commitWord.stateOf(1))
        assertEquals(Player.STATE_IDLE, rig.decks[1].playbackState)
        rig.player.play()
        untilHandover(rig)
        assertTrue(rig.runUntil(10_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertEquals(listOf("q:a", "q:b"), rig.transitions)
        assertEquals(rig.notes.toString(), emptyList<String>(), rig.breaksOf(1, to = rig.firstMixed()))
        assertEquals(((rig.song("b").frames - 1) % SAW).toInt(), rig.framesOf(-1).last())
    }

    @Test
    fun aSongThatEndsBeforeItsPlannedStartGoesOnIntoItsNextOnceFromItsFirstFrame() {
        // Listed at 5 s, its sound 3 s: the blend planned at 3.5 s never comes.
        val rig = PlayerRig(listOf(PcmSong("a", 5_000, realMs = 3_000), PcmSong("b", 2_500, sign = -1)))
        rig.play("a", "b", settings = crossfade)
        assertTrue(rig.runUntil(9_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertEquals(listOf("q:a", "q:b"), rig.transitions)
        assertEquals(0, rig.mixedCount())
        assertFalse("no handover", rig.events.any { it.kind == BlendEventKind.PROPOSED })
        // Cancelled once as A moving on, and the same song never planned again.
        assertEquals(rig.told(), listOf(BlendCause.A_MOVED_ON), rig.events.filter { it.kind == BlendEventKind.CANCELLED }.map { it.cause })
        assertEquals("armed once: ${rig.told()}", null, rig.mix.mixer.commitWord.stateOf(2))
        assertEquals(Player.STATE_IDLE, rig.decks[1].playbackState)
        // The next song once, gapless, from its first frame.
        assertWhole(rig, 1, rig.song("a"))
        assertEquals(emptyList<String>(), rig.breaksOf(-1))
        assertEquals(0, rig.framesOf(-1).first())
        assertEquals(((rig.song("b").frames - 1) % SAW).toInt(), rig.framesOf(-1).last())
        val songs = rig.heardSongs()
        assertEquals(songs.indexOfLast { it.first == 1 } + 1, songs.indexOfFirst { it.first == -1 })
    }

    @Test
    fun theEndOfTheSongPausesThereAndNothingOfTheNextIsHeard() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        rig.runFor(1_000)
        rig.player.pauseAtEndOfSong = true
        assertTrue(rig.runUntil(8_000) { !rig.player.playWhenReady })
        rig.runFor(500)
        assertFalse(rig.anyOf(-1))
        assertEquals(0, rig.mixedCount())
        assertWhole(rig, 1, rig.song("a"))
    }

    @Test
    fun castingMidBlendStopsBothDecksAndNothingMoreIsHeard() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        untilHandover(rig)
        rig.runFor(400)
        // What Casting does to the phone's player.
        rig.player.pause()
        rig.player.stop()
        rig.player.clearMediaItems()
        rig.runFor(300)
        val after = rig.heardCount
        rig.runFor(1_000)
        assertEquals(after, rig.heardCount)
        assertTrue(rig.decks.all { it.playbackState == Player.STATE_IDLE })
        assertFalse(rig.player.isBlending)
    }

    // A blend the mixer ended on its own before the handover leaves the
    // playing song a lone deck again: a seek plays the new place at once,
    // and, switched to Gapless, so does a Next, nothing of the old place
    // heard after either past what the output had already taken.
    private fun movesAfterTheBlendEnded(rig: PlayerRig, next: (PlayerRig) -> Unit = { it.player.seekToNextMediaItem() }) {
        // Well away from where the song is, so the seek is a jump.
        val targetMs = if (rig.player.currentPosition < 1_500) 2_500L else 500L
        val target = (targetMs * 48 % SAW).toInt()
        val seekAt = rig.heardCount
        rig.player.seekTo(targetMs)
        // Long enough for a seek through a fresh output's gate, then a period.
        rig.runFor(2_000)
        // The old place runs on until the song's first break after the seek;
        // from there it runs on, unbroken, from the target for more than a
        // saw's period, so no old frame can pass for the new place.
        val s = rig.heardSongs()
        // From the old place's last frames before the seek.
        val scanFrom = maxOf(0, seekAt - 2_000)
        assertTrue(
            "a jump after the seek to $targetMs (heard ${rig.heardCount} from $seekAt, at ${rig.player.currentPosition} ms, " +
                "state ${rig.player.playbackState}, playing ${rig.player.isPlaying}): ${rig.notes} ${rig.told()}",
            rig.breakFrames(1, from = scanFrom).isNotEmpty(),
        )
        var lastAt = -1
        var lastFrame = -1
        var landed = -1
        for (i in scanFrom until rig.heardCount) {
            val (kind, f) = s[i]
            if (kind == 1) {
                if (lastAt >= 0 && f != (lastFrame + (i - lastAt)) % SAW) {
                    landed = i
                    break
                }
                lastAt = i
                lastFrame = f
            } else if (kind != 0) {
                lastAt = -1
            }
        }
        assertTrue("landed near the target: ${s[landed].second} for $target", near(s[landed].second, target))
        assertEquals("unbroken from the target: ${rig.notes}", emptyList<String>(), rig.breaksOf(1, from = landed, to = minOf(rig.heardCount, landed + SAW + 960)))
        assertTrue("ran on a period: ${rig.heardCount - landed}", rig.heardCount - landed > SAW + 960)
        assertTrue("the jump after the seek: $landed from $seekAt", landed >= seekAt)
        val oldAfterSeek = (seekAt until landed).count { s[it].first == 1 }
        assertTrue("old place after the seek: $oldAfterSeek frames, ${rig.told()}, ${rig.notes}", oldAfterSeek <= moveFrames)
        rig.player.automix = AutomixSettings(maxOverlapMs = 0)
        rig.runFor(300)
        val nextAt = rig.heardCount
        next(rig)
        rig.runFor(1_000)
        // The old song's frames after the move (the next songs are told
        // apart by their sign or their flipped saw).
        val oldAfterNext = rig.framesOf(1, from = nextAt).size
        assertTrue("old song after the move on: $oldAfterNext frames, ${rig.notes}", oldAfterNext <= moveFrames)
    }

    // Main switches to Gapless as it hears `kind` with `cause`, so nothing is
    // armed again after it; it acks a start `ackAfterMs` after hearing it.
    private fun gaplessAt(rig: PlayerRig, kind: BlendEventKind, cause: BlendCause, ackAfterMs: Long = 0) {
        rig.mix.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED && ackAfterMs > 0) {
                rig.mix.sched.at(rig.mix.sched.now + ackAfterMs * 1_000_000) { rig.player.mixListener.onBlendEvent(e) }
            } else {
                rig.player.mixListener.onBlendEvent(e)
                if (e.kind == kind && e.cause == cause) rig.player.automix = AutomixSettings(maxOverlapMs = 0)
            }
        }
    }

    private fun until(rig: PlayerRig, kind: BlendEventKind, cause: BlendCause, maxMs: Long = 8_000) =
        assertTrue("$kind $cause: ${rig.events.map { "${it.kind}/${it.cause}" }}", rig.runUntil(maxMs) { rig.events.any { it.kind == kind && it.cause == cause } })

    @Test
    fun afterANotReadyStartASeekAndANextPlayTheNewPlaceAtOnce() {
        val rig = PlayerRig(threeSongs(), stalled = setOf("b"))
        rig.play("a", "b", "c", settings = crossfade)
        // Gapless from the moment main hears it: nothing is armed again.
        gaplessAt(rig, BlendEventKind.INCOMING_NOT_READY, BlendCause.NOT_READY)
        until(rig, BlendEventKind.INCOMING_NOT_READY, BlendCause.NOT_READY)
        // The stalled song is passed over: the move goes to the one after.
        movesAfterTheBlendEnded(rig) { it.player.seekTo(2, 0) }
    }

    @Test
    fun afterTheOldSongMovedOnBeforeTheStartASeekAndANextPlayTheNewPlaceAtOnce() {
        val rig = PlayerRig(listOf(PcmSong("a", 5_000, realMs = 3_000), PcmSong("b", 2_500, sign = -1)))
        rig.play("a", "b", settings = crossfade)
        until(rig, BlendEventKind.CANCELLED, BlendCause.A_MOVED_ON)
        movesAfterTheBlendEnded(rig)
    }

    @Test
    fun afterAFloorCancelASeekAndANextPlayTheNewPlaceAtOnce() {
        val rig = PlayerRig(threeSongs(), latencyCycles = 10)
        rig.play("a", "b", "c", settings = crossfade)
        // Main acks 500 ms late, and is Gapless from the moment it hears the
        // cancel: nothing is armed again.
        gaplessAt(rig, BlendEventKind.INCOMING_NOT_READY, BlendCause.FLOOR, ackAfterMs = 500)
        until(rig, BlendEventKind.INCOMING_NOT_READY, BlendCause.FLOOR)
        // The old song already holds its next pass: the move goes past it.
        movesAfterTheBlendEnded(rig) { it.player.seekTo(2, 0) }
    }

    @Test
    fun afterALateRefusalASeekAndANextPlayTheNewPlaceAtOnce() {
        val rig = PlayerRig(twoSongs(), planner = tooLate)
        rig.play("a", "b", settings = crossfade)
        until(rig, BlendEventKind.INCOMING_NOT_READY, BlendCause.LATE)
        // Refused before anything was heard: the song plays a while first,
        // never armed again.
        rig.runFor(1_000)
        movesAfterTheBlendEnded(rig)
    }

    @Test
    fun afterAnUnplugInTheRunUpASeekAndANextPlayTheNewPlaceAtOnce() {
        val rig = PlayerRig(twoSongs())
        rig.play("a", "b", settings = crossfade)
        assertTrue(rig.runUntil(6_000) { rig.decks[1].playWhenReady })
        // Gapless from the moment main hears it: nothing is armed again.
        gaplessAt(rig, BlendEventKind.CANCELLED, BlendCause.PAUSED)
        rig.context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        until(rig, BlendEventKind.CANCELLED, BlendCause.PAUSED, maxMs = 300)
        rig.player.play()
        rig.runFor(100)
        movesAfterTheBlendEnded(rig)
    }

    @Test
    fun afterTheNextSongFailedInTheSpareASeekAndANextPlayTheNewPlaceAtOnce() {
        // Its decoder fails once at 2 s of it: past what the parked spare
        // decodes, before the start.
        val rig = PlayerRig(listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1, failAtMs = 2_000, failOnce = true)))
        rig.play("a", "b", settings = crossfade)
        assertTrue("armed and failed: ${rig.told()}", rig.runUntil(6_000) { rig.mix.mixer.commitWord.stateOf(1) == CommitState.CANCELLED })
        rig.runFor(50)
        movesAfterTheBlendEnded(rig)
    }

    @Test
    fun afterThePlayingSongFailedBeforeTheStartASeekAndANextPlayTheNewPlaceAtOnce() {
        // Its decoder fails once at 3 s of it: after the arm, before the start.
        val rig = PlayerRig(listOf(PcmSong("a", 5_000, failAtMs = 3_000, failOnce = true), PcmSong("b", 2_500, sign = -1)))
        rig.play("a", "b", settings = crossfade)
        assertTrue("armed, then failed: ${rig.told()}", rig.runUntil(6_000) { rig.player.playerError != null })
        assertEquals(CommitState.CANCELLED, rig.mix.mixer.commitWord.stateOf(1))
        rig.player.automix = AutomixSettings(maxOverlapMs = 0)
        rig.player.prepare()
        rig.player.play()
        rig.runFor(200)
        movesAfterTheBlendEnded(rig)
    }

    @Test
    fun aRouteChangeKeepsEveryFrameAndADeadOutputRepeatsNone() {
        val rig = PlayerRig(listOf(PcmSong("a", 4_000)))
        rig.play("a")
        rig.runFor(1_000)
        rig.mix.lastTrack.reroute("usb")
        rig.runFor(1_000)
        assertEquals("the re-route kept every frame", emptyList<String>(), rig.breaksOf(1))
        rig.mix.lastTrack.dead = true
        assertTrue(rig.runUntil(6_000) { rig.player.playbackState == Player.STATE_ENDED })
        // The dead output's own frames are lost, never played again: at most
        // one jump, forward, by no more than the output's latency.
        val breaks = rig.breaksOf(1)
        assertTrue("$breaks", breaks.size <= 1)
        val frames = rig.framesOf(1)
        val jumps = frames.zipWithNext().map { (x, y) -> Math.floorMod(y - x, SAW) }.filter { it != 1 }
        assertTrue("$jumps", jumps.all { it in 2..(1_920 * 5) })
        assertEquals(((rig.song("a").frames - 1) % SAW).toInt(), frames.last())
    }
}
