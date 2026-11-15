import Foundation
import Testing
@testable import OctoKit

/// The phone's mix per render cycle through an Automix blend: both decks, Octo's shaping with the
/// filter sweeps, the equalizer, ReplayGain and the limiter. Timed in the thread's own CPU time,
/// so other work on the machine does not count.
@Suite struct PhoneMixTimingTests {
    /// One render cycle's length: 1,024 frames at 48 kHz, in microseconds.
    static let cycleMicros = Double(MixRig.frames) / 48.0 * 1_000

    static func threadMicros() -> Double {
        var t = timespec()
        clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t)
        return Double(t.tv_sec) * 1e6 + Double(t.tv_nsec) / 1e3
    }

    @Test func aBlendThroughThePhonesMixStaysWellInsideItsRenderCycle() {
        let rig = MixRig(a: 2_400, 1, b: 4_320, 1, shaped: true)
        rig.shape(TransitionPlan(startMs: 10_000, entryMs: 2_000, overlapMs: 8_000, kind: .blend, k: 0.5, filterStrength: filterStrength,
                                 beatMatchRate: nil, beatMs: nil, beatAnchorMs: nil, late: false, headroomDb: 1, reason: "timing",
                                 curve: nil, holdShare: 0, riseShare: 0, latestEndMs: 22_000, lateMoveBars: lateMoveUnbounded))
        rig.arm()
        var inBlend: [Double] = []
        for _ in 0..<700 {
            let start = Self.threadMicros()
            rig.cycle()
            let took = Self.threadMicros() - start
            if rig.handedOver && rig.finishedAt < 0 { inBlend.append(took) }
        }
        #expect(inBlend.count > 300)
        inBlend.sort()
        let median = inBlend.isEmpty ? 0 : inBlend[inBlend.count / 2]
        // The bound sits between the optimized mix (about 13% of the cycle on a desktop CPU) and
        // the unoptimized one (about 80%), which overruns an iPhone's output.
        #expect(median < Self.cycleMicros * 0.35, "median \(Int(median)) us of a \(Int(Self.cycleMicros)) us cycle")
    }
}
