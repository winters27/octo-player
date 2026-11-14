package app.winters.octo.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixTest {
    private fun sine(hz: Double, seconds: Double, rate: Int, amplitude: Double = 1.0) =
        FloatArray((seconds * rate).toInt()) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun envelopeOf(samples: FloatArray, rate: Int, channels: Int = 1, block: Int = samples.size): SectionEnvelope {
        val builder = EnvelopeBuilder(rate, channels, 0)
        var at = 0
        while (at < samples.size) {
            val length = minOf(block, samples.size - at)
            builder.push(samples, at, length)
            at += length
        }
        return builder.build()
    }

    // Envelope

    @Test
    fun aFullScaleSineReadsMinusThreeDecibels() {
        val envelope = envelopeOf(sine(1_000.0, 1.0, 48_000), 48_000)
        assertEquals(100, envelope.size)
        assertEquals(-3.01f, envelope.db[50], 0.02f)
        assertTrue("1 kHz barely reaches the bass band", envelope.lowDb[50] < -30f)
    }

    @Test
    fun aBassToneFillsTheBassBand() {
        val envelope = envelopeOf(sine(50.0, 1.0, 44_100, amplitude = 0.5), 44_100)
        assertEquals(envelope.db[60], envelope.lowDb[60], 0.5f)
    }

    @Test
    fun silenceIsTheFloorAndOnsetsMarkTheRise() {
        val samples = FloatArray(48_000) + sine(1_000.0, 1.0, 48_000)
        val envelope = envelopeOf(samples, 48_000)
        assertEquals(SILENT_DB, envelope.db[10])
        assertEquals(0f, envelope.onset[10])
        assertTrue(envelope.onset[100] > 1f)
        assertEquals(0f, envelope.onset[150], 0.05f)
    }

    @Test
    fun blocksOfAnySizeAndSplitFramesGiveTheSameEnvelope() {
        val mono = sine(220.0, 2.0, 22_050, amplitude = 0.3)
        val stereo = FloatArray(mono.size * 2) { mono[it / 2] }
        val whole = envelopeOf(mono, 22_050)
        val pieces = envelopeOf(stereo, 22_050, channels = 2, block = 333)
        assertEquals(200, whole.size)
        assertTrue(whole.db.contentEquals(pieces.db))
        assertTrue(whole.lowDb.contentEquals(pieces.lowDb))
        val shorts = ShortArray(mono.size) { (mono[it] * 32767).toInt().toShort() }
        val builder = EnvelopeBuilder(22_050, 1, 0)
        builder.push(shorts)
        assertEquals(whole.db[100], builder.build().db[100], 0.01f)
    }

    @Test
    fun aMinuteOfStereoAt48kHzIsQuick() {
        val song = AutomixCases.songs.getValue("beat-120")
        val samples = song.renderMono(140_000.0, 200_000.0).let { mono -> FloatArray(mono.size * 2) { mono[it / 2] } }
        fun run(): Long {
            val began = System.nanoTime()
            val builder = EnvelopeBuilder(48_000, 2, 140_000)
            var at = 0
            while (at < samples.size) {
                val length = minOf(8_192, samples.size - at)
                builder.push(samples, at, length)
                at += length
            }
            analyzeTail(builder.build())
            return (System.nanoTime() - began) / 1_000_000
        }
        repeat(3) { run() }
        val ms = (1..5).minOf { run() }
        println("automix: envelope and features of 60 s of 48 kHz stereo in $ms ms")
        assertTrue("took $ms ms", ms < 400)
    }

    // Features

    @Test
    fun trailingSilenceEndsTheSound() {
        val features = AutomixCases.tail("silence-tail").features
        assertEquals(192_000.0, features.soundEndMs!!.toDouble(), 30.0)
        assertEquals(features.soundEndMs, features.outroStartMs)
        assertEquals(-60.0, features.gateDb, 0.0)
        assertTrue(features.boundariesMs.any { abs(it - 192_000) <= 300 })
    }

    @Test
    fun aFadeOutStartsTheOutroWhereItDropsSixDecibels() {
        val features = AutomixCases.tail("fade-out").features
        // The fade loses 1.5 dB a second from 180 s, so 6 dB is gone at 184 s.
        assertEquals(184_000.0, features.outroStartMs!!.toDouble(), 500.0)
        assertTrue(features.soundEndMs!! in 205_000..215_000)
        assertNull(features.tempo)
    }

    @Test
    fun aQuietIntroPlaysUnderTheEndOfAHotSong() {
        val head = AutomixCases.head("quiet-intro").features
        // The 1 s level, power averaged, reaches the body less 6 dB once a
        // quarter of its window holds the full-level part.
        assertEquals(5_750.0, head.introEndMs!!.toDouble(), 20.0)
        assertEquals(0L, AutomixCases.head("hot-end").features.introEndMs)
        val plan = plan("quiet-intro")
        // Without the intro a hot ending gets the shortest blend.
        assertEquals(SHORTEST_BLEND_MS, plan("hot-end").overlapMs)
        assertEquals(TransitionKind.INTRO, plan.kind)
        assertTrue("${plan.overlapMs}", plan.overlapMs >= head.introEndMs!! - plan.entryMs)
        assertEquals(199_750L, plan.startMs + plan.overlapMs)
        assertTrue(plan.reason, plan.reason.contains("(full from 5."))
        // Never longer than the longest intro allows, which says so.
        val capped = plan("quiet-intro-capped")
        assertEquals(TransitionKind.INTRO, capped.kind)
        assertEquals(6_000L, capped.overlapMs)
        assertTrue(capped.reason, capped.reason.contains(", capped)"))
    }

    @Test
    fun aHeadMeasuredAgainstTheWholeSongSeesALongQuietIntro() {
        val song = AutomixCases.songs.getValue("long-intro")
        val scouted = AutomixCases.head("long-intro")
        assertEquals(AUTOMIX_HEAD_MS, scouted.envelope.endMs)
        // Against its own body, which is mostly the quiet pad, the start
        // sounds full at once.
        assertEquals(0L, scouted.features.introEndMs)
        // Against the whole song's body the intro ends where the full level
        // comes in.
        val body = song.wholeBodyDb()!!
        val head = song.profileHead(body).features
        assertEquals(body, head.bodyDb, 0.0)
        assertEquals(34_750.0, head.introEndMs!!.toDouble(), 30.0)
    }

    @Test
    fun aHeadThatNeverReachesTheBodyEndsItsIntroAtTheHeadsEnd() {
        val head = AutomixCases.songs.getValue("endless-intro").profileHead().features
        assertEquals(0L, head.soundStartMs)
        assertEquals(AUTOMIX_HEAD_MS, head.introEndMs)
        // A silent head has no intro to end.
        assertNull(analyzeHead(envelopeOf(FloatArray(22_050 * 5), 22_050), bodyLevelDb = -14.0).features.introEndMs)
        // A tail keeps the rule it had.
        assertNull(analyzeTail(AutomixCases.songs.getValue("endless-intro").envelope(0.0, 45_000.0), bodyLevelDb = -14.0).features.introEndMs)
    }

    @Test
    fun everyPlanNamesTheLatestItMayEnd() {
        // A planned blend: where the outgoing song's sound ends, at most
        // END_MARGIN_MS before its end.
        assertEquals(200_000L - END_MARGIN_MS, plan("hot-end").latestEndMs)
        assertEquals(200_000L - END_MARGIN_MS, plan("same-tempo").latestEndMs)
        assertEquals(192_000.0, plan("trailing-silence").latestEndMs.toDouble(), 30.0)
        // A fading song blends at its outro, well before its sound ends.
        val fading = plan("fade-out")
        assertEquals(AutomixCases.tail("fade-out").features.soundEndMs, fading.latestEndMs)
        assertTrue("${fading.startMs + fading.overlapMs}", fading.startMs + fading.overlapMs < fading.latestEndMs - 10_000)
        // A late blend: the end it was placed against.
        assertTrue(plan("late").late)
        assertEquals(200_000L - END_MARGIN_MS, plan("late").latestEndMs)
        assertEquals(220_000L - END_MARGIN_MS, plan("no-tail").let { assertTrue(it.late); it.latestEndMs })
        // The fixed crossfade and the plain ones: the song's end.
        assertEquals(200_000L, plan("smart-off").latestEndMs)
        assertEquals(200_000L, plan("classical").latestEndMs)
        assertEquals(200_000L, plan("podcast").latestEndMs)
        // A gapless join, also when it is too late to blend: the song's end.
        assertEquals(200_000L, plan("album-in-order").latestEndMs)
        assertEquals(200_000L, plan("crossfade-off").latestEndMs)
        assertEquals(TransitionKind.GAPLESS, plan("too-late").kind)
        assertEquals(200_000L, plan("too-late").latestEndMs)
        for (pair in AutomixCases.pairs) {
            val p = AutomixCases.plan(pair)
            assertTrue("${pair.name}: ${p.latestEndMs}", p.startMs + p.overlapMs <= p.latestEndMs)
            if (p.kind == TransitionKind.INTRO) continue
            // Any other kind holds only through the next song's silent
            // lead-in, which ends before its beats meet A's.
            assertTrue(pair.name, p.holdShare * p.overlapMs <= p.lockMs + 1)
            assertEquals(pair.name, 0.0, p.riseShare, 0.0)
        }
        // The plain crossfade for a short song: its end.
        val short = planTransition(FadeSong("a", 1, 30_000), FadeSong("b", 1, 180_000), AutomixCases.tail("hot-end"), AutomixCases.head("plain-b"), AutomixSettings(8_000))
        assertEquals(TransitionKind.CROSSFADE, short.kind)
        assertEquals(30_000L, short.latestEndMs)
    }

    @Test
    fun aLateStartMayMoveAPlanAnyNumberOfBarsOnlyWhereItsBeatsCannotDrift() {
        // No bar grid: the fixed crossfade, the plain ones, gapless joins,
        // unlocked blends and late ones, and one tempo untrusted.
        val unlocked = listOf("smart-off", "classical", "podcast", "album-in-order", "crossfade-off", "too-late", "hot-end", "no-analysis", "no-tail", "no-head", "pace-steady")
        for (name in unlocked) {
            assertTrue(name, !plan(name).barLocked)
            assertEquals(name, LATE_MOVE_UNBOUNDED, plan(name).lateMoveBars)
        }
        // Both tempos confident but not lined up: kept where both songs are
        // never heard together, which a move could undo, so none.
        for (name in listOf("pace", "skip-silence", "drift-phone-pace")) {
            assertTrue(name, !plan(name).barLocked && plan(name).kind != TransitionKind.GAPLESS)
            assertEquals(name, 0, plan(name).lateMoveBars)
        }
        val short = planTransition(FadeSong("a", 1, 30_000), FadeSong("b", 1, 180_000), AutomixCases.tail("hot-end"), AutomixCases.head("plain-b"), AutomixSettings(8_000))
        assertEquals(LATE_MOVE_UNBOUNDED, short.lateMoveBars)
        // A rate keeps the beats together wherever the blend sits.
        for (name in listOf("beat-match", "drift-late-matched")) {
            assertNotNull(name, plan(name).beatMatchRate)
            assertEquals(name, LATE_MOVE_UNBOUNDED, plan(name).lateMoveBars)
        }
        // An intro's move must also keep its trim, so it is never unbounded.
        for (pair in AutomixCases.pairs) {
            val p = AutomixCases.plan(pair)
            if (p.kind == TransitionKind.INTRO) assertTrue("${pair.name}: ${p.reason}", p.lateMoveBars != LATE_MOVE_UNBOUNDED)
        }
        // Two equal tempos never drift apart either.
        val tail = AutomixCases.tail("beat-120")
        val head = AutomixCases.head("beat-120-b")
        val grid = tail.features.tempo!!
        val equal = SectionAnalysis.fromFeatures(head.envelope, head.features.copy(tempo = head.features.tempo!!.copy(bpm = grid.bpm, beatMs = grid.beatMs)))
        val same = planTransition(AutomixCases.pair("same-tempo").current, AutomixCases.pair("same-tempo").next, tail, equal, AutomixSettings(8_000))
        assertTrue(same.reason, same.barLocked && same.beatMatchRate == null)
        assertEquals(LATE_MOVE_UNBOUNDED, same.lateMoveBars)
        // Locked to the bar with a tempo gap and no rate: only as far as the
        // latest end leaves room and the beats stay within the bound.
        for (name in listOf("beat-match-off", "same-tempo", "drift-1", "drift-3", "late")) {
            val p = plan(name)
            assertTrue(name, p.barLocked && p.beatMatchRate == null)
            val room = ((p.latestEndMs - p.startMs - p.overlapMs) / p.beatMs!! / BEATS_PER_BAR).toInt()
            assertTrue("$name: ${p.lateMoveBars} of $room", p.lateMoveBars in 0..room)
        }
    }

    // How far apart the beats of `plan` are, in song time, where they are
    // furthest apart while both songs are within 10 dB of their bodies,
    // with the plan moved `bars` bars later; its beats meet at `lockMs`
    // (counted the same way as the plan is moved).
    private fun driftOf(plan: TransitionPlan, a: SectionAnalysis, b: SectionAnalysis, gap: Double, lockMs: Double, bars: Int): Double {
        val shift = bars * plan.beatMs!! * BEATS_PER_BAR
        val room = Math.pow(10.0, -OVERLAP_HEADROOM_DB / 20)
        var first = Double.NaN
        var last = Double.NaN
        var since = 0.0
        while (since <= plan.overlapMs) {
            val t = since / plan.overlapMs
            val out = plan.outGain(t) * room
            val into = plan.inGain(t) * room
            val at = plan.startMs + since + shift
            val heardA = out > 0 && a.levelAt(at) + 20 * kotlin.math.log10(out) >= a.features.bodyDb - BOTH_HEARD_BELOW_BODY_DB
            val heardB = into > 0 && b.levelAt(plan.entryMs + since * (plan.beatMatchRate ?: 1.0)) + 20 * kotlin.math.log10(into) >= b.features.bodyDb - BOTH_HEARD_BELOW_BODY_DB
            if (heardA && heardB) {
                if (first.isNaN()) first = at
                last = at
            }
            since += 10.0
        }
        if (first.isNaN()) return 0.0
        val lock = lockMs + shift
        return gap * maxOf(0.0, last - lock, lock - first)
    }

    @Test
    fun aLateMoveStopsAtTheFirstBarWhoseBeatsWouldBeHeardApart() {
        val pair = AutomixCases.pair("late-move-count")
        val p = AutomixCases.plan(pair)
        val a = AutomixCases.tail(pair.a)
        val b = AutomixCases.head(pair.b)
        assertTrue(p.reason, p.barLocked && p.beatMatchRate == null && p.kind != TransitionKind.INTRO)
        val gap = abs(foldTempoRatio(a.features.tempo!!.bpm / b.features.tempo!!.bpm) - 1)
        val bar = p.beatMs!! * BEATS_PER_BAR
        val count = p.lateMoveBars
        // The beats meet where B's first beat from its entry plays.
        val gridB = b.features.tempo!!
        val firstBeat = gridB.firstBeatMs + kotlin.math.ceil((p.entryMs - gridB.firstBeatMs) / gridB.beatMs - 1e-9) * gridB.beatMs
        val lock = p.startMs + (firstBeat - p.entryMs)
        // The plan sits in A's quiet stretch, with room to move several bars
        // before A's louder end comes under its blend.
        assertTrue("$count: ${p.reason}", count >= 1)
        assertTrue(p.reason, p.startMs + p.overlapMs + (count + 1) * bar <= p.latestEndMs)
        for (bars in 0..count) {
            val drift = driftOf(p, a, b, gap, lock, bars)
            assertTrue("$bars bars later: $drift ms", drift <= DRIFT_LIMIT_MS + 0.5)
        }
        val past = driftOf(p, a, b, gap, lock, count + 1)
        assertTrue("${count + 1} bars later: $past ms", past > DRIFT_LIMIT_MS)
    }

    @Test
    fun aHotEndingHasNoOutro() {
        val features = AutomixCases.tail("hot-end").features
        assertEquals(200_000L, features.soundEndMs)
        assertEquals(features.soundEndMs, features.outroStartMs)
        assertNull("a steady tone has no beat", features.tempo)
    }

    @Test
    fun aThreeThreeTwoKickPatternKeepsItsBeat() {
        // A half-time beat at 80 BPM whose kicks fall on the 1st, 4th and 7th
        // sixteenths of every two beats, with a snare on the third beat of
        // each bar: the kicks repeat every three sixteenths more often than
        // every beat.
        val length = 60_000.0
        fun hit(hz: Double, db: Double, bpm: Double, firstMs: Double, decayMs: Double) =
            SongLayer("pulse", 0.0, length, hz = hz, db = db, bpm = bpm, firstMs = firstMs, every = 1, decayMs = decayMs)
        val song = SyntheticSong(
            44_100, 1, 60_000,
            listOf(
                SongLayer("tone", 0.0, length, hz = 300.0, db = -30.0),
                hit(60.0, -6.0, 40.0, 0.0, 80.0),
                hit(60.0, -6.0, 40.0, 562.5, 80.0),
                hit(60.0, -6.0, 40.0, 1_125.0, 80.0),
                hit(2_000.0, -12.0, 20.0, 1_500.0, 30.0),
            ),
        )
        val tempo = song.head().features.tempo!!
        // 80 BPM or its double, never the 107 BPM of the kicks' three sixteenths.
        assertEquals("$tempo", 1.0, foldTempoRatio(tempo.bpm / 80.0), 0.01)
        assertTrue("$tempo", tempo.confident)
    }

    @Test
    fun aClickTrackHasItsTempoBeatAndBar() {
        val tempo = AutomixCases.tail("beat-120").features.tempo
        assertNotNull(tempo)
        tempo!!
        assertTrue(tempo.confident)
        assertTrue(tempo.consistency >= 0.9)
        assertEquals(120.0, tempo.bpm, 1.0)
        // Bars start at 500 ms and every 2 s after.
        assertEquals(0.0, offBy(tempo.downbeatMs - 500, 2_000.0), 20.0)
        assertEquals(0.0, offBy(tempo.firstBeatMs - 500, 500.0), 20.0)
        val head = AutomixCases.head("beat-120-b").features.tempo!!
        assertEquals(1_000.0, head.downbeatMs, 20.0)
    }

    @Test
    fun otherTempos() {
        assertEquals(100.0, AutomixCases.tail("beat-100").features.tempo!!.bpm, 1.0)
        assertEquals(118.0, AutomixCases.head("beat-118").features.tempo!!.bpm, 0.1)
        assertEquals(118.0, AutomixCases.tail("beat-118").features.tempo!!.bpm, 0.1)
        assertTrue(AutomixCases.tail("beat-118").features.tempo!!.confident)
    }

    @Test
    fun aSilentSectionHasNoSound() {
        val analysis = analyzeHead(envelopeOf(FloatArray(48_000 * 5), 48_000))
        assertNull(analysis.features.soundStartMs)
        assertNull(analysis.features.soundEndMs)
        assertNull(analysis.features.tempo)
    }

    @Test
    fun scatteredHitsAreNotABeat() {
        // Hits at random times: plenty of onsets, no steady grid.
        var seed = 12345L
        fun next(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return (seed ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        val rate = 22_050
        val samples = FloatArray(rate * 30)
        var at = 0.0
        while (true) {
            at += 0.15 + next() * 0.6
            val start = (at * rate).toInt()
            if (start >= samples.size) break
            for (i in 0 until minOf(2_000, samples.size - start)) {
                samples[start + i] += (0.5 * exp(-i / 300.0) * sin(2 * PI * 80.0 * i / rate)).toFloat()
            }
        }
        val tempo = analyzeHead(envelopeOf(samples, rate)).features.tempo
        assertTrue("found $tempo", tempo == null || !tempo.confident)
    }

    @Test
    fun aTagTempoPicksOnlyAmongTheFoundOctaves() {
        val envelope = AutomixCases.songs.getValue("beat-120").envelope(0.0, 30_000.0)
        val found = analyzeHead(envelope).features.tempo!!
        assertEquals(120.0, found.bpm, 0.5)
        assertEquals(60.0, analyzeHead(envelope, tagBpm = 61.0).features.tempo!!.bpm, 0.5)
        assertEquals(240.0, analyzeHead(envelope, tagBpm = 238.0).features.tempo!!.bpm, 1.0)
        assertEquals(found, analyzeHead(envelope, tagBpm = 121.0).features.tempo)
        // A tag near no octave of the tempo found is ignored.
        for (tag in listOf(125.0, 62.0, 0.0, -120.0, 90.0, Double.NaN)) {
            assertEquals("tag $tag", found, analyzeHead(envelope, tagBpm = tag).features.tempo)
        }
        val fast = AutomixCases.songs.getValue("beat-128").envelope(0.0, 30_000.0)
        val fastFound = analyzeHead(fast).features.tempo!!
        assertEquals(128.0, fastFound.bpm, 0.5)
        assertEquals(fastFound, analyzeHead(fast, tagBpm = 70.0).features.tempo)
    }

    @Test
    fun theLiveTapHearsTheWholeSong() {
        val song = AutomixCases.songs.getValue("fade-out")
        val tap = LiveTap(song.rate, 1)
        val samples = song.renderMono(0.0, 220_000.0)
        var at = 0
        while (at < samples.size) {
            val length = minOf(4_800, samples.size - at)
            tap.push(samples, at, length)
            at += length
        }
        assertEquals(220_000L, tap.heardMs())
        assertEquals(-15.25, tap.bodyLevelDb()!!, 0.1)
        assertNull("a steady tone has no tempo", tap.tempoPrior())
        val beat = AutomixCases.songs.getValue("beat-100")
        val beatTap = LiveTap(beat.rate, 1)
        beatTap.push(beat.renderMono(0.0, 60_000.0))
        assertEquals(100.0, beatTap.tempoPrior()!!, 0.1)
        assertNull(LiveTap(44_100, 2).bodyLevelDb())
    }

    @Test
    fun theFftMatchesAPlainDft() {
        val n = 64
        val input = DoubleArray(n) { sin(it * 0.37) + 0.5 * cos(it * 1.9) + if (it == 5) 1.0 else 0.0 }
        val out = DoubleArray(n / 2 + 1)
        RealFft(n).magnitudes(input, out)
        for (k in 0..n / 2) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                re += input[t] * cos(2 * PI * k * t / n)
                im -= input[t] * sin(2 * PI * k * t / n)
            }
            assertEquals("bin $k", sqrt(re * re + im * im), out[k], 1e-9)
        }
    }

    @Test
    fun theBiquadsPassAndCutWhereTheyShould() {
        fun gainOf(filter: Biquad, hz: Double, rate: Int): Double {
            var peak = 0.0
            for (i in 0 until rate) {
                val y = filter.process(sin(2 * PI * hz * i / rate))
                if (i > rate / 2) peak = maxOf(peak, abs(y))
            }
            return peak
        }
        assertEquals(1.0, gainOf(Biquad.lowPass(48_000, 1_000.0), 100.0, 48_000), 0.01)
        assertEquals(0.7071, gainOf(Biquad.lowPass(48_000, 1_000.0), 1_000.0, 48_000), 0.01)
        assertTrue(gainOf(Biquad.lowPass(48_000, 1_000.0), 10_000.0, 48_000) < 0.02)
        assertEquals(1.0, gainOf(Biquad.highPass(44_100, 200.0), 5_000.0, 44_100), 0.01)
        assertTrue(gainOf(Biquad.highPass(44_100, 200.0), 20.0, 44_100) < 0.02)
        // An 18 kHz cutoff at 22.05 kHz is held below the top of the band and stays stable.
        assertTrue(gainOf(Biquad.lowPass(22_050, 18_000.0), 1_000.0, 22_050) < 1.01)
    }

    private fun offBy(x: Double, period: Double): Double {
        val r = ((x % period) + period) % period
        return if (r > period / 2) r - period else r
    }

    // Plans

    private fun plan(name: String) = AutomixCases.plan(AutomixCases.pair(name))

    @Test
    fun trailingSilenceIsSkipped() {
        val plan = plan("trailing-silence")
        val soundEnd = AutomixCases.tail("silence-tail").features.soundEndMs!!
        assertTrue(plan.startMs + plan.overlapMs <= soundEnd)
        assertTrue(plan.startMs + plan.overlapMs >= soundEnd - 1_000)
        assertEquals(1_800L, plan.overlapMs)
        assertEquals(TransitionKind.CUT, plan.kind)
        assertEquals(1_480.0, plan.entryMs.toDouble(), 30.0)
    }

    @Test
    fun aLongFadeOutBlendsFromTheOutro() {
        val plan = plan("fade-out")
        val outro = AutomixCases.tail("fade-out").features.outroStartMs!!
        assertTrue("start ${plan.startMs} outro $outro", plan.startMs in outro - 500..outro + 3_000)
        assertEquals(8_000L, plan.overlapMs)
        assertEquals("the next song is louder than the fading one", TransitionKind.LIFT, plan.kind)
        assertEquals(LIFT_CURVE, plan.k, 0.0)
        assertTrue(plan.reason.startsWith("lift at"))
    }

    @Test
    fun aHotEndingCutsShortAtTheEnd() {
        val plan = plan("hot-end")
        assertEquals(200_000L - END_MARGIN_MS, plan.startMs + plan.overlapMs)
        assertEquals(TransitionKind.CUT, plan.kind)
        assertEquals(CUT_CURVE, plan.k, 0.0)
    }

    @Test
    fun theSameTempoBlendsFourBarsFromABarLine() {
        val plan = plan("same-tempo")
        assertEquals(0.0, offBy(plan.startMs - 500.0, 2_000.0), 20.0)
        assertEquals(8_000.0, plan.overlapMs.toDouble(), 10.0)
        assertEquals(TransitionKind.BLEND, plan.kind)
        // The next song's last beat at or before its first sound (its
        // clicks start at 1 s, every half second): none of it is cut.
        assertEquals(500.0, plan.entryMs.toDouble(), 20.0)
        assertTrue(plan.barLocked)
        assertEquals(OVERLAP_HEADROOM_DB, plan.headroomDb, 0.0)
        assertEquals(FILTER_STRENGTH, plan.filterStrength, 0.0)
        assertNull(plan.beatMatchRate)
        assertTrue(plan.reason.contains("4 bars of 120.0 BPM"))
        assertEquals(8_000L, plan("same-tempo-long").overlapMs)
    }

    @Test
    fun aShortSliderKeepsWholeBars() {
        assertEquals(4_000.0, plan("same-tempo-short").overlapMs.toDouble(), 10.0)
    }

    @Test
    fun temposTooFarApartNeverSoundTogetherOffTheBeat() {
        // 120 against 100 BPM, 20% apart: the click tracks are heard only
        // around their kicks, and the cut left sits where the two are never
        // clearly heard at once.
        val plan = plan("tempo-clash")
        assertEquals(plan.reason, TransitionKind.CUT, plan.kind)
        assertTrue(plan.reason, plan.barLocked && plan.driftAtEndMs <= DRIFT_LIMIT_MS)
        assertEquals(0L, plan.bothHeardEndMs)
    }

    @Test
    fun beatMatchNudgesTheNextSongOntoTheBar() {
        val plan = plan("beat-match")
        assertEquals(120.0 / 118.0, plan.beatMatchRate!!, 0.005)
        val head = AutomixCases.head("beat-118").features.tempo!!
        // Its last bar line at or before its first sound (0.4 s) falls before
        // its start: it comes in from 0, and the outgoing song starts earlier
        // by its first bar line's time, so that bar line lands on one of A's.
        assertEquals(0L, plan.entryMs)
        val firstBar = head.downbeatMs - kotlin.math.floor(head.downbeatMs / head.barMs) * head.barMs
        val meets = plan.startMs + firstBar / plan.beatMatchRate!!
        assertTrue("$meets", onBar("beat-120", meets))
        assertEquals(8_000.0, plan.overlapMs.toDouble(), 10.0)
        assertNull("beat match is off unless asked for", plan("beat-match-off").beatMatchRate)
    }

    @Test
    fun smartOffIsThePlainCrossfade() {
        val plan = plan("smart-off")
        assertEquals(TransitionKind.CROSSFADE, plan.kind)
        assertEquals(192_000L, plan.startMs)
        assertEquals(8_000L, plan.overlapMs)
        assertEquals(0L, plan.entryMs)
        // Exactly the equal-power crossfade from before planned transitions.
        assertEquals(0.0, plan.k, 0.0)
        assertEquals(0.0, plan.filterStrength, 0.0)
        assertEquals(0.0, plan.headroomDb, 0.0)
    }

    @Test
    fun thePlainCrossfadeForAGenreKeepsItsCurve() {
        val plan = plan("classical")
        assertEquals(TransitionKind.CROSSFADE, plan.kind)
        assertEquals(CROSSFADE_CURVE, plan.k, 0.0)
        assertEquals(0.0, plan.filterStrength, 0.0)
    }

    @Test
    fun withoutTheTailItBlendsLateAtTheEnd() {
        for (name in listOf("no-analysis", "no-tail")) {
            val plan = plan(name)
            val length = AutomixCases.songs.getValue(AutomixCases.pair(name).a).lengthMs
            assertTrue(name, plan.late)
            assertEquals(name, length - END_MARGIN_MS - 8_000, plan.startMs)
            assertEquals(name, 8_000L, plan.overlapMs)
            assertEquals(name, FILTER_STRENGTH, plan.filterStrength, 0.0)
            assertTrue(name, !plan.barLocked)
        }
        assertEquals(0L, plan("no-analysis").entryMs)
        assertEquals("the next song still skips its silence", 1_480.0, plan("no-tail").entryMs.toDouble(), 30.0)
    }

    @Test
    fun withoutTheHeadThereIsNoBarLock() {
        val plan = plan("no-head")
        assertTrue(!plan.barLocked)
        assertTrue(!plan.late)
        assertEquals(0L, plan.entryMs)
    }

    @Test
    fun genresThatShouldNotBeMixedGetThePlainCrossfade() {
        assertEquals(TransitionKind.CROSSFADE, plan("classical").kind)
        assertEquals(TransitionKind.CROSSFADE, plan("podcast").kind)
        assertTrue(plan("podcast").reason.endsWith("genre Podcast"))
    }

    @Test
    fun paceAndSkipSilenceTurnOffBarLockAndBeatMatch() {
        // Both tempos are confident, so with the beats unable to line up a
        // blend where both songs are clearly heard together is never
        // planned: these click tracks are heard only around their kicks, and
        // the cut sits where the two never are at once.
        for (name in listOf("pace", "skip-silence")) {
            val plan = plan(name)
            assertTrue(name, !plan.barLocked)
            assertNull(name, plan.beatMatchRate)
            assertEquals(name, TransitionKind.CUT, plan.kind)
            assertEquals(name, 0L, plan.bothHeardEndMs)
        }
        // Where every start has both heard together, they join gaplessly.
        assertEquals("gapless: the beats cannot be lined up", plan("pace-steady").reason)
        // Where the outgoing song is no longer clearly heard, the blend goes
        // ahead unlocked, as it always did.
        val outro = plan("drift-phone-pace")
        assertTrue(outro.reason, !outro.barLocked && outro.beatMatchRate == null && outro.kind != TransitionKind.GAPLESS)
        assertEquals(0L, outro.bothHeardEndMs)
        assertTrue(outro.reason, outro.startMs >= 190_000 - 250)
    }

    @Test
    fun tooLateForTheChosenStartBlendsAtTheEnd() {
        // Both tempos trusted: from A's first bar line after the start it
        // could have had, to the latest end.
        val late = plan("late")
        assertTrue(late.late)
        assertTrue(late.barLocked)
        assertEquals(0.0, offBy(late.startMs - 500.0, 2_000.0), 20.0)
        assertTrue(late.reason, late.startMs >= 199_750L - 8_000)
        assertEquals(199_750L, late.startMs + late.overlapMs)
        // Under SHORTEST_BLEND_MS from the next bar line: a cut from A's
        // earliest beat after the decision.
        val short = plan("late-short")
        assertTrue(short.late && short.barLocked)
        assertEquals(0.0, offBy(short.startMs - 500.0, 500.0), 20.0)
        assertTrue(short.reason, short.startMs >= 197_000L && short.overlapMs <= SHORTEST_BLEND_MS)
        assertEquals(199_750L, short.startMs + short.overlapMs)
        assertEquals(TransitionKind.GAPLESS, plan("too-late").kind)
    }

    @Test
    fun theStartLeavesTimeToPreRoll() {
        val plan = plan("pre-roll")
        assertTrue(plan.startMs >= 183_000 + PRE_ROLL_MS)
        assertTrue(!plan.late)
    }

    @Test
    fun theBlendWaitsUntilTheSongCountsAsPlayed() {
        // At 170 s with 60 s heard, the song counts as played (110 s heard)
        // only at 220 s, past the end, so the blend goes to the very end.
        val plan = plan("seeked-forward")
        assertTrue(plan.late)
        val soundEnd = AutomixCases.tail("fade-out").features.soundEndMs!!
        assertEquals(soundEnd, plan.startMs + plan.overlapMs)
        for (pair in AutomixCases.pairs) {
            val p = AutomixCases.plan(pair)
            if (p.kind == TransitionKind.GAPLESS || p.kind == TransitionKind.CROSSFADE) continue
            assertTrue(pair.name, p.startMs >= AutomixCases.songs.getValue(pair.a).lengthMs / 2)
        }
    }

    @Test
    fun theWholeSongsLevelMovesTheOutro() {
        val plan = plan("whole-song-level")
        val moved = AutomixCases.tail("fade-out").withBodyLevel(-24.0).features
        assertEquals(-24.0, moved.bodyDb, 0.0)
        assertTrue(moved.outroStartMs!! > AutomixCases.tail("fade-out").features.outroStartMs!! + 4_000)
        assertTrue(plan.startMs in moved.outroStartMs!! - 500..moved.outroStartMs!! + 3_000)
    }

    @Test
    fun theWholeSongsTempoSetsTheOctaveOrDropsTheLock() {
        val half = plan("tempo-prior")
        assertEquals(1_000.0, half.beatMs!!, 1.0)
        assertTrue(half.reason, half.reason.contains("of 120.0 BPM"))
        assertTrue("a tempo the whole song does not share is not trusted", !plan("tempo-prior-off").barLocked)
    }

    @Test
    fun anAlbumInOrderStaysGapless() {
        val plan = plan("album-in-order")
        assertEquals(TransitionKind.GAPLESS, plan.kind)
        assertEquals(0L, plan.overlapMs)
        assertEquals(200_000L, plan.startMs)
        assertEquals("gapless: the album plays in order", plan.reason)
    }

    @Test
    fun repeatOneTheSleepTimerAndCrossfadeOffNeverBlend() {
        assertEquals(TransitionKind.GAPLESS, plan("stop-at-end").kind)
        assertEquals(TransitionKind.GAPLESS, plan("repeat-one").kind)
        assertEquals("gapless: repeat one", plan("repeat-one").reason)
        assertEquals(TransitionKind.GAPLESS, plan("crossfade-off").kind)
    }

    @Test
    fun sweepsOffLeavesTheFiltersOpen() {
        val plan = plan("no-sweeps")
        assertEquals(0.0, plan.filterStrength, 0.0)
        assertEquals(OPEN_LOW_PASS_HZ, plan.outgoingLowPassAt(0.9), 1e-9)
        assertEquals(OPEN_HIGH_PASS_HZ, plan.outgoingHighPassAt(0.9), 1e-9)
        assertEquals(INCOMING_OPEN_HZ, plan.incomingHighPassAt(0.1), 1e-9)
    }

    @Test
    fun aShortSongGetsThePlainCrossfade() {
        val tail = AutomixCases.tail("hot-end")
        val head = AutomixCases.head("plain-b")
        val plan = planTransition(
            FadeSong("a", 1, 30_000), FadeSong("b", 1, 180_000), tail, head, AutomixSettings(8_000),
        )
        assertEquals(TransitionKind.CROSSFADE, plan.kind)
        assertEquals(22_000L, plan.startMs)
    }

    // A's bar line nearest a time, from its tail's grid.
    private fun barOf(song: String, ms: Double): Double {
        val grid = AutomixCases.tail(song).features.tempo!!
        return grid.downbeatMs + roundHalfUp((ms - grid.downbeatMs) / grid.barMs) * grid.barMs
    }

    private fun onBar(song: String, ms: Double, within: Double = 2.0) = abs(barOf(song, ms) - ms) <= within

    // Where in A's time a locked plan's beats meet: B's first beat (its
    // first bar line with a rate) from its entry on, played from the start.
    private fun lockOf(name: String): Double {
        val p = plan(name)
        val grid = AutomixCases.head(AutomixCases.pair(name).b).features.tempo!!
        val rate = p.beatMatchRate
        val (anchor, step) = if (rate != null) grid.downbeatMs to grid.barMs else grid.firstBeatMs to grid.beatMs
        val first = anchor + kotlin.math.ceil((p.entryMs - anchor) / step - 1e-9) * step
        return p.startMs + (first - p.entryMs) / (rate ?: 1.0)
    }

    @Test
    fun fullLevelMusicIsKeptOverEarlyExitsAtSectionBoundaries() {
        val tail = AutomixCases.tail("steady-100-sections")
        // Section boundaries 24 s and 4.8 s before the last bars, each within
        // the reach of a bar line's boundary bonus.
        assertTrue("${tail.features.boundariesMs}", tail.features.boundariesMs.any { abs(it - 170_400) <= 250 })
        assertTrue("${tail.features.boundariesMs}", tail.features.boundariesMs.any { abs(it - 189_600) <= 250 })
        val plan = plan("kept-music")
        // The last bars, losing at most a second of the music at full level.
        assertTrue(plan.reason, plan.latestEndMs - (plan.startMs + plan.overlapMs) <= KEPT_MUSIC_LIMIT_MS)
        assertTrue(plan.reason, plan.startMs > 189_600 + 1_000)
        // A song fading out still leaves at its outro.
        val fading = plan("fade-out")
        assertTrue(fading.reason, fading.startMs + fading.overlapMs < fading.latestEndMs - 10_000)
    }

    @Test
    fun aPlaceThatCutsTheSongShortIsNeverPlannedEvenWhenItIsTheOnlyOneLeft() {
        // Tempos 12% to 25% apart, the outgoing song at full level to the
        // end: only blends and intros in its quiet stretch keep the beats
        // apart, and each would cut seconds of its music. The songs join
        // gaplessly at its end instead.
        for (name in listOf("kept-cut-25", "kept-intro-24", "kept-intro-20", "kept-blend-12", "kept-intro-18")) {
            val p = plan(name)
            assertEquals("$name: ${p.reason}", TransitionKind.GAPLESS, p.kind)
            assertEquals(name, AutomixCases.pair(name).current.durationMs, p.startMs)
            assertEquals(name, "gapless: every blend left would cut the song short", p.reason)
        }
        // Where the places that keep the beats apart cut under a second of
        // the full-level music, one of them is still planned.
        val short = plan("kept-short-cut")
        val tail = AutomixCases.tail("dip-short")
        val end = (short.startMs + short.overlapMs).toDouble()
        assertTrue(short.reason, short.kind != TransitionKind.GAPLESS && end < short.latestEndMs - 500)
        val cut = tail.msAtLeast(end, short.latestEndMs.toDouble(), tail.features.bodyDb - KEPT_MUSIC_BELOW_BODY_DB)
        assertTrue("$cut", cut > 500 && cut <= KEPT_MUSIC_LIMIT_MS)
        assertTrue(short.reason, short.driftAtEndMs <= DRIFT_LIMIT_MS)
    }

    @Test
    fun aBlendEndingOffTheBarStretchesToTheLatestEnd() {
        // The last bar line a whole-bar blend can end on is 1.9 s before the
        // latest end: the blend keeps its bar-line start and runs to it.
        val plan = plan("off-grid-end")
        assertEquals(plan.latestEndMs, plan.startMs + plan.overlapMs)
        assertTrue(plan.reason, onBar("steady-100", lockOf("off-grid-end")))
        assertEquals(7_200.0 + 1_900, plan.overlapMs.toDouble(), 30.0)
        assertTrue(plan.reason, !plan.reason.contains("bars of"))
        // Over the longest blend from there: one bar later.
        val over = plan("off-grid-end-over")
        assertEquals(over.latestEndMs, over.startMs + over.overlapMs)
        assertTrue(over.reason, onBar("steady-100", lockOf("off-grid-end-over")))
        assertEquals(plan.startMs + 2_400.0, over.startMs.toDouble(), 30.0)
    }

    @Test
    fun anIntroEndingOffTheBarFadesTheOutgoingSongToTheLatestEnd() {
        val introEnd = AutomixCases.head("quiet-100").features.introEndMs!!
        // Five bars to the next song's full point, a two-bar fade: A holds
        // for four bars, and only its fade end moves to the latest end.
        val plan = plan("off-grid-intro")
        assertEquals(plan.reason, TransitionKind.INTRO, plan.kind)
        assertEquals(plan.latestEndMs, plan.startMs + plan.overlapMs)
        assertTrue(plan.reason, onBar("steady-100", lockOf("off-grid-intro")))
        assertEquals(9_600.0, plan.holdShare * plan.overlapMs, 30.0)
        assertEquals(14_400.0 + 1_500, plan.overlapMs.toDouble(), 30.0)
        // Over the longest intro: B's full point kept, one bar later.
        val capped = plan("off-grid-intro-capped")
        assertEquals(capped.reason, TransitionKind.INTRO, capped.kind)
        assertEquals(capped.latestEndMs, capped.startMs + capped.overlapMs)
        assertTrue(capped.reason, onBar("steady-100", lockOf("off-grid-intro-capped")))
        assertEquals(plan("off-grid-intro").startMs + 2_400.0, capped.startMs.toDouble(), 30.0)
        // A at its own level until four bars in.
        val holdEnd = capped.holdShare * capped.overlapMs
        assertEquals(9_600.0, holdEnd, 30.0)
        assertEquals(1.0, capped.outGain((holdEnd - 1) / capped.overlapMs), 0.0)
        // At the middle of A's fade, B has played at least its quiet start
        // less half a bar.
        val fadeMiddle = holdEnd + (capped.overlapMs - holdEnd) / 2
        assertTrue("$fadeMiddle", capped.entryMs + fadeMiddle >= introEnd - 2_400 / 2.0)
        assertTrue(capped.reason, capped.overlapMs <= AUTOMIX_LONGEST_INTRO_MS)
        // With a fade of half the quiet start, so short that a bar later it
        // would be under half the planned fade: no capped twin. The intro
        // left ends on its bar, 1.2 s of full-level music before the latest
        // end, so it is not planned; the blend ends at the latest end.
        val short = plan("off-grid-intro-short-fade")
        assertTrue(short.reason, short.kind != TransitionKind.INTRO && short.kind != TransitionKind.GAPLESS)
        assertEquals(short.latestEndMs, short.startMs + short.overlapMs)
    }

    @Test
    fun aCutOnTheBeatEndsAtAnOffGridLatestEnd() {
        val plan = plan("off-grid-cut")
        assertEquals(plan.latestEndMs, plan.startMs + plan.overlapMs)
        assertTrue(plan.reason, plan.overlapMs < SHORTEST_BLEND_MS)
        val grid = AutomixCases.tail("steady-100").features.tempo!!
        assertEquals(0.0, offBy(lockOf("off-grid-cut") - grid.firstBeatMs, grid.beatMs), 2.0)
        assertTrue("${plan.driftAtEndMs}", plan.driftAtEndMs <= DRIFT_LIMIT_MS)
    }

    @Test
    fun theRateComesFirstAndReachesSixPercent() {
        // 0.3% apart is matched.
        val small = plan("rate-small-gap")
        assertEquals(1.003, small.beatMatchRate!!, 0.0005)
        // 5.7% apart is matched, the intro's quiet start counted at the rate.
        val far = plan("intro-matched")
        assertEquals(100.0 / 106.0, far.beatMatchRate!!, 0.001)
    }

    @Test
    fun theNextSongEntersOnItsOwnGridWithoutLosingASound() {
        val head = AutomixCases.head("steady-100-late").features
        val soundStart = head.soundStartMs!!
        // Its first sound 350 ms after a beat: the entry is that beat.
        val beat = plan("entry-on-beat")
        assertEquals(1_150.0, beat.entryMs.toDouble(), 15.0)
        assertTrue(beat.entryMs <= soundStart)
        // With a rate its last bar line at or before the first sound falls
        // before its start: it comes in from 0, and A starts earlier so that
        // its first bar line (1.75 s) lands on A's.
        val bar = plan("entry-on-downbeat")
        assertNotNull(bar.beatMatchRate)
        assertEquals(0L, bar.entryMs)
        assertTrue("$soundStart", bar.entryMs != soundStart)
        assertTrue(bar.reason, onBar("steady-101", bar.startMs + 1_750 / bar.beatMatchRate!!))
        assertEquals(bar.startMs + 1_750 / bar.beatMatchRate!!, lockOf("entry-on-downbeat"), 15.0)
        // Its last beat before its first sound falls before its start: from 0,
        // with A earlier by its first beat's 300 ms.
        val early = plan("entry-before-start")
        assertEquals(0L, early.entryMs)
        val grid = AutomixCases.tail("steady-101").features.tempo!!
        assertEquals(0.0, offBy(early.startMs + 300 - grid.firstBeatMs, grid.beatMs), 2.0)
    }

    @Test
    fun whereverBothSongsAreHeardTheirBeatsStayWithin40Ms() {
        for (name in listOf("drift-1", "drift-3", "drift-7", "drift-10", "drift-20", "drift-40", "entry-on-beat", "entry-before-start", "beat-match-off", "same-tempo", "late-move-count")) {
            val p = plan(name)
            assertTrue("$name: ${p.reason}", p.kind == TransitionKind.GAPLESS || p.driftAtEndMs <= DRIFT_LIMIT_MS)
        }
        // About 1.5% apart: the three-bar blend (stretched to the latest end)
        // starts later by whole bars, its end kept.
        val one = plan("drift-1")
        assertTrue(one.reason, one.barLocked && one.overlapMs < 3 * 2_376 && one.overlapMs >= SHORTEST_BLEND_MS)
        assertTrue(one.reason, onBar("steady-101", lockOf("drift-1")))
        assertEquals(one.latestEndMs, one.startMs + one.overlapMs)
        assertTrue(one.reason, one.driftAtEndMs > 0)
        // 7.1% apart: no 1.8 s cut (74 ms apart where both stop being heard).
        val seven = plan("drift-7")
        assertTrue(seven.reason, seven.kind == TransitionKind.GAPLESS || seven.overlapMs < SHORTEST_BLEND_MS)
        // 20% and 40% apart: even the shortest cut is too long.
        assertEquals(TransitionKind.GAPLESS, plan("drift-20").kind)
        assertEquals("gapless: the beats would drift apart", plan("drift-40").reason)
        // A in its outro, no longer clearly heard: the blend as planned.
        val outro = plan("drift-outro")
        assertTrue(outro.reason, outro.reason.contains("(2 bars of"))
        // On the desktop, locked at 1.25 times: within 50 ms of song time.
        val desk = plan("drift-desktop-pace")
        assertTrue(desk.reason, desk.barLocked && desk.driftAtEndMs <= DRIFT_LIMIT_MS * 1.25)
        assertTrue(desk.reason, desk.driftAtEndMs > 0)
    }

    @Test
    fun lateLockedPlansKeepTheirRateOrTheirBound() {
        // No planned start left, 3% apart and matched: from A's first bar line.
        val matched = plan("drift-late-matched")
        assertTrue(matched.reason, matched.late && matched.barLocked && matched.beatMatchRate != null)
        assertTrue(matched.reason, onBar("steady-100", lockOf("drift-late-matched")))
        assertEquals(matched.latestEndMs, matched.startMs + matched.overlapMs)
        // 7.1% apart, unmatched: capped to a cut.
        val capped = plan("drift-late-capped")
        assertTrue(capped.reason, capped.late && capped.barLocked && capped.beatMatchRate == null)
        assertTrue(capped.reason, capped.overlapMs < SHORTEST_BLEND_MS && capped.driftAtEndMs <= DRIFT_LIMIT_MS)
        // Under SHORTEST_BLEND_MS left: the cut never starts before the plan
        // was made.
        val cut = plan("drift-late-cut")
        assertTrue(cut.reason, cut.kind == TransitionKind.GAPLESS || cut.startMs >= 200_500)
        assertTrue(cut.reason, cut.kind != TransitionKind.GAPLESS)
        // A window under a bar long: the locked late plan, on a bar line.
        val window = plan("drift-short-window")
        assertTrue(window.reason, window.late && window.barLocked)
        assertTrue(window.reason, onBar("steady-100", lockOf("drift-short-window")))
    }

    @Test
    fun aRateTheNextSongIsTooShortToEaseBackFromIsPlannedAgain() {
        val plan = plan("rate-room")
        val pair = AutomixCases.pair("rate-room")
        assertNull(plan.reason, plan.beatMatchRate)
        // The entry without a rate: its last beat before its first sound
        // (15.9 s), not its last bar line (15.27 s).
        assertEquals(15_900.0, plan.entryMs.toDouble(), 20.0)
        assertTrue(plan.reason, plan.barLocked && plan.driftAtEndMs <= DRIFT_LIMIT_MS)
        // With the rate the next song would have been too short.
        val rated = 1.05
        assertTrue(pair.next.durationMs - 15_270 <= 14_400 * rated + BEAT_MATCH_SETTLE_MS + 1_000)
    }

    @Test
    fun aQuietStartLongEnoughPlaysUnderTheOutgoingSongsLastBars() {
        // Matched at 5.7%: an intro of whole bars from where the beats meet,
        // no sweeps, the fade centered on a bar line at or after the next
        // song's full point.
        val plan = plan("intro-matched")
        assertEquals(plan.reason, TransitionKind.INTRO, plan.kind)
        assertEquals(0.0, plan.filterStrength, 0.0)
        val bar = 2_400.0
        val fade = (1 - plan.holdShare) * plan.overlapMs
        assertEquals(2 * bar, fade, 30.0)
        assertEquals(bar / plan.overlapMs, plan.riseShare, 0.003)
        val fullAt = plan.holdShare * plan.overlapMs + fade / 2
        assertTrue("$fullAt", onBar("steady-100", plan.startMs + fullAt))
        val introEnd = AutomixCases.head("quiet-106").features.introEndMs!!
        val quiet = (introEnd - plan.entryMs) / plan.beatMatchRate!!
        assertTrue("$fullAt for $quiet", fullAt >= quiet && fullAt < quiet + bar)
        // The quiet start counted at the rate: five bars from the lock
        // point, where it is four in the next song's own time.
        assertEquals(5 * bar, fullAt - plan.lockMs, 30.0)
        assertTrue("$introEnd", introEnd - plan.entryMs <= 4 * bar)
        // It ends 150 ms before the latest end, short of a bar: no move.
        assertEquals(0, plan.lateMoveBars)
    }

    @Test
    fun anIntroLowersTheNextSongThroughTheHoldUntilItStaysWellUnderTheOutgoingSong() {
        // The quiet starts against the outgoing song at the same moments
        // (bars lined up): 7 dB under it, 5 dB under it and at its level.
        fun under(outgoing: String, fromMs: Double, song: String): Double {
            val a = AutomixCases.tail(outgoing)
            val b = AutomixCases.head(song)
            val apart = (300 until 1_000).map { b.levelAt(it * 10.0) - a.levelAt(fromMs + it * 10.0) }.sorted()
            return apart[apart.size / 2]
        }
        assertEquals(-7.0, under("steady-100", 180_000.0, "under-100"), 0.5)
        assertEquals(-5.0, under("steady-100", 180_000.0, "near-100"), 0.5)
        assertEquals(0.0, under("steady-100", 180_000.0, "level-100"), 0.5)
        fun trimDb(plan: TransitionPlan) = -20 * kotlin.math.log10(plan.introLevel)
        // Where the incoming song is the quieter, the overlap's rule keeps it
        // this far under the outgoing song at every step of the hold (the
        // two summed, with the headroom, INTRO_EXCESS_LIMIT_DB from the
        // louder alone); the hold's rule keeps it INTRO_UNDER_OUTGOING_DB
        // under.
        val overlapUnder = -10 * log10(10.0.pow((INTRO_EXCESS_LIMIT_DB + OVERLAP_HEADROOM_DB) / 10) - 1)
        // At the outgoing song's level: lowered by the larger of the two and
        // held there, then brought up as the outgoing song fades.
        val level = plan("intro-trimmed")
        assertEquals(level.reason, TransitionKind.INTRO, level.kind)
        assertEquals(max(INTRO_UNDER_OUTGOING_DB, overlapUnder), trimDb(level), 0.5)
        // 7 dB and 5 dB under: lowered at least by what each lacks of the
        // hold's margin, and less than the one at the level. The overlap's
        // rule lowers them further for the fade, where the next song comes
        // up through the outgoing one's level while that still plays near its
        // own.
        val quiet = plan("intro-under-line")
        assertEquals(quiet.reason, TransitionKind.INTRO, quiet.kind)
        assertTrue("${trimDb(quiet)}", trimDb(quiet) >= INTRO_UNDER_OUTGOING_DB - 7 - 0.5 && trimDb(quiet) < trimDb(level))
        val near = plan("intro-over-line")
        assertEquals(near.reason, TransitionKind.INTRO, near.kind)
        assertTrue("${trimDb(near)}", trimDb(near) >= INTRO_UNDER_OUTGOING_DB - 5 - 0.5 && trimDb(near) < trimDb(level))
        assertTrue(level.reason, level.reason.contains(", next song lowered "))
        assertEquals(level.introLevel, level.inGain(level.holdShare / 2), 0.0)
        assertEquals(1.0, level.inGain(1.0), 1e-9)
        // At its own level the next song would be clearly heard from its rise
        // on, together with the outgoing song held at its own; lowered, it is
        // not heard with it before the hold ends.
        assertTrue(level.reason, level.bothHeardStartMs >= level.holdShare * level.overlapMs - 1)
        // Under an outgoing song near silence it would have to come down over
        // INTRO_TRIM_CAP_DB: no intro, the blend lengthened into the quiet
        // start.
        assertTrue(under("faint-100", 72_000.0, "level-100") + INTRO_UNDER_OUTGOING_DB > INTRO_TRIM_CAP_DB)
        val faint = plan("intro-trim-capped")
        assertTrue(faint.reason, faint.kind != TransitionKind.INTRO && faint.kind != TransitionKind.GAPLESS)
        assertTrue(faint.reason, faint.reason.contains("(full from"))
    }

    @Test
    fun anIntroWhoseBeatsWouldBeHeardApartIsRefused() {
        // 5.7% apart, unmatched: the intro would be far apart where the next
        // song is full; the blend is planned instead, within the bound.
        for (name in listOf("intro-unmatched", "intro-short-drift", "intro-drift")) {
            val p = plan(name)
            assertTrue("$name: ${p.reason}", p.kind != TransitionKind.INTRO)
            assertTrue("$name: ${p.reason}", p.kind == TransitionKind.GAPLESS || p.driftAtEndMs <= DRIFT_LIMIT_MS)
        }
        // 1% apart with A dropping 12 dB at its outro: the starts whose fade
        // falls in the outro keep the beats apart only where A is quiet.
        val later = plan("intro-later-start")
        assertEquals(later.reason, TransitionKind.INTRO, later.kind)
        assertTrue(later.reason, later.driftAtEndMs <= DRIFT_LIMIT_MS)
        assertTrue(later.reason, later.startMs + later.overlapMs * later.holdShare >= 186_000)
        // A's tempo not the whole song's: no intro.
        assertTrue(plan("intro-tempo-dropped").kind != TransitionKind.INTRO)
        // At a phone's 1.25 times, a next song with no beat: an intro as
        // before, unlocked; with a beat both are confident, and an intro
        // where both are heard together is refused.
        val beatless = plan("intro-phone-pace")
        assertEquals(beatless.reason, TransitionKind.INTRO, beatless.kind)
        assertTrue(beatless.reason, !beatless.barLocked)
        val confident = plan("intro-phone-pace-confident")
        assertTrue(confident.reason, confident.kind != TransitionKind.INTRO && confident.bothHeardEndMs == 0L)
        // A quiet start of 35 s against the whole song's body: 16 s, capped.
        val cap = plan("intro-cap")
        assertEquals(cap.reason, TransitionKind.INTRO, cap.kind)
        assertEquals(AUTOMIX_LONGEST_INTRO_MS, cap.overlapMs)
        assertTrue(cap.reason, cap.reason.contains(", capped)"))
    }

    @Test
    fun theBeatsAreBoundOnBothSidesOfWhereTheyMeet() {
        // The next song is loud from its first sample, its beats 600 ms in:
        // it is heard well before its beats meet A's, and that side is the
        // further apart.
        val p = plan("drift-before-lock")
        assertTrue(p.reason, p.barLocked && p.beatMatchRate == null && p.kind != TransitionKind.GAPLESS)
        assertEquals(600.0, p.lockMs, 15.0)
        assertTrue(p.reason, p.bothHeardStartMs < p.lockMs)
        val before = p.lockMs - p.bothHeardStartMs
        val after = p.bothHeardEndMs - p.lockMs
        assertTrue("$before before, $after after", before > after)
        assertEquals(p.driftGap * before, p.driftAtEndMs, p.driftGap * 10 + 0.01)
        assertTrue("${p.driftAtEndMs}", p.driftAtEndMs <= DRIFT_LIMIT_MS)
    }

    @Test
    fun anIntroWithALeadInStillFadesAroundABarLine() {
        val p = plan("intro-lead")
        assertEquals(p.reason, TransitionKind.INTRO, p.kind)
        // Its beats meet A's 100 ms after it starts.
        assertEquals(100.0, p.lockMs, 15.0)
        assertTrue(p.reason, onBar("steady-100", p.startMs + p.lockMs))
        // The middle of A's fade, where the next song is full, on A's bar line.
        val hold = p.holdShare * p.overlapMs
        val fadeMiddle = p.startMs + hold + (p.overlapMs - hold) / 2
        assertTrue("$fadeMiddle: ${p.reason}", onBar("steady-100", fadeMiddle))
    }

    @Test
    fun aBlendHoldsThroughTheNextSongsSilentLeadIn() {
        // Matched, it enters at 0 for its first bar line at 0.9 s, its first
        // sound at 0.5 s: A alone at its own level until then.
        val p = plan("lead-held-cut")
        assertEquals(p.reason, TransitionKind.CUT, p.kind)
        assertNotNull(p.beatMatchRate)
        assertEquals(0L, p.entryMs)
        val soundStart = AutomixCases.head("steady-100-lead").features.soundStartMs!!
        assertEquals(soundStart / p.beatMatchRate!!, p.holdShare * p.overlapMs, 2.0)
        for (step in 0..99) {
            val t = p.holdShare * step / 100
            assertEquals("out at $t", 1.0, p.outGain(t), 0.0)
            assertEquals("in at $t", 0.0, p.inGain(t), 0.0)
        }
        // Then the cut's own curve over the rest.
        val rest = (0.5 - p.holdShare) / (1 - p.holdShare)
        assertEquals(automixOutGain(rest, CUT_CURVE), p.outGain(0.5), 1e-12)
        assertEquals(automixInGain(rest, CUT_CURVE), p.inGain(0.5), 1e-12)
    }

    @Test
    fun theHoldsTrimFollowsALongBreakButNotAShortDropout() {
        fun trimDb(name: String) = -20 * log10(plan(name).introLevel)
        val plain = plan("intro-trimmed")
        // 300 ms of the outgoing song 10 dB down inside the hold: the hold's
        // 95th percentile leaves it out, where its loudest moment would ask
        // nearly 5 dB more; the overlap's rule measures the two against the
        // louder alone, which there is the next song, so it asks nothing more.
        val dropout = plan("intro-trim-dropout")
        assertEquals(dropout.reason, plain.startMs, dropout.startMs)
        assertEquals(trimDb("intro-trimmed"), trimDb("intro-trim-dropout"), 1.5)
        // 1.5 s down: the hold's trim follows it.
        val broken = plan("intro-trim-break")
        assertEquals(broken.reason, plain.startMs, broken.startMs)
        assertTrue(broken.reason, trimDb("intro-trim-break") > trimDb("intro-trimmed") + 6)
    }

    @Test
    fun aQuietStartSilentThroughMostOfTheHoldIsTrimmedForWhereItSounds() {
        // A quiet start 2 dB over the outgoing song is lowered 10 dB by the
        // hold's rule; the overlap's, measuring against the louder alone,
        // asks only about 3. Silent from 0.3 s to 9.2 s, under its gate,
        // those steps are left out, so its last half second of the hold still
        // sets the same 10 dB. Counted as far under, they would leave it to
        // the overlap's rule.
        fun trimDb(name: String) = -20 * log10(plan(name).introLevel)
        val full = plan("intro-trim-loud")
        val gap = plan("intro-trim-gap")
        assertEquals(full.reason, TransitionKind.INTRO, full.kind)
        assertEquals(gap.reason, TransitionKind.INTRO, gap.kind)
        assertEquals(trimDb("intro-trim-loud"), trimDb("intro-trim-gap"), 0.5)
    }

    @Test
    fun anIntroMovesLateOnlyWhereItsTrimStillHolds() {
        // Equal tempos, so the beats would allow any move; the hold ends
        // where the outgoing song drops 10 dB.
        val trimmed = plan("intro-trimmed-at-outro")
        assertEquals(trimmed.reason, TransitionKind.INTRO, trimmed.kind)
        assertTrue(trimmed.reason, trimmed.introLevel < 1)
        val room = floor((trimmed.latestEndMs - trimmed.startMs - trimmed.overlapMs) / 2_400.0).toInt()
        assertTrue("$room", room >= 2)
        // A bar later the hold runs over the drop, where the next song at its
        // planned level no longer stays under: no move.
        assertEquals(0, trimmed.lateMoveBars)
        // 24 dB under, it stays under through the drop: every bar the room
        // allows.
        val quiet = plan("intro-quiet-at-outro")
        assertEquals(quiet.reason, TransitionKind.INTRO, quiet.kind)
        val quietRoom = floor((quiet.latestEndMs - quiet.startMs - quiet.overlapMs) / 2_400.0).toInt()
        assertTrue("$quietRoom", quietRoom >= 1)
        assertEquals(quietRoom, quiet.lateMoveBars)
    }

    @Test
    fun anUnlockedIntroMovesOnlyWhenEveryStepToTheLatestEndKeepsItsTrim() {
        // The next song has no beat, so no bar grid: a late start may move
        // the intro any amount up to the latest end. The outgoing song drops
        // 14 dB more at 197 s, partway along the room past the intro's end.
        val drop = plan("intro-unlocked-drop")
        val quiet = plan("intro-unlocked-quiet")
        for (p in listOf(drop, quiet)) {
            assertEquals(p.reason, TransitionKind.INTRO, p.kind)
            assertTrue(p.reason, !p.barLocked)
            val end = p.startMs + p.overlapMs
            assertTrue(p.reason, end < 197_000 && 197_000 < p.latestEndMs)
        }
        // Its body 20 dB under the outgoing song is louder than the drop, so
        // once moved far enough the two cross there: no move at all.
        assertEquals(0, drop.lateMoveBars)
        // 36 dB under, it stays apart from the outgoing song wherever it
        // moves.
        assertEquals(1, quiet.lateMoveBars)
    }

    @Test
    fun aCutOrALateBlendTooShortToFadeTheOutgoingSongOutIsNotPlanned() {
        // Bounded with no rate, 15% apart: the only cut whose beats stay
        // within the bound would leave a few ms after the 560 ms hold.
        val cut = plan("drift-lead-cut")
        assertTrue(cut.reason, cut.kind == TransitionKind.GAPLESS || (1 - cut.holdShare) * cut.overlapMs >= SHORTEST_FADE_MS - 1)
        // Matched and late: the blend left before the latest end is shorter
        // than the 1.9 s lead-in it would hold A through.
        val late = plan("late-lead-fills")
        assertEquals(late.reason, TransitionKind.GAPLESS, late.kind)
        assertTrue(late.reason, late.reason.startsWith("gapless: too late to blend"))
    }

    @Test
    fun aLateBlendCappedTooShortToFadeTheOutgoingSongOutIsNotPlanned() {
        // A's grid at 132 BPM (1818 ms bars, a downbeat at 201.45 s), the next
        // song's at 35 BPM (a tagged slow song), 5.7% apart with no rate. The
        // next song is silent until 1.6 s and its first beat is at 1.65 s, so
        // it enters at 0 and a blend holds A through those 1.6 s. Started at
        // 199 s, the late blend runs from A's downbeat at 201.45 s less that
        // lead to the latest end, 1.95 s: within the drift bound, but only
        // 350 ms after the hold to fade A out, and every cut is shorter
        // still. Neither the capped blend nor a cut is planned.
        val tail = AutomixCases.tail("steady-100")
        val head = AutomixCases.head("steady-100-late-sound")
        val a = tail.features.tempo!!
        val b = head.features.tempo!!
        val fast = SectionAnalysis.fromFeatures(
            tail.envelope,
            tail.features.copy(tempo = a.copy(bpm = 132.0, beatMs = 60_000.0 / 132, firstBeatMs = 1_470.0, downbeatMs = 1_470.0)),
        )
        val slow = SectionAnalysis.fromFeatures(
            head.envelope,
            head.features.copy(tempo = b.copy(bpm = 35.0, beatMs = 60_000.0 / 35, firstBeatMs = 1_650.0, downbeatMs = 1_650.0)),
        )
        val p = planTransition(
            FadeSong("a", 1, 202_000), FadeSong("b", 1, 40_000), fast, slow, AutomixSettings(8_000), TransitionContext(nowMs = 199_000),
        )
        assertEquals(p.reason, TransitionKind.GAPLESS, p.kind)
        assertTrue(p.reason, p.reason.startsWith("gapless: too late to blend"))
    }

    @Test
    fun aSilentLeadInFillingEveryBlendGivesTheGaplessJoin() {
        // Matched, the next song enters at 0 for its first bar line at 2.1 s,
        // silent until 1.9 s (1881 ms at the 1.01 rate): a 1.8 s cut would
        // hold A at its own level to the end and drop it there.
        val p = plan("lead-fills-the-cut")
        assertEquals(p.reason, TransitionKind.GAPLESS, p.kind)
    }

    @Test
    fun everyPlannedBlendFadesTheOutgoingSongOut() {
        for (pair in AutomixCases.pairs) {
            val p = AutomixCases.plan(pair)
            if (p.kind == TransitionKind.GAPLESS || p.kind == TransitionKind.INTRO || p.holdShare <= 0) continue
            assertTrue("${pair.name}: ${p.reason}", (1 - p.holdShare) * p.overlapMs >= SHORTEST_FADE_MS - 1)
        }
    }

    @Test
    fun aLoweredSpeedCutsWhereTheBeatsFirstGetTooFarApart() {
        fun heard(start: Long, end: Long, lock: Double, gap: Double) =
            fixedCrossfadePlan(200_000, 8_000, "test").copy(bothHeardStartMs = start, bothHeardEndMs = end, lockMs = lock, driftGap = gap)
        // An intro heard together from 1.9 s to 3.5 s, 35 ms apart by the
        // end: at half speed the limit is 20 ms, passed 2 s in, after A's
        // hold; at full speed never.
        val intro = heard(1_900, 3_500, 0.0, 0.01)
        assertEquals(2_000.0, dropCutAtMs(intro, 0.5, 500.0)!!, 1e-9)
        assertNull(dropCutAtMs(intro, 1.0, 500.0))
        // Dropped past that moment: at once.
        assertEquals(2_500.0, dropCutAtMs(intro, 0.5, 2_500.0)!!, 1e-9)
        // Heard before the beats meet, far enough out to be apart already.
        val early = heard(100, 650, 400.0, 0.1)
        assertEquals(100.0, dropCutAtMs(early, 0.5, 0.0)!!, 1e-9)
        assertEquals(600.0, dropCutAtMs(early, 0.5, 300.0)!!, 1e-9)
        assertEquals(620.0, dropCutAtMs(early, 0.5, 620.0)!!, 1e-9)
        assertNull(dropCutAtMs(early, 0.5, 700.0))
        // A plan whose beats reach the limit just as both stop being heard,
        // at the speed it was made for: no cut.
        assertNull(dropCutAtMs(heard(0, 2_000, 0.0, 0.02), 1.0, 0.0))
        // Never heard together, or never apart.
        assertNull(dropCutAtMs(heard(0, 0, 0.0, 0.02), 0.5, 0.0))
        assertNull(dropCutAtMs(heard(500, 3_000, 200.0, 0.0), 0.5, 0.0))
        // A planned blend's own numbers: at half speed its 24 ms by the end
        // of the stretch are 48 ms heard.
        val seven = plan("drift-7")
        val cut = dropCutAtMs(seven, 0.5, 0.0)
        assertNotNull(seven.reason, cut)
        assertTrue("$cut", cut!! <= seven.bothHeardEndMs && seven.driftGap * abs(cut - seven.lockMs) >= DRIFT_LIMIT_MS * 0.5 - 1e-6)
    }

    @Test
    fun aLoweredSpeedNeverCutsInsideAHold() {
        // Heard together from 0.5 s to 7.5 s, the beats meeting at 6 s and
        // 0.25% apart: at quarter speed the limit is 10 ms, reached 4 s from
        // the lock, so without a hold the outgoing song goes at once.
        val free = fixedCrossfadePlan(200_000, 8_000, "test").copy(
            bothHeardStartMs = 500, bothHeardEndMs = 7_500, lockMs = 6_000.0, driftGap = 0.0025,
        )
        assertEquals(500.0, dropCutAtMs(free, 0.25, 0.0)!!, 1e-9)
        // An intro whose hold ends at 3 s, within the limit, and the stretch
        // ends before the beats part again: only the hold stops the cut.
        assertNull(dropCutAtMs(free.copy(kind = TransitionKind.INTRO, holdShare = 3_000.0 / 8_000), 0.25, 0.0))
        // A hold ending at 1.5 s, the beats still apart there: at its end.
        assertEquals(1_500.0, dropCutAtMs(free.copy(kind = TransitionKind.INTRO, holdShare = 1_500.0 / 8_000), 0.25, 0.0)!!, 1e-9)
    }

    // Curves

    @Test
    fun theGainsKeepEqualPowerAndEndCleanly() {
        for (k in listOf(0.0, BLEND_CURVE, CROSSFADE_CURVE, LIFT_CURVE, CUT_CURVE)) {
            assertEquals(1.0, automixOutGain(0.0, k), 1e-12)
            assertEquals(0.0, automixInGain(0.0, k), 1e-6)
            assertEquals(0.0, automixOutGain(1.0, k), 1e-12)
            assertEquals(1.0, automixInGain(1.0, k), 1e-12)
            var last = 1.0
            for (step in 0..100) {
                val t = step / 100.0
                val out = automixOutGain(t, k)
                val into = automixInGain(t, k)
                assertEquals(1.0, out * out + into * into, 1e-9)
                assertTrue(out <= last + 1e-12)
                last = out
            }
        }
        // With no S curve it is the existing equal-power fade.
        assertEquals(fadeOutVolume(0.3f).toDouble(), automixOutGain(0.3, 0.0), 1e-6)
        assertEquals(0.5, sCurve(0.5), 1e-12)
    }

    // An intro plan over `overlapMs` whose outgoing song starts to fade
    // `fadeMs` before its end and whose incoming song rises over `riseMs`.
    private fun introPlan(overlapMs: Long, fadeMs: Long, riseMs: Long) = TransitionPlan(
        startMs = 180_000, entryMs = 0, overlapMs = overlapMs, kind = TransitionKind.INTRO, k = 0.0, filterStrength = 0.0,
        beatMatchRate = null, beatMs = null, beatAnchorMs = null, late = false, headroomDb = OVERLAP_HEADROOM_DB, reason = "test",
        holdShare = (overlapMs - fadeMs).toDouble() / overlapMs, riseShare = riseMs.toDouble() / overlapMs, lateMoveBars = 0,
    )

    @Test
    fun anIntroHoldsTheOutgoingSongThenFadesItOut() {
        val hold = 10_500.0 / 13_500
        for (t in listOf(-0.1, 0.0, 0.3, hold - 1e-9, hold)) assertEquals("at $t", 1.0, introOutGain(t, hold), 0.0)
        // An equal-power fade from the hold to the end.
        assertEquals(cos(PI / 4), introOutGain(hold + (1 - hold) / 2, hold), 1e-12)
        assertEquals(cos(0.25 * PI / 2), introOutGain(hold + (1 - hold) / 4, hold), 1e-12)
        assertEquals(0.0, introOutGain(1.0, hold), 1e-12)
        assertEquals(0.0, introOutGain(1.2, hold), 1e-12)
        var last = 1.0
        for (step in 0..200) {
            val out = introOutGain(step / 200.0, hold)
            assertTrue(out <= last + 1e-12)
            last = out
        }
        // No hold is a whole equal-power fade.
        assertEquals(automixOutGain(0.3, 0.0), introOutGain(0.3, 0.0), 1e-12)
    }

    @Test
    fun anIntroRaisesTheIncomingSongQuicklyThenHoldsIt() {
        val rise = 1_000.0 / 13_500
        val hold = 10_500.0 / 13_500
        assertEquals(0.0, introInGain(0.0, rise, hold, 1.0), 0.0)
        assertEquals(0.0, introInGain(-0.1, rise, hold, 1.0), 0.0)
        assertEquals(sin(PI / 4), introInGain(rise / 2, rise, hold, 1.0), 1e-12)
        assertEquals(sin(PI / 2 * 0.2), introInGain(rise * 0.2, rise, hold, 1.0), 1e-12)
        for (t in listOf(rise, 0.2, 0.5, 0.9, 1.0, 1.2)) assertEquals("at $t", 1.0, introInGain(t, rise, hold, 1.0), 0.0)
        // No rise is full at once.
        assertEquals(1.0, introInGain(0.0, 0.0, hold, 1.0), 0.0)
    }

    @Test
    fun aLoweredIntroHoldsTheIncomingSongThereThenBringsItUpAsTheOutgoingSongLeaves() {
        val rise = 1_000.0 / 13_500
        val hold = 10_500.0 / 13_500
        val level = 0.5
        assertEquals(sin(PI / 4) * level, introInGain(rise / 2, rise, hold, level), 1e-12)
        for (t in listOf(rise, 0.3, hold)) assertEquals("at $t", level, introInGain(t, rise, hold, level), 0.0)
        // Past the hold the incoming song comes up as the outgoing song's
        // power leaves, to its own level at the end.
        for (t in listOf(hold + 0.02, hold + (1 - hold) / 2, 0.95)) {
            val out = introOutGain(t, hold)
            val expected = sqrt(level * level + (1 - level * level) * (1 - out * out))
            assertEquals("at $t", expected, introInGain(t, rise, hold, level), 1e-12)
        }
        assertEquals(1.0, introInGain(1.0, rise, hold, level), 1e-12)
        var last = 0.0
        for (step in 0..400) {
            val gain = introInGain(step / 400.0, rise, hold, level)
            assertTrue("at $step", gain >= last - 1e-12)
            last = gain
        }
        // At level 1 nothing changes: full from the rise on.
        for (t in listOf(rise, 0.5, hold + 0.1, 1.0)) assertEquals("at $t", 1.0, introInGain(t, rise, hold, 1.0), 0.0)
    }

    @Test
    fun anIntroPlanPlaysWithTheIntroGains() {
        val plan = introPlan(13_500, 3_000, 1_000)
        for (step in 0..100) {
            val t = step / 100.0
            assertEquals("out at $t", introOutGain(t, plan.holdShare), plan.outGain(t), 0.0)
            assertEquals("in at $t", introInGain(t, plan.riseShare, plan.holdShare, plan.introLevel), plan.inGain(t), 0.0)
        }
        assertEquals(1.0, plan.outGain(0.7), 0.0)
        assertEquals(1.0, plan.inGain(0.5), 0.0)
        // Other kinds keep their own shares at 0 and their curves.
        val blend = plan.copy(kind = TransitionKind.BLEND, k = BLEND_CURVE, holdShare = 0.0, riseShare = 0.0)
        assertEquals(automixOutGain(0.7, BLEND_CURVE), blend.outGain(0.7), 0.0)
    }

    @Test
    fun anIntroKeepsTheLevelUpAcrossTheIncomingSongsFullPoint() {
        // Both songs' bodies at one level (0 dB); the incoming song's intro
        // 24 dB under it until its full point, where the outgoing song's
        // fade is halfway. Unlocked: a 12 s quiet stretch, a 3 s fade.
        // Locked at 120 BPM: an 11 s stretch takes 6 bars, a 2-bar fade, a
        // 1-bar rise.
        for ((plan, fullAtMs) in listOf(introPlan(13_500, 3_000, 1_000) to 12_000L, introPlan(14_000, 4_000, 2_000) to 12_000L)) {
            val fadeMs = (1 - plan.holdShare) * plan.overlapMs
            assertEquals(fullAtMs.toDouble(), plan.overlapMs - fadeMs / 2, 1e-6)
            val hopMs = 10
            val hops = (plan.overlapMs / hopMs).toInt()
            val power = DoubleArray(hops) { i ->
                val ms = i * hopMs + hopMs / 2.0
                val t = ms / plan.overlapMs
                val incoming = if (ms < fullAtMs) Math.pow(10.0, -24.0 / 10) else 1.0
                plan.outGain(t).let { it * it } + plan.inGain(t).let { it * it } * incoming
            }
            // Every 400 ms of the mix from the start of the fade on.
            val window = 40
            for (from in ((plan.holdShare * plan.overlapMs) / hopMs).toInt()..hops - window) {
                val db = 10 * kotlin.math.log10(power.copyOfRange(from, from + window).average())
                assertTrue("${plan.overlapMs} ms intro at ${from * hopMs} ms: $db dB", abs(db) <= 3.0)
            }
        }
    }

    @Test
    fun theFiltersSweepTheirRanges() {
        assertEquals(18_000.0, outgoingLowPassHz(0.0, FILTER_STRENGTH), 1e-9)
        assertEquals(18_000.0 * Math.pow(450.0 / 18_000.0, 0.7), outgoingLowPassHz(1.0, FILTER_STRENGTH), 1e-6)
        assertEquals(10.0, outgoingHighPassHz(0.0, FILTER_STRENGTH), 1e-9)
        assertEquals(210.0, outgoingHighPassHz(0.5, FILTER_STRENGTH), 1e-9)
        assertEquals(210.0, outgoingHighPassHz(0.9, FILTER_STRENGTH), 1e-9)
        assertEquals(472.5, incomingHighPassHz(0.0, FILTER_STRENGTH), 1e-9)
        assertEquals(20.0, incomingHighPassHz(0.6, FILTER_STRENGTH), 1e-9)
        assertEquals(20.0, incomingHighPassHz(1.0, FILTER_STRENGTH), 1e-9)
        assertEquals(18_000.0, outgoingLowPassHz(1.0, 0.0), 1e-9)
    }

    @Test
    fun theSteppedLowPassHoldsEachBeatThenGlides() {
        val beats = doubleArrayOf(0.25, 0.5, 0.75)
        val glide = 0.05
        assertEquals(18_000.0, steppedOutgoingLowPassHz(0.1, FILTER_STRENGTH, beats, glide), 1e-9)
        assertEquals(outgoingLowPassHz(0.25, FILTER_STRENGTH), steppedOutgoingLowPassHz(0.3, FILTER_STRENGTH, beats, glide), 1e-9)
        assertEquals(outgoingLowPassHz(0.25, FILTER_STRENGTH), steppedOutgoingLowPassHz(0.45, FILTER_STRENGTH, beats, glide), 1e-9)
        assertEquals(outgoingLowPassHz(0.375, FILTER_STRENGTH), steppedOutgoingLowPassHz(0.475, FILTER_STRENGTH, beats, glide), 1e-9)
        assertEquals(outgoingLowPassHz(0.5, FILTER_STRENGTH), steppedOutgoingLowPassHz(0.5, FILTER_STRENGTH, beats, glide), 1e-9)
        assertEquals(outgoingLowPassHz(1.0, FILTER_STRENGTH), steppedOutgoingLowPassHz(1.0, FILTER_STRENGTH, beats, glide), 1e-9)
    }

    @Test
    fun aPlanOnTheBeatStepsItsLowPass() {
        val plan = plan("same-tempo")
        val beats = plan.beatProgress()
        assertEquals(15, beats.size)
        assertEquals(0.0625, beats[0], 0.002)
        assertEquals(plan.outgoingLowPassAt(beats[0] + 0.01), plan.outgoingLowPassAt(beats[1] - 0.035), 1e-9)
    }

    @Test
    fun beatMatchEasesBackAfterTheBlend() {
        assertEquals(1.03, beatMatchRateAt(1.03, 2_000.0, 8_000.0), 1e-12)
        assertEquals(1.03, beatMatchRateAt(1.03, 8_000.0, 8_000.0), 1e-12)
        assertEquals(1.015, beatMatchRateAt(1.03, 10_500.0, 8_000.0), 1e-12)
        assertEquals(1.0, beatMatchRateAt(1.03, 13_000.0, 8_000.0), 1e-12)
        assertEquals(1.0, plan("same-tempo").incomingRateAt(1_000.0), 0.0)
    }

    @Test
    fun foldingTreatsHalfAndDoubleTimeAsTheSame() {
        assertEquals(1.0, foldTempoRatio(2.0), 1e-12)
        assertEquals(1.0, foldTempoRatio(0.5), 1e-12)
        assertEquals(1.2, foldTempoRatio(1.2), 1e-12)
    }
}
