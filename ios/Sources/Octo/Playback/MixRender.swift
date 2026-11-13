import AVFoundation
import OctoKit

/// The mix limiter unit's render side: the phone mix's cycle around the pull (OctoKit's
/// `PhoneMix`), then the limiter on the sum.
final class MixLimiterRender: EffectRenderer, @unchecked Sendable {
    let mix: PhoneMix
    /// The sum's channel buffers of the cycle, in a fixed table so a cycle allocates nothing.
    private let channels: UnsafeMutablePointer<UnsafeMutablePointer<Float>>
    private static let slots = 16

    init(_ mix: PhoneMix) {
        self.mix = mix
        channels = .allocate(capacity: Self.slots)
        channels.initialize(repeating: UnsafeMutablePointer<Float>(bitPattern: 16)!, count: Self.slots)
    }

    deinit { channels.deallocate() }

    func prepare(sampleRate: Double, channels: Int) { mix.prepare(sampleRate: sampleRate, channels: channels) }
    func willPull(frames: Int) { mix.willPull(frames: frames) }

    func process(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        var count = 0
        for i in 0..<min(buffers.count, Self.slots) {
            guard let data = buffers[i].mData else { break }
            channels[i] = data.assumingMemoryBound(to: Float.self)
            count += 1
        }
        mix.process(channels, channels: count, frames: frames)
    }
}

/// A deck's level unit's render side: OctoKit's `DeckLevel`, the session's leave and fade ramps on
/// bus frames.
final class DeckLevelRender: EffectRenderer, @unchecked Sendable {
    let level: DeckLevel

    init(_ deck: DeckRender) { level = DeckLevel(deck) }

    func prepare(sampleRate: Double, channels: Int) {}
    func willPull(frames: Int) {}

    func process(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        level.process(frames: frames, count: buffers.count) { i in buffers[i].mData?.assumingMemoryBound(to: Float.self) }
    }
}
