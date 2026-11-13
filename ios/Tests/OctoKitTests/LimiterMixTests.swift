import Foundation
import Testing
@testable import OctoKit

/// The limiter as the mix of two songs uses it: a ramped attack, snapshots for running a stretch
/// again, and a reset to unity.
@Suite struct LimiterMixTests {
    let rate = 48_000.0
    var ceiling: Float { Limiter(channels: 1, sampleRate: rate).ceilingLevel }

    /// Quiet, then stereo noise at twice full scale (a fixed generator, so every run is the same).
    var loud: [[Float]] {
        var seed: UInt64 = 7
        func noise() -> Float {
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Float(seed >> 40) / Float(1 << 24) * 2 - 1
        }
        var left: [Float] = []
        var right: [Float] = []
        for frame in 0..<9_600 {
            if frame < 4_800 {
                let quiet = Float(0.3 * sin(2 * Double.pi * 440 * Double(frame) / rate))
                left.append(quiet)
                right.append(quiet)
            } else {
                left.append(2 * noise())
                right.append(2 * noise())
            }
        }
        return [left, right]
    }

    /// One frame at a time, keeping the level after each, then the drain.
    func runFrames(_ limiter: Limiter, _ input: [[Float]], offAt: Int = -1) -> (levels: [Float], out: [Float]) {
        var levels: [Float] = []
        var out: [Float] = []
        for frame in 0..<input[0].count {
            if frame == offAt { limiter.enabled = false }
            let got = limitChannels(limiter, [[input[0][frame]], [input[1][frame]]])
            out += got[0] + got[1]
            levels.append(limiter.level)
        }
        var tailLeft = [Float](repeating: 0, count: limiter.latency)
        var tailRight = [Float](repeating: 0, count: limiter.latency)
        let drained = tailLeft.withUnsafeMutableBufferPointer { l in
            tailRight.withUnsafeMutableBufferPointer { r in limiter.drain([l.baseAddress!, r.baseAddress!]) }
        }
        out += Array(tailLeft.prefix(drained)) + Array(tailRight.prefix(drained))
        return (levels, out)
    }

    func largestDrop(_ levels: [Float]) -> Float {
        zip(levels, levels.dropFirst()).map { $0 - $1 }.max() ?? 0
    }

    @Test func theRampedAttackNeverStepsTheLevelAndHoldsTheCeiling() {
        let ramped = runFrames(Limiter(channels: 2, sampleRate: rate, rampedAttack: true), loud)
        let instant = runFrames(Limiter(channels: 2, sampleRate: rate), loud)
        // The deepest need is the ceiling over twice full scale; its straight line down across
        // the lookahead falls by this much a frame at most.
        let slope = (1 - ceiling / 2) / Float(Limiter(channels: 2, sampleRate: rate).latency)
        #expect(largestDrop(ramped.levels) <= slope + 1e-6)
        #expect(largestDrop(instant.levels) > 0.3, "the instant attack does step")
        #expect(ramped.out.allSatisfy { abs($0) <= ceiling })
        #expect(ramped.out.map(abs).max()! > ceiling * 0.95, "held near the ceiling")
    }

    @Test func turningOffMidReductionNeverStepsTheLevel() {
        let limiter = Limiter(channels: 2, sampleRate: rate, rampedAttack: true)
        let levels = runFrames(limiter, loud, offAt: 7_000).levels
        #expect(levels[6_999] < 0.9)
        let rise = zip(levels.dropFirst(7_000), levels.dropFirst(7_001)).map { $1 - $0 }.max() ?? 0
        #expect(rise < 1e-3)
        #expect(largestDrop(levels) <= (1 - ceiling / 2) / Float(limiter.latency) + 1e-6)
    }

    @Test func aCopyRunsOnExactlyAsTheOriginal() {
        for ramped in [false, true] {
            let original = Limiter(channels: 2, sampleRate: rate, rampedAttack: ramped)
            let input = loud
            _ = limitChannels(original, input.map { Array($0.prefix(6_000)) })
            let copy = Limiter(channels: 2, sampleRate: rate, rampedAttack: ramped)
            copy.copyFrom(original)
            let rest = input.map { Array($0.dropFirst(6_000)) }
            #expect(limitChannels(original, rest) == limitChannels(copy, rest))
            #expect(original.level == copy.level)
        }
    }

    @Test func aResetStartsOverAtUnity() {
        let limiter = Limiter(channels: 2, sampleRate: rate, rampedAttack: true)
        _ = limitChannels(limiter, loud)
        #expect(limiter.level < 0.9)
        limiter.reset()
        #expect(limiter.level == 1)
        let song = (0..<4_800).map { Float(0.85 * sin(2 * Double.pi * 1_000 * Double($0) / rate)) }
        let out = limitChannels(limiter, [song, song])
        #expect(out[0].count == 4_800 - limiter.latency)
        // The delay fills from the song's own first frames, then the song comes out untouched.
        #expect(out[0] == Array(song.prefix(out[0].count)))
        #expect(out[1] == Array(song.prefix(out[1].count)))
    }
}
