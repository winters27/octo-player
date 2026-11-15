import AVFoundation
import OctoKit

/// The sound going out: one audio engine both decks play into, the phone's audio session, and
/// what the system says about it (a call, another app, headphones coming and going, a new
/// output). Both decks share the one session.
///
/// Each deck plays through its time-pitch stage and its level unit into the main mixer; the
/// limiter on the sum sits between the main mixer and the output, and runs the blend's rules each
/// cycle (OctoKit's `PhoneMix`), so it is reconnected at the output's format whenever that
/// changes.
@MainActor
final class DeckOutput {
    let engine = AVAudioEngine()
    let mix: PhoneMix
    #if DEBUG
    /// The debug build's tap on the mix, held here (the mix only points at it).
    let tap = MixTap()
    #endif
    private let decks: [Deck]
    private let limiter: AVAudioUnitEffect?
    /// Whether the engine should be running: music plays, or it waits silent a while after a pause.
    private var wanted = false
    /// The engine stopped by itself (a new output, a reset): the next start resets both time-pitch
    /// stages.
    private var resetSinceStart = false
    private var stopTask: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []

    /// A call or another app took the sound (`began`), or gave it back (`shouldResume` when the
    /// system says playing may go on).
    var onInterruption: ((_ began: Bool, _ shouldResume: Bool) -> Void)?
    /// The output changed: why, and the outputs now and before.
    var onRouteChange: ((_ reason: AVAudioSession.RouteChangeReason, _ now: [AVAudioSessionPortDescription],
                         _ before: [AVAudioSessionPortDescription]) -> Void)?

    init(decks: [Deck]) {
        self.decks = decks
        let mix = PhoneMix(decks: decks.map(\.render))
        self.mix = mix
        limiter = OctoEffectUnit.node(OctoEffectUnit.mixLimiter, name: "mix limiter", renderer: MixLimiterRender(mix))
        #if DEBUG
        mix.observer = tap
        #endif
        for deck in decks { connect(deck) }
        if let limiter { engine.attach(limiter) }
        connectOutput()
        engine.prepare()
        listen()
    }

    private func connect(_ deck: Deck) {
        let format = AVAudioFormat(standardFormatWithSampleRate: deckRate, channels: 2)
        engine.attach(deck.node)
        engine.attach(deck.timePitch)
        engine.connect(deck.node, to: deck.timePitch, format: format)
        if let level = deck.level {
            engine.attach(level)
            engine.connect(deck.timePitch, to: level, format: format)
            engine.connect(level, to: engine.mainMixerNode, format: format)
        } else {
            engine.connect(deck.timePitch, to: engine.mainMixerNode, format: format)
        }
    }

    /// The main mixer into the limiter into the output, at the output's own rate and channels.
    private func connectOutput() {
        guard let limiter else { return }
        let hardware = engine.outputNode.outputFormat(forBus: 0)
        let rate = hardware.sampleRate > 0 ? hardware.sampleRate : deckRate
        let channels = max(1, hardware.channelCount)
        guard let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: channels) else { return }
        engine.disconnectNodeOutput(engine.mainMixerNode)
        engine.disconnectNodeOutput(limiter)
        engine.connect(engine.mainMixerNode, to: limiter, format: format)
        engine.connect(limiter, to: engine.outputNode, format: format)
    }

    /// How starting the sound went.
    enum Start {
        case started
        /// The system would not give the sound (a call is on).
        case refused
        /// The sound device would not start.
        case failed
    }

    /// Starts the sound: the session for long playback, then the engine.
    @discardableResult
    func start() -> Start {
        stopTask?.cancel()
        stopTask = nil
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, policy: .longFormAudio)
            try session.setActive(true)
        } catch {
            playbackLog.info("audio session refused: \(String(describing: error), privacy: .public)")
            return .refused
        }
        wanted = true
        if !engine.isRunning {
            do {
                try startEngine()
            } catch {
                playbackLog.info("audio engine did not start: \(String(describing: error), privacy: .public)")
                return .failed
            }
        }
        updateDelays()
        return .started
    }

    /// Starts the engine; after it stopped by itself, both decks are anchored again on the bus.
    private func startEngine() throws {
        if resetSinceStart {
            resetSinceStart = false
            mix.restarts.add(1, ordering: .releasing)
        }
        try engine.start()
    }

    /// Lets the engine stop once it has stayed silent for `after` seconds; playing again before
    /// then keeps it.
    func stopLater(after seconds: Double) {
        stopTask?.cancel()
        stopTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            self?.stopNow()
        }
    }

    /// Stops the engine and gives the sound back to other apps.
    func stopNow() {
        stopTask?.cancel()
        stopTask = nil
        wanted = false
        engine.pause()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    var isRunning: Bool { engine.isRunning }

    /// The time between a deck handing out sound and it being heard, for each deck's position, and
    /// the numbers the blend's rules run on: the output's delay (the limiter's included) and the
    /// larger time-pitch latency.
    func updateDelays() {
        let session = AVAudioSession.sharedInstance()
        let rate = engine.outputNode.outputFormat(forBus: 0).sampleRate
        let limiterDelay = rate > 0 ? Double(mix.limiterLatencyFrames) / rate : 0
        let device = session.outputLatency + session.ioBufferDuration + limiterDelay
        for deck in decks {
            deck.refreshLatency()
            deck.outputDelayMs = (device + deck.latencyFrames / deckRate) * 1000
        }
        mix.outputDelayMs = device * 1000
        mix.noteLatency()
    }

    private func listen() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map(AVAudioSession.InterruptionOptions.init)
            MainActor.assumeIsolated {
                guard let self, let type else { return }
                self.onInterruption?(type == .began, options?.contains(.shouldResume) == true)
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            let before = (note.userInfo?[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription)?.outputs ?? []
            MainActor.assumeIsolated {
                guard let self, let reason else { return }
                self.updateDelays()
                self.onRouteChange?(reason, AVAudioSession.sharedInstance().currentRoute.outputs, before)
            }
        })
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                // The output changed under the engine (a new device, another rate): it stopped and
                // reset its stages, so the limiter is reconnected at the new format, and the engine
                // starts again when it should be running.
                guard let self, !self.engine.isRunning else { return }
                self.resetSinceStart = true
                self.connectOutput()
                guard self.wanted else { return }
                try? self.startEngine()
                self.updateDelays()
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.engine.stop()
                self.resetSinceStart = true
                self.connectOutput()
                if self.wanted { self.start() }
            }
        })
    }
}
