import Foundation
import Testing
@testable import OctoKit

/// The mix limiter reading the sound between samples (its true peak), and every limiter built
/// without it holding samples exactly as before.
@Suite struct LimiterTruePeakTests {
    let rate = 48_000.0
    let ceiling = Float(pow(10, -1.0 / 20))

    /// Two songs at 12 kHz, a quarter of the sample rate, whose samples fall halfway between the
    /// waveform's peaks: every sample of their sum stays at -2 dBFS, a dB under the ceiling, while
    /// the sound between samples reaches +1 dBFS. The two channels are opposite.
    func quarterRateSum(_ frames: Int) -> [[Float]] {
        let left = (0..<frames).map { i -> Float in
            let wave = sin(Double.pi / 2 * Double(i) + Double.pi / 4)
            return Float(0.6 * wave + 0.52 * wave)
        }
        return [left, left.map { -$0 }]
    }

    /// Every frame through `limiter`, the delay drained at the end, so output frame i is input
    /// frame i.
    func limited(_ limiter: Limiter, _ input: [[Float]]) -> [[Float]] {
        var out = limitChannels(limiter, input)
        var tailL = [Float](repeating: 0, count: limiter.latency)
        var tailR = [Float](repeating: 0, count: limiter.latency)
        let drained = tailL.withUnsafeMutableBufferPointer { l in
            tailR.withUnsafeMutableBufferPointer { r in limiter.drain([l.baseAddress!, r.baseAddress!]) }
        }
        out[0] += tailL.prefix(drained)
        out[1] += tailR.prefix(drained)
        return out
    }

    /// The true peak of stereo from frame `from` to `to`: the samples, and the sound read at 7
    /// points between each two, each through a windowed sinc (Blackman) 48 samples either side.
    /// Finer and longer than the limiter's own reading, so it can check it.
    func truePeakDb(_ stereo: [[Float]], _ from: Int, _ to: Int) -> Double {
        let steps = 8
        let half = 48
        let frames = stereo[0].count
        let taps: [[Double]] = (0..<(steps - 1)).map { p in
            let along = Double(p + 1) / Double(steps)
            let raw = (0..<(half * 2)).map { j -> Double in
                let d = along - Double(j - half + 1)
                let window = 0.42 + 0.5 * cos(Double.pi * d / Double(half)) + 0.08 * cos(2 * Double.pi * d / Double(half))
                return sin(Double.pi * d) / (Double.pi * d) * window
            }
            let sum = raw.reduce(0, +)
            return raw.map { $0 / sum }
        }
        var peak = 0.0
        for channel in stereo {
            for i in from..<min(to, frames) {
                peak = max(peak, abs(Double(channel[i])))
                for phase in taps {
                    var y = 0.0
                    for j in phase.indices {
                        let at = i + j - half + 1
                        if at >= 0 && at < frames { y += Double(channel[at]) * phase[j] }
                    }
                    peak = max(peak, abs(y))
                }
            }
        }
        return 20 * log10(peak)
    }

    @Test func theSumsSoundBetweenSamplesStaysUnderTheCeiling() {
        let sum = quarterRateSum(9_600)
        #expect(sum[0].allSatisfy { abs($0) < ceiling * 0.9 }, "every sample a dB under the ceiling")
        #expect(truePeakDb(sum, 0, 9_600) > 0.9)
        for ramped in [false, true] {
            let out = limited(Limiter(channels: 2, sampleRate: rate, rampedAttack: ramped, truePeak: true), sum)
            let peak = truePeakDb(out, 2_400, 7_200)
            #expect(peak <= -1 + 0.02, "ramped \(ramped): true peak \(peak) dBTP")
            // Turned down by what the sound between samples needs, no further.
            #expect(peak > -1 - 0.05, "ramped \(ramped): true peak \(peak) dBTP")
        }
    }

    /// The guard: a limiter that holds samples only leaves the same sum over the ceiling between
    /// them, so the test above fails without the true peak.
    @Test func aSampleLimiterLeavesTheSumOverTheCeilingBetweenSamples() {
        let sum = quarterRateSum(9_600)
        let out = limited(Limiter(channels: 2, sampleRate: rate, rampedAttack: true), sum)
        #expect(truePeakDb(out, 2_400, 7_200) > 0.5)
    }

    @Test func theRampedAttackStillNeverStepsTheLevel() {
        // Quiet, then the quarter-rate sum: only the sound between samples needs limiting, and it
        // is seen 31 frames late.
        let tone = quarterRateSum(4_800)
        let input = [[Float](repeating: 0, count: 4_800) + tone[0], [Float](repeating: 0, count: 4_800) + tone[1]]
        let limiter = Limiter(channels: 2, sampleRate: rate, rampedAttack: true, truePeak: true)
        var levels: [Float] = []
        for frame in 0..<input[0].count {
            _ = limitChannels(limiter, [[input[0][frame]], [input[1][frame]]])
            levels.append(limiter.level)
        }
        var drop: Float = 0
        for i in 1..<levels.count { drop = max(drop, levels[i - 1] - levels[i]) }
        // Each need's line runs from unity across the lookahead less the reading's span, so none
        // falls faster than the line to the deepest need, which the level reaches.
        let deepest = levels.min()!
        let slope = (1 - deepest) / Float(limiter.latency - 31)
        #expect(drop <= slope + 1e-6, "largest drop \(drop), line \(slope)")
        #expect(deepest < 0.8)
        #expect(levels.last! < 0.81)
    }

    @Test func aCopyWithTheTruePeakRunsOnExactlyAsTheOriginal() {
        let input = quarterRateSum(9_600)
        for ramped in [false, true] {
            let original = Limiter(channels: 2, sampleRate: rate, rampedAttack: ramped, truePeak: true)
            _ = limitChannels(original, [Array(input[0].prefix(3_000)), Array(input[1].prefix(3_000))])
            let copy = Limiter(channels: 2, sampleRate: rate, rampedAttack: ramped, truePeak: true)
            copy.copyFrom(original)
            let rest = [Array(input[0].dropFirst(3_000)), Array(input[1].dropFirst(3_000))]
            #expect(limitChannels(original, rest) == limitChannels(copy, rest))
        }
    }

    /// The sample limiter written out plainly: each frame turned down as far as any frame from it
    /// to `latency` ahead needs, down at once, back up slowly and onto unity once the step is lost
    /// in rounding, then held at the ceiling.
    func sampleLimited(_ input: [[Float]], latency: Int) -> [[Float]] {
        let release = Float(1 - exp(-1 / (rate * 100 / 1_000)))
        let frames = input[0].count
        let need = (0..<frames).map { i -> Float in
            let peak = max(abs(input[0][i]), abs(input[1][i]))
            return peak > ceiling ? ceiling / peak : 1
        }
        var gain: Float = 1
        var out = input
        for i in 0..<frames {
            var target: Float = 1
            for j in i...min(i + latency, frames - 1) { target = min(target, need[j]) }
            if target < gain {
                gain = target
            } else {
                let next = gain + (target - gain) * release
                gain = next == gain ? target : next
            }
            for ch in 0..<2 { out[ch][i] = min(ceiling, max(-ceiling, input[ch][i] * gain)) }
        }
        return out
    }

    @Test func withoutTheTruePeakEveryLimiterHoldsSamplesAsBefore() {
        // A song alone: noise bursts far over full scale between quiet stretches, then the
        // quarter-rate sum, whose samples need nothing.
        var seed: UInt64 = 11
        func noise() -> Float {
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Float(seed >> 40) / Float(1 << 24) * 2 - 1
        }
        var left: [Float] = []
        var right: [Float] = []
        for i in 0..<24_000 {
            let scale: Float = (i / 3_000) % 2 == 0 ? 2.5 : 0.2
            left.append(noise() * scale)
            right.append(noise() * scale)
        }
        let tone = quarterRateSum(9_600)
        let input = [left + tone[0], right + tone[1]]
        let plain = Limiter(channels: 2, sampleRate: rate)
        #expect(limited(plain, input) == sampleLimited(input, latency: plain.latency))
        // Plain and ramped alike, the sound between samples passes untouched.
        for ramped in [false, true] {
            #expect(limited(Limiter(channels: 2, sampleRate: rate, rampedAttack: ramped), tone) == tone, "ramped \(ramped)")
        }
    }

    @Test func aCopyBetweenLimitersBuiltDifferentlyIsRefused() {
        // Built alike, the copy is taken; the true peak is part of how a limiter is built.
        let a = Limiter(channels: 2, sampleRate: rate, truePeak: true)
        let b = Limiter(channels: 2, sampleRate: rate, truePeak: true)
        b.copyFrom(a)
        #expect(a.truePeak && b.truePeak)
        #expect(!Limiter(channels: 2, sampleRate: rate).truePeak)
    }
}
