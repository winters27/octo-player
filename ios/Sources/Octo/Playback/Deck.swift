import AVFoundation
import OctoKit
import Synchronization
import os

/// The rate every deck plays at; the mixer turns it into whatever the output wants.
let deckRate: Double = blendBusRate

/// What a deck noticed since it was last asked.
enum DeckEvent {
    /// It moved on to the following song by itself, without a gap.
    case advanced(from: QueueItem, to: QueueItem)
    /// The song ended with nothing to follow, or stopped at its end.
    case ended(QueueItem, paused: Bool)
    /// The song could not play.
    case failed(QueueItem, PlayError)
}

/// One of the two players: a song loaded (and the one to follow it without a gap), playing
/// through Octo's sound processor, a speed and pitch stage and its level after it into the mixer.
/// Its render (OctoKit's `DeckRender`) runs on the audio thread; this is its main side.
@MainActor
final class Deck {
    let name: String
    let render: DeckRender
    let node: AVAudioSourceNode
    let timePitch = AVAudioUnitTimePitch()
    /// The deck's level after its time-pitch stage, where the blend's leave and fade ramps land on
    /// their exact frames; nil when the unit could not be made.
    let level: AVAudioUnitEffect?
    var processor: DeckProcessor { render.processor }

    private(set) var current: DeckSource?
    private(set) var following: DeckSource?
    /// Songs let go, kept a moment so the audio thread is never the one to free them.
    private var retired: [(source: DeckSource, at: Date)] = []
    private var seenAdvances = 0
    private var seenEndings = 0
    private var reportedFailure: ObjectIdentifier?
    /// The delay between the song time the audio thread reports and what is heard, in ms.
    var outputDelayMs: Double = 0

    init(name: String, sound: SoundProcessor?, keepActive: @escaping @Sendable () -> Bool) {
        self.name = name
        let stream = sound?.makeStream(keepActive: keepActive)
        let processor = DeckProcessor(keepActive: keepActive, tap: LiveTapFeed(), stage: stream.map(SoundStage.init))
        let render = DeckRender(processor: processor)
        if let stream { render.onNewSong = Self.newSongHook(stream) }
        self.render = render
        node = Self.sourceNode(render)
        level = OctoEffectUnit.node(OctoEffectUnit.deckLevel, name: "deck level", renderer: DeckLevelRender(render))
    }

    /// Each new song's stream is judged for loudness as it starts. Made outside the main actor: the
    /// audio thread calls it.
    nonisolated private static func newSongHook(_ stream: SoundStream) -> (any DeckFeed) -> Void {
        { feed in
            guard let source = feed as? DeckSource, let setUp = source.setUp else { return }
            stream.setNextSong(setUp, tags: source.loudness)
        }
    }

    /// The node the engine pulls the deck's sound from. Made outside the main actor: the audio
    /// thread calls it.
    nonisolated private static func sourceNode(_ render: DeckRender) -> AVAudioSourceNode {
        let format = AVAudioFormat(standardFormatWithSampleRate: deckRate, channels: 2)!
        return AVAudioSourceNode(format: format) { _, _, frameCount, list in
            let buffers = UnsafeMutableAudioBufferListPointer(list)
            guard buffers.count >= 2, let left = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                  let right = buffers[1].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            render.render(Int(frameCount), left, right)
            return noErr
        }
    }

    // MARK: What it holds

    var item: QueueItem? { current?.item }
    var followingItem: QueueItem? { following?.item }

    /// Loads a song, at `ms`, replacing whatever was loaded.
    func load(_ source: DeckSource) {
        let old = (current, following)
        current = source
        following = nil
        reportedFailure = nil
        render.orders.withLock {
            $0.current = source
            $0.following = nil
        }
        retire(old.0)
        retire(old.1)
    }

    /// The song to follow this one without a gap, or none.
    func setFollowing(_ source: DeckSource?) {
        let old = following
        following = source
        render.orders.withLock { $0.following = source }
        retire(old)
    }

    /// Empties the deck.
    func clear() {
        let old = (current, following)
        current = nil
        following = nil
        render.orders.withLock {
            $0.current = nil
            $0.following = nil
            $0.playing = false
        }
        retire(old.0)
        retire(old.1)
    }

    func seek(toMs ms: Int) {
        current?.seek(toMs: ms)
    }

    // MARK: Playing

    var playWhenReady: Bool {
        get { render.orders.withLock { $0.playing } }
        set { render.orders.withLock { $0.playing = newValue } }
    }

    var pauseAtEndOfSong: Bool {
        get { render.orders.withLock { $0.pauseAtEnd } }
        set { render.orders.withLock { $0.pauseAtEnd = newValue } }
    }

    var volume: Float {
        get { render.orders.withLock { $0.volume } }
        set { render.orders.withLock { $0.volume = newValue } }
    }

    var startAfterMs: Int {
        get { render.orders.withLock { $0.startAfterMs } }
        set { render.orders.withLock { $0.startAfterMs = newValue } }
    }

    /// A spare loaded for a blend: silent and consuming nothing until the blend starts it.
    var parked: Bool {
        get { render.orders.withLock { $0.parked } }
        set { render.orders.withLock { $0.parked = newValue } }
    }

    /// The old song's frames kept through a pause past a handover are no longer wanted.
    func dropKept() { render.orders.withLock { $0.dropKept += 1 } }

    /// The pace: speed (tempo) and pitch as a multiple of the original.
    private(set) var pace = Pace(speed: 1, pitch: 1)

    func setPace(_ next: Pace) {
        pace = next
        timePitch.rate = next.speed
        timePitch.pitch = next.pitch > 0 ? 1200 * log2(next.pitch) : 0
        timePitch.bypass = stageSetting.bypass
        refreshLatency()
        render.orders.withLock { $0.speed = next.speed }
    }

    private var stageSetting: (bypass: Bool, latencyFrames: Double) {
        timePitchSetting(speed: pace.speed, pitch: pace.pitch, latencySeconds: timePitch.latency, deckRate: deckRate)
    }

    /// How far the time-pitch stage delays this deck's sound, in bus frames, bypassed or not.
    var latencyFrames: Double { stageSetting.latencyFrames }

    /// The stage's latency read again (it is known once the engine runs), for this deck's render
    /// and for main's own estimate of the larger of the two.
    func refreshLatency() {
        let latency = latencyFrames
        render.orders.withLock { $0.latencyFrames = latency }
        render.mix?.noteLatency()
    }

    var skipSilence = false {
        didSet {
            current?.skipSilence.store(skipSilence, ordering: .relaxed)
            following?.skipSilence.store(skipSilence, ordering: .relaxed)
        }
    }

    var state: DeckState {
        guard let current else { return .idle }
        if current.failure != nil { return .ended }
        // Sound of an earlier song or from before a jump says nothing of this one yet.
        guard soundIsCurrent else { return .buffering }
        return DeckState(code: render.phaseCode.load(ordering: .relaxed))
    }

    /// Whether the last sound handed out was of this song, since its latest jump.
    private var soundIsCurrent: Bool {
        guard let current else { return false }
        return render.renderedSerial.load(ordering: .relaxed) == current.serial
            && render.renderedGeneration.load(ordering: .relaxed) == current.ring.generation.load(ordering: .relaxed)
    }

    var isPlaying: Bool { playWhenReady && state == .ready }

    /// Whether the song has enough sound waiting to start playing now, by the start-after rule.
    var hasEnoughToStart: Bool {
        guard let current, current.failure == nil else { return false }
        if state == .ready || current.decodedToEnd.load(ordering: .relaxed) { return true }
        let waiting = current.ring.waitingFrames
        guard waiting > 0 else { return false }
        if waiting >= current.ring.capacity * PCMRing.chunkFrames * 3 / 4 { return true }
        let bufferedUs = Int(Double(waiting) * 1_000_000 / deckRate)
        return OctoKit.hasEnoughToStart(bufferedUs: bufferedUs, speed: pace.speed, rebuffering: false,
                                        startAfterMs: current.isLocal ? 250 : startAfterMs)
    }

    /// Where the song is, as heard.
    var positionMs: Int {
        guard let current else { return 0 }
        guard soundIsCurrent else { return current.jumpMs.load(ordering: .relaxed) }
        let ms = Double(bitPattern: render.songMsBits.load(ordering: .relaxed))
        let latency = Double(processor.latency) * 1000 / deckRate + outputDelayMs * Double(pace.speed)
        return max(0, Int(ms - latency))
    }

    /// Where the song is at the render's head (ahead of what is heard by the output delay), for
    /// planning and arming a blend: on AirPlay the heard position is about 2 s behind it. The
    /// heard position when the render has not reported one for this song.
    var headPositionMs: Int {
        guard current != nil, soundIsCurrent else { return positionMs }
        let ms = render.headMs
        return ms.isNaN ? positionMs : max(0, Int(ms))
    }

    /// The song's length as the deck measured it, when it has.
    var durationMs: Int? { current?.measuredMs }

    // MARK: Events

    /// What happened since the last look.
    func poll() -> [DeckEvent] {
        var events: [DeckEvent] = []
        let (renderCurrent, renderFollowing, advances, endings, paused) = render.orders.withLock {
            ($0.current, $0.following, $0.advances, $0.endings, !$0.playing)
        }
        if advances != seenAdvances {
            seenAdvances = advances
            if let from = current, let to = renderCurrent as? DeckSource, to !== from {
                current = to
                following = renderFollowing as? DeckSource
                retire(from)
                reportedFailure = nil
                events.append(.advanced(from: from.item, to: to.item))
            }
        }
        if endings != seenEndings {
            seenEndings = endings
            if let current { events.append(.ended(current.item, paused: paused)) }
        }
        if let current, let failure = current.failure, reportedFailure != ObjectIdentifier(current) {
            reportedFailure = ObjectIdentifier(current)
            events.append(.failed(current.item, failure))
        }
        let now = Date()
        retired.removeAll { entry in now.timeIntervalSince(entry.at) > 3 }
        return events
    }

    private func retire(_ source: DeckSource?) {
        guard let source else { return }
        source.close()
        retired.append((source, Date()))
    }
}

/// The sound processor's stream as a deck's sound stage.
final class SoundStage: DeckSoundStage {
    let stream: SoundStream

    init(_ stream: SoundStream) {
        self.stream = stream
    }

    @discardableResult
    func configure(sampleRate: Double, channels: Int) -> Bool { stream.configure(sampleRate: sampleRate, channels: channels) }
    func flush() { stream.flush() }
    func process(_ input: [UnsafeMutablePointer<Float>], frames: Int, output: [UnsafeMutablePointer<Float>]) -> Int {
        stream.process(input, frames: frames, output: output)
    }
    func endOfStream(into output: [UnsafeMutablePointer<Float>]) -> Int { stream.endOfStream(into: output) }
    var latency: Int { stream.latency }
}
