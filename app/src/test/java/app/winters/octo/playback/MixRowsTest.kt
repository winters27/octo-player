package app.winters.octo.playback

import android.media.AudioManager
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sound.MixTuning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// The phone's player through the one mixer, around a blend and in plain
// playback: an undo in the unheard window at 400 ms of output latency, the
// second message after the old song read past its end from each source,
// the floor's own cancel with main stalled, edits and a skip after the
// handover, a seek that holds, a station's pause and focus regain, the
// player's own restart of a removed next pass, and a stream reopened after
// an error.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MixRowsTest {
    private val crossfade = AutomixSettings(maxOverlapMs = 1_500, smart = false)

    @After
    fun close() = PlayerRig.closeAll()

    private fun endOf(song: PcmSong): Int = ((song.frames - 1) % SAW).toInt()

    private fun ended(rig: PlayerRig, maxMs: Long = 12_000): Boolean {
        if (rig.runUntil(maxMs) { rig.player.playbackState == Player.STATE_ENDED }) return true
        throw AssertionError(
            "not ended: ${rig.player.currentMediaItem?.mediaId} at ${rig.player.currentPosition} ms, state ${rig.player.playbackState}, " +
                "playing ${rig.player.playWhenReady}, error ${rig.player.playerError}, transitions ${rig.transitions}, " +
                    "told ${rig.events.map { "${it.kind}/${it.generation}/${it.cause}@${it.frame}" }}, notes ${rig.notes.takeLast(30)}",
        )
    }

    // Runs `source` in main's turn that handles the first proposal, before
    // the proposal is handled.
    private fun atTheProposal(rig: PlayerRig, source: (PlayerRig) -> Unit) {
        var done = false
        rig.mix.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED && !done) {
                done = true
                source(rig)
            }
            rig.player.mixListener.onBlendEvent(e)
        }
    }

    // The song with `sign` heard alone at its own level from heard frame
    // `from` until `to`, a new output's 20 ms ramp-up excepted.
    private fun atOwnLevel(rig: PlayerRig, sign: Int, from: Int, to: Int): List<String> {
        val s = rig.heardSongs()
        val out = ArrayList<String>()
        for (i in maxOf(from, 960) until to) if (s[i].first == sign && rig.level(i) < 0.999f && out.size < 3) out += "heard $i at ${rig.level(i)}"
        return out
    }

    // Undoing an unheard commit

    @Test
    fun anEditAnywhereInTheUnheardWindowAt400MsWritesNothingOfTheNextSong() {
        val songs = { listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1), PcmSong("c", 2_000, flip = true)) }
        // Where the start is first mixed and where it is proposed.
        val probe = PlayerRig(songs(), latencyCycles = 10)
        probe.play("a", "b", settings = crossfade)
        assertTrue(probe.runUntil(8_000) { probe.mix.mixer.sessionState == BlendState.MIXING })
        val mixedAt = probe.mix.sched.now
        var proposedAt = -1L
        probe.mix.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED && proposedAt < 0) proposedAt = probe.mix.sched.now
            probe.player.mixListener.onBlendEvent(e)
        }
        assertTrue(probe.runUntil(2_000) { proposedAt >= 0 })
        probe.close()
        val window = (proposedAt - mixedAt) / 1_000_000
        assertTrue("the unheard window lasts $window ms", window >= 20)
        // The first block of it, its middle and its last moment (main's
        // turn with the proposal already queued).
        for (afterMs in listOf(1L, window / 2, -1L)) {
            val rig = PlayerRig(songs(), latencyCycles = 10)
            rig.play("a", "b", settings = crossfade)
            var word: CommitState? = null
            val edit = { r: PlayerRig ->
                r.player.addMediaItems(listOf(r.song("c").item()))
                word = r.mix.mixer.commitWord.stateOf(1)
            }
            if (afterMs < 0) {
                atTheProposal(rig, edit)
                assertTrue(rig.runUntil(8_000) { rig.player.mediaItemCount == 3 })
            } else {
                assertTrue(rig.runUntil(8_000) { rig.mix.mixer.sessionState == BlendState.MIXING })
                rig.runFor(afterMs)
                assertEquals("$afterMs ms in: not handed over", listOf("q:a"), rig.transitions)
                edit(rig)
            }
            assertEquals("$afterMs ms in", CommitState.CANCELLED, word)
            assertTrue("$afterMs ms in", ended(rig, 15_000))
            // Nothing of the undone start: the old song alone, whole and at its
            // own level, until a later blend or its own end.
            val firstB = rig.firstOf(-1)
            val firstMixed = rig.firstMixed().takeIf { it >= 0 } ?: Int.MAX_VALUE
            val upTo = minOf(firstB, firstMixed)
            assertTrue("$afterMs ms in: the next song first heard at $firstB, mixed from $firstMixed", firstB > 0)
            assertEquals("$afterMs ms in: ${rig.notes}", emptyList<String>(), rig.breaksOf(1, to = upTo))
            assertEquals("$afterMs ms in, up to $upTo: ${rig.events.map { "${it.kind}/${it.generation}/${it.cause}@${it.frame}" }}", emptyList<String>(), atOwnLevel(rig, 1, 0, upTo))
            assertEquals("$afterMs ms in", listOf("q:a", "q:b", "q:c"), rig.transitions)
            rig.close()
        }
    }

    @Test
    fun repeatOneSetInTheUnheardWindowRepeatsTheOldSong() {
        val rig = PlayerRig(listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1)), latencyCycles = 10)
        rig.play("a", "b", settings = crossfade)
        var word: CommitState? = null
        atTheProposal(rig) {
            it.player.repeatMode = Player.REPEAT_MODE_ONE
            word = it.mix.mixer.commitWord.stateOf(1)
        }
        assertTrue(rig.runUntil(9_000) { rig.heardCount > 5_000 * 48 + 48_000 })
        assertEquals(CommitState.CANCELLED, word)
        assertFalse("nothing of the next song", rig.anyOf(-1))
        assertEquals(0, rig.mixedCount())
        // Once to its end, then again from its start.
        val breaks = rig.breakFrames(1)
        assertEquals("${rig.notes}: $breaks", 1, breaks.size)
        assertEquals(endOf(rig.song("a")), breaks[0].first)
        assertTrue("again from ${breaks[0].second}", breaks[0].second <= 1)
    }

    // The second message after the old song read past its end

    // A listed at 5 s but its sound 3.755 s long: the crossfade planned at
    // 3.75 s (its 1.25 s, half of the next song) finds its end 5 ms after
    // the start, while the start is mixed
    // ahead. `source` undoes the start in main's turn with the proposal.
    private fun pastItsEnd(source: (PlayerRig) -> Unit): PlayerRig {
        val rig = PlayerRig(
            listOf(PcmSong("a", 5_000, realMs = 3_755), PcmSong("b", 2_500, sign = -1), PcmSong("x", 2_000, flip = true)),
            latencyCycles = 10,
        )
        rig.play("a", "b", settings = crossfade)
        atTheProposal(rig, source)
        return rig
    }

    // The old song whole, then the song `kind` from its first frame with
    // nothing between but silence.
    private fun thenWhole(rig: PlayerRig, kind: Int, next: PcmSong) {
        assertEquals("the old song: ${rig.notes}", emptyList<String>(), rig.breaksOf(1))
        assertEquals(endOf(rig.song("a")), rig.framesOf(1).last())
        val s = rig.heardSongs()
        val lastA = s.indexOfLast { it.first == 1 }
        val first = rig.firstOf(kind)
        assertTrue("then ${next.id}: first at $first after $lastA", first > lastA)
        assertTrue("nothing between: ${s.subList(lastA + 1, first).filter { it.first != 0 }.take(3)}", s.subList(lastA + 1, first).all { it.first == 0 })
        assertTrue("${next.id} from its start: ${s[first].second}", s[first].second <= 1)
        assertEquals(emptyList<String>(), rig.breaksOf(kind))
        assertEquals(endOf(next), rig.framesOf(kind).last())
        assertEquals("nothing mixed", 0, rig.mixedCount())
    }

    @Test
    fun playNextInTheWindowPlaysThatSongAfterTheOldOnesEndAndNothingOfTheHeldOne() {
        val rig = pastItsEnd { it.player.addNext(listOf(it.song("x").item())) }
        assertTrue(rig.runUntil(9_000) { rig.transitions.lastOrNull() == "q:b" })
        assertTrue(rig.runUntil(4_000) { rig.firstOf(-1) >= 0 })
        val firstB = rig.firstOf(-1)
        assertTrue("x before b: x at ${rig.firstOf(FLIP)}, b at $firstB, ${rig.transitions} ${rig.told()} ${rig.notes}", rig.firstOf(FLIP) in 0 until firstB)
        assertEquals(listOf("q:a", "q:x", "q:b"), rig.transitions.take(3))
        val songs = rig.heardSongs().subList(0, firstB)
        assertFalse("the held next song unheard", songs.any { it.first == -1 })
        assertEquals(emptyList<String>(), rig.breaksOf(1))
        assertEquals(endOf(rig.song("a")), rig.framesOf(1).last())
        assertTrue(rig.framesOf(FLIP).first() <= 1)
        assertEquals(emptyList<String>(), rig.breaksOf(FLIP))
    }

    @Test
    fun aPauseInTheWindowThenPlayGoesOnIntoTheOldSongsOwnNext() {
        val rig = pastItsEnd { r ->
            r.player.pause()
            r.mix.sched.at(r.mix.sched.now + 300_000_000) { r.player.play() }
        }
        assertTrue(ended(rig))
        assertEquals(listOf("q:a", "q:b"), rig.transitions)
        thenWhole(rig, -1, rig.song("b"))
        // The pause's own follow-up answered it, after its settle.
        assertFalse(rig.told(), rig.events.any { it.kind == BlendEventKind.FOLLOW_UP_NEEDED })
    }

    @Test
    fun aFocusLossInTheWindowThenItsReturnGoesOnIntoTheOldSongsOwnNext() {
        val rig = pastItsEnd { r ->
            val audio = r.context.getSystemService(AudioManager::class.java)
            val listener = shadowOf(audio).lastAudioFocusRequest.listener
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            r.mix.sched.at(r.mix.sched.now + 300_000_000) { listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN) }
        }
        assertTrue(ended(rig))
        assertEquals(listOf("q:a", "q:b"), rig.transitions)
        thenWhole(rig, -1, rig.song("b"))
        assertFalse(rig.told(), rig.events.any { it.kind == BlendEventKind.FOLLOW_UP_NEEDED })
    }

    @Test
    fun turningBlendsOffInTheWindowGoesOnIntoTheOldSongsOwnNext() {
        val rig = pastItsEnd { it.player.automix = AutomixSettings(maxOverlapMs = 0) }
        assertTrue(ended(rig))
        assertEquals(listOf("q:a", "q:b"), rig.transitions)
        thenWhole(rig, -1, rig.song("b"))
    }

    // The floor's cancel with main stalled

    @Test
    fun withMainStalledTheFloorCancelsAndTheOldSongGoesOnIntoItsHeldNextWithNoGap() {
        val rig = PlayerRig(listOf(PcmSong("a", 5_000, realMs = 3_755), PcmSong("b", 2_500, sign = -1)), latencyCycles = 10)
        rig.play("a", "b", settings = crossfade)
        // Main handles the proposal 500 ms late.
        rig.mix.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED) {
                rig.mix.sched.at(rig.mix.sched.now + 500_000_000) { rig.player.mixListener.onBlendEvent(e) }
            } else {
                rig.player.mixListener.onBlendEvent(e)
            }
        }
        assertTrue("${rig.told()} ${rig.notes}", ended(rig))
        assertTrue("${rig.events.map { "${it.kind}/${it.cause}@${it.frame}" }} ${rig.notes}", rig.events.any { it.kind == BlendEventKind.INCOMING_NOT_READY && it.cause == BlendCause.FLOOR })
        assertFalse("no follow-up asked of main", rig.events.any { it.kind == BlendEventKind.FOLLOW_UP_NEEDED })
        assertEquals(listOf("q:a", "q:b"), rig.transitions)
        thenWhole(rig, -1, rig.song("b"))
    }

    // Actions after the handover

    @Test
    fun aQueueEditJustAfterTheHandoverPlaysTheOldSongToTheStartThenTheNewOneWithNoGap() {
        // Main's lag at the margin's cap: the start is proposed and handed
        // over well before it is written.
        val rig = PlayerRig(
            listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1), PcmSong("c", 2_000, flip = true)),
            tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 500.0),
        )
        rig.play("a", "b", settings = crossfade)
        rig.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (mediaItem?.entryId == "q:b") rig.mix.sched.at(rig.mix.sched.now + 10_000_000) { rig.player.addMediaItems(listOf(rig.song("c").item())) }
            }
        })
        assertTrue(ended(rig))
        val firstB = rig.firstOf(-1)
        val s = rig.heardSongs()
        val lastA = s.indexOfLast { it.first == 1 }
        // The old song alone at its own level up to the start, then the cut:
        // at most its 20 ms of both, then the new song alone, never a gap.
        assertEquals(emptyList<String>(), rig.breaksOf(1))
        // Up to the cut's first frames, where the old song starts to leave.
        assertEquals(emptyList<String>(), atOwnLevel(rig, 1, 0, (rig.firstMixed().takeIf { it >= 0 } ?: lastA) - 2))
        assertTrue("the cut: mixed ${rig.mixedCount()}", rig.mixedCount() <= 960)
        val between = s.subList(minOf(lastA, firstB), maxOf(lastA, firstB))
        assertTrue("no gap: ${between.count { it.first == 0 }} silent", between.count { it.first == 0 } <= 1)
        // Past the cut (where a frame of the two can read as some frame of
        // one), the new song alone from where the cut left it, its entry at
        // the start.
        val firstMixed = rig.firstMixed()
        val afterCut = rig.lastMixed() + 1
        val firstAfter = rig.framesOf(-1, from = afterCut).first()
        assertTrue("the new song from its start: $firstAfter after a cut of ${afterCut - firstMixed}", firstAfter in afterCut - firstMixed - 2..afterCut - firstMixed + 2)
        assertEquals(emptyList<String>(), rig.breaksOf(-1, from = afterCut))
        assertEquals(listOf("q:a", "q:b", "q:c"), rig.transitions)
    }

    @Test
    fun nextJustAfterTheHandoverSkipsTheNewSongUnheardAndPlaysTheOneAfterFromItsStart() {
        val rig = PlayerRig(
            listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1), PcmSong("c", 2_000, flip = true)),
            tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 500.0),
        )
        rig.play("a", "b", "c", settings = crossfade)
        rig.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (mediaItem?.entryId == "q:b") rig.mix.sched.at(rig.mix.sched.now + 10_000_000) { rig.player.seekToNextMediaItem() }
            }
        })
        assertTrue(ended(rig))
        assertEquals(listOf("q:a", "q:b", "q:c"), rig.transitions)
        assertFalse("the skipped song unheard", rig.anyOf(-1))
        assertEquals(emptyList<String>(), rig.breaksOf(1))
        assertTrue(rig.framesOf(FLIP).first() <= 1)
        assertEquals(emptyList<String>(), rig.breaksOf(FLIP))
        assertEquals(endOf(rig.song("c")), rig.framesOf(FLIP).last())
    }

    // Holds

    // Just after the handover, the start still ahead of the head: a seek
    // `byMs` from where the new song stands holds the output. Exactly one
    // fresh output starts, each output played once.
    private fun seekJustAfterTheHandover(byMs: Long): Pair<PlayerRig, Int> {
        val rig = PlayerRig(listOf(PcmSong("a", 5_000), PcmSong("b", 4_000, sign = -1)))
        rig.play("a", "b", settings = crossfade)
        assertTrue(rig.runUntil(9_000) { rig.transitions.lastOrNull() == "q:b" })
        rig.runFor(300)
        val built = rig.mix.builder.built.size
        val heard = rig.heardCount
        rig.player.seekTo(rig.player.currentPosition + byMs)
        rig.runFor(800)
        assertEquals("$byMs ms: one fresh output: ${rig.notes}", built + 1, rig.mix.builder.built.size)
        assertTrue("$byMs ms: each output played once", rig.mix.builder.built.all { it.plays <= 1 })
        assertTrue("$byMs ms: sound again", rig.heardCount > heard + 9_600)
        assertTrue(ended(rig))
        assertEquals(emptyList<String>(), rig.breaksOf(-1, from = rig.heardCount - 48_000))
        return rig to heard
    }

    @Test
    fun aSeekToWhereTheNewSongStandsJustAfterTheHandoverStartsExactlyOneFreshOutput() {
        seekJustAfterTheHandover(0)
    }

    @Test
    fun aRealSeekJustAfterTheHandoverPlaysOnlyTheNewPlaceThroughOneFreshOutput() {
        val (rig, heard) = seekJustAfterTheHandover(500)
        // Past the cycle that was mixing at the seek, only the new song's new
        // place: nothing of the old song up to the start, nothing mixed.
        val after = heard + 1_920 + 480
        assertEquals("the old song after the seek: ${rig.notes}", emptyList<Int>(), rig.framesOf(1, from = after).take(3))
        assertEquals(-1, rig.heardSongs().subList(after, rig.heardCount).indexOfFirst { it.first == MIXED })
    }

    // A station: paused (stopped and let go) and resumed, and a focus
    // loss and its return, each opens the stream afresh and is heard at
    // once, nothing of it dropped as if it went on.

    private fun station() = PlayerRig(listOf(PcmSong("radio:st", 60_000)))

    @Test
    fun aStationPausedTenSecondsThenPlayedIsHeardAtOnceFromWhereItOpens() {
        val rig = station()
        rig.play("radio:st")
        rig.runFor(1_500)
        rig.player.pause()
        rig.runFor(10_000)
        val atPlay = rig.heardCount
        rig.player.play()
        assertTrue("heard again within a second: ${rig.notes}", rig.runUntil(1_000) { rig.firstOf(1, atPlay) >= 0 })
        val first = rig.heardSongs()[rig.firstOf(1, atPlay)].second
        assertTrue("opened afresh, first heard $first", first <= 960)
    }

    @Test
    fun aStationsFocusLossAndReturnIsHeardAtOnceFromWhereItOpens() {
        val rig = station()
        rig.play("radio:st")
        rig.runFor(1_500)
        val audio = rig.context.getSystemService(AudioManager::class.java)
        val listener = shadowOf(audio).lastAudioFocusRequest.listener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        rig.runFor(2_000)
        val atGain = rig.heardCount
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("heard again within a second: ${rig.notes}", rig.runUntil(1_000) { rig.firstOf(1, atGain) >= 0 })
        val first = rig.heardSongs()[rig.firstOf(1, atGain)].second
        assertTrue("opened afresh, first heard $first", first <= 960)
    }

    // The player's own restart of a removed next pass, in plain playback

    // A at 3 s, its next song b read ahead; `edit` made `leadMs` before A's
    // end. At most b's frames up to the old output's settled head, then x
    // from its first frame; A never repeats.
    private fun restartBefore(leadMs: Long, edit: (PlayerRig) -> Unit, repeatOne: Boolean = false): PlayerRig {
        val rig = PlayerRig(listOf(PcmSong("a", 3_000), PcmSong("b", 2_000, sign = -1), PcmSong("x", 2_000, flip = true)))
        rig.play("a", "b")
        if (repeatOne) rig.player.repeatMode = Player.REPEAT_MODE_ONE
        assertTrue(rig.runUntil(5_000) { rig.player.currentPosition >= 3_000 - leadMs })
        edit(rig)
        return rig
    }

    @Test
    fun playNextOrRemovingTheNextSongNearTheEndRestartsWithNoRepeatAndTheNewNextFromItsStart() {
        for (leadMs in listOf(100L, 400L, 700L)) {
            for (remove in listOf(false, true)) {
                val rig = restartBefore(leadMs, { r ->
                    if (remove) {
                        r.player.addMediaItems(listOf(r.song("x").item()))
                        r.player.removeMediaItem(1)
                    } else {
                        r.player.addNext(listOf(r.song("x").item()))
                    }
                })
                val what = "${if (remove) "removing" else "Play next"} $leadMs ms before the end"
                assertTrue(what, rig.runUntil(9_000) { rig.firstOf(FLIP) >= 0 && rig.player.currentMediaItem?.mediaId == "x" })
                rig.runFor(2_500)
                assertEquals("$what: ${rig.notes}", emptyList<String>(), rig.breaksOf(1))
                assertEquals(what, endOf(rig.song("a")), rig.framesOf(1).last())
                val firstX = rig.firstOf(FLIP)
                val removed = rig.framesOf(-1, to = firstX)
                assertTrue("$what: the removed song heard ${removed.size} frames", removed.size <= 48 * 700)
                if (removed.isNotEmpty()) assertTrue(what, removed.first() <= 1 && rig.breaksOf(-1, to = firstX).isEmpty())
                assertTrue(what, rig.framesOf(FLIP).first() <= 1)
                assertEquals(what, emptyList<String>(), rig.breaksOf(FLIP))
                rig.close()
            }
        }
    }

    @Test
    fun repeatOneTurnedOffNearTheEndGoesOnToTheNextSongWithNoRepeat() {
        for (leadMs in listOf(100L, 400L)) {
            val rig = restartBefore(leadMs, { it.player.repeatMode = Player.REPEAT_MODE_OFF }, repeatOne = true)
            assertTrue(rig.runUntil(9_000) { rig.player.currentMediaItem?.mediaId == "b" })
            rig.runFor(2_500)
            val firstB = rig.firstOf(-1)
            assertTrue("$leadMs ms: b heard", firstB >= 0)
            // A once whole, then at most its restart up to the settled head.
            val a = rig.framesOf(1, to = firstB)
            val wrap = (1 until a.size).firstOrNull { a[it] != (a[it - 1] + 1) % SAW } ?: a.size
            assertEquals("$leadMs ms", endOf(rig.song("a")), a[wrap - 1])
            assertTrue("$leadMs ms: the restart heard ${a.size - wrap} frames", a.size - wrap <= 48 * 700)
            assertTrue("$leadMs ms", rig.framesOf(-1).first() <= 1)
            assertEquals("$leadMs ms", emptyList<String>(), rig.breaksOf(-1))
            rig.close()
        }
    }

    // A stream reopened after an error goes on by song time; any other
    // action after the error starts over.

    @Test
    fun aStreamReopenedAfterAnErrorGoesOnWithNothingHeardTwice() {
        // Its decoder fails 2 s into the song the first time it opens, while
        // the song plays; reopened, it plays on.
        val rig = PlayerRig(listOf(PcmSong("a", 3_000, failAtMs = 2_000, failOnce = true)))
        var reopened = 0
        rig.player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // As the service does for a stream the server began again.
                if (reopened++ == 0) android.os.Handler(android.os.Looper.getMainLooper()).post { rig.player.reopen() }
            }
        })
        rig.play("a")
        assertTrue(ended(rig, 8_000))
        assertEquals(1, reopened)
        assertEquals(rig.notes.toString(), emptyList<String>(), rig.breaksOf(1))
        assertTrue(rig.framesOf(1).first() <= 1)
        assertEquals(endOf(rig.song("a")), rig.framesOf(1).last())
    }

    @Test
    fun aSkipAfterAnErrorPlaysTheNextSongFromItsStart() {
        val rig = PlayerRig(listOf(PcmSong("a", 3_000, failAtMs = 1_500), PcmSong("b", 2_000, sign = -1)))
        var skipped = 0
        rig.player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (skipped++ == 0) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        rig.player.seekToNextMediaItem()
                        rig.player.prepare()
                    }
                }
            }
        })
        rig.play("a", "b")
        assertTrue(ended(rig, 8_000))
        assertEquals(1, skipped)
        assertTrue(rig.framesOf(-1).first() <= 1)
        assertEquals(emptyList<String>(), rig.breaksOf(-1))
        assertEquals(endOf(rig.song("b")), rig.framesOf(-1).last())
    }
}
