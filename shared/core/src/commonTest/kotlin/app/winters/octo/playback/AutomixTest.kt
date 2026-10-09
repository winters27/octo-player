package app.winters.octo.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
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
        assertTrue(envelope.onset[100] > 90f)
        assertEquals(0f, envelope.onset[150], 0.01f)
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
            analyzeSection(builder.build())
            return (System.nanoTime() - began) / 1_000_000
        }
        repeat(3) { run() }
        val ms = (1..5).minOf { run() }
        println("automix: envelope and features of 60 s of 48 kHz stereo in $ms ms")
        assertTrue("took $ms ms", ms < 250)
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
        assertEquals(118.0, AutomixCases.head("beat-118").features.tempo!!.bpm, 1.0)
        assertEquals(118.0, AutomixCases.tail("beat-118").features.tempo!!.bpm, 1.0)
    }

    @Test
    fun aSilentSectionHasNoSound() {
        val analysis = analyzeSection(envelopeOf(FloatArray(48_000 * 5), 48_000))
        assertNull(analysis.features.soundStartMs)
        assertNull(analysis.features.soundEndMs)
        assertNull(analysis.features.tempo)
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
        assertEquals(1_000L, plan.entryMs)
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
    fun withoutAnalysisItIsThePlainCrossfade() {
        for (name in listOf("no-analysis", "no-head", "smart-off")) {
            val plan = plan(name)
            val length = AutomixCases.songs.getValue(AutomixCases.pair(name).a).lengthMs
            assertEquals(name, TransitionKind.CROSSFADE, plan.kind)
            assertEquals(name, length - 8_000, plan.startMs)
            assertEquals(name, 8_000L, plan.overlapMs)
            assertEquals(name, 0L, plan.entryMs)
            assertEquals(name, CROSSFADE_CURVE, plan.k, 0.0)
            assertEquals(name, 0.0, plan.filterStrength, 0.0)
        }
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
    fun repeatOneAndCrossfadeOffNeverBlend() {
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
            FadeSong("a", 1, 30_000), FadeSong("b", 1, 180_000), tail, head,
            AutomixSettings(8_000), repeatOne = false, stopAtEndOfSong = false,
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
