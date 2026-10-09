package app.winters.octo.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
    fun aHotEndingHasNoOutro() {
        val features = AutomixCases.tail("hot-end").features
        assertEquals(200_000L, features.soundEndMs)
        assertEquals(features.soundEndMs, features.outroStartMs)
        assertNull("a steady tone has no beat", features.tempo)
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
    fun aTagTempoPicksTheOctave() {
        val song = AutomixCases.songs.getValue("beat-120")
        val envelope = song.envelope(0.0, 30_000.0)
        assertEquals(60.0, analyzeHead(envelope, tagBpm = 62.0).features.tempo!!.bpm, 0.5)
        assertEquals(120.0, analyzeHead(envelope, tagBpm = 125.0).features.tempo!!.bpm, 0.5)
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
        assertEquals(1_000.0, plan.entryMs.toDouble(), 20.0)
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
    fun differentTemposBlendTwoBars() {
        val plan = plan("tempo-clash")
        assertEquals(4_000.0, plan.overlapMs.toDouble(), 10.0)
        assertEquals(0.0, offBy(plan.startMs - 500.0, 2_000.0), 20.0)
    }

    @Test
    fun beatMatchNudgesTheNextSongOntoTheBar() {
        val plan = plan("beat-match")
        assertEquals(120.0 / 118.0, plan.beatMatchRate!!, 0.005)
        val head = AutomixCases.head("beat-118").features.tempo!!
        assertEquals(0.0, offBy(plan.entryMs - head.downbeatMs, head.barMs), 1.0)
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
        for (name in listOf("pace", "skip-silence")) {
            val plan = plan(name)
            assertTrue(name, !plan.barLocked)
            assertNull(name, plan.beatMatchRate)
            assertTrue(name, plan.kind != TransitionKind.CROSSFADE && plan.kind != TransitionKind.GAPLESS)
        }
    }

    @Test
    fun tooLateForTheChosenStartBlendsAtTheEnd() {
        val late = plan("late")
        assertTrue(late.late)
        assertEquals(199_750L - 8_000, late.startMs)
        assertEquals(8_000L, late.overlapMs)
        assertTrue(!late.barLocked)
        val short = plan("late-short")
        assertEquals(197_000L, short.startMs)
        assertEquals(2_750L, short.overlapMs)
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
        assertTrue(half.reason.contains("of 60.0 BPM"))
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
