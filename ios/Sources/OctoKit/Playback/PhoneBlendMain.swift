import Foundation

/// Main's side of the phone's blend rules, the one the app's crossfader and the tests both run:
/// the generation registered and the one handed over, the phase rule's disarm (a registered start
/// is undone and owed a follow-up; a handed-over one has its old song leave), the deck holding the
/// old song's kept frames through a pause past the handover, and a blend end that waits until
/// those frames are out. It reaches the render thread only through the decks' orders and the
/// mix's ring. Main thread only.
public final class PhoneBlendMain {
    public let mix: PhoneMix
    public private(set) var nextGeneration: Int64 = 1
    /// The generation registered with the session and not yet handed over (-1 for none).
    public private(set) var registered: Int64 = -1
    /// The generation handed over and not yet finished (-1 for none), its old song's deck, and
    /// whether that song was told to leave.
    public private(set) var handedOver: Int64 = -1
    public private(set) var outgoing: DeckRender?
    public private(set) var leaving = false
    /// An undone generation owed its follow-up, once the edit's own write has applied.
    public private(set) var followUpFor: Int64 = -1
    /// The deck holding the old song's frames up to the start through a pause past the handover.
    public private(set) var keptOn: DeckRender?
    /// A blend end heard while the old song still had kept frames to play: its ease point.
    private var endWaiting: Double?
    /// Told when the ring to the render thread was full and a message was lost.
    public var onLost: (BlendMainInput) -> Void = { _ in }

    public enum Proposal: Sendable, Equatable {
        /// Not the generation registered, or one already handed over: nothing to do.
        case ignored
        /// Acknowledged: the player hands over to the incoming deck now.
        case handedOver
        /// The render canceled it first, at its commit deadline: forgotten.
        case cancelled
    }

    public init(mix: PhoneMix) {
        self.mix = mix
    }

    public func send(_ input: BlendMainInput) {
        if !mix.send(input) { onLost(input) }
    }

    // MARK: Registering and handing over

    /// Registers a blend of `from` into `into` under a new generation and answers it. The spare
    /// plays first (silent, consuming nothing until its start), so it is never seen paused at its
    /// registration; the arm names the song on each deck.
    public func register(from: DeckRender, into: DeckRender, entryMs: Double, arm make: (Int64) -> BlendArm) -> Int64 {
        into.orders.withLock { $0.playing = true }
        let g = nextGeneration
        nextGeneration += 1
        registered = g
        let outgoingSerial = from.orders.withLock { $0.current.map { Int64($0.serial) } ?? -1 }
        let incomingSerial = into.orders.withLock { $0.current.map { Int64($0.serial) } ?? -1 }
        mix.word.open(g)
        send(.arm(make(g), outgoing: from.index, entryMs: entryMs, outgoingSerial: outgoingSerial, incomingSerial: incomingSerial))
        return g
    }

    /// The session proposed generation `g`'s start: main acknowledges it and hands over, `into`
    /// no longer parked by main, so everything from here lands on the song on screen.
    public func proposed(_ g: Int64, from: DeckRender, into: DeckRender) -> Proposal {
        // A second proposal for a generation handed over does nothing.
        guard g != handedOver, g == registered else { return .ignored }
        if mix.word.ack(g) || mix.word.state(of: g) == .acked {
            handedOver = g
            outgoing = from
            leaving = false
            registered = -1
            endWaiting = nil
            into.orders.withLock { $0.parked = false }
            return .handedOver
        }
        forget()
        return .cancelled
    }

    /// Forgets the registered generation, its word left cancelled unless main acknowledged it.
    public func forget() {
        if registered >= 0 { _ = mix.word.cancel(registered) }
        registered = -1
    }

    // MARK: The phase rule

    /// A registered start has its commit word cancelled before the disarm goes, so every edit
    /// handled before this lands on the old song, and is owed its follow-up; a handed-over one has
    /// its old song leave. Answers the generation undone, if one was.
    @discardableResult
    public func disarm(_ reason: BlendDisarm) -> Int64? {
        let g = registered
        if g >= 0 {
            _ = mix.word.cancel(g)
            followUpFor = g
            registered = -1
            send(.disarm(reason, generation: g))
            return g
        }
        if handedOver >= 0 && !leaving {
            leaving = true
            send(.disarm(reason, generation: handedOver))
        }
        return nil
    }

    /// Before anything that changes what plays or where; the caller then lets its own change apply
    /// and calls `followUp`. A releasing change (a seek, a skip, new items, a stop, an edit that
    /// leaves another song on now) drops the old song's kept frames; a pause pauses them; a pause
    /// past the handover pauses the old song first, so its frames to the start are kept for the
    /// resume. Answers the generation undone, if one was.
    @discardableResult
    public func interrupt(_ reason: BlendDisarm, releasing: Bool) -> Int64? {
        if releasing {
            keptOn?.orders.withLock { $0.dropKept += 1 }
            keptOn = nil
        }
        if reason == .pause { keptOn?.orders.withLock { $0.playing = false } }
        let paused = reason == .pause && handedOver >= 0 && !leaving ? outgoing : nil
        paused?.orders.withLock { $0.playing = false }
        let undone = disarm(reason)
        if let paused { keptOn = paused }
        return undone
    }

    /// Playing again: the old song's kept frames play first, then the new song comes in where
    /// they end. Called before the song on now plays.
    public func resumed() {
        keptOn?.orders.withLock { $0.playing = true }
    }

    /// The listener's pace changed: a start registered and not handed over is undone at once,
    /// before any deck's rate or latency moves; a blend under way hears the pace. Answers whether
    /// a start was undone (the caller then sends its follow-up and plans again).
    @discardableResult
    public func paceChanged(_ pace: Double) -> Bool {
        if registered >= 0 {
            disarm(.edit)
            return true
        }
        if handedOver >= 0 { send(.paceChanged(pace)) }
        return false
    }

    /// What the undone generation plays after its end (`pass`), sent once the change that undid it
    /// has applied.
    public func followUp(pass: () -> Int64) {
        let g = followUpFor
        guard g >= 0 else { return }
        followUpFor = -1
        send(.followUp(generation: g, pass: pass()))
    }

    /// The session asks what generation `g`'s old song plays after its end.
    public func followUpNeeded(_ g: Int64, pass: Int64) {
        if g == followUpFor { followUpFor = -1 }
        send(.followUp(generation: g, pass: pass))
    }

    /// "Pause at the end of this song" on `deck` changes to `stop`. With a start registered and not
    /// handed over, `undo` (the caller's interrupt) runs first, so the song on now ends where it
    /// stops; then the flag is written; then the undone start hears what follows (`pass`). The
    /// same value again touches nothing.
    public func endOfSongChanged(on deck: DeckRender, to stop: Bool, undo: () -> Void, pass: () -> Int64) {
        let old = deck.orders.withLock { $0.pauseAtEnd }
        switch endOfSongWrite(old: old, new: stop, registered: registered >= 0) {
        case .skip:
            return
        case .write:
            deck.orders.withLock { $0.pauseAtEnd = stop }
        case .disarmThenWrite:
            undo()
            deck.orders.withLock { $0.pauseAtEnd = stop }
            followUp(pass: pass)
        }
    }

    // MARK: The blend's end

    /// The session says generation `g`'s blend has ended (or was canceled after the handover).
    /// The old deck stops now, unless it still has kept frames to play after a pause past the
    /// handover: then the end waits for them (`endDue`). Answers the ease point to finish with
    /// now, or nil.
    public func blendEnded(_ g: Int64, easeFromMs: Double) -> Double? {
        guard g == handedOver else { return nil }
        if outgoing?.keptPending.load(ordering: .acquiring) == true {
            endWaiting = easeFromMs
            return nil
        }
        return easeFromMs
    }

    /// Every tick: a blend end that waited for the old song's kept frames, once they are out.
    public func endDue() -> Double? {
        guard let ease = endWaiting, outgoing?.keptPending.load(ordering: .acquiring) != true else { return nil }
        endWaiting = nil
        return ease
    }

    /// Whether a blend end waits for kept frames.
    public var endWaits: Bool { endWaiting != nil }

    /// The blend is over: the caller stops the old deck (it becomes the spare), and the render
    /// hears it.
    public func finished() {
        let g = handedOver
        if keptOn === outgoing { keptOn = nil }
        handedOver = -1
        outgoing = nil
        leaving = false
        endWaiting = nil
        if g >= 0 { send(.outgoingStopped(generation: g)) }
    }

    /// The old song failed mid-blend, or the new one after the handover: the render hears it.
    public func failedAfterHandover(_ deck: BlendDeck) {
        guard handedOver >= 0 else { return }
        if deck == .b {
            guard !leaving else { return }
            leaving = true
        }
        send(.failed(deck, generation: handedOver, followUp: blendNoAnswer))
    }

    /// A deck failed before the handover: the start is undone, carrying `followUp` (what the old
    /// song plays after its end, or `blendNoAnswer`).
    public func failedBeforeHandover(_ deck: BlendDeck, followUp: Int64) {
        let g = registered
        guard g >= 0 else { return }
        _ = mix.word.cancel(g)
        registered = -1
        send(.failed(deck, generation: g, followUp: followUp))
    }
}
