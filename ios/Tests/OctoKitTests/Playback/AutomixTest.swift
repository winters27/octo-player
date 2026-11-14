import Foundation
import Testing
@testable import OctoKit

@Suite struct AutomixTest {
    func sine(_ hz: Double, _ seconds: Double, _ rate: Int, amplitude: Double = 1) -> [Float] {
        (0..<Int(seconds * Double(rate))).map { Float(amplitude * sin(2 * Double.pi * hz * Double($0) / Double(rate))) }
    }

    func envelopeOf(_ samples: [Float], _ rate: Int, channels: Int = 1, block: Int? = nil) -> SectionEnvelope {
        let builder = EnvelopeBuilder(sampleRate: rate, channels: channels, startMs: 0)
        let step = block ?? samples.count
        var at = 0
        while at < samples.count {
            let length = min(step, samples.count - at)
            builder.push(samples, offset: at, length: length)
            at += length
        }
        return builder.build()
    }

    func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool { abs(a - b) <= tolerance }

    func plan(_ name: String) -> TransitionPlan { AutomixCases.plan(AutomixCases.pair(name)) }

    func offBy(_ x: Double, _ period: Double) -> Double {
        let r = (x.truncatingRemainder(dividingBy: period) + period).truncatingRemainder(dividingBy: period)
        return r > period / 2 ? r - period : r
    }

    // Envelope

    @Test func aFullScaleSineReadsMinusThreeDecibels() {
        let envelope = envelopeOf(sine(1_000, 1, 48_000), 48_000)
        #expect(envelope.size == 100)
        #expect(near(Double(envelope.db[50]), -3.01, 0.02))
        #expect(envelope.lowDb[50] < -30, "1 kHz barely reaches the bass band")
    }

    @Test func aBassToneFillsTheBassBand() {
        let envelope = envelopeOf(sine(50, 1, 44_100, amplitude: 0.5), 44_100)
        #expect(near(Double(envelope.db[60]), Double(envelope.lowDb[60]), 0.5))
    }

    @Test func silenceIsTheFloorAndOnsetsMarkTheRise() {
        let samples = [Float](repeating: 0, count: 48_000) + sine(1_000, 1, 48_000)
        let envelope = envelopeOf(samples, 48_000)
        #expect(envelope.db[10] == silentDb)
        #expect(envelope.onset[10] == 0)
        #expect(envelope.onset[100] > 1)
        #expect(near(Double(envelope.onset[150]), 0, 0.05))
    }

    @Test func blocksOfAnySizeAndSplitFramesGiveTheSameEnvelope() {
        let mono = sine(220, 2, 22_050, amplitude: 0.3)
        let stereo = (0..<(mono.count * 2)).map { mono[$0 / 2] }
        let whole = envelopeOf(mono, 22_050)
        let pieces = envelopeOf(stereo, 22_050, channels: 2, block: 333)
        #expect(whole.size == 200)
        #expect(whole.db == pieces.db)
        #expect(whole.lowDb == pieces.lowDb)
        let shorts = mono.map { Int16(Int($0 * 32767)) }
        let builder = EnvelopeBuilder(sampleRate: 22_050, channels: 1, startMs: 0)
        builder.push(shorts)
        #expect(near(Double(whole.db[100]), Double(builder.build().db[100]), 0.01))
    }

    @Test func aMinuteOfStereoAt48kHzIsQuick() {
        let mono = AutomixCases.songs["beat-120"]!.renderMono(140_000, 200_000)
        let samples = (0..<(mono.count * 2)).map { mono[$0 / 2] }
        func run() -> Int {
            let began = Date()
            let builder = EnvelopeBuilder(sampleRate: 48_000, channels: 2, startMs: 140_000)
            var at = 0
            while at < samples.count {
                let length = min(8_192, samples.count - at)
                builder.push(samples, offset: at, length: length)
                at += length
            }
            _ = analyzeTail(builder.build())
            return Int(Date().timeIntervalSince(began) * 1000)
        }
        _ = run()
        let ms = (1...2).map { _ in run() }.min()!
        print("automix: envelope and features of 60 s of 48 kHz stereo in \(ms) ms")
        // An unoptimized test build runs many times slower than the app; the bound holds there.
        #if !DEBUG
        #expect(ms < 400, "took \(ms) ms")
        #endif
    }

    // Features

    @Test func trailingSilenceEndsTheSound() {
        let features = AutomixCases.tail("silence-tail").features
        #expect(near(Double(features.soundEndMs!), 192_000, 30))
        #expect(features.soundEndMs == features.outroStartMs)
        #expect(features.gateDb == -60)
        #expect(features.boundariesMs.contains { abs($0 - 192_000) <= 300 })
    }

    @Test func aFadeOutStartsTheOutroWhereItDropsSixDecibels() {
        let features = AutomixCases.tail("fade-out").features
        #expect(near(Double(features.outroStartMs!), 184_000, 500))
        #expect((205_000...215_000).contains(features.soundEndMs!))
        #expect(features.tempo == nil)
    }

    @Test func aQuietIntroPlaysUnderTheEndOfAHotSong() {
        let head = AutomixCases.head("quiet-intro").features
        // The 1 s level, power averaged, reaches the body less 6 dB once a quarter of its window
        // holds the full-level part.
        #expect(near(Double(head.introEndMs!), 5_750, 20))
        #expect(AutomixCases.head("hot-end").features.introEndMs == 0)
        let plan = plan("quiet-intro")
        // Without the intro a hot ending gets the shortest blend.
        #expect(self.plan("hot-end").overlapMs == shortestBlendMs)
        #expect(plan.kind == .intro)
        #expect(plan.overlapMs >= head.introEndMs! - plan.entryMs, "\(plan.overlapMs)")
        #expect(plan.startMs + plan.overlapMs == 199_750)
        #expect(plan.reason.contains("(full from 5."), "\(plan.reason)")
        // Never longer than the longest intro allows, which says so.
        let capped = self.plan("quiet-intro-capped")
        #expect(capped.kind == .intro)
        #expect(capped.overlapMs == 6_000)
        #expect(capped.reason.contains(", capped)"), "\(capped.reason)")
    }

    @Test func aHeadMeasuredAgainstTheWholeSongSeesALongQuietIntro() throws {
        let song = AutomixCases.songs["long-intro"]!
        let scouted = AutomixCases.head("long-intro")
        #expect(scouted.envelope.endMs == automixHeadMs)
        // Against its own body, which is mostly the quiet pad, the start sounds full at once.
        #expect(scouted.features.introEndMs == 0)
        // Against the whole song's body the intro ends where the full level comes in.
        let body = try #require(song.wholeBodyDb())
        let head = song.profileHead(body).features
        #expect(head.bodyDb == body)
        #expect(near(Double(head.introEndMs!), 34_750, 30))
    }

    @Test func aHeadThatNeverReachesTheBodyEndsItsIntroAtTheHeadsEnd() {
        let head = AutomixCases.songs["endless-intro"]!.profileHead().features
        #expect(head.soundStartMs == 0)
        #expect(head.introEndMs == automixHeadMs)
        // A silent head has no intro to end.
        #expect(analyzeHead(envelopeOf([Float](repeating: 0, count: 22_050 * 5), 22_050), bodyLevelDb: -14).features.introEndMs == nil)
        // A tail keeps the rule it had.
        #expect(analyzeTail(AutomixCases.songs["endless-intro"]!.envelope(0, 45_000), bodyLevelDb: -14).features.introEndMs == nil)
    }

    @Test func everyPlanNamesTheLatestItMayEnd() {
        // A planned blend: where the outgoing song's sound ends, at most `endMarginMs` before its end.
        #expect(plan("hot-end").latestEndMs == 200_000 - endMarginMs)
        #expect(plan("same-tempo").latestEndMs == 200_000 - endMarginMs)
        #expect(near(Double(plan("trailing-silence").latestEndMs), 192_000, 30))
        // A fading song blends at its outro, well before its sound ends.
        let fading = plan("fade-out")
        #expect(fading.latestEndMs == AutomixCases.tail("fade-out").features.soundEndMs)
        #expect(fading.startMs + fading.overlapMs < fading.latestEndMs - 10_000, "\(fading.startMs + fading.overlapMs)")
        // A late blend: the end it was placed against.
        #expect(plan("late").late)
        #expect(plan("late").latestEndMs == 200_000 - endMarginMs)
        #expect(plan("no-tail").late)
        #expect(plan("no-tail").latestEndMs == 220_000 - endMarginMs)
        // The fixed crossfade and the plain ones: the song's end.
        #expect(plan("smart-off").latestEndMs == 200_000)
        #expect(plan("classical").latestEndMs == 200_000)
        #expect(plan("podcast").latestEndMs == 200_000)
        // A gapless join, also when it is too late to blend: the song's end.
        #expect(plan("album-in-order").latestEndMs == 200_000)
        #expect(plan("crossfade-off").latestEndMs == 200_000)
        #expect(plan("too-late").kind == .gapless)
        #expect(plan("too-late").latestEndMs == 200_000)
        for pair in AutomixCases.pairs {
            let p = AutomixCases.plan(pair)
            #expect(p.startMs + p.overlapMs <= p.latestEndMs, "\(pair.name): \(p.latestEndMs)")
            if p.kind == .intro { continue }
            // Any other kind holds only through the next song's silent lead-in, which ends before
            // its beats meet A's.
            #expect(p.holdShare * Double(p.overlapMs) <= p.lockMs + 1, "\(pair.name)")
            #expect(p.riseShare == 0, "\(pair.name)")
        }
        // The plain crossfade for a short song: its end.
        let short = planTransition(current: FadeSong(albumId: "a", albumOrder: 1, durationMs: 30_000),
                                   next: FadeSong(albumId: "b", albumOrder: 1, durationMs: 180_000),
                                   tail: AutomixCases.tail("hot-end"), head: AutomixCases.head("plain-b"),
                                   settings: AutomixSettings(maxOverlapMs: 8_000))
        #expect(short.kind == .crossfade)
        #expect(short.latestEndMs == 30_000)
    }

    @Test func aLateStartMayMoveAPlanAnyNumberOfBarsOnlyWhereItsBeatsCannotDrift() {
        // No bar grid: the fixed crossfade, the plain ones, gapless joins, unlocked blends and late
        // ones, and one tempo untrusted.
        for name in ["smart-off", "classical", "podcast", "album-in-order", "crossfade-off", "too-late", "hot-end", "no-analysis",
                     "no-tail", "no-head", "pace-steady"] {
            #expect(!plan(name).barLocked, "\(name)")
            #expect(plan(name).lateMoveBars == lateMoveUnbounded, "\(name)")
        }
        // Both tempos confident but not lined up: kept where both songs are never heard together,
        // which a move could undo, so none.
        for name in ["pace", "skip-silence", "drift-phone-pace"] {
            #expect(!plan(name).barLocked && plan(name).kind != .gapless, "\(name)")
            #expect(plan(name).lateMoveBars == 0, "\(name)")
        }
        let short = planTransition(current: FadeSong(albumId: "a", albumOrder: 1, durationMs: 30_000),
                                   next: FadeSong(albumId: "b", albumOrder: 1, durationMs: 180_000),
                                   tail: AutomixCases.tail("hot-end"), head: AutomixCases.head("plain-b"),
                                   settings: AutomixSettings(maxOverlapMs: 8_000))
        #expect(short.lateMoveBars == lateMoveUnbounded)
        // A rate keeps the beats together wherever the blend sits.
        for name in ["beat-match", "drift-late-matched"] {
            #expect(plan(name).beatMatchRate != nil, "\(name)")
            #expect(plan(name).lateMoveBars == lateMoveUnbounded, "\(name)")
        }
        // An intro's move must also keep its trim, so it is never unbounded.
        for pair in AutomixCases.pairs {
            let p = AutomixCases.plan(pair)
            if p.kind == .intro { #expect(p.lateMoveBars != lateMoveUnbounded, "\(pair.name): \(p.reason)") }
        }
        // Two equal tempos never drift apart either.
        let tail = AutomixCases.tail("beat-120")
        let head = AutomixCases.head("beat-120-b")
        let grid = tail.features.tempo!
        var features = head.features
        features.tempo!.bpm = grid.bpm
        features.tempo!.beatMs = grid.beatMs
        let equal = SectionAnalysis.fromFeatures(head.envelope, features)
        let pair = AutomixCases.pair("same-tempo")
        let same = planTransition(current: pair.current, next: pair.next, tail: tail, head: equal, settings: AutomixSettings(maxOverlapMs: 8_000))
        #expect(same.barLocked && same.beatMatchRate == nil, "\(same.reason)")
        #expect(same.lateMoveBars == lateMoveUnbounded)
        // Locked to the bar with a tempo gap and no rate: only as far as the latest end leaves room
        // and the beats stay within the bound.
        for name in ["beat-match-off", "same-tempo", "drift-1", "drift-3", "late"] {
            let p = plan(name)
            #expect(p.barLocked && p.beatMatchRate == nil, "\(name)")
            let room = Int(Double(p.latestEndMs - p.startMs - p.overlapMs) / p.beatMs! / Double(beatsPerBar))
            #expect((0...room).contains(p.lateMoveBars), "\(name): \(p.lateMoveBars) of \(room)")
        }
    }

    /// How far apart the beats of `plan` are, in song time, where they are furthest apart while
    /// both songs are within 10 dB of their bodies, with the plan moved `bars` bars later; its
    /// beats meet at `lockMs` (counted the same way as the plan is moved).
    func driftOf(_ plan: TransitionPlan, _ a: SectionAnalysis, _ b: SectionAnalysis, _ gap: Double, _ lockMs: Double, _ bars: Int) -> Double {
        let shift = Double(bars) * plan.beatMs! * Double(beatsPerBar)
        let room = pow(10, -overlapHeadroomDb / 20)
        var first = Double.nan
        var last = Double.nan
        var since = 0.0
        while since <= Double(plan.overlapMs) {
            let t = since / Double(plan.overlapMs)
            let out = plan.outGain(t) * room
            let into = plan.inGain(t) * room
            let at = Double(plan.startMs) + since + shift
            let heardA = out > 0 && a.levelAt(at) + 20 * log10(out) >= a.features.bodyDb - bothHeardBelowBodyDb
            let heardB = into > 0
                && b.levelAt(Double(plan.entryMs) + since * (plan.beatMatchRate ?? 1)) + 20 * log10(into) >= b.features.bodyDb - bothHeardBelowBodyDb
            if heardA && heardB {
                if first.isNaN { first = at }
                last = at
            }
            since += 10
        }
        if first.isNaN { return 0 }
        let lock = lockMs + shift
        return gap * max(0, last - lock, lock - first)
    }

    @Test func aLateMoveStopsAtTheFirstBarWhoseBeatsWouldBeHeardApart() {
        let pair = AutomixCases.pair("late-move-count")
        let p = AutomixCases.plan(pair)
        let a = AutomixCases.tail(pair.a)
        let b = AutomixCases.head(pair.b)
        #expect(p.barLocked && p.beatMatchRate == nil && p.kind != .intro, "\(p.reason)")
        let gap = abs(foldTempoRatio(a.features.tempo!.bpm / b.features.tempo!.bpm) - 1)
        let bar = p.beatMs! * Double(beatsPerBar)
        let count = p.lateMoveBars
        // The beats meet where B's first beat from its entry plays.
        let gridB = b.features.tempo!
        let firstBeat = gridB.firstBeatMs + ((Double(p.entryMs) - gridB.firstBeatMs) / gridB.beatMs - 1e-9).rounded(.up) * gridB.beatMs
        let lock = Double(p.startMs) + (firstBeat - Double(p.entryMs))
        // The plan sits in A's quiet stretch, with room to move several bars before A's louder end
        // comes under its blend.
        #expect(count >= 1, "\(count): \(p.reason)")
        #expect(Double(p.startMs + p.overlapMs) + Double(count + 1) * bar <= Double(p.latestEndMs), "\(p.reason)")
        for bars in 0...max(0, count) {
            let drift = driftOf(p, a, b, gap, lock, bars)
            #expect(drift <= blendDriftLimitMs + 0.5, "\(bars) bars later: \(drift) ms")
        }
        let past = driftOf(p, a, b, gap, lock, count + 1)
        #expect(past > blendDriftLimitMs, "\(count + 1) bars later: \(past) ms")
    }

    @Test func aHotEndingHasNoOutro() {
        let features = AutomixCases.tail("hot-end").features
        #expect(features.soundEndMs == 200_000)
        #expect(features.soundEndMs == features.outroStartMs)
        #expect(features.tempo == nil, "a steady tone has no beat")
    }

    @Test func aThreeThreeTwoKickPatternKeepsItsBeat() {
        let length = 60_000.0
        func hit(_ hz: Double, _ db: Double, _ bpm: Double, _ firstMs: Double, _ decayMs: Double) -> SongLayer {
            SongLayer("pulse", 0, length, hz: hz, db: db, bpm: bpm, firstMs: firstMs, every: 1, decayMs: decayMs)
        }
        let song = SyntheticSong(rate: 44_100, channels: 1, lengthMs: 60_000, layers: [
            SongLayer("tone", 0, length, hz: 300, db: -30),
            hit(60, -6, 40, 0, 80),
            hit(60, -6, 40, 562.5, 80),
            hit(60, -6, 40, 1_125, 80),
            hit(2_000, -12, 20, 1_500, 30),
        ])
        let tempo = song.head().features.tempo!
        #expect(near(foldTempoRatio(tempo.bpm / 80), 1, 0.01), "\(tempo)")
        #expect(tempo.confident, "\(tempo)")
    }

    @Test func aClickTrackHasItsTempoBeatAndBar() throws {
        let tempo = try #require(AutomixCases.tail("beat-120").features.tempo)
        #expect(tempo.confident)
        #expect(tempo.consistency >= 0.9)
        #expect(near(tempo.bpm, 120, 1))
        #expect(near(offBy(tempo.downbeatMs - 500, 2_000), 0, 20))
        #expect(near(offBy(tempo.firstBeatMs - 500, 500), 0, 20))
        let head = AutomixCases.head("beat-120-b").features.tempo!
        #expect(near(head.downbeatMs, 1_000, 20))
    }

    @Test func otherTempos() {
        #expect(near(AutomixCases.tail("beat-100").features.tempo!.bpm, 100, 1))
        #expect(near(AutomixCases.head("beat-118").features.tempo!.bpm, 118, 0.1))
        #expect(near(AutomixCases.tail("beat-118").features.tempo!.bpm, 118, 0.1))
        #expect(AutomixCases.tail("beat-118").features.tempo!.confident)
    }

    @Test func aSilentSectionHasNoSound() {
        let analysis = analyzeHead(envelopeOf([Float](repeating: 0, count: 48_000 * 5), 48_000))
        #expect(analysis.features.soundStartMs == nil)
        #expect(analysis.features.soundEndMs == nil)
        #expect(analysis.features.tempo == nil)
    }

    @Test func scatteredHitsAreNotABeat() {
        var seed: UInt64 = 12345
        func next() -> Double {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Double(seed >> 11) / Double(UInt64(1) << 53)
        }
        let rate = 22_050
        var samples = [Float](repeating: 0, count: rate * 30)
        var at = 0.0
        while true {
            at += 0.15 + next() * 0.6
            let start = Int(at * Double(rate))
            if start >= samples.count { break }
            for i in 0..<min(2_000, samples.count - start) {
                samples[start + i] += Float(0.5 * exp(-Double(i) / 300) * sin(2 * Double.pi * 80 * Double(i) / Double(rate)))
            }
        }
        let tempo = analyzeHead(envelopeOf(samples, rate)).features.tempo
        #expect(tempo == nil || !tempo!.confident, "found \(String(describing: tempo))")
    }

    @Test func aTagTempoPicksOnlyAmongTheFoundOctaves() {
        let envelope = AutomixCases.songs["beat-120"]!.envelope(0, 30_000)
        let found = analyzeHead(envelope).features.tempo!
        #expect(near(found.bpm, 120, 0.5))
        #expect(near(analyzeHead(envelope, tagBpm: 61).features.tempo!.bpm, 60, 0.5))
        #expect(near(analyzeHead(envelope, tagBpm: 238).features.tempo!.bpm, 240, 1))
        #expect(analyzeHead(envelope, tagBpm: 121).features.tempo == found)
        for tag in [125.0, 62, 0, -120, 90, Double.nan] {
            #expect(analyzeHead(envelope, tagBpm: tag).features.tempo == found, "tag \(tag)")
        }
        let fast = AutomixCases.songs["beat-128"]!.envelope(0, 30_000)
        let fastFound = analyzeHead(fast).features.tempo!
        #expect(near(fastFound.bpm, 128, 0.5))
        #expect(analyzeHead(fast, tagBpm: 70).features.tempo == fastFound)
    }

    @Test func theLiveTapHearsTheWholeSong() {
        let song = AutomixCases.songs["fade-out"]!
        let tap = LiveTap(sampleRate: song.rate, channels: 1)
        let samples = song.renderMono(0, 220_000)
        var at = 0
        while at < samples.count {
            let length = min(4_800, samples.count - at)
            tap.push(samples, offset: at, length: length)
            at += length
        }
        #expect(tap.heardMs() == 220_000)
        #expect(near(tap.bodyLevelDb()!, -15.25, 0.1))
        #expect(tap.tempoPrior() == nil, "a steady tone has no tempo")
        let beat = AutomixCases.songs["beat-100"]!
        let beatTap = LiveTap(sampleRate: beat.rate, channels: 1)
        beatTap.push(beat.renderMono(0, 60_000))
        #expect(near(beatTap.tempoPrior()!, 100, 0.1))
        #expect(LiveTap(sampleRate: 44_100, channels: 2).bodyLevelDb() == nil)
    }

    @Test func theFftMatchesAPlainDft() {
        let n = 64
        let input = (0..<n).map { sin(Double($0) * 0.37) + 0.5 * cos(Double($0) * 1.9) + ($0 == 5 ? 1.0 : 0.0) }
        var out = [Double](repeating: 0, count: n / 2 + 1)
        RealFft(size: n).magnitudes(input, &out)
        for k in 0...(n / 2) {
            var re = 0.0, im = 0.0
            for t in 0..<n {
                re += input[t] * cos(2 * Double.pi * Double(k * t) / Double(n))
                im -= input[t] * sin(2 * Double.pi * Double(k * t) / Double(n))
            }
            #expect(near((re * re + im * im).squareRoot(), out[k], 1e-9), "bin \(k)")
        }
    }

    @Test func theBiquadsPassAndCutWhereTheyShould() {
        func gainOf(_ filter: Biquad, _ hz: Double, _ rate: Int) -> Double {
            var peak = 0.0
            for i in 0..<rate {
                let y = filter.process(sin(2 * Double.pi * hz * Double(i) / Double(rate)))
                if i > rate / 2 { peak = max(peak, abs(y)) }
            }
            return peak
        }
        #expect(near(gainOf(Biquad.lowPass(sampleRate: 48_000, hz: 1_000), 100, 48_000), 1, 0.01))
        #expect(near(gainOf(Biquad.lowPass(sampleRate: 48_000, hz: 1_000), 1_000, 48_000), 0.7071, 0.01))
        #expect(gainOf(Biquad.lowPass(sampleRate: 48_000, hz: 1_000), 10_000, 48_000) < 0.02)
        #expect(near(gainOf(Biquad.highPass(sampleRate: 44_100, hz: 200), 5_000, 44_100), 1, 0.01))
        #expect(gainOf(Biquad.highPass(sampleRate: 44_100, hz: 200), 20, 44_100) < 0.02)
        #expect(gainOf(Biquad.lowPass(sampleRate: 22_050, hz: 18_000), 1_000, 22_050) < 1.01)
    }

    // Plans

    @Test func trailingSilenceIsSkipped() {
        let plan = plan("trailing-silence")
        let soundEnd = AutomixCases.tail("silence-tail").features.soundEndMs!
        #expect(plan.startMs + plan.overlapMs <= soundEnd)
        #expect(plan.startMs + plan.overlapMs >= soundEnd - 1_000)
        #expect(plan.overlapMs == 1_800)
        #expect(plan.kind == .cut)
        #expect(near(Double(plan.entryMs), 1_480, 30))
    }

    @Test func aLongFadeOutBlendsFromTheOutro() {
        let plan = plan("fade-out")
        let outro = AutomixCases.tail("fade-out").features.outroStartMs!
        #expect(((outro - 500)...(outro + 3_000)).contains(plan.startMs), "start \(plan.startMs) outro \(outro)")
        #expect(plan.overlapMs == 8_000)
        #expect(plan.kind == .lift, "the next song is louder than the fading one")
        #expect(plan.k == liftCurve)
        #expect(plan.reason.hasPrefix("lift at"))
    }

    @Test func aHotEndingCutsShortAtTheEnd() {
        let plan = plan("hot-end")
        #expect(plan.startMs + plan.overlapMs == 200_000 - endMarginMs)
        #expect(plan.kind == .cut)
        #expect(plan.k == cutCurve)
    }

    @Test func theSameTempoBlendsFourBarsFromABarLine() {
        let plan = plan("same-tempo")
        #expect(near(offBy(Double(plan.startMs) - 500, 2_000), 0, 20))
        #expect(near(Double(plan.overlapMs), 8_000, 10))
        #expect(plan.kind == .blend)
        // The next song's last beat at or before its first sound (its clicks start at 1 s, every
        // half second): none of it is cut.
        #expect(near(Double(plan.entryMs), 500, 20))
        #expect(plan.barLocked)
        #expect(plan.headroomDb == overlapHeadroomDb)
        #expect(plan.filterStrength == filterStrength)
        #expect(plan.beatMatchRate == nil)
        #expect(plan.reason.contains("4 bars of 120.0 BPM"))
        #expect(self.plan("same-tempo-long").overlapMs == 8_000)
    }

    @Test func aShortSliderKeepsWholeBars() {
        #expect(near(Double(plan("same-tempo-short").overlapMs), 4_000, 10))
    }

    @Test func temposTooFarApartNeverSoundTogetherOffTheBeat() {
        // 120 against 100 BPM, 20% apart: the click tracks are heard only around their kicks, and
        // the cut left sits where the two are never clearly heard at once.
        let plan = plan("tempo-clash")
        #expect(plan.kind == .cut, "\(plan.reason)")
        #expect(plan.barLocked && plan.driftAtEndMs <= blendDriftLimitMs, "\(plan.reason)")
        #expect(plan.bothHeardEndMs == 0)
    }

    @Test func beatMatchNudgesTheNextSongOntoTheBar() {
        let plan = plan("beat-match")
        #expect(near(plan.beatMatchRate!, 120.0 / 118.0, 0.005))
        let head = AutomixCases.head("beat-118").features.tempo!
        // Its last bar line at or before its first sound (0.4 s) falls before its start: it comes
        // in from 0, and the outgoing song starts earlier by its first bar line's time, so that bar
        // line lands on one of A's.
        #expect(plan.entryMs == 0)
        let firstBar = head.downbeatMs - (head.downbeatMs / head.barMs).rounded(.down) * head.barMs
        let meets = Double(plan.startMs) + firstBar / plan.beatMatchRate!
        #expect(onBar("beat-120", meets), "\(meets)")
        #expect(near(Double(plan.overlapMs), 8_000, 10))
        #expect(self.plan("beat-match-off").beatMatchRate == nil, "beat match is off unless asked for")
    }

    @Test func smartOffIsThePlainCrossfade() {
        let plan = plan("smart-off")
        #expect(plan.kind == .crossfade)
        #expect(plan.startMs == 192_000)
        #expect(plan.overlapMs == 8_000)
        #expect(plan.entryMs == 0)
        #expect(plan.k == 0)
        #expect(plan.filterStrength == 0)
        #expect(plan.headroomDb == 0)
    }

    @Test func thePlainCrossfadeForAGenreKeepsItsCurve() {
        let plan = plan("classical")
        #expect(plan.kind == .crossfade)
        #expect(plan.k == crossfadeCurve)
        #expect(plan.filterStrength == 0)
    }

    @Test func withoutTheTailItBlendsLateAtTheEnd() {
        for name in ["no-analysis", "no-tail"] {
            let plan = plan(name)
            let length = AutomixCases.songs[AutomixCases.pair(name).a]!.lengthMs
            #expect(plan.late, "\(name)")
            #expect(plan.startMs == length - endMarginMs - 8_000, "\(name)")
            #expect(plan.overlapMs == 8_000, "\(name)")
            #expect(plan.filterStrength == filterStrength, "\(name)")
            #expect(!plan.barLocked, "\(name)")
        }
        #expect(plan("no-analysis").entryMs == 0)
        #expect(near(Double(plan("no-tail").entryMs), 1_480, 30), "the next song still skips its silence")
    }

    @Test func withoutTheHeadThereIsNoBarLock() {
        let plan = plan("no-head")
        #expect(!plan.barLocked)
        #expect(!plan.late)
        #expect(plan.entryMs == 0)
    }

    @Test func genresThatShouldNotBeMixedGetThePlainCrossfade() {
        #expect(plan("classical").kind == .crossfade)
        #expect(plan("podcast").kind == .crossfade)
        #expect(plan("podcast").reason.hasSuffix("genre Podcast"))
    }

    @Test func paceAndSkipSilenceTurnOffBarLockAndBeatMatch() {
        // Both tempos are confident, so with the beats unable to line up a blend where both songs
        // are clearly heard together is never planned: these click tracks are heard only around
        // their kicks, and the cut sits where the two never are at once.
        for name in ["pace", "skip-silence"] {
            let plan = plan(name)
            #expect(!plan.barLocked, "\(name)")
            #expect(plan.beatMatchRate == nil, "\(name)")
            #expect(plan.kind == .cut, "\(name)")
            #expect(plan.bothHeardEndMs == 0, "\(name)")
        }
        // Where every start has both heard together, they join gaplessly.
        #expect(plan("pace-steady").reason == "gapless: the beats cannot be lined up")
        // Where the outgoing song is no longer clearly heard, the blend goes ahead unlocked, as it
        // always did.
        let outro = plan("drift-phone-pace")
        #expect(!outro.barLocked && outro.beatMatchRate == nil && outro.kind != .gapless, "\(outro.reason)")
        #expect(outro.bothHeardEndMs == 0)
        #expect(outro.startMs >= 190_000 - 250, "\(outro.reason)")
    }

    @Test func tooLateForTheChosenStartBlendsAtTheEnd() {
        // Both tempos trusted: from A's first bar line after the start it could have had, to the
        // latest end.
        let late = plan("late")
        #expect(late.late)
        #expect(late.barLocked)
        #expect(near(offBy(Double(late.startMs) - 500, 2_000), 0, 20))
        #expect(late.startMs >= 199_750 - 8_000, "\(late.reason)")
        #expect(late.startMs + late.overlapMs == 199_750)
        // Under `shortestBlendMs` from the next bar line: a cut from A's earliest beat after the
        // decision.
        let short = plan("late-short")
        #expect(short.late && short.barLocked)
        #expect(near(offBy(Double(short.startMs) - 500, 500), 0, 20))
        #expect(short.startMs >= 197_000 && short.overlapMs <= shortestBlendMs, "\(short.reason)")
        #expect(short.startMs + short.overlapMs == 199_750)
        #expect(plan("too-late").kind == .gapless)
    }

    @Test func theStartLeavesTimeToPreRoll() {
        let plan = plan("pre-roll")
        #expect(plan.startMs >= 183_000 + preRollMs)
        #expect(!plan.late)
    }

    @Test func theBlendWaitsUntilTheSongCountsAsPlayed() {
        let plan = plan("seeked-forward")
        #expect(plan.late)
        let soundEnd = AutomixCases.tail("fade-out").features.soundEndMs!
        #expect(plan.startMs + plan.overlapMs == soundEnd)
        for pair in AutomixCases.pairs {
            let p = AutomixCases.plan(pair)
            if p.kind == .gapless || p.kind == .crossfade { continue }
            #expect(p.startMs >= AutomixCases.songs[pair.a]!.lengthMs / 2, "\(pair.name)")
        }
    }

    @Test func theWholeSongsLevelMovesTheOutro() {
        let plan = plan("whole-song-level")
        let moved = AutomixCases.tail("fade-out").withBodyLevel(-24).features
        #expect(moved.bodyDb == -24)
        #expect(moved.outroStartMs! > AutomixCases.tail("fade-out").features.outroStartMs! + 4_000)
        #expect(((moved.outroStartMs! - 500)...(moved.outroStartMs! + 3_000)).contains(plan.startMs))
    }

    @Test func theWholeSongsTempoSetsTheOctaveOrDropsTheLock() {
        let half = plan("tempo-prior")
        #expect(near(half.beatMs!, 1_000, 1))
        #expect(half.reason.contains("of 120.0 BPM"), "\(half.reason)")
        #expect(!plan("tempo-prior-off").barLocked, "a tempo the whole song does not share is not trusted")
    }

    @Test func anAlbumInOrderStaysGapless() {
        let plan = plan("album-in-order")
        #expect(plan.kind == .gapless)
        #expect(plan.overlapMs == 0)
        #expect(plan.startMs == 200_000)
        #expect(plan.reason == "gapless: the album plays in order")
    }

    @Test func repeatOneTheSleepTimerAndCrossfadeOffNeverBlend() {
        #expect(plan("stop-at-end").kind == .gapless)
        #expect(plan("repeat-one").kind == .gapless)
        #expect(plan("repeat-one").reason == "gapless: repeat one")
        #expect(plan("crossfade-off").kind == .gapless)
    }

    @Test func sweepsOffLeavesTheFiltersOpen() {
        let plan = plan("no-sweeps")
        #expect(plan.filterStrength == 0)
        #expect(near(plan.outgoingLowPassAt(0.9), openLowPassHz, 1e-9))
        #expect(near(plan.outgoingHighPassAt(0.9), openHighPassHz, 1e-9))
        #expect(near(plan.incomingHighPassAt(0.1), incomingOpenHz, 1e-9))
    }

    @Test func aShortSongGetsThePlainCrossfade() {
        let plan = planTransition(current: FadeSong(albumId: "a", albumOrder: 1, durationMs: 30_000),
                                  next: FadeSong(albumId: "b", albumOrder: 1, durationMs: 180_000),
                                  tail: AutomixCases.tail("hot-end"), head: AutomixCases.head("plain-b"),
                                  settings: AutomixSettings(maxOverlapMs: 8_000))
        #expect(plan.kind == .crossfade)
        #expect(plan.startMs == 22_000)
    }

    /// A's bar line nearest a time, from its tail's grid.
    func barOf(_ song: String, _ ms: Double) -> Double {
        let grid = AutomixCases.tail(song).features.tempo!
        return grid.downbeatMs + roundHalfUp((ms - grid.downbeatMs) / grid.barMs) * grid.barMs
    }

    func onBar(_ song: String, _ ms: Double, within: Double = 2) -> Bool { abs(barOf(song, ms) - ms) <= within }

    /// Where in A's time a locked plan's beats meet: B's first beat (its first bar line with a
    /// rate) from its entry on, played from the start.
    func lockOf(_ name: String) -> Double {
        let p = plan(name)
        let grid = AutomixCases.head(AutomixCases.pair(name).b).features.tempo!
        let rate = p.beatMatchRate
        let (anchor, step) = rate != nil ? (grid.downbeatMs, grid.barMs) : (grid.firstBeatMs, grid.beatMs)
        let first = anchor + ((Double(p.entryMs) - anchor) / step - 1e-9).rounded(.up) * step
        return Double(p.startMs) + (first - Double(p.entryMs)) / (rate ?? 1)
    }

    @Test func fullLevelMusicIsKeptOverEarlyExitsAtSectionBoundaries() {
        let tail = AutomixCases.tail("steady-100-sections")
        // Section boundaries 24 s and 4.8 s before the last bars, each within the reach of a bar
        // line's boundary bonus.
        #expect(tail.features.boundariesMs.contains { abs($0 - 170_400) <= 250 }, "\(tail.features.boundariesMs)")
        #expect(tail.features.boundariesMs.contains { abs($0 - 189_600) <= 250 }, "\(tail.features.boundariesMs)")
        let plan = plan("kept-music")
        // The last bars, losing at most a second of the music at full level.
        #expect(plan.latestEndMs - (plan.startMs + plan.overlapMs) <= keptMusicLimitMs, "\(plan.reason)")
        #expect(plan.startMs > 189_600 + 1_000, "\(plan.reason)")
        // A song fading out still leaves at its outro.
        let fading = self.plan("fade-out")
        #expect(fading.startMs + fading.overlapMs < fading.latestEndMs - 10_000, "\(fading.reason)")
    }

    @Test func aBlendEndingOffTheBarStretchesToTheLatestEnd() {
        // The last bar line a whole-bar blend can end on is 1.9 s before the latest end: the blend
        // keeps its bar-line start and runs to it.
        let plan = plan("off-grid-end")
        #expect(plan.startMs + plan.overlapMs == plan.latestEndMs)
        #expect(onBar("steady-100", lockOf("off-grid-end")), "\(plan.reason)")
        #expect(near(Double(plan.overlapMs), 7_200 + 1_900, 30))
        #expect(!plan.reason.contains("bars of"), "\(plan.reason)")
        // Over the longest blend from there: one bar later.
        let over = self.plan("off-grid-end-over")
        #expect(over.startMs + over.overlapMs == over.latestEndMs)
        #expect(onBar("steady-100", lockOf("off-grid-end-over")), "\(over.reason)")
        #expect(near(Double(over.startMs), Double(plan.startMs) + 2_400, 30))
    }

    @Test func anIntroEndingOffTheBarFadesTheOutgoingSongToTheLatestEnd() {
        let introEnd = AutomixCases.head("quiet-100").features.introEndMs!
        // Five bars to the next song's full point, a two-bar fade: A holds for four bars, and only
        // its fade end moves to the latest end.
        let plan = plan("off-grid-intro")
        #expect(plan.kind == .intro, "\(plan.reason)")
        #expect(plan.startMs + plan.overlapMs == plan.latestEndMs)
        #expect(onBar("steady-100", lockOf("off-grid-intro")), "\(plan.reason)")
        #expect(near(plan.holdShare * Double(plan.overlapMs), 9_600, 30))
        #expect(near(Double(plan.overlapMs), 14_400 + 1_500, 30))
        // Over the longest intro: B's full point kept, one bar later.
        let capped = self.plan("off-grid-intro-capped")
        #expect(capped.kind == .intro, "\(capped.reason)")
        #expect(capped.startMs + capped.overlapMs == capped.latestEndMs)
        #expect(onBar("steady-100", lockOf("off-grid-intro-capped")), "\(capped.reason)")
        #expect(near(Double(capped.startMs), Double(plan.startMs) + 2_400, 30))
        // A at its own level until four bars in.
        let holdEnd = capped.holdShare * Double(capped.overlapMs)
        #expect(near(holdEnd, 9_600, 30))
        #expect(capped.outGain((holdEnd - 1) / Double(capped.overlapMs)) == 1)
        // At the middle of A's fade, B has played at least its quiet start less half a bar.
        let fadeMiddle = holdEnd + (Double(capped.overlapMs) - holdEnd) / 2
        #expect(Double(capped.entryMs) + fadeMiddle >= Double(introEnd) - 2_400 / 2.0, "\(fadeMiddle)")
        #expect(capped.overlapMs <= automixLongestIntroMs, "\(capped.reason)")
        // With a fade of half the quiet start, so short that a bar later it would be under half the
        // planned fade: no capped twin. The intro left ends on its bar, 1.2 s of full-level music
        // before the latest end, so it is not planned; the blend ends at the latest end.
        let short = self.plan("off-grid-intro-short-fade")
        #expect(short.kind != .intro && short.kind != .gapless, "\(short.reason)")
        #expect(short.latestEndMs == short.startMs + short.overlapMs)
    }

    @Test func aPlaceThatCutsTheSongShortIsNeverPlannedEvenWhenItIsTheOnlyOneLeft() {
        // Tempos 12% to 25% apart, the outgoing song at full level to the end: only blends and
        // intros in its quiet stretch keep the beats apart, and each would cut seconds of its
        // music. The songs join gaplessly at its end instead.
        for name in ["kept-cut-25", "kept-intro-24", "kept-intro-20", "kept-blend-12", "kept-intro-18"] {
            let p = plan(name)
            #expect(p.kind == .gapless, "\(name): \(p.reason)")
            #expect(p.startMs == AutomixCases.pair(name).current.durationMs, "\(name)")
            #expect(p.reason == "gapless: every blend left would cut the song short", "\(name)")
        }
        // Where the places that keep the beats apart cut under a second of the full-level music, one
        // of them is still planned.
        let short = plan("kept-short-cut")
        let tail = AutomixCases.tail("dip-short")
        let end = Double(short.startMs + short.overlapMs)
        #expect(short.kind != .gapless && end < Double(short.latestEndMs) - 500, "\(short.reason)")
        let cut = tail.msAtLeast(end, Double(short.latestEndMs), tail.features.bodyDb - keptMusicBelowBodyDb)
        #expect(cut > 500 && cut <= Double(keptMusicLimitMs), "\(cut)")
        #expect(short.driftAtEndMs <= blendDriftLimitMs, "\(short.reason)")
    }

    @Test func anIntroLowersTheNextSongThroughTheHoldUntilItStaysWellUnderTheOutgoingSong() {
        // The quiet starts against the outgoing song at the same moments (bars lined up): 7 dB under
        // it, 5 dB under it and at its level.
        func under(_ outgoing: String, _ fromMs: Double, _ song: String) -> Double {
            let a = AutomixCases.tail(outgoing)
            let b = AutomixCases.head(song)
            let apart = (300..<1_000).map { b.levelAt(Double($0) * 10) - a.levelAt(fromMs + Double($0) * 10) }.sorted()
            return apart[apart.count / 2]
        }
        #expect(near(under("steady-100", 180_000, "under-100"), -7, 0.5))
        #expect(near(under("steady-100", 180_000, "near-100"), -5, 0.5))
        #expect(near(under("steady-100", 180_000, "level-100"), 0, 0.5))
        func trimDb(_ plan: TransitionPlan) -> Double { -20 * log10(plan.introLevel) }
        // Where the incoming song is the quieter, the overlap's rule keeps it this far under the
        // outgoing song at every step of the hold (the two summed, with the headroom,
        // `introExcessLimitDb` from the louder alone); the hold's rule keeps it `introUnderOutgoingDb`
        // under.
        let overlapUnder = -10 * log10(pow(10, (introExcessLimitDb + overlapHeadroomDb) / 10) - 1)
        // At the outgoing song's level: lowered by the larger of the two and held there, then brought
        // up as the outgoing song fades.
        let level = plan("intro-trimmed")
        #expect(level.kind == .intro, "\(level.reason)")
        #expect(near(trimDb(level), max(introUnderOutgoingDb, overlapUnder), 0.5), "\(level.introLevel)")
        // 7 dB and 5 dB under: lowered at least by what each lacks of the hold's margin, and less than
        // the one at the level. The overlap's rule lowers them further for the fade, where the next
        // song comes up through the outgoing one's level while that still plays near its own.
        let quiet = plan("intro-under-line")
        #expect(quiet.kind == .intro, "\(quiet.reason)")
        #expect(trimDb(quiet) >= introUnderOutgoingDb - 7 - 0.5 && trimDb(quiet) < trimDb(level), "\(quiet.introLevel)")
        let near5 = plan("intro-over-line")
        #expect(near5.kind == .intro, "\(near5.reason)")
        #expect(trimDb(near5) >= introUnderOutgoingDb - 5 - 0.5 && trimDb(near5) < trimDb(level), "\(near5.introLevel)")
        #expect(level.reason.contains(", next song lowered "), "\(level.reason)")
        #expect(level.inGain(level.holdShare / 2) == level.introLevel)
        #expect(near(level.inGain(1), 1, 1e-9))
        // At its own level the next song would be clearly heard from its rise on, together with the
        // outgoing song held at its own; lowered, it is not heard with it before the hold ends.
        #expect(Double(level.bothHeardStartMs) >= level.holdShare * Double(level.overlapMs) - 1, "\(level.reason)")
        // Under an outgoing song near silence it would have to come down over `introTrimCapDb`: no
        // intro, the blend lengthened into the quiet start.
        #expect(under("faint-100", 72_000, "level-100") + introUnderOutgoingDb > introTrimCapDb)
        let faint = plan("intro-trim-capped")
        #expect(faint.kind != .intro && faint.kind != .gapless, "\(faint.reason)")
        #expect(faint.reason.contains("(full from"), "\(faint.reason)")
    }

    @Test func aCutOnTheBeatEndsAtAnOffGridLatestEnd() {
        let plan = plan("off-grid-cut")
        #expect(plan.startMs + plan.overlapMs == plan.latestEndMs)
        #expect(plan.overlapMs < shortestBlendMs, "\(plan.reason)")
        let grid = AutomixCases.tail("steady-100").features.tempo!
        #expect(near(offBy(lockOf("off-grid-cut") - grid.firstBeatMs, grid.beatMs), 0, 2))
        #expect(plan.driftAtEndMs <= blendDriftLimitMs, "\(plan.driftAtEndMs)")
    }

    @Test func theRateComesFirstAndReachesSixPercent() {
        // 0.3% apart is matched.
        #expect(near(plan("rate-small-gap").beatMatchRate!, 1.003, 0.0005))
        // 5.7% apart is matched, the intro's quiet start counted at the rate.
        #expect(near(plan("intro-matched").beatMatchRate!, 100.0 / 106.0, 0.001))
    }

    @Test func theNextSongEntersOnItsOwnGridWithoutLosingASound() {
        let head = AutomixCases.head("steady-100-late").features
        let soundStart = head.soundStartMs!
        // Its first sound 350 ms after a beat: the entry is that beat.
        let beat = plan("entry-on-beat")
        #expect(near(Double(beat.entryMs), 1_150, 15))
        #expect(beat.entryMs <= soundStart)
        // With a rate its last bar line at or before the first sound falls before its start: it
        // comes in from 0, and A starts earlier so that its first bar line (1.75 s) lands on A's.
        let bar = plan("entry-on-downbeat")
        #expect(bar.beatMatchRate != nil)
        #expect(bar.entryMs == 0)
        #expect(bar.entryMs != soundStart, "\(soundStart)")
        #expect(onBar("steady-101", Double(bar.startMs) + 1_750 / bar.beatMatchRate!), "\(bar.reason)")
        #expect(near(lockOf("entry-on-downbeat"), Double(bar.startMs) + 1_750 / bar.beatMatchRate!, 15))
        // Its last beat before its first sound falls before its start: from 0, with A earlier by
        // its first beat's 300 ms.
        let early = plan("entry-before-start")
        #expect(early.entryMs == 0)
        let grid = AutomixCases.tail("steady-101").features.tempo!
        #expect(near(offBy(Double(early.startMs) + 300 - grid.firstBeatMs, grid.beatMs), 0, 2))
    }

    @Test func whereverBothSongsAreHeardTheirBeatsStayWithin40Ms() {
        for name in ["drift-1", "drift-3", "drift-7", "drift-10", "drift-20", "drift-40", "entry-on-beat", "entry-before-start",
                     "beat-match-off", "same-tempo", "late-move-count"] {
            let p = plan(name)
            #expect(p.kind == .gapless || p.driftAtEndMs <= blendDriftLimitMs, "\(name): \(p.reason)")
        }
        // About 1.5% apart: the three-bar blend (stretched to the latest end) starts later by whole
        // bars, its end kept.
        let one = plan("drift-1")
        #expect(one.barLocked && one.overlapMs < 3 * 2_376 && one.overlapMs >= shortestBlendMs, "\(one.reason)")
        #expect(onBar("steady-101", lockOf("drift-1")), "\(one.reason)")
        #expect(one.startMs + one.overlapMs == one.latestEndMs)
        #expect(one.driftAtEndMs > 0, "\(one.reason)")
        // 7.1% apart: no 1.8 s cut (74 ms apart where both stop being heard).
        let seven = plan("drift-7")
        #expect(seven.kind == .gapless || seven.overlapMs < shortestBlendMs, "\(seven.reason)")
        // 20% and 40% apart: even the shortest cut is too long.
        #expect(plan("drift-20").kind == .gapless)
        #expect(plan("drift-40").reason == "gapless: the beats would drift apart")
        // A in its outro, no longer clearly heard: the blend as planned.
        let outro = plan("drift-outro")
        #expect(outro.reason.contains("(2 bars of"), "\(outro.reason)")
        // On the desktop, locked at 1.25 times: within 50 ms of song time.
        let desk = plan("drift-desktop-pace")
        #expect(desk.barLocked && desk.driftAtEndMs <= blendDriftLimitMs * 1.25, "\(desk.reason)")
        #expect(desk.driftAtEndMs > 0, "\(desk.reason)")
    }

    @Test func lateLockedPlansKeepTheirRateOrTheirBound() {
        // No planned start left, 3% apart and matched: from A's first bar line.
        let matched = plan("drift-late-matched")
        #expect(matched.late && matched.barLocked && matched.beatMatchRate != nil, "\(matched.reason)")
        #expect(onBar("steady-100", lockOf("drift-late-matched")), "\(matched.reason)")
        #expect(matched.startMs + matched.overlapMs == matched.latestEndMs)
        // 7.1% apart, unmatched: capped to a cut.
        let capped = plan("drift-late-capped")
        #expect(capped.late && capped.barLocked && capped.beatMatchRate == nil, "\(capped.reason)")
        #expect(capped.overlapMs < shortestBlendMs && capped.driftAtEndMs <= blendDriftLimitMs, "\(capped.reason)")
        // Under `shortestBlendMs` left: the cut never starts before the plan was made.
        let cut = plan("drift-late-cut")
        #expect(cut.kind != .gapless && cut.startMs >= 200_500, "\(cut.reason)")
        // A window under a bar long: the locked late plan, on a bar line.
        let window = plan("drift-short-window")
        #expect(window.late && window.barLocked, "\(window.reason)")
        #expect(onBar("steady-100", lockOf("drift-short-window")), "\(window.reason)")
    }

    @Test func aRateTheNextSongIsTooShortToEaseBackFromIsPlannedAgain() {
        let plan = plan("rate-room")
        let pair = AutomixCases.pair("rate-room")
        #expect(plan.beatMatchRate == nil, "\(plan.reason)")
        // The entry without a rate: its last beat before its first sound (15.9 s), not its last bar
        // line (15.27 s).
        #expect(near(Double(plan.entryMs), 15_900, 20))
        #expect(plan.barLocked && plan.driftAtEndMs <= blendDriftLimitMs, "\(plan.reason)")
        // With the rate the next song would have been too short.
        #expect(Double(pair.next.durationMs - 15_270) <= 14_400 * 1.05 + beatMatchSettleMs + 1_000)
    }

    @Test func aQuietStartLongEnoughPlaysUnderTheOutgoingSongsLastBars() {
        // Matched at 5.7%: an intro of whole bars from where the beats meet, no sweeps, the fade
        // centered on a bar line at or after the next song's full point.
        let plan = plan("intro-matched")
        #expect(plan.kind == .intro, "\(plan.reason)")
        #expect(plan.filterStrength == 0)
        let bar = 2_400.0
        let fade = (1 - plan.holdShare) * Double(plan.overlapMs)
        #expect(near(fade, 2 * bar, 30))
        #expect(near(plan.riseShare, bar / Double(plan.overlapMs), 0.003))
        let fullAt = plan.holdShare * Double(plan.overlapMs) + fade / 2
        #expect(onBar("steady-100", Double(plan.startMs) + fullAt), "\(fullAt)")
        let introEnd = AutomixCases.head("quiet-106").features.introEndMs!
        let quiet = Double(introEnd - plan.entryMs) / plan.beatMatchRate!
        #expect(fullAt >= quiet && fullAt < quiet + bar, "\(fullAt) for \(quiet)")
        // The quiet start counted at the rate: five bars from the lock point, where it is four in
        // the next song's own time.
        #expect(near(fullAt - plan.lockMs, 5 * bar, 30))
        #expect(Double(introEnd - plan.entryMs) <= 4 * bar, "\(introEnd)")
        // It ends 150 ms before the latest end, short of a bar: no move.
        #expect(plan.lateMoveBars == 0)
    }

    @Test func anIntroWhoseBeatsWouldBeHeardApartIsRefused() {
        // 5.7% apart, unmatched: the intro would be far apart where the next song is full; the
        // blend is planned instead, within the bound.
        for name in ["intro-unmatched", "intro-short-drift", "intro-drift"] {
            let p = plan(name)
            #expect(p.kind != .intro, "\(name): \(p.reason)")
            #expect(p.kind == .gapless || p.driftAtEndMs <= blendDriftLimitMs, "\(name): \(p.reason)")
        }
        // 1% apart with A dropping 12 dB at its outro: the starts whose fade falls in the outro keep
        // the beats apart only where A is quiet.
        let later = plan("intro-later-start")
        #expect(later.kind == .intro, "\(later.reason)")
        #expect(later.driftAtEndMs <= blendDriftLimitMs, "\(later.reason)")
        #expect(Double(later.startMs) + Double(later.overlapMs) * later.holdShare >= 186_000, "\(later.reason)")
        // A's tempo not the whole song's: no intro.
        #expect(plan("intro-tempo-dropped").kind != .intro)
        // At a phone's 1.25 times, a next song with no beat: an intro as before, unlocked; with a
        // beat both are confident, and an intro where both are heard together is refused.
        let beatless = plan("intro-phone-pace")
        #expect(beatless.kind == .intro, "\(beatless.reason)")
        #expect(!beatless.barLocked, "\(beatless.reason)")
        let confident = plan("intro-phone-pace-confident")
        #expect(confident.kind != .intro && confident.bothHeardEndMs == 0, "\(confident.reason)")
        // A quiet start of 35 s against the whole song's body: 16 s, capped.
        let cap = plan("intro-cap")
        #expect(cap.kind == .intro, "\(cap.reason)")
        #expect(cap.overlapMs == automixLongestIntroMs)
        #expect(cap.reason.contains(", capped)"), "\(cap.reason)")
    }

    @Test func theBeatsAreBoundOnBothSidesOfWhereTheyMeet() {
        // The next song is loud from its first sample, its beats 600 ms in: it is heard well before
        // its beats meet A's, and that side is the further apart.
        let p = plan("drift-before-lock")
        #expect(p.barLocked && p.beatMatchRate == nil && p.kind != .gapless, "\(p.reason)")
        #expect(near(p.lockMs, 600, 15))
        #expect(Double(p.bothHeardStartMs) < p.lockMs, "\(p.reason)")
        let before = p.lockMs - Double(p.bothHeardStartMs)
        let after = Double(p.bothHeardEndMs) - p.lockMs
        #expect(before > after, "\(before) before, \(after) after")
        #expect(near(p.driftAtEndMs, p.driftGap * before, p.driftGap * 10 + 0.01))
        #expect(p.driftAtEndMs <= blendDriftLimitMs, "\(p.driftAtEndMs)")
    }

    @Test func anIntroWithALeadInStillFadesAroundABarLine() {
        let p = plan("intro-lead")
        #expect(p.kind == .intro, "\(p.reason)")
        // Its beats meet A's 100 ms after it starts.
        #expect(near(p.lockMs, 100, 15))
        #expect(onBar("steady-100", Double(p.startMs) + p.lockMs), "\(p.reason)")
        // The middle of A's fade, where the next song is full, on A's bar line.
        let hold = p.holdShare * Double(p.overlapMs)
        let fadeMiddle = Double(p.startMs) + hold + (Double(p.overlapMs) - hold) / 2
        #expect(onBar("steady-100", fadeMiddle), "\(fadeMiddle): \(p.reason)")
    }

    @Test func aLateBlendCappedTooShortToFadeTheOutgoingSongOutIsNotPlanned() {
        // A's grid at 132 BPM (1818 ms bars, a downbeat at 201.45 s), the next song's at 35 BPM (a
        // tagged slow song), 5.7% apart with no rate. The next song is silent until 1.6 s and its
        // first beat is at 1.65 s, so it enters at 0 and a blend holds A through those 1.6 s. Started
        // at 199 s, the late blend runs from A's downbeat at 201.45 s less that lead to the latest
        // end, 1.95 s: within the drift bound, but only 350 ms after the hold to fade A out, and every
        // cut is shorter still. Neither the capped blend nor a cut is planned.
        let tail = AutomixCases.tail("steady-100")
        let head = AutomixCases.head("steady-100-late-sound")
        var fast = tail.features
        fast.tempo!.bpm = 132
        fast.tempo!.beatMs = 60_000.0 / 132
        fast.tempo!.firstBeatMs = 1_470
        fast.tempo!.downbeatMs = 1_470
        var slow = head.features
        slow.tempo!.bpm = 35
        slow.tempo!.beatMs = 60_000.0 / 35
        slow.tempo!.firstBeatMs = 1_650
        slow.tempo!.downbeatMs = 1_650
        let p = planTransition(current: FadeSong(albumId: "a", albumOrder: 1, durationMs: 202_000),
                               next: FadeSong(albumId: "b", albumOrder: 1, durationMs: 40_000),
                               tail: SectionAnalysis.fromFeatures(tail.envelope, fast),
                               head: SectionAnalysis.fromFeatures(head.envelope, slow),
                               settings: AutomixSettings(maxOverlapMs: 8_000), context: TransitionContext(nowMs: 199_000))
        #expect(p.kind == .gapless, "\(p.reason)")
        #expect(p.reason.hasPrefix("gapless: too late to blend"), "\(p.reason)")
    }

    @Test func aBlendHoldsThroughTheNextSongsSilentLeadIn() {
        // Matched, it enters at 0 for its first bar line at 0.9 s, its first sound at 0.5 s: A alone
        // at its own level until then.
        let p = plan("lead-held-cut")
        #expect(p.kind == .cut, "\(p.reason)")
        #expect(p.beatMatchRate != nil)
        #expect(p.entryMs == 0)
        let soundStart = AutomixCases.head("steady-100-lead").features.soundStartMs!
        #expect(near(p.holdShare * Double(p.overlapMs), Double(soundStart) / p.beatMatchRate!, 2))
        for step in 0...99 {
            let t = p.holdShare * Double(step) / 100
            #expect(p.outGain(t) == 1, "out at \(t)")
            #expect(p.inGain(t) == 0, "in at \(t)")
        }
        // Then the cut's own curve over the rest.
        let rest = (0.5 - p.holdShare) / (1 - p.holdShare)
        #expect(near(p.outGain(0.5), automixOutGain(rest, cutCurve), 1e-12))
        #expect(near(p.inGain(0.5), automixInGain(rest, cutCurve), 1e-12))
    }

    @Test func aHeldStartHoldsOnEveryCurveThenFollowsIt() {
        // A fixed crossfade on a chosen curve with a held start: the curve plays over what is left.
        for curve in FadeCurve.allCases where curve != .equalPower {
            var p = fixedCrossfadePlan(lenA: 200_000, length: 8_000, why: "test", curve: curve)
            p.holdShare = 0.25
            #expect(p.outGain(0.2) == 1, "\(curve)")
            #expect(p.inGain(0.2) == 0, "\(curve)")
            let left = fadeGains(curve, (0.6 - 0.25) / 0.75, overlapMs: 6_000.0)
            #expect(near(p.outGain(0.6), left.out, 1e-12), "\(curve)")
            #expect(near(p.inGain(0.6), left.incoming, 1e-12), "\(curve)")
        }
    }

    @Test func aLoweredSpeedCutsWhereTheBeatsFirstGetTooFarApart() throws {
        func heard(_ start: Int, _ end: Int, _ lock: Double, _ gap: Double) -> TransitionPlan {
            var p = fixedCrossfadePlan(lenA: 200_000, length: 8_000, why: "test")
            p.bothHeardStartMs = start
            p.bothHeardEndMs = end
            p.lockMs = lock
            p.driftGap = gap
            return p
        }
        // An intro heard together from 1.9 s to 3.5 s, 35 ms apart by the end: at half speed the
        // limit is 20 ms, passed 2 s in, after A's hold; at full speed never.
        let intro = heard(1_900, 3_500, 0, 0.01)
        #expect(near(try #require(dropCutAtMs(intro, 0.5, 500)), 2_000, 1e-9))
        #expect(dropCutAtMs(intro, 1, 500) == nil)
        // Dropped past that moment: at once.
        #expect(near(try #require(dropCutAtMs(intro, 0.5, 2_500)), 2_500, 1e-9))
        // Heard before the beats meet, far enough out to be apart already.
        let early = heard(100, 650, 400, 0.1)
        #expect(near(try #require(dropCutAtMs(early, 0.5, 0)), 100, 1e-9))
        #expect(near(try #require(dropCutAtMs(early, 0.5, 300)), 600, 1e-9))
        #expect(near(try #require(dropCutAtMs(early, 0.5, 620)), 620, 1e-9))
        #expect(dropCutAtMs(early, 0.5, 700) == nil)
        // A plan whose beats reach the limit just as both stop being heard, at the speed it was
        // made for: no cut.
        #expect(dropCutAtMs(heard(0, 2_000, 0, 0.02), 1, 0) == nil)
        // Never heard together, or never apart.
        #expect(dropCutAtMs(heard(0, 0, 0, 0.02), 0.5, 0) == nil)
        #expect(dropCutAtMs(heard(0, 0, 5_000, 0.02), 0.5, 0) == nil)
        #expect(dropCutAtMs(heard(500, 3_000, 200, 0), 0.5, 0) == nil)
        // A planned blend's own numbers: at half speed its 24 ms by the end of the stretch are 48 ms
        // heard.
        let seven = plan("drift-7")
        let cut = try #require(dropCutAtMs(seven, 0.5, 0), "\(seven.reason)")
        #expect(cut <= Double(seven.bothHeardEndMs) && seven.driftGap * abs(cut - seven.lockMs) >= blendDriftLimitMs * 0.5 - 1e-6, "\(cut)")
    }

    // Curves

    @Test func theGainsKeepEqualPowerAndEndCleanly() {
        for k in [0.0, blendCurve, crossfadeCurve, liftCurve, cutCurve] {
            #expect(near(automixOutGain(0, k), 1, 1e-12))
            #expect(near(automixInGain(0, k), 0, 1e-6))
            #expect(near(automixOutGain(1, k), 0, 1e-12))
            #expect(near(automixInGain(1, k), 1, 1e-12))
            var last = 1.0
            for step in 0...100 {
                let t = Double(step) / 100
                let out = automixOutGain(t, k), into = automixInGain(t, k)
                #expect(near(out * out + into * into, 1, 1e-9))
                #expect(out <= last + 1e-12)
                last = out
            }
        }
        #expect(near(automixOutGain(0.3, 0), Double(fadeOutVolume(0.3)), 1e-6))
        #expect(near(sCurve(0.5), 0.5, 1e-12))
    }

    /// An intro plan over `overlapMs` whose outgoing song starts to fade `fadeMs` before its end and
    /// whose incoming song rises over `riseMs`.
    func introPlan(_ overlapMs: Int, _ fadeMs: Int, _ riseMs: Int) -> TransitionPlan {
        TransitionPlan(startMs: 180_000, entryMs: 0, overlapMs: overlapMs, kind: .intro, k: 0, filterStrength: 0, beatMatchRate: nil,
                       beatMs: nil, beatAnchorMs: nil, late: false, headroomDb: overlapHeadroomDb, reason: "test",
                       holdShare: Double(overlapMs - fadeMs) / Double(overlapMs), riseShare: Double(riseMs) / Double(overlapMs), lateMoveBars: 0)
    }

    @Test func anIntroHoldsTheOutgoingSongThenFadesItOut() {
        let hold = 10_500.0 / 13_500
        for t in [-0.1, 0, 0.3, hold - 1e-9, hold] { #expect(introOutGain(t, hold) == 1, "at \(t)") }
        // An equal-power fade from the hold to the end.
        #expect(near(introOutGain(hold + (1 - hold) / 2, hold), cos(Double.pi / 4), 1e-12))
        #expect(near(introOutGain(hold + (1 - hold) / 4, hold), cos(0.25 * Double.pi / 2), 1e-12))
        #expect(near(introOutGain(1, hold), 0, 1e-12))
        #expect(near(introOutGain(1.2, hold), 0, 1e-12))
        var last = 1.0
        for step in 0...200 {
            let out = introOutGain(Double(step) / 200, hold)
            #expect(out <= last + 1e-12)
            last = out
        }
        // No hold is a whole equal-power fade.
        #expect(near(introOutGain(0.3, 0), automixOutGain(0.3, 0), 1e-12))
    }

    @Test func anIntroRaisesTheIncomingSongQuicklyThenHoldsIt() {
        let rise = 1_000.0 / 13_500
        let hold = 10_500.0 / 13_500
        #expect(introInGain(0, rise, hold, 1) == 0)
        #expect(introInGain(-0.1, rise, hold, 1) == 0)
        #expect(near(introInGain(rise / 2, rise, hold, 1), sin(Double.pi / 4), 1e-12))
        #expect(near(introInGain(rise * 0.2, rise, hold, 1), sin(Double.pi / 2 * 0.2), 1e-12))
        for t in [rise, 0.2, 0.5, 0.9, 1, 1.2] { #expect(introInGain(t, rise, hold, 1) == 1, "at \(t)") }
        // No rise is full at once.
        #expect(introInGain(0, 0, hold, 1) == 1)
    }

    @Test func aLoweredIntroHoldsTheIncomingSongThereThenBringsItUpAsTheOutgoingSongLeaves() {
        let rise = 1_000.0 / 13_500
        let hold = 10_500.0 / 13_500
        let level = 0.5
        #expect(near(introInGain(rise / 2, rise, hold, level), sin(Double.pi / 4) * level, 1e-12))
        for t in [rise, 0.3, hold] { #expect(introInGain(t, rise, hold, level) == level, "at \(t)") }
        // Past the hold the incoming song comes up as the outgoing song's power leaves, to its own
        // level at the end.
        for t in [hold + 0.02, hold + (1 - hold) / 2, 0.95] {
            let out = introOutGain(t, hold)
            let expected = (level * level + (1 - level * level) * (1 - out * out)).squareRoot()
            #expect(near(introInGain(t, rise, hold, level), expected, 1e-12), "at \(t)")
        }
        #expect(near(introInGain(1, rise, hold, level), 1, 1e-12))
        var last = 0.0
        for step in 0...400 {
            let gain = introInGain(Double(step) / 400, rise, hold, level)
            #expect(gain >= last - 1e-12, "at \(step)")
            last = gain
        }
        // At level 1 nothing changes: full from the rise on.
        for t in [rise, 0.5, hold + 0.1, 1] { #expect(introInGain(t, rise, hold, 1) == 1, "at \(t)") }
    }

    @Test func anIntroPlanPlaysWithTheIntroGains() {
        let plan = introPlan(13_500, 3_000, 1_000)
        for step in 0...100 {
            let t = Double(step) / 100
            #expect(plan.outGain(t) == introOutGain(t, plan.holdShare), "out at \(t)")
            #expect(plan.inGain(t) == introInGain(t, plan.riseShare, plan.holdShare, plan.introLevel), "in at \(t)")
        }
        #expect(plan.outGain(0.7) == 1)
        #expect(plan.inGain(0.5) == 1)
        // Other kinds keep their own shares at 0 and their curves.
        var blend = plan
        blend.kind = .blend
        blend.k = blendCurve
        blend.holdShare = 0
        blend.riseShare = 0
        #expect(blend.outGain(0.7) == automixOutGain(0.7, blendCurve))
    }

    @Test func anIntroKeepsTheLevelUpAcrossTheIncomingSongsFullPoint() {
        // Both songs' bodies at one level (0 dB); the incoming song's intro 24 dB under it until its
        // full point, where the outgoing song's fade is halfway. Unlocked: a 12 s quiet stretch, a
        // 3 s fade. Locked at 120 BPM: an 11 s stretch takes 6 bars, a 2-bar fade, a 1-bar rise.
        for (plan, fullAtMs) in [(introPlan(13_500, 3_000, 1_000), 12_000.0), (introPlan(14_000, 4_000, 2_000), 12_000.0)] {
            let fadeMs = (1 - plan.holdShare) * Double(plan.overlapMs)
            #expect(near(Double(plan.overlapMs) - fadeMs / 2, fullAtMs, 1e-6))
            let hopMs = 10
            let hops = plan.overlapMs / hopMs
            let power = (0..<hops).map { i -> Double in
                let ms = Double(i * hopMs) + Double(hopMs) / 2
                let t = ms / Double(plan.overlapMs)
                let incoming = ms < fullAtMs ? pow(10, -24.0 / 10) : 1
                return plan.outGain(t) * plan.outGain(t) + plan.inGain(t) * plan.inGain(t) * incoming
            }
            // Every 400 ms of the mix from the start of the fade on.
            let window = 40
            let from = Int(plan.holdShare * Double(plan.overlapMs)) / hopMs
            for start in from...(hops - window) {
                let db = 10 * log10(power[start..<(start + window)].reduce(0, +) / Double(window))
                #expect(abs(db) <= 3, "\(plan.overlapMs) ms intro at \(start * hopMs) ms: \(db) dB")
            }
        }
    }

    @Test func theFiltersSweepTheirRanges() {
        #expect(near(outgoingLowPassHz(0, filterStrength), 18_000, 1e-9))
        #expect(near(outgoingLowPassHz(1, filterStrength), 18_000 * pow(450.0 / 18_000, 0.7), 1e-6))
        #expect(near(outgoingHighPassHz(0, filterStrength), 10, 1e-9))
        #expect(near(outgoingHighPassHz(0.5, filterStrength), 210, 1e-9))
        #expect(near(outgoingHighPassHz(0.9, filterStrength), 210, 1e-9))
        #expect(near(incomingHighPassHz(0, filterStrength), 472.5, 1e-9))
        #expect(near(incomingHighPassHz(0.6, filterStrength), 20, 1e-9))
        #expect(near(incomingHighPassHz(1, filterStrength), 20, 1e-9))
        #expect(near(outgoingLowPassHz(1, 0), 18_000, 1e-9))
    }

    @Test func theSteppedLowPassHoldsEachBeatThenGlides() {
        let beats = [0.25, 0.5, 0.75]
        let glide = 0.05
        #expect(near(steppedOutgoingLowPassHz(0.1, filterStrength, beats, glide), 18_000, 1e-9))
        #expect(near(steppedOutgoingLowPassHz(0.3, filterStrength, beats, glide), outgoingLowPassHz(0.25, filterStrength), 1e-9))
        #expect(near(steppedOutgoingLowPassHz(0.45, filterStrength, beats, glide), outgoingLowPassHz(0.25, filterStrength), 1e-9))
        #expect(near(steppedOutgoingLowPassHz(0.475, filterStrength, beats, glide), outgoingLowPassHz(0.375, filterStrength), 1e-9))
        #expect(near(steppedOutgoingLowPassHz(0.5, filterStrength, beats, glide), outgoingLowPassHz(0.5, filterStrength), 1e-9))
        #expect(near(steppedOutgoingLowPassHz(1, filterStrength, beats, glide), outgoingLowPassHz(1, filterStrength), 1e-9))
    }

    @Test func aPlanOnTheBeatStepsItsLowPass() {
        let plan = plan("same-tempo")
        let beats = plan.beatProgress()
        #expect(beats.count == 15)
        #expect(near(beats[0], 0.0625, 0.002))
        #expect(near(plan.outgoingLowPassAt(beats[0] + 0.01), plan.outgoingLowPassAt(beats[1] - 0.035), 1e-9))
    }

    @Test func beatMatchEasesBackAfterTheBlend() {
        #expect(near(beatMatchRateAt(1.03, 2_000, 8_000), 1.03, 1e-12))
        #expect(near(beatMatchRateAt(1.03, 8_000, 8_000), 1.03, 1e-12))
        #expect(near(beatMatchRateAt(1.03, 10_500, 8_000), 1.015, 1e-12))
        #expect(near(beatMatchRateAt(1.03, 13_000, 8_000), 1, 1e-12))
        #expect(plan("same-tempo").incomingRateAt(1_000) == 1)
    }

    @Test func foldingTreatsHalfAndDoubleTimeAsTheSame() {
        #expect(near(foldTempoRatio(2), 1, 1e-12))
        #expect(near(foldTempoRatio(0.5), 1, 1e-12))
        #expect(near(foldTempoRatio(1.2), 1.2, 1e-12))
    }
}
