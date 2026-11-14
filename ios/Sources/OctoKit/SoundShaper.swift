import Foundation

/// How long a change of level, balance or curve takes, so it never clicks.
public let glideMs = 30.0

/// Moves the first `written` frames of each buffer to the end of its `frames`, with silence
/// before them.
func shiftToEnd(_ buffers: [UnsafeMutablePointer<Float>], frames: Int, written: Int) {
    guard written < frames else { return }
    let gap = frames - written
    for buffer in buffers {
        if written > 0 { memmove(buffer + gap, buffer, written * MemoryLayout<Float>.stride) }
        buffer.update(repeating: 0, count: gap)
    }
}

extension SoundSettings {
    /// Whether these settings ask for any shaping at all.
    public var shapesSound: Bool {
        eqEnabled || replayGain != .off || limiter || balance != 0 || mono || crossfeed.on
    }
}

/// A value that glides to a new setting over a number of frames.
struct Glide {
    private(set) var value: Float
    private var target: Float
    private var step: Float = 0
    private var left = 0

    init(_ initial: Float) {
        value = initial
        target = initial
    }

    var moving: Bool { left > 0 }

    mutating func to(_ next: Float, frames: Int) {
        target = next
        if frames <= 0 || next == value {
            value = next
            left = 0
        } else {
            step = (next - value) / Float(frames)
            left = frames
        }
    }

    mutating func next() -> Float {
        if left > 0 {
            left -= 1
            value = left == 0 ? target : value + step
        }
        return value
    }
}

/// The sound shaping for one stream. In order: the level (ReplayGain and the equalizer's
/// preamp), the equalizer's filters (headphone correction, then the curve), crossfeed, mono and
/// balance, then the limiter. Samples are floats, one buffer per channel, full scale at 1.
public final class SoundShaper: @unchecked Sendable {
    public let sampleRate: Double
    public let channels: Int
    private let glideFrames: Int
    private var gain = Glide(1)
    private var left = Glide(1)
    private var right = Glide(1)
    private var mono = Glide(0)

    private var bank: FilterBank
    /// The shaping the running filters came from (its bank is `bank`, or carried on into it).
    private var prep: ShapingPrep?
    /// While the curve changes, the old filters keep running and fade out.
    private var fadingFrom: FilterBank?
    private var fadeLeft = 0
    /// Room for the old filters' sound while they fade out, made with the shaper; a longer buffer
    /// is shaped in pieces of this size.
    private var fadeScratch: [[Float]]
    public static let fadeScratchFrames = 8_192

    /// The crossfeed on the first two channels, `CrossfeedCoefficients.none` while it is off.
    private var feed = CrossfeedCoefficients.none
    private var feedFilter = CrossfeedFilter()
    /// While the crossfeed changes, the old one keeps running and fades out.
    private var feedFrom = CrossfeedCoefficients.none
    private var feedFromFilter = CrossfeedFilter()
    private var feedFadeLeft = 0

    private let limiter: Limiter
    private var started = false

    /// How many frames late the sound comes out.
    public var latency: Int { limiter.latency }

    public init(sampleRate: Double, channels: Int) {
        self.sampleRate = sampleRate
        self.channels = channels
        glideFrames = max(1, Int((sampleRate * glideMs / 1_000).rounded()))
        bank = FilterBank(channels: channels, coefficients: [])
        limiter = Limiter(channels: channels, sampleRate: sampleRate)
        fadeScratch = (0..<channels).map { _ in [Float](repeating: 0, count: Self.fadeScratchFrames) }
    }

    /// Whether old filters still fade out under the new ones.
    public var fadingFilters: Bool { fadingFrom != nil }

    /// Takes up new settings or a new song. Changes glide in, unless `instant`, as at the very
    /// start of a song. Works the shaping out on the spot (see `adopt` for the audio thread).
    public func apply(_ settings: SoundSettings, song: SongLoudness?, instant: Bool) {
        adopt(ShapingPrep(settings: settings, sampleRate: sampleRate, channels: channels), song: song, instant: instant)
    }

    /// Takes up a shaping worked out elsewhere, and the song's level. Nothing is made here: the
    /// shaping's own filter bank takes over, carrying on from the running one (and the running one
    /// fades out under it when the curve changed); a bank let go here is still the shaping's, so
    /// whoever handed it over frees it.
    public func adopt(_ next: ShapingPrep, song: SongLoudness?, instant: Bool) {
        let now = instant || !started
        started = true
        let frames = now ? 0 : glideFrames

        if next !== prep {
            let curveChanged = next.bands != (prep?.bands ?? [])
            next.bank.continueFrom(bank)
            fadingFrom = curveChanged && !now ? bank : nil
            fadeLeft = curveChanged && !now ? glideFrames : 0
            bank = next.bank
            prep = next
        }

        let level = Float(replayGainFactor(next.settings, song)) * next.preamp
        gain.to(level, frames: frames)

        let balance = next.balance
        left.to(balance > 0 ? 1 - balance : 1, frames: frames)
        right.to(balance < 0 ? 1 + balance : 1, frames: frames)
        let toMono = next.toMono
        mono.to(toMono ? 1 : 0, frames: frames)

        let nextFeed = next.feed
        let feeds = nextFeed != .none
        let bands = next.bands
        if nextFeed != feed {
            // Switched on from off, the filter starts from silence; otherwise it carries on.
            if feed == .none && feedFadeLeft == 0 { feedFilter.clear() }
            feedFrom = feed
            feedFromFilter = feedFilter
            feedFadeLeft = now ? 0 : glideFrames
            feed = nextFeed
        }

        // Sound that is left as it is passes through untouched; the limiter only catches peaks
        // the shaping could push over.
        let untouched = bands.isEmpty && level == 1 && balance == 0 && !toMono && !feeds
        limiter.enabled = next.settings.limiter && !untouched
    }

    /// Shapes `frames` frames of `input` (changed on the way) and writes what comes out of the
    /// limiter to `output`, which may be the same buffers. Returns the frames written: fewer
    /// than went in only while the limiter's delay fills at the start of a stream.
    public func process(_ input: [UnsafeMutablePointer<Float>], frames: Int, output: [UnsafeMutablePointer<Float>]) -> Int {
        guard input.count == channels, output.count == channels, frames > 0 else { return 0 }
        applyGain(input, frames)
        applyFilters(input, frames)
        applyCrossfeed(input, frames)
        applyMix(input, frames)
        return limiter.process(input, frames: frames, output: output)
    }

    /// Shapes `frames` frames in place, one buffer per channel, the same number out as in. The
    /// sound comes out the limiter's latency late, after silence at the start of a stream.
    public func process(_ buffers: [UnsafeMutablePointer<Float>], frames: Int) {
        guard buffers.count == channels, frames > 0 else { return }
        let written = process(buffers, frames: frames, output: buffers)
        shiftToEnd(buffers, frames: frames, written: written)
    }

    /// Hands out the sound still held back, at the end of a stream. `output` needs room for
    /// `latency` frames.
    public func drain(_ output: [UnsafeMutablePointer<Float>]) -> Int { limiter.drain(output) }

    /// Forgets the sound held back, after a jump to another place.
    public func clearHeld() { limiter.clear() }

    private func applyGain(_ buffers: [UnsafeMutablePointer<Float>], _ frames: Int) {
        if !gain.moving && gain.value == 1 { return }
        for i in 0..<frames {
            let g = gain.next()
            for ch in 0..<channels { buffers[ch][i] *= g }
        }
    }

    private func applyFilters(_ buffers: [UnsafeMutablePointer<Float>], _ frames: Int) {
        guard let old = fadingFrom else {
            for ch in 0..<channels { bank.process(buffers[ch], frames: frames, channel: ch) }
            return
        }
        var done = 0
        while done < frames {
            let n = min(frames - done, Self.fadeScratchFrames)
            for ch in 0..<channels {
                fadeScratch[ch].withUnsafeMutableBufferPointer { scratch in
                    scratch.baseAddress!.update(from: buffers[ch] + done, count: n)
                    old.process(scratch.baseAddress!, frames: n, channel: ch)
                }
                bank.process(buffers[ch] + done, frames: n, channel: ch)
            }
            for i in 0..<n {
                let w: Float = fadeLeft > 0 ? 1 - Float(fadeLeft) / Float(glideFrames) : 1
                if fadeLeft > 0 { fadeLeft -= 1 }
                for ch in 0..<channels {
                    let faded = fadeScratch[ch][i]
                    buffers[ch][done + i] = faded + (buffers[ch][done + i] - faded) * w
                }
            }
            done += n
        }
        if fadeLeft == 0 { fadingFrom = nil }
    }

    /// Runs the crossfeed on the first two channels. While it changes, the old and the new
    /// settings both run and the sound moves from one to the other over the glide.
    private func applyCrossfeed(_ buffers: [UnsafeMutablePointer<Float>], _ frames: Int) {
        guard channels >= 2 else { return }
        let left = buffers[0], right = buffers[1]
        if feedFadeLeft == 0 {
            if feed != .none { feedFilter.process(left, right, frames: frames, feed) }
            return
        }
        for i in 0..<frames {
            let l = Double(left[i]), r = Double(right[i])
            let new = feedFilter.process(l, r, feed)
            let old = feedFromFilter.process(l, r, feedFrom)
            let w = feedFadeLeft > 0 ? 1 - Double(feedFadeLeft) / Double(glideFrames) : 1
            if feedFadeLeft > 0 { feedFadeLeft -= 1 }
            left[i] = Float(old.left + (new.left - old.left) * w)
            right[i] = Float(old.right + (new.right - old.right) * w)
        }
        feedFilter.flushDenormals()
        feedFromFilter.flushDenormals()
    }

    private func applyMix(_ buffers: [UnsafeMutablePointer<Float>], _ frames: Int) {
        guard channels >= 2 else { return }
        let still = !mono.moving && !left.moving && !right.moving
        if still && mono.value == 0 && left.value == 1 && right.value == 1 { return }
        for i in 0..<frames {
            let m = mono.next()
            if m != 0 {
                var sum: Float = 0
                for ch in 0..<channels { sum += buffers[ch][i] }
                let average = sum / Float(channels)
                for ch in 0..<channels { buffers[ch][i] += (average - buffers[ch][i]) * m }
            }
            buffers[0][i] *= left.next()
            buffers[1][i] *= right.next()
        }
    }
}

/// Where the sound is going: the phone's speaker, a cable, or one Bluetooth, USB, AirPlay or
/// car device, each able to keep its own sound settings. `formerKeys` are keys the same output
/// was saved under before, read when nothing is saved under `key` yet.
public struct AudioOutput: Sendable, Equatable {
    public var key: String
    public var label: String
    public var formerKeys: [String] = []

    public init(key: String, label: String, formerKeys: [String] = []) {
        self.key = key
        self.label = label
        self.formerKeys = formerKeys
    }

    /// Every key this output's settings may be saved under, the current one first.
    public var keys: [String] { [key] + formerKeys }

    /// Whether this output can be headphones, where crossfeed belongs: not the phone's speaker,
    /// a car or an AirPlay device.
    public var takesCrossfeed: Bool {
        !(key == Self.phoneSpeaker.key || key.hasPrefix("airplay:") || key.hasPrefix("car:"))
    }

    public static let phoneSpeaker = AudioOutput(key: "speaker", label: "iPhone speaker")
    public static let bluetoothPrefix = "bluetooth:"
    /// The key of the one set every output shares while outputs do not keep their own.
    public static let shared = "all"
}

/// One output device as the system lists it.
public struct OutputDevice: Sendable, Equatable {
    public enum Kind: Int, Sendable { case bluetooth = 0, airPlay = 1, car = 2, usb = 3, wired = 4, other = 9 }

    public var kind: Kind
    public var name: String
    public var address: String

    public init(kind: Kind, name: String, address: String) {
        self.kind = kind
        self.name = name
        self.address = address
    }
}

public enum SoundOutputs {
    /// The output music goes to: a Bluetooth, AirPlay, car or USB device or a cable when one is
    /// in use, otherwise the speaker. A pair of earbuds can be listed more than once, so a
    /// Bluetooth output is known by its name; its addresses are where its settings were saved
    /// before.
    public static func outputFor(_ devices: [OutputDevice]) -> AudioOutput {
        guard let rank = devices.map(\.kind.rawValue).min(), let kind = OutputDevice.Kind(rawValue: rank), kind != .other else {
            return .phoneSpeaker
        }
        let listed = devices.filter { $0.kind == kind }.sorted {
            if $0.name.isEmpty != $1.name.isEmpty { return !$0.name.isEmpty }
            if $0.name != $1.name { return $0.name < $1.name }
            return $0.address < $1.address
        }
        let name = listed[0].name
        switch kind {
        case .bluetooth:
            let same = listed.filter { $0.name == name }
            var addresses: [String] = []
            for address in same.map(\.address) where !address.isEmpty && !addresses.contains(address) { addresses.append(address) }
            let key = AudioOutput.bluetoothPrefix + (name.isEmpty ? (addresses.first ?? "") : name)
            let former = name.isEmpty ? Array(addresses.dropFirst()) : addresses
            return AudioOutput(key: key, label: name.isEmpty ? "Bluetooth" : name, formerKeys: former.map { AudioOutput.bluetoothPrefix + $0 })
        case .airPlay:
            return AudioOutput(key: "airplay:\(name)", label: name.isEmpty ? "AirPlay" : name)
        case .car:
            return AudioOutput(key: "car:\(name)", label: name.isEmpty ? "CarPlay" : name)
        case .usb:
            return AudioOutput(key: "usb:\(name)", label: name.isEmpty ? "USB audio" : name)
        case .wired, .other:
            return AudioOutput(key: "wired", label: "Headphones")
        }
    }

    /// The settings saved for an output: under its key, or else under a key it had before.
    public static func savedFor<T>(_ saved: [String: T], _ output: AudioOutput) -> T? {
        for key in output.keys { if let found = saved[key] { return found } }
        return nil
    }

    /// The settings that play now: the output's own while outputs keep their own, else the
    /// shared set, else the defaults.
    public static func current(_ saved: [String: SoundSettings], perOutput: Bool, output: AudioOutput) -> SoundSettings {
        (perOutput ? savedFor(saved, output) : nil) ?? saved[AudioOutput.shared] ?? SoundSettings()
    }

    /// The settings the audio path follows on an output: as saved, with crossfeed left off where
    /// there are no headphones.
    public static func playing(_ settings: SoundSettings, on output: AudioOutput) -> SoundSettings {
        guard settings.crossfeed.on, !output.takesCrossfeed else { return settings }
        var held = settings
        held.crossfeed.on = false
        return held
    }

    /// The saved sets after a change to the output playing now. With each output keeping its
    /// own, the change is saved under the output's current key and nowhere else.
    public static func saving(_ change: (SoundSettings) -> SoundSettings, to saved: [String: SoundSettings],
                              perOutput: Bool, output: AudioOutput) -> [String: SoundSettings] {
        let before = current(saved, perOutput: perOutput, output: output)
        var kept = saved
        if perOutput { for key in output.formerKeys { kept[key] = nil } }
        kept[perOutput ? output.key : AudioOutput.shared] = change(before)
        return kept
    }
}
