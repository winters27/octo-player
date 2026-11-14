import Foundation
import Synchronization

/// The sound settings the audio path follows, swapped in whole. Each change bumps `version`,
/// so a stream can tell a change by comparing one number, and has every step following the box
/// work out its shaping for the new settings off the audio thread. `version` and `shapes` are read
/// without a lock.
public final class SoundSettingsBox: @unchecked Sendable {
    private let lock = NSLock()
    private var settings: SoundSettings
    private var count = 0
    private let versionNow = Atomic<Int>(0)
    private let shapesNow: Atomic<Bool>
    private var followers: [Follower] = []

    private struct Follower {
        weak var step: ShapingStep?
    }

    public init(_ settings: SoundSettings = SoundSettings()) {
        self.settings = settings
        shapesNow = Atomic(settings.shapesSound)
    }

    public func set(_ next: SoundSettings) {
        lock.lock()
        settings = next
        count += 1
        versionNow.store(count, ordering: .releasing)
        shapesNow.store(next.shapesSound, ordering: .releasing)
        followers.removeAll { $0.step == nil }
        let steps = followers.compactMap(\.step)
        lock.unlock()
        for step in steps { step.settingsChanged() }
    }

    public var version: Int { versionNow.load(ordering: .acquiring) }

    /// Whether the settings ask for any shaping at all.
    public var shapes: Bool { shapesNow.load(ordering: .acquiring) }

    public func read() -> (version: Int, settings: SoundSettings) {
        lock.lock()
        defer { lock.unlock() }
        return (count, settings)
    }

    /// `step` works out its shaping again on each change, on the thread that sets it.
    func follow(_ step: ShapingStep) {
        lock.lock()
        followers.append(Follower(step: step))
        lock.unlock()
    }
}

/// What a stream's shaping takes from the settings, worked out off the audio thread: the
/// equalizer's filters (a bank of their own, ready to carry on from the one running), the preamp,
/// the crossfeed, balance and mono. The audio thread takes it whole and gives it back once it no
/// longer runs its filters, so nothing is made or freed there.
public final class ShapingPrep: @unchecked Sendable {
    public let settings: SoundSettings
    public let version: Int
    public let sampleRate: Double
    public let channels: Int
    let bands: [Coefficients]
    let bank: FilterBank
    let preamp: Float
    let balance: Float
    let toMono: Bool
    let feed: CrossfeedCoefficients

    public init(settings: SoundSettings, version: Int = 0, sampleRate: Double, channels: Int) {
        self.settings = settings
        self.version = version
        self.sampleRate = sampleRate
        self.channels = channels
        bands = bandCoefficients(settings.activeFilters(), sampleRate: sampleRate)
        bank = FilterBank(channels: channels, coefficients: bands)
        preamp = Float(pow(10, settings.effectivePreampDb(sampleRate: sampleRate) / 20))
        let stereo = channels >= 2
        balance = stereo ? Float(min(1, max(-1, settings.balance))) : 0
        toMono = stereo && settings.mono
        // With mono on there is nothing to blend.
        let feeds = stereo && settings.crossfeed.on && !settings.mono
        feed = feeds ? CrossfeedCoefficients(settings.crossfeed, sampleRate: sampleRate) : .none
    }
}

/// Octo's shaping as one step of a player's audio path, for one stream of sound: a deck, or
/// one song's tap. Each runs its own, all following the same settings.
///
/// A song's loudness is handed over with `setNextSong` and taken up at the next `flush`, which
/// comes once the song before has played out, so the level changes exactly between the two
/// songs. Whether the step runs is decided when the stream is set up (`configure`): it runs
/// while the settings shape the sound, or always while `keepActive` says so, passing the sound
/// through unchanged until there is something to shape.
///
/// Everything made from the settings (the shaper, its filters) is made where the stream is set
/// up and where the settings change, never on the audio thread: that thread takes the shaping
/// main worked out last without waiting, and gives back what it no longer runs.
public final class ShapingStep: @unchecked Sendable {
    private let source: SoundSettingsBox
    private let keepActive: @Sendable () -> Bool
    /// From the other threads: the next song's loudness and the shaping worked out last; back from
    /// the audio thread: shapings it no longer runs, freed by the next one worked out.
    private let handover = Mutex(Handover())
    private struct Handover {
        var song: SongLoudness?
        var prep: ShapingPrep?
        var returned0: ShapingPrep?
        var returned1: ShapingPrep?

        mutating func give(_ prep: ShapingPrep) -> Bool {
            if returned0 == nil {
                returned0 = prep
                return true
            }
            if returned1 == nil {
                returned1 = prep
                return true
            }
            return false
        }
    }
    /// A next song handed over while the hand-over was busy, taken at the next `flush`.
    private var songAside: SongLoudness?
    private var song: SongLoudness?
    private var shaper: SoundShaper?
    /// The shaping running now, and the one whose filters still fade out under it.
    private var current: ShapingPrep?
    private var fading: ShapingPrep?
    public private(set) var isActive = false
    public private(set) var sampleRate = 48_000.0
    public private(set) var channels = 2
    /// Sound to play while the limiter's delay fills at the start of the stream, in place of
    /// silence: the end of the song before, handed over.
    private var lead: [[Float]] = []
    private var leadUsed = 0

    public init(settings: SoundSettingsBox, keepActive: @escaping @Sendable () -> Bool = { false }) {
        source = settings
        self.keepActive = keepActive
        settings.follow(self)
    }

    /// The loudness of the song that plays after the current one.
    public func setNextSong(_ next: SongLoudness) {
        if handover.withLockIfAvailable({ $0.song = next }) == nil { songAside = next }
    }

    /// Sets up the step for a stream's format. Returns whether it runs; when it does not, the
    /// sound passes through untouched, bit for bit. A new format makes the shaper and its first
    /// shaping here, so call it off the audio thread first.
    @discardableResult
    public func configure(sampleRate: Double, channels: Int) -> Bool {
        let fresh = shaper == nil || sampleRate != self.sampleRate || channels != self.channels
        self.sampleRate = sampleRate
        self.channels = channels
        isActive = source.shapes || keepActive()
        if fresh {
            let made = SoundShaper(sampleRate: sampleRate, channels: channels)
            let now = source.read()
            let prep = ShapingPrep(settings: now.settings, version: now.version, sampleRate: sampleRate, channels: channels)
            made.adopt(prep, song: song, instant: true)
            shaper = made
            current = prep
            fading = nil
        }
        return isActive
    }

    /// The settings changed: the shaping for them is worked out here, on the thread that set them,
    /// and handed to the audio thread; what it gave back is freed.
    func settingsChanged() {
        let now = source.read()
        let prep = ShapingPrep(settings: now.settings, version: now.version, sampleRate: sampleRate, channels: channels)
        let dropped = handover.withLock { h -> (ShapingPrep?, ShapingPrep?, ShapingPrep?) in
            let old = (h.prep, h.returned0, h.returned1)
            h.prep = prep
            h.returned0 = nil
            h.returned1 = nil
            return old
        }
        _ = dropped
    }

    /// Runs once the song before has played out, and after a jump to another place. A new
    /// song's level holds from its very first sample; anything else that changed glides in.
    /// `lead` is the end of the song before, played while the delay fills.
    public func flush(lead: [[Float]]? = nil) {
        let newSong = takeSong()
        if let newSong { song = newSong }
        guard isActive, let shaper else { return }
        shaper.clearHeld()
        let next = takeShaping()
        if let prep = next ?? current { shaper.adopt(prep, song: song, instant: newSong != nil) }
        if let lead, lead.count == channels {
            self.lead = lead
        } else if !self.lead.isEmpty {
            self.lead = []
        }
        leadUsed = 0
    }

    /// Shapes `frames` frames of `input` and writes what comes out to `output`, which may be
    /// the same buffers. Returns the frames written: fewer than went in only while the
    /// limiter's delay fills; `endOfStream` hands out the rest.
    public func process(_ input: [UnsafeMutablePointer<Float>], frames: Int, output: [UnsafeMutablePointer<Float>]) -> Int {
        guard frames > 0 else { return 0 }
        guard isActive, input.count == channels, output.count == channels, let shaper else {
            // An index loop: an array's own iterator allocates in an unoptimized build.
            for ch in 0..<min(input.count, output.count) where input[ch] != output[ch] {
                output[ch].update(from: input[ch], count: frames)
            }
            return frames
        }
        if source.version != (current?.version ?? -1), let next = takeShaping() {
            shaper.adopt(next, song: song, instant: false)
        }
        let written = shaper.process(input, frames: frames, output: output)
        giveBackFaded()
        return written
    }

    /// Shapes `frames` frames in place, the same number out as in, for a path that cannot
    /// hand out more or fewer frames than it was given. While the delay fills at the start of
    /// a stream, the end of the song before plays (when it was handed over), else silence.
    public func process(_ buffers: [UnsafeMutablePointer<Float>], frames: Int) {
        guard isActive, buffers.count == channels, frames > 0 else { return }
        let written = process(buffers, frames: frames, output: buffers)
        guard written < frames else { return }
        shiftToEnd(buffers, frames: frames, written: written)
        let gap = frames - written
        let available = lead.first.map { $0.count - leadUsed } ?? 0
        let take = min(gap, available)
        guard take > 0 else { return }
        // The handed-over sound lands right before the first shaped frame.
        let start = gap - take
        for ch in 0..<channels {
            lead[ch].withUnsafeBufferPointer { from in
                guard let base = from.baseAddress else { return }
                (buffers[ch] + start).update(from: base + leadUsed, count: take)
            }
        }
        leadUsed += take
    }

    /// Hands out the sound still held back, at the end of a stream. `output` needs room for
    /// `latency` frames. Returns the frames written.
    public func endOfStream(into output: [UnsafeMutablePointer<Float>]) -> Int {
        guard isActive, let shaper, output.count == channels else { return 0 }
        return shaper.drain(output)
    }

    /// How many frames late the sound comes out while the step runs.
    public var latency: Int { isActive ? (shaper?.latency ?? 0) : 0 }

    /// Forgets everything about the stream.
    public func reset() {
        shaper = nil
        song = nil
        current = nil
        fading = nil
        lead = []
        leadUsed = 0
    }

    // MARK: The hand-over, on the audio thread

    private func takeSong() -> SongLoudness? {
        if let aside = songAside {
            songAside = nil
            return aside
        }
        let taken = handover.withLockIfAvailable { h -> SongLoudness? in
            let next = h.song
            h.song = nil
            return next
        }
        return taken ?? nil
    }

    /// The shaping main worked out last, never waiting: taken only when the one still fading out
    /// can be given back with it, so nothing is freed here.
    private func takeShaping() -> ShapingPrep? {
        let giving = fading
        let rate = sampleRate
        let count = channels
        let taken = handover.withLockIfAvailable { h -> ShapingPrep? in
            // One worked out for another format (before this stream was set up) is never run.
            guard let next = h.prep, next.sampleRate == rate, next.channels == count else { return nil }
            if let giving, !h.give(giving) { return nil }
            h.prep = nil
            return next
        }
        guard let next = taken ?? nil else { return nil }
        fading = current
        current = next
        return next
    }

    /// Gives back the shaping whose filters no longer run.
    private func giveBackFaded() {
        guard let faded = fading, shaper?.fadingFilters != true else { return }
        if handover.withLockIfAvailable({ $0.give(faded) }) == true { fading = nil }
    }
}

/// Passes the last moment of one song's sound to the stream of the song after it, for paths
/// that give each song a stream of its own: what the limiter still held when the song ended
/// plays at the start of the next one, so nothing is lost or added between them. A hand-over
/// waits only `window` seconds, so a song started later does not open with an old one's end.
public final class SoundHandOff: @unchecked Sendable {
    private let lock = NSLock()
    private var frames: [[Float]] = []
    private var sampleRate = 0.0
    private var at = -Double.infinity
    public let window: Double

    public init(window: Double = 0.5) {
        self.window = window
    }

    /// Keeps `count` frames of `buffers`, one buffer per channel, from a song that just ended.
    public func put(_ buffers: [UnsafeMutablePointer<Float>], count: Int, sampleRate: Double, now: Double) {
        let kept = count > 0 ? buffers.map { Array(UnsafeBufferPointer(start: $0, count: count)) } : []
        lock.lock()
        frames = kept
        self.sampleRate = sampleRate
        at = now
        lock.unlock()
    }

    /// The sound handed over for a stream of this format, taken once, or nothing when there is
    /// none, it is for another format, or it waited too long.
    public func take(sampleRate: Double, channels: Int, now: Double) -> [[Float]]? {
        lock.lock()
        defer {
            frames = []
            lock.unlock()
        }
        guard !frames.isEmpty, frames.count == channels, self.sampleRate == sampleRate, now - at <= window, now >= at else { return nil }
        return frames
    }
}
