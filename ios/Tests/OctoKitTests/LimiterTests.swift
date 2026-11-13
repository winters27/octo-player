import Foundation
import Testing
@testable import OctoKit

/// Runs one or two channels' buffers through a limiter and returns what came out.
func limitChannels(_ limiter: Limiter, _ input: [[Float]]) -> [[Float]] {
    let frames = input[0].count
    let stereo = input.count > 1
    var left = input[0]
    var right = stereo ? input[1] : [0]
    var outLeft = [Float](repeating: 0, count: frames)
    var outRight = [Float](repeating: 0, count: stereo ? frames : 1)
    let written = left.withUnsafeMutableBufferPointer { inL in
        outLeft.withUnsafeMutableBufferPointer { outL in
            right.withUnsafeMutableBufferPointer { inR in
                outRight.withUnsafeMutableBufferPointer { outR in
                    stereo
                        ? limiter.process([inL.baseAddress!, inR.baseAddress!], frames: frames, output: [outL.baseAddress!, outR.baseAddress!])
                        : limiter.process([inL.baseAddress!], frames: frames, output: [outL.baseAddress!])
                }
            }
        }
    }
    return stereo ? [Array(outLeft.prefix(written)), Array(outRight.prefix(written))] : [Array(outLeft.prefix(written))]
}

/// The limiter's level comes all the way back to unity after a peak, so a song that needs no
/// limiting afterwards passes bit for bit.
@Suite struct LimiterUnityTests {
    let rate = 48_000.0

    /// A burst at twice full scale, which turns the level down to about 0.45.
    func burst(_ limiter: Limiter) {
        _ = limitChannels(limiter, [(0..<4_800).map { $0 % 2 == 0 ? 2 : -2 }])
    }

    func quiet(_ limiter: Limiter, _ frames: Int) {
        _ = limitChannels(limiter, [[Float](repeating: 0, count: frames)])
    }

    /// A sine under the ceiling comes out exactly as it went in, one delay later.
    func passesExactly(_ limiter: Limiter) -> Bool {
        let song = (0..<9_600).map { Float(0.85 * sin(2 * Double.pi * 1_000 * Double($0) / rate)) }
        let out = limitChannels(limiter, [song])[0]
        return Array(out.dropFirst(limiter.latency)) == Array(song.prefix(out.count - limiter.latency))
    }

    @Test func aSongAfterLimitingWithTheLimiterOffPassesBitForBit() {
        let limiter = Limiter(channels: 1, sampleRate: rate)
        burst(limiter)
        // Switched off, as the mix limiter is once a blend's window ends.
        limiter.enabled = false
        quiet(limiter, Int(rate) * 2)
        #expect(passesExactly(limiter))
    }

    @Test func aSongAfterLimitingWithTheLimiterOnPassesBitForBit() {
        let limiter = Limiter(channels: 1, sampleRate: rate)
        burst(limiter)
        quiet(limiter, Int(rate) * 2)
        #expect(passesExactly(limiter))
    }

    @Test func theLevelLandsOnUnityAt44100Too() {
        let limiter = Limiter(channels: 2, sampleRate: 44_100)
        let loud: [Float] = (0..<4_410).map { $0 % 2 == 0 ? 3 : -3 }
        _ = limitChannels(limiter, [loud, loud])
        limiter.enabled = false
        let silence = [Float](repeating: 0, count: 44_100 * 2)
        _ = limitChannels(limiter, [silence, silence])
        let song = (0..<4_410).map { Float(0.99 * sin(2 * Double.pi * 440 * Double($0) / 44_100)) }
        let out = limitChannels(limiter, [song, song])
        for channel in out {
            #expect(Array(channel.dropFirst(limiter.latency)) == Array(song.prefix(channel.count - limiter.latency)))
        }
    }
}
