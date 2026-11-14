import Foundation

/// The limiter on the sum of both decks, after the phone's mixer: the shared `Limiter` at
/// -1 dBFS with its attack ramped across the look-ahead and the sound between samples read (its
/// true peak). It is always in the path, with its fixed delay, so turning it on or off never skips
/// or repeats sound; it limits only inside a blend's window (from the first frame that can carry
/// the incoming song to the blend's end and its last ramp), whatever the Limiter setting, and
/// passes at unity outside it, its level coming back by a need of 1 and its release. Runs on the
/// render thread at the hardware rate: nothing allocated after `init`.
public final class MixLimiter: @unchecked Sendable {
    public let sampleRate: Double
    public let channels: Int
    private let limiter: Limiter
    /// The window, in bus frames of the sum's input: on from `onAt`, off again from `offAt`.
    public private(set) var onAt = Int64.max
    public private(set) var offAt = Int64.max
    /// Hardware frames passed since this limiter was built.
    public private(set) var framesPassed: Int64 = 0

    /// Each channel's place in the frames of one call, kept so a call allocates nothing.
    private var parts: [UnsafeMutablePointer<Float>]

    public init(sampleRate: Double, channels: Int) {
        self.sampleRate = sampleRate
        self.channels = max(1, channels)
        limiter = Limiter(channels: self.channels, sampleRate: sampleRate, rampedAttack: true, truePeak: true)
        parts = []
        limiter.enabled = false
        // Primed with silence, so every call hands out as many frames as it takes.
        let zeros = (0..<self.channels).map { _ in UnsafeMutablePointer<Float>.allocate(capacity: limiter.latency) }
        for z in zeros { z.initialize(repeating: 0, count: limiter.latency) }
        _ = limiter.process(zeros, frames: limiter.latency, output: zeros)
        parts = zeros
        for z in zeros { z.deallocate() }
    }

    /// The delay, in hardware frames.
    public var latencyFrames: Int { limiter.latency }

    /// The level on the last frame out.
    public var level: Float { limiter.level }

    public var enabled: Bool { limiter.enabled }

    /// The window opens at bus frame `bus` (the session's start).
    public func windowOn(fromBus bus: Int64) {
        onAt = bus
        offAt = Int64.max
    }

    /// The window closes at bus frame `bus` (the blend's end plus its last ramp).
    public func windowOff(fromBus bus: Int64) { offAt = bus }

    /// Whether the sum is limited at bus frame `bus`.
    public func limits(atBus bus: Double) -> Bool { bus >= Double(onAt) && bus < Double(offAt) }

    /// Limits `frames` hardware frames in place, one buffer per channel. The first is at bus frame
    /// `firstBus` and each lasts `busPerFrame` bus frames.
    public func process(_ buffers: [UnsafeMutablePointer<Float>], frames: Int, firstBus: Double, busPerFrame: Double) {
        guard frames > 0, buffers.count == channels else { return }
        var done = 0
        while done < frames {
            let on = limits(atBus: firstBus + Double(done) * busPerFrame)
            var run = 1
            while done + run < frames && limits(atBus: firstBus + Double(done + run) * busPerFrame) == on { run += 1 }
            limiter.enabled = on
            for ch in 0..<channels { parts[ch] = buffers[ch] + done }
            _ = limiter.process(parts, frames: run, output: parts)
            done += run
        }
        framesPassed += Int64(frames)
    }
}

/// A deck's level after its time-pitch stage, on bus frames: the session's leave and fade ramps
/// (the outgoing song leaving, an incoming song's rendered frames faded out after an undo) land on
/// the exact frame they name, never a time-pitch latency late. Render thread only.
public final class DeckGainRamp: @unchecked Sendable {
    private var gain: Float = 1
    private var from: Float = 1
    private var rampAt = Int64.max
    private var rampFrames: Int64 = 0

    public init() {}

    /// The level on the last frame out.
    public var level: Float { gain }

    /// Silent from now on.
    public var silent: Bool { gain == 0 && rampAt == Int64.max }

    /// Leaves from bus frame `bus`, down to silence in a straight line over `frames` (at once for 0).
    public func leave(atBus bus: Int64, frames: Int64) {
        if rampAt != Int64.max && bus >= rampAt { return }
        rampAt = bus
        rampFrames = max(0, frames)
        from = gain
    }

    /// The deck's sound is heard `frames` bus frames later from now on (an engine restart's
    /// replay): a leave placed ahead moves with it.
    public func shift(byBus frames: Int64) {
        if rampAt != Int64.max && frames > 0 { rampAt += frames }
    }

    /// Back to the deck's own level at once: the deck plays as itself again.
    public func open() {
        gain = 1
        from = 1
        rampAt = Int64.max
        rampFrames = 0
    }

    /// Applies the level to `frames` frames of `count` channel buffers in place, the first at bus
    /// frame `firstBus`.
    public func process(_ buffers: UnsafeMutablePointer<UnsafeMutablePointer<Float>>, count: Int, frames: Int, firstBus: Int64) {
        if gain == 1 && (rampAt == Int64.max || rampAt >= firstBus + Int64(frames)) { return }
        for i in 0..<frames {
            let bus = firstBus + Int64(i)
            if rampAt != Int64.max && bus >= rampAt {
                let into = bus - rampAt
                if into >= rampFrames {
                    gain = 0
                    rampAt = Int64.max
                } else {
                    gain = from * (1 - Float(into + 1) / Float(rampFrames))
                }
            }
            for ch in 0..<count { buffers[ch][i] *= gain }
        }
    }
}
