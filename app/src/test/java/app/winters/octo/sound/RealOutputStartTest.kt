package app.winters.octo.sound

import android.os.Build
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// How a real output starts and what the mixer learns of its route: the
// start threshold lowered to what the output holds when its head stands
// (API 31 and up) or its buffer size (API 29 and 30), each put back after
// the first head step; the head quantum and change interval from full
// spans; routes keyed by device; the position clamp after every fresh
// start; and nothing written that is not music.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealOutputStartTest {
    private val ms = 1_000_000L

    private fun speaker(q: Long = 1_920, intervalMs: Long = 40, latencyMs: Long = 0) =
        RouteMeasure().apply {
            quantum = q
            largestStep = q
            intervalNanos = intervalMs * ms
            latencyNanos = latencyMs * ms
        }

    private fun counting(fr: Long): Float = (fr % 400_000 + 1) / 1_000_000f

    private fun runUntil(rig: MixRig, limitMs: Int = 20_000, done: () -> Boolean) {
        var n = 0
        while (!done()) {
            rig.runFor(1.0)
            check(++n < limitMs) { "never happened" }
        }
    }

    // A queue's last 30 ms: 1,440 frames, less than one quantum plus a block.
    private fun lastFrames(sdk: Int, intervalMs: Long = 40, quantum: Int = 1_920, spacing: Double = 40.0): Pair<MixRig, TestDeck> {
        val rig = MixRig(quantum = quantum, spacingMs = doubleArrayOf(spacing), sdk = sdk, routes = hashMapOf("speaker" to speaker(quantum.toLong(), intervalMs)))
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.write(1_440)
        d.end()
        return rig to d
    }

    @Test
    @Config(sdk = [31])
    fun aStartThatHoldsLessThanItsThresholdIsLoweredAfterOneIntervalAndPutBackAfterItsFirstStep() {
        val (rig, d) = lastFrames(Build.VERSION.SDK_INT)
        rig.runFor(600.0)
        val t = rig.track(0)
        val firstWrite = t.writes.first()
        val calls = t.thresholdCalls
        // Built at one quantum plus a block, set before play().
        assertEquals(2_400L, calls[0][1])
        assertTrue(calls[0][0] <= t.playTimes[0])
        assertTrue("the buffer size is not touched on API 31", t.sizeCalls.isEmpty())
        // Lowered to what it holds, one change interval after the first
        // music write (never timed from play()).
        val lowered = calls.first { it[1] < 2_400 }
        assertEquals(t.written, lowered[1])
        assertTrue("lowered at ${lowered[0] / 1e6} ms, music at ${firstWrite / 1e6}", lowered[0] >= firstWrite + 40 * ms)
        // Put back only after the head's first step.
        val firstStep = t.steps.first()[0]
        val back = calls.first { it[0] > lowered[0] }
        assertEquals(2_400L, back[1])
        assertTrue(back[0] >= firstStep)
        // Every frame played and the deck ended where its song did.
        assertEquals(t.written, t.head)
        assertEquals(androidx.media3.common.util.Util.sampleCountToDurationUs(1_440, 48_000), d.output.positionUs)
    }

    @Test
    @Config(sdk = [31])
    fun underTheGatesHoldTheLoweringCountsFromTheFirstMusicWriteNotPlay() {
        // A 20 ms route with one block in the deck's ring when the slack ends.
        val rig = MixRig(quantum = 960, spacingMs = doubleArrayOf(20.0), sdk = Build.VERSION.SDK_INT, routes = hashMapOf("speaker" to speaker(960, 20)))
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.write(480)
        rig.sched.at(60 * ms) { d.feed() }
        rig.runFor(400.0)
        val t = rig.track(0)
        val lowered = t.thresholdCalls.firstOrNull { it[1] < 1_440 }
        if (lowered != null) {
            assertTrue("lowered ${lowered[0] / 1e6} ms, music ${t.writes.first() / 1e6} ms", lowered[0] >= t.writes.first() + 20 * ms)
        }
        assertEquals(1, rig.builder.built.size)
        assertTrue(t.head > 4_800)
    }

    @Test
    @Config(sdk = [31])
    fun afterAnUnderrunTheLoweringCountsFromTheFirstFrameWrittenAfterTheHeadStopped() {
        val rig = MixRig(sdk = Build.VERSION.SDK_INT, routes = hashMapOf("speaker" to speaker()))
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.write(30_000)
        rig.runFor(1.0)
        runUntil(rig) { rig.track(0).underrunCount > 0 }
        val t = rig.track(0)
        // 25 ms after the underrun the source gives its last 30 ms (its
        // stream ends, so the dry deck takes them at once).
        rig.runFor(25.0)
        val before = t.thresholdCalls.size
        val writtenAt = rig.sched.now
        d.write(1_440)
        d.end()
        rig.runFor(400.0)
        val calls = t.thresholdCalls.drop(before)
        val lowered = calls.first { it[1] < 2_400 }
        assertTrue("lowered at ${(lowered[0] - writtenAt) / 1e6} ms after the write", lowered[0] >= writtenAt + 40 * ms)
        val stepAfter = t.steps.first { it[0] > lowered[0] }[0]
        val back = calls.first { it[0] > lowered[0] }
        assertEquals(2_400L, back[1])
        assertTrue("put back at ${back[0] / 1e6} ms, its step at ${stepAfter / 1e6} ms", back[0] >= stepAfter)
        assertEquals(t.written, t.head)
    }

    @Test
    @Config(sdk = [30])
    fun onApi30TheBufferSizeStartsTheOutputAndIsPutBackToTheWholeBuffer() {
        val (rig, d) = lastFrames(Build.VERSION.SDK_INT)
        rig.runFor(600.0)
        val t = rig.track(0)
        assertTrue("no start threshold before API 31", t.thresholdCalls.isEmpty())
        val sizes = t.sizeCalls
        assertEquals(2_400L, sizes[0][1])
        assertTrue(sizes[0][0] <= t.playTimes[0])
        val lowered = sizes.first { it[1] < 2_400 }
        assertEquals(maxOf(t.written, 960L), lowered[1])
        assertTrue(lowered[0] >= t.writes.first() + 40 * ms)
        val firstStep = t.steps.first()[0]
        val back = sizes.first { it[0] > lowered[0] }
        assertEquals("the whole buffer again", t.bufferFrames.toLong(), back[1])
        assertTrue(back[0] >= firstStep)
        assertEquals(t.written, t.head)
        assertEquals(androidx.media3.common.util.Util.sampleCountToDurationUs(1_440, 48_000), d.output.positionUs)
    }

    @Test
    @Config(sdk = [30])
    fun onApi30APlayingOutputKeepsItsWholeBufferOnceItsRouteIsLearned() {
        val rig = MixRig(sdk = Build.VERSION.SDK_INT)
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(3_000.0)
        val t = rig.track(0)
        assertTrue("learned", rig.routes["speaker"]?.known == true)
        assertEquals(t.bufferFrames, t.sizeInUse)
        // The refill keeps the written lead at plain playback's.
        assertTrue("lead ${t.written - t.head}", t.written - t.head >= t.bufferFrames - t.bufferFrames / 4 - 1_920)
    }

    @Test
    @Config(sdk = [30])
    fun onApi30AReRouteDuringTheGatesHoldSetsTheSmallSizeAgain() {
        val rig = MixRig(sdk = Build.VERSION.SDK_INT, routes = hashMapOf("speaker" to speaker(), "usb" to speaker()))
        rig.list.hidden = true
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(30.0)
        val t = rig.track(0)
        assertEquals(2_400, t.sizeInUse)
        // The new route's client proxy starts at the whole buffer.
        t.resetSize()
        t.reroute("usb")
        rig.runFor(5.0)
        assertEquals(2_400, t.sizeInUse)
    }

    // Head measures

    @Test
    fun theQuantumIsTheMostCommonStepWithPartCyclesAmongThem() {
        val rig = MixRig()
        rig.system.takes = { c -> if (c % 5 == 3L) 128 else 1_920 }
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(2_500.0)
        val m = rig.routes.getValue("speaker")
        assertEquals(1_920L, m.quantum)
        assertEquals(1_920L, m.largestStep)
        assertEquals(2_400L, rig.track(0).thresholdCalls.last()[1])
    }

    @Test
    fun anAlternatingStepCountsAsOneQuantumAndItsJitter() {
        val rig = MixRig()
        rig.system.takes = { c -> if (c % 2 == 0L) 1_837 else 1_838 }
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(2_500.0)
        val m = rig.routes.getValue("speaker")
        assertTrue("the most common of the two: ${m.quantum}", m.quantum == 1_837L || m.quantum == 1_838L)
        // Either step is within the quantum's jitter.
        assertEquals(1_838L, m.largestStep)
        assertEquals(m.quantum + 480, rig.track(0).thresholdCalls.last()[1])
    }

    @Test
    fun theChangeIntervalIsTheLongestTimeBetweenSteps() {
        val rig = MixRig(spacingMs = doubleArrayOf(40.0, 40.0, 40.0, 46.0))
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(2_500.0)
        val m = rig.routes.getValue("speaker")
        assertTrue("interval ${m.intervalNanos / 1e6} ms", m.intervalNanos in 46 * ms..48 * ms)
    }

    @Test
    fun theWindowStartsThreeHundredMsAfterTheFirstMusicCycleAndRunsAtLeast600Ms() {
        val rig = MixRig()
        // Data comes at its own pace at first, too slowly for the first music
        // to go in; the deck reads ahead from 250 ms, the first music goes in
        // the gate's slack after that, and the system mixer's first cycle
        // comes only at 360 ms, well after it.
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.pace(everyMs = 10.0, chunk = 480, forMs = 250.0)
        rig.sched.at(250 * ms) { d.feed() }
        var firstCycle = -1L
        var firstGap = true
        rig.system.gapAfter = { now ->
            if (firstCycle < 0 && rig.builder.built.isNotEmpty() && rig.track(0).firstCycleNanos >= 0) firstCycle = rig.track(0).firstCycleNanos
            when {
                firstGap -> {
                    firstGap = false
                    360.0
                }
                // One long gap 200 ms after the first music cycle, inside a
                // window that would start at the first write but not after
                // the cycle.
                firstCycle >= 0 && now in (firstCycle + 200 * ms) until (firstCycle + 240 * ms) -> 90.0
                else -> null
            }
        }
        rig.runFor(3_000.0)
        val t = rig.track(0)
        val m = rig.routes.getValue("speaker")
        assertTrue("first music ${t.writes.first() / 1e6} ms, first cycle ${t.firstCycleNanos / 1e6} ms", t.firstCycleNanos - t.writes.first() >= 60 * ms)
        assertTrue("interval ${m.intervalNanos / 1e6} ms", m.intervalNanos in 40 * ms..42 * ms)
        val published = t.thresholdCalls.first { it[1] == 2_400L }[0]
        assertTrue("published ${published / 1e6} ms", published >= t.firstCycleNanos + 900 * ms)
    }

    @Test
    fun aRouteWithALongIntervalLearnsFromEightSpansWithinThreeSeconds() {
        // 100 ms between steps: 600 ms holds only 6 spans, so the window runs on.
        val slow = MixRig(quantum = 4_800, spacingMs = doubleArrayOf(100.0))
        val d = slow.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        slow.runFor(2_500.0)
        val m = slow.routes["speaker"]
        assertNotNull(m)
        assertEquals(4_800L, m!!.quantum)
        assertTrue(m.intervalNanos in 100 * ms..102 * ms)
        // 400 ms between steps: 7 spans in 3 s, never published.
        val slower = MixRig(quantum = 4_800, spacingMs = doubleArrayOf(400.0))
        val e = slower.deck(0)
        e.signal = ::counting
        e.play()
        e.feed()
        slower.runFor(4_500.0)
        assertFalse(slower.routes["speaker"]?.known == true)
    }

    @Test
    fun aRebufferInsideTheWindowIsNotLearnedFrom() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        // Twice real time, then a trickle of 10 ms every 50 ms (the output
        // underruns), then twice real time again.
        d.pace(everyMs = 10.0, chunk = 960, forMs = 400.0)
        rig.sched.at(400 * ms) { d.pace(everyMs = 50.0, chunk = 480, forMs = 600.0) }
        rig.sched.at(1_000 * ms) { d.pace(everyMs = 10.0, chunk = 960) }
        rig.runFor(4_000.0)
        val m = rig.routes["speaker"]
        assertNotNull(m)
        assertEquals(1_920L, m!!.quantum)
        assertTrue("interval ${m.intervalNanos / 1e6} ms", m.intervalNanos in 40 * ms..42 * ms)
    }

    @Test
    fun aRouteChangeRestartsLearningAndEachDeviceKeepsItsOwn() {
        for (byListener in listOf(true, false)) {
            val rig = MixRig()
            val d = rig.deck(0)
            d.signal = ::counting
            d.play()
            d.feed()
            rig.runFor(2_000.0)
            val t = rig.track(0)
            assertEquals(1_920L, rig.routes.getValue("speaker").quantum)
            // Moved to a device with a 960-frame, 20 ms cycle.
            val at = rig.sched.now
            rig.system.quantum = 960
            rig.system.gapAfter = { 20.0 }
            if (byListener) t.reroute("usb") else {
                t.route = "usb"
                rig.mixer.devicesChanged()
            }
            rig.runFor(5.0)
            // The new route has no quantum yet: the whole buffer.
            assertEquals(t.bufferFrames.toLong(), t.thresholdCalls.last()[1])
            rig.runFor(2_500.0)
            val usb = rig.routes.getValue("usb")
            assertEquals("listener $byListener", 960L, usb.quantum)
            assertTrue(usb.intervalNanos in 20 * ms..22 * ms)
            assertEquals(1_920L, rig.routes.getValue("speaker").quantum)
            val published = t.thresholdCalls.last()
            assertEquals(1_440L, published[1])
            assertTrue("learned from 300 ms after the change", published[0] >= at + 900 * ms)
            // And back: the speaker's own measures at once.
            rig.system.quantum = 3_840
            rig.system.gapAfter = { 80.0 }
            t.reroute("speaker")
            rig.runFor(5.0)
            assertEquals(2_400L, t.thresholdCalls.last()[1])
        }
    }

    // The position clamp

    // A fake whose timestamps claim every frame presented the moment it is
    // taken (no latency at all) and then stop after the first cycle, so no
    // timestamp ever comes 100 ms after the first music cycle and the clamp
    // stays on; on route `route`.
    private fun clamped(route: String, routes: HashMap<String, RouteMeasure> = HashMap()): Pair<MixRig, TestDeck> {
        val rig = MixRig(latencyCycles = 0, routes = routes)
        rig.builder.nextRoute = route
        rig.builder.nextStampsUpTo = 1_920
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(1.0)
        return rig to d
    }

    // While clamped the presented point is the head less `latencyMs`, the
    // head as read when the position was taken (within one cycle, for a
    // cycle at that same moment), never more; once a timestamp 100 ms after
    // the first music cycle comes, it follows the timestamps.
    private fun checkClamp(rig: MixRig, d: TestDeck, t: FakeTrack, base: Long, latencyMs: Long) {
        runUntil(rig) { t.firstCycleNanos >= 0 }
        rig.sched.runUntil(t.firstCycleNanos + 600 * ms)
        val lag = latencyMs * 48
        while (rig.sched.now < t.firstCycleNanos + 1_000 * ms) {
            rig.runFor(10.0)
            val s = d.output.snap
            val head = base + (t.steps.lastOrNull { it[0] <= s.nanos }?.get(1) ?: 0L)
            assertTrue("$latencyMs ms: presented ${s.presentedIn}, head $head", s.presentedIn in (head - lag - 1_921)..(head - lag + 1))
        }
        t.stampsUpTo = Long.MAX_VALUE
        rig.runFor(300.0)
        // Taken at the last position snapshot: the head then, with no latency.
        val s = d.output.snap
        val headThen = base + (t.steps.lastOrNull { it[0] <= s.nanos }?.get(1) ?: 0L)
        assertTrue("then it follows the timestamps: presented ${s.presentedIn} at ${s.nanos / 1e6} ms, head $headThen", s.presentedIn >= headThen - 2_000)
    }

    @Test
    fun afterAFreshStartPositionsAreClampedToTheHeadLessTheRoutesLatency() {
        // Speaker 250 ms, Bluetooth 400 ms, Android Auto 500 ms before they
        // are measured: a phone projecting to a car routes to a remote
        // submix, Android Automotive to its bus.
        for ((route, latency) in listOf("2:0:speaker" to 250L, "8:00:11:22:bt" to 400L, "25:0:car" to 500L, "21:0:bus" to 500L)) {
            val (rig, d) = clamped(route)
            checkClamp(rig, d, rig.track(0), 0, latency)
        }
    }

    @Test
    fun aStoredBluetoothFigureNeverWidensTheSpeakersClampButAnUnknownTypeTakesTheLargest() {
        val stored = hashMapOf("8:00:11:22:bt" to speaker(latencyMs = 400))
        val (rig, d) = clamped("2:0:speaker", HashMap(stored))
        checkClamp(rig, d, rig.track(0), 0, 250)
        // A device of no known type: the largest stored, at least 250 ms.
        val (rig3, d3) = clamped("speaker", HashMap(stored))
        checkClamp(rig3, d3, rig3.track(0), 0, 400)
        val (rig4, d4) = clamped("speaker")
        checkClamp(rig4, d4, rig4.track(0), 0, 250)
    }

    @Test
    fun aMeasuredRouteClampsByItsOwnLatency() {
        val (rig, d) = clamped("2:0:speaker", hashMapOf("2:0:speaker" to speaker(latencyMs = 120)))
        checkClamp(rig, d, rig.track(0), 0, 120)
        // And the measure itself: the fake presents each cycle three
        // cycles after it takes it, so at each step the head leads the
        // presented point by 120 ms.
        val rig2 = MixRig()
        val e = rig2.deck(0)
        e.signal = ::counting
        e.play()
        e.feed()
        rig2.runFor(2_500.0)
        val m = rig2.routes.getValue("speaker")
        assertTrue("latency ${m.latencyNanos / 1e6} ms", m.latencyNanos in 78 * ms..122 * ms)
    }

    @Test
    fun theClampRunsFromTheFirstMusicCycleNotTheFirstWrite() {
        val rig = MixRig(latencyCycles = 0)
        rig.builder.nextRoute = "2:0:speaker"
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        // Data at its own pace for 200 ms, then the stream ends: its first
        // music is less than the output's whole buffer, so the output starts
        // once its start is lowered, well after its first music write.
        d.pace(forMs = 200.0)
        rig.sched.at(200 * ms) { d.end() }
        rig.runFor(1.0)
        val t = rig.track(0)
        runUntil(rig) { t.firstCycleNanos >= 0 }
        assertTrue("first cycle ${(t.firstCycleNanos - t.writes.first()) / 1e6} ms after the first write", t.firstCycleNanos - t.writes.first() >= 100 * ms)
        // Its timestamps claim every frame presented at once; for 100 ms
        // after the first cycle positions stay clamped to the head less 250 ms.
        while (rig.sched.now < t.firstCycleNanos + 90 * ms) {
            rig.runFor(1.0)
            val s = d.output.snap
            val head = t.steps.lastOrNull { it[0] <= s.nanos }?.get(1) ?: 0L
            assertTrue("presented ${s.presentedIn}, head $head", s.presentedIn <= maxOf(0L, head - 250L * 48) + 1)
        }
    }

    @Test
    fun theClampAppliesAgainAfterAResume() {
        val (rig, d) = clamped("2:0:speaker")
        rig.runFor(1_500.0)
        rig.track(0).stampsUpTo = Long.MAX_VALUE
        rig.runFor(300.0)
        rig.mixer.pause(hold = false)
        d.pause()
        shadowOf(Looper.getMainLooper()).idle()
        rig.runFor(500.0)
        rig.mixer.wantPlaying(true)
        d.play()
        runUntil(rig) { rig.builder.built.size > 1 && rig.tap.byOutput[rig.lastTrack.id] != null }
        val f = rig.lastTrack
        checkClamp(rig, d, f, rig.tap.byOutput.getValue(f.id).first(), 250)
    }

    // No written silence

    @Test
    fun everyFrameWrittenIsMusicAndNothingGoesIntoAPausedOutput() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(1_000.0)
        val p = rig.track(0)
        rig.mixer.pause(hold = false)
        d.pause()
        shadowOf(Looper.getMainLooper()).idle()
        rig.runFor(100.0)
        val writtenAtPause = p.written
        // The deck keeps buffering 400 ms while paused.
        rig.stalled[0] = rig.sched.now
        d.write(19_200)
        rig.runFor(500.0)
        assertEquals(writtenAtPause, p.written)
        rig.stalled[0] = Long.MAX_VALUE
        rig.mixer.wantPlaying(true)
        d.play()
        d.feed()
        rig.runFor(1_000.0)
        // The gate's hold and the resume wrote no silence: every frame
        // carries a song frame, the onset ramp's first frame excepted.
        for (t in rig.builder.built) {
            for (k in 1 until t.written) assertTrue("track ${t.id} frame $k", t.left(k) != 0f)
        }
    }

    @Test
    fun dataAtRealTimeNeverUnderrunsAFreshOutput() {
        // A first start, a seek, a dead-object rebuild and a rebuffer's
        // refill, each with its data arriving at 1.0 times real time.
        val rig = MixRig(routes = hashMapOf("speaker" to speaker()))
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.pace()
        rig.runFor(1_500.0)
        assertEquals("first start", 0, rig.track(0).underrunCount)
        rig.mixer.announce(0)
        d.restartAt(10_000.0)
        d.play()
        d.pace()
        rig.runFor(1_500.0)
        val seek = rig.lastTrack
        assertEquals("seek", 0, seek.underrunCount)
        seek.dead = true
        rig.runFor(1_500.0)
        val rebuilt = rig.lastTrack
        assertTrue(rebuilt !== seek)
        assertEquals("rebuild", 0, rebuilt.underrunCount)
        // A 300 ms stall, then the refill at real time.
        rig.stalled[0] = rig.sched.now
        rig.runFor(800.0)
        rig.stalled[0] = Long.MAX_VALUE
        d.pace()
        rig.runFor(1_500.0)
        val refilled = rig.lastTrack
        assertTrue(
            "tracks ${rig.builder.built.map { "${it.id}: plays ${it.plays} pauses ${it.pauses} written ${it.written} head ${it.head} threshold ${it.threshold} underruns ${it.underrunCount}" }}; notes ${rig.tap.notes}",
            refilled !== rebuilt,
        )
        assertEquals("refill", 0, refilled.underrunCount)
        val twice = rig.builder.built.firstOrNull { it.plays > 1 }
        assertNull("track ${twice?.id} played ${twice?.plays} times at ${twice?.playTimes}; notes ${rig.tap.notes}", twice)
    }
}
