import Foundation
import OctoKit

/// How often to check on the song: rarely while the end is far off, and every frame or so near
/// it.
private let farCheckMs = 1_000
private let nearCheckMs = 40

/// The planner needs the end of the playing song and the start of the next once the song has
/// played this long.
private let scoutAfterMs = 1_000

/// The song's body level from what has been heard counts once this much has been heard.
private let levelHeardMs = 20_000

/// A tempo-matched song eases back to its own speed in steps this small.
private let rateStep = 0.0025

/// Runs the transition between two decks. While a song plays it reads the end of it and the
/// start of the next song, and plans where and how they meet (`planTransition`), each from the
/// server's transition profile of the song when the server has one, else from scouting it. Ahead
/// of the blend it loads the spare deck with the next song, parked just before its entry; a
/// little before the blend it registers the blend with the render thread's session under a new
/// generation and the spare plays, silent and consuming nothing until the session starts it on
/// its exact frame. The session decides the start first and then proposes it; main agrees in the
/// turn that hands over, by moving the generation's commit word to acknowledged, so every edit
/// handled before lands on the old song and every edit after on the new one. Anything the
/// listener does before the handover undoes the start (nothing of the new song is rendered); after
/// it, the old song leaves and the change lands on the song on screen. The session says when the
/// blend ends.
@MainActor
final class Crossfader {
    private unowned let player: PlaybackEngine
    private(set) var spare: Deck
    private let mix: PhoneMix
    /// The decks' sound shaping, for each song's ReplayGain; nil plays every song at its own level.
    private let sound: SoundProcessor?
    private let scout: Scout?
    private let profiles: ServerProfiles?
    private let lengths: RealLengths

    /// How transitions are planned; a longest blend of 0 turns them off. A change ends a blend not
    /// yet handed over by the phase rule; one already handed over plays on.
    var settings = AutomixSettings(maxOverlapMs: 0) {
        didSet {
            guard oldValue != settings else { return }
            if registered >= 0 {
                disarm(.edit)
                followUp()
            }
            dropPlan()
            unloadSpare()
            schedule(0)
        }
    }

    /// The longest blend, or 0 when crossfade is off.
    var fadeMs: Int { settings.maxOverlapMs }

    private var check: DispatchWorkItem?

    /// The queue entry and file the spare is loaded with, if it is loaded, and the plan it was
    /// loaded for.
    private var loadedFor: String?
    private var loadedUri: String?
    private var loadedPlan: TransitionPlan?
    /// The spare's song that failed for this transition, not loaded again until the song changes.
    private var failed = BlendFailedSpare()

    /// The plan for the coming transition, and what it was made from.
    private var plan: TransitionPlan?
    private var planKey: PlanKey?

    /// The silence gate the playing song was armed with, for an early fade.
    private var outgoingGate: Double?

    /// Main's side of the blend's rules (OctoKit's `PhoneBlendMain`): the generations registered
    /// and handed over, the phase rule's disarm, the old song's kept frames through a pause, and a
    /// blend end that waits for them.
    private let main: PhoneBlendMain
    private var registered: Int64 { main.registered }
    private var handedOver: Int64 { main.handedOver }
    private var leaving: Bool { main.leaving }
    private var followUpFor: Int64 { main.followUpFor }
    /// The registered generation's plan and next song, and the generations whose start commits
    /// before main's acknowledgement (AirPlay's quiet starts).
    private var registeredPlan: TransitionPlan?
    private var registeredNext: (entryId: String?, uri: String)?
    private var quietStarts: Set<Int64> = []

    /// During the blend: the deck playing out the old song, and the plan.
    private(set) var outgoing: Deck?
    private var blending: TransitionPlan?

    /// A tempo-matched song easing back to its own speed.
    private var easing: Easing?

    /// The scouted end of the playing song and start of the next.
    private var tail: Scouted?
    private var head: Scouted?
    private var reading: Reading?

    /// Where the song was at the last check, to see it jump.
    private var lastPosition = -1
    private var lastCheckAt = 0

    /// The session's events drained from the ring, waiting until they are due.
    private var waiting: [BlendEvent] = []
    /// Main's lag: the ticker's own lateness, over the last ten minutes.
    private var lag = LagWindow()
    private var lastTickMs = -1.0

    /// The app's own queue upkeep waiting for the blend to be over, and whether its one message is
    /// on its way.
    private var upkeep: [() -> Void] = []
    private var upkeepPosted = false

    private final class Scouted {
        let entryId: String
        var job: Scout.Job?
        var done = false
        var analysis: SectionAnalysis?
        /// The server's profile of the song, when the analysis came from it.
        var profile: TransitionProfile?

        init(_ entryId: String) { self.entryId = entryId }
    }

    private final class Reading {
        let entryId: String
        var job: Scout.Job?
        var done = false
        var value: TapReading?

        init(_ entryId: String) { self.entryId = entryId }
    }

    /// A tempo-matched song on its way back to its own speed, on its own song time: matched until
    /// `easeFromMs` (its song time since entry over the rate, from the blend's end), then eased;
    /// `applied` is the rate last set before the listener's pace.
    @MainActor
    private final class Easing {
        let deck: Deck
        let entryId: String?
        let plan: TransitionPlan
        let rate: Double
        var easeFromMs = Double.infinity
        var applied: Double

        init(deck: Deck, entryId: String?, plan: TransitionPlan, rate: Double) {
            self.deck = deck
            self.entryId = entryId
            self.plan = plan
            self.rate = rate
            applied = rate
        }

        var sinceEntryMs: Double { Double(deck.positionMs - plan.entryMs) / rate }
    }

    /// Everything a plan depends on besides the clock. A change means a new plan. Once a blend is
    /// registered, the scouts' readiness never changes it (`core`).
    private struct PlanKey: Equatable {
        var current: String?
        var next: String?
        var currentMs: Int
        var nextMs: Int
        var settings: AutomixSettings
        var repeatOne: Bool
        var stopAtEnd: Bool
        var speed: Float
        var skipSilence: Bool
        var tail: Bool
        var head: Bool
        var reading: Bool
        /// Whether each deck has read its song's own loudness tags (the incoming song's gain).
        var currentTags: Bool
        var nextTags: Bool

        var core: PlanKey {
            var c = self
            c.tail = false
            c.head = false
            c.reading = false
            c.currentTags = false
            c.nextTags = false
            return c
        }
    }

    init(player: PlaybackEngine, spare: Deck, mix: PhoneMix, sound: SoundProcessor?, scout: Scout?, profiles: ServerProfiles?,
         lengths: RealLengths) {
        self.player = player
        self.spare = spare
        self.mix = mix
        self.sound = sound
        self.scout = scout
        self.profiles = profiles
        self.lengths = lengths
        main = PhoneBlendMain(mix: mix)
        main.onLost = { _ in playbackLog.error("automix: the mix's message ring was full; a message was lost") }
        spare.parked = true
    }

    private var nowMs: Int { Int(ProcessInfo.processInfo.systemUptime * 1000) }

    // MARK: What the player tells it

    /// A pause reaches the blend through the player's pause and a rebuffer through the session:
    /// neither is decided here.
    func isPlayingChanged(_ isPlaying: Bool) {
        if isPlaying { schedule(0) }
    }

    /// The song on now changed some other way (the playing deck moved on into its own next song,
    /// a jump): what the spare holds is stale.
    func songChanged() {
        failed.songChanged()
        if outgoing != nil { return }
        disarm(.edit)
        // The change has applied by now (the deck moved on, or the jump was made).
        followUp()
        dropPlan()
        unloadSpare()
        schedule(0)
    }

    /// Called before anything that changes what plays or where: by the phase rule, a start not yet
    /// handed over is undone and the spare unloaded; a blend already handed over lets the old song
    /// leave and the change lands on the song on screen (a pause keeps the old song's frames up to
    /// the start for the resume). `releasing` is a change that lets go of what plays (a seek, a
    /// skip, new items, a stop, an edit that leaves another song on now): only it drops those kept
    /// frames. Every caller calls `followUp()` once its own change has applied.
    func interrupt(_ reason: BlendDisarm = .edit, releasing: Bool = true) {
        let wasRegistered = registered >= 0
        main.interrupt(reason, releasing: releasing)
        if wasRegistered { clearRegistration() }
        dropPlan()
        unloadSpare()
        schedule(0)
    }

    /// Playing again: the old song's kept frames play first, then the new song comes in where they
    /// end. Called before the song on now plays.
    func resumed() {
        main.resumed()
    }

    /// "Pause at the end of this song" changes to `stop`: a start not yet handed over is undone
    /// first, so the song on now ends where it stops; then the flag is written; then the undone
    /// start hears what follows the song. A blend already handed over plays on, the flag landing
    /// on the song on screen.
    func endOfSongChanged(to stop: Bool) {
        main.endOfSongChanged(on: player.deck.render, to: stop, undo: { interrupt(.edit, releasing: false) }, pass: followUpPass)
    }

    /// An edit of the queue that left the song on now unchanged: on the iPhone the spare holds no
    /// copy of the queue, so an edit that leaves the registered next song (entry and file) as it
    /// was keeps the blend; any other interrupts it.
    func edited(currentBefore: String?) {
        let queue = player.queue
        let next = queue.nextOnEnd.map { (entryId: queue.items[$0].entryId, uri: queue.items[$0].uri) }
        if registered >= 0, editKeepsBlend(currentBefore: currentBefore, currentAfter: queue.currentItem?.entryId,
                                          registeredNext: registeredNext, nextAfter: next) {
            return
        }
        if registered < 0 && handedOver < 0 && loadedFor != nil, let at = queue.nextOnEnd, entryKey(queue.items[at]) == loadedFor,
           queue.items[at].uri == loadedUri, currentBefore == queue.currentItem?.entryId {
            // Loaded but not yet registered, and still next: nothing to undo.
            return
        }
        interrupt(.edit, releasing: editReleases(currentBefore: currentBefore, currentAfter: queue.currentItem?.entryId))
    }

    /// What the playing song plays after its end, for an undone start whose song had already read
    /// past its end: sent once the change that undid it has applied (its follower brought in line
    /// with the queue), so a song put next by that change is the one heard.
    func followUp() {
        main.followUp(pass: followUpPass)
    }

    /// The listener's speed and pitch changed: every deck plays at them, except that a
    /// tempo-matched song (in its blend, or easing back after it) plays at its rate times them,
    /// and a parked spare at its plan's. A start registered and not handed over is undone first,
    /// before any deck's rate or latency moves; during a blend the session hears of it.
    func paceChanged(_ pace: Pace) {
        if main.paceChanged(Double(pace.speed)) {
            clearRegistration()
            followUp()
            dropPlan()
            unloadSpare()
            schedule(0)
        }
        let ease = easing.flatMap { $0.deck === player.deck ? $0 : nil }
        for deck in [player.deck, spare] + (outgoing.map { [$0] } ?? []) {
            if deck === outgoing {
                deck.setPace(pace)
            } else if let ease, deck === ease.deck {
                deck.setPace(Pace(speed: Float(ease.applied) * pace.speed, pitch: pace.pitch))
            } else if deck === spare && loadedFor != nil {
                deck.setPace(pacedFor(loadedPlan, pace))
            } else {
                deck.setPace(pace)
            }
        }
    }

    /// Runs `write` now, or, while a blend is under way, once it is over. For the app's own queue
    /// upkeep only; each write checks its own guards again when it runs.
    func deferUpkeep(_ write: @escaping () -> Void) {
        upkeep.append(write)
        idle()
    }

    func release() {
        cancelCheck()
        upkeep.removeAll()
        disarm(.edit)
        finishFade()
        tail?.job?.cancel()
        head?.job?.cancel()
        reading?.job?.cancel()
        scout?.release()
        spare.clear()
    }

    /// The song of `entryId` opens anew as another file (a version was picked for it): what was
    /// read of its end and start, the deck's reading of it and any plan with it belonged to the old
    /// file and go; they are read again from the new one.
    func forgetReads(of entryId: String) {
        if tail?.entryId == entryId {
            tail?.job?.cancel()
            tail = nil
        }
        if head?.entryId == entryId {
            head?.job?.cancel()
            head = nil
        }
        if reading?.entryId == entryId {
            reading?.job?.cancel()
            reading = nil
        }
        dropPlan()
        schedule(0)
    }

    /// Whether a crossfade is under way.
    var isBlending: Bool { outgoing != nil }

    /// Whether a blend is under way or being set up: a spare loaded, a generation registered or
    /// handed over. The app's own queue upkeep waits while it is.
    var busy: Bool { loadedFor != nil || registered >= 0 || handedOver >= 0 }

    /// Whether a blend's start is registered and not yet handed over.
    var armed: Bool { registered >= 0 }

    /// Whether the ticker must keep running: a generation is live or owes main something.
    var live: Bool { registered >= 0 || handedOver >= 0 || followUpFor >= 0 || !waiting.isEmpty || outgoing != nil || main.endWaits }

    // MARK: The ticker

    /// Every 40 ms while anything plays or a generation is live: main's lag is measured, the
    /// session's events are drained in order and each acted on once due, and both other decks'
    /// own events are heard.
    func tick() {
        let now = ProcessInfo.processInfo.systemUptime * 1000
        if lastTickMs >= 0 {
            lag.add(nowMs: Int64(now), lagMs: max(0, now - lastTickMs - blendTickerMs))
            mix.mainLagMs = lag.value(nowMs: Int64(now))
        }
        lastTickMs = now
        drainEvents()
        // A blend end that waited for the old song's kept frames, once they are out.
        if let ease = main.endDue() { finishFade(easeFromMs: ease) }
        #if DEBUG
        MixTapReport.shared.poll(player.output)
        #endif
        if let outgoing {
            for event in outgoing.poll() { if case .failed = event { deckFailed(outgoing) } }
        }
        for event in spare.poll() { if case .failed = event { deckFailed(spare) } }
    }

    /// The ticker stopped; the next tick is not late.
    func tickerStopped() { lastTickMs = -1 }

    private func drainEvents() {
        while let event = mix.events.pop() { waiting.append(event) }
        let nowBus = mix.nowBus
        let delay = Int64(mix.outputDelayMs * blendBusRate / 1_000)
        var blocked: Set<Int64> = []
        var i = 0
        while i < waiting.count {
            let event = waiting[i]
            if blocked.contains(event.generation) {
                i += 1
                continue
            }
            if blendEventDue(event, nowBus: nowBus, outputDelayFrames: delay, quietStart: quietStarts.contains(event.generation)) {
                waiting.remove(at: i)
                handle(event)
            } else {
                // A generation's events are acted on in order.
                blocked.insert(event.generation)
                i += 1
            }
        }
    }

    private func handle(_ event: BlendEvent) {
        let g = event.generation
        switch event.kind {
        case .proposed:
            proposed(g)
        case .incomingNotReady:
            guard g == registered else { return }
            playbackLog.info("automix: the next song was not ready at the start (\(event.cause.rawValue, privacy: .public))")
            forgetRegistration()
            // The spare stays parked; the rest of the song is planned again from where it is now.
            dropPlan()
            idle()
            schedule(0)
        case .moved:
            if g == registered || g == handedOver {
                playbackLog.info("automix: the blend moved to \(Int(event.ms)) ms, \(Int(event.ms2)) ms longer")
            }
        case .blendEnd:
            // Once the old song's kept frames are out, after a pause past the handover.
            if let ease = main.blendEnded(g, easeFromMs: event.ms) { finishFade(easeFromMs: ease) }
        case .cancelled:
            canceled(g, event.cause)
        case .followUpNeeded:
            main.followUpNeeded(g, pass: followUpPass())
        }
    }

    private func proposed(_ g: Int64) {
        switch main.proposed(g, from: player.deck.render, into: spare.render) {
        case .ignored:
            return
        case .handedOver:
            handOver(g)
        case .cancelled:
            // The render cancelled the start itself (at its commit deadline): the playing song goes
            // on and the rest of it is planned again.
            clearRegistration()
            dropPlan()
            idle()
            schedule(0)
        }
    }

    private func canceled(_ g: Int64, _ cause: BlendCause) {
        if g == registered {
            playbackLog.info("automix: the blend was cancelled (\(cause.rawValue, privacy: .public))")
            forgetRegistration()
            // A deck's own pause undid it: what the playing song plays after its end is told now.
            if cause == .paused { main.send(.followUp(generation: g, pass: followUpPass())) }
            dropPlan()
            unloadSpare()
            schedule(0)
        } else if let ease = main.blendEnded(g, easeFromMs: .nan) {
            finishFade(easeFromMs: ease)
        }
    }

    // MARK: The phase rule

    /// Forgets the registered generation, its word left cancelled unless main acknowledged it.
    private func forgetRegistration() {
        let wasRegistered = registered >= 0
        main.forget()
        if wasRegistered { clearRegistration() }
    }

    /// The registered generation is gone: the playing song's part in a start that did not happen,
    /// and its plan and next song.
    private func clearRegistration() {
        player.deck.processor.arm(nil)
        registeredPlan = nil
        registeredNext = nil
    }

    /// Main's side of the phase rule (`PhoneBlendMain.disarm`): a registered start is undone, a
    /// handed-over one has its old song leave.
    private func disarm(_ reason: BlendDisarm) {
        let wasRegistered = registered >= 0
        main.disarm(reason)
        if wasRegistered { clearRegistration() }
    }

    /// What the outgoing deck plays after its end: nothing when it stops there, else the follower
    /// loaded on it now, when that is the queue's next song.
    private func followUpPass() -> Int64 {
        let deck = player.deck
        let queue = player.queue
        let next = queue.nextOnEnd.map { queue.items[$0] }
        let follower = deck.following
        let matches = follower != nil && next != nil && follower?.item.entryId == next?.entryId && follower?.item.uri == next?.uri
        return outgoingFollowUp(stopAtEnd: deck.pauseAtEndOfSong, followerSerial: follower.map { Int64($0.serial) },
                                followerMatchesNext: matches)
    }

    /// A deck's player failed.
    func deckFailed(_ deck: Deck) {
        if deck === outgoing && handedOver >= 0 {
            // The old song failed mid-blend: the new one plays on alone.
            main.failedAfterHandover(.a)
        } else if deck === player.deck && handedOver >= 0 && !leaving {
            // The new song failed after the handover: the player hears it as the song on screen,
            // and the old one leaves at once.
            main.failedAfterHandover(.b)
        } else if deck === spare && loadedFor != nil {
            // The next song failed in the spare: the playing deck reaches it by its own advance
            // instead, once, and the spare is not loaded with it again for this transition.
            if let entry = loadedFor { failed.failed(entry) }
            playbackLog.info("automix: the next song could not start in the spare")
            if registered >= 0 {
                main.failedBeforeHandover(.b, followUp: followUpPass())
                clearRegistration()
            }
            unloadSpare()
            schedule(0)
        } else if deck === player.deck && registered >= 0 {
            // The playing song failed before the handover: the start is undone, and the player's
            // own failure handling plays the next song once on this deck.
            main.failedBeforeHandover(.a, followUp: blendNoAnswer)
            clearRegistration()
            dropPlan()
            unloadSpare()
        }
    }

    // MARK: The check

    private func step() {
        easeRate()
        if outgoing != nil {
            // The blend is the session's until it says it has ended.
            schedule(farCheckMs)
            return
        }
        let deck = player.deck
        if !deck.playWhenReady {
            // Paused: nothing to do until playing starts again.
            lastPosition = -1
            return
        }
        if !deck.isPlaying {
            // Loading after a seek, or a rebuffer: keep watching.
            lastPosition = -1
            schedule(nearCheckMs)
            return
        }
        let queue = player.queue
        // A live station has no end to fade out of, and is never faded into.
        guard fadeMs > 0, let next = queue.nextOnEnd, let current = queue.currentItem, !current.isRadioStation,
              !queue.items[next].isRadioStation else {
            replanAway()
            schedule(farCheckMs)
            return
        }
        let nextItem = queue.items[next]
        let position = deck.positionMs
        let speed = player.pace.speed
        if jumped(position, speed) && registered < 0 { dropPlan() }
        let currentSong = current.fadeSong(durationMs: lengthOf(deck, current))
        let nextSong = nextItem.fadeSong(durationMs: nextLengthOf(nextItem))
        let repeatOne = queue.repeatMode == .one
        let stopAtEnd = player.pauseAtEndOfSong
        // In real time: at 1.5x speed the song's last 6 seconds pass in 4.
        let remaining = Int(Double(currentSong.durationMs - position) / Double(speed))
        let plain = crossfadeLength(currentSong, nextSong, fadeMs: fadeMs, repeatOne: repeatOne, stopAtEndOfSong: stopAtEnd)
        let lengthUnknown = plain == 0 && waitsOnLength(currentSong, nextSong, fadeMs: fadeMs, repeatOne: repeatOne, stopAtEndOfSong: stopAtEnd)
        if lengthUnknown {
            // The next song's length is only known once it has opened, so open it early on the
            // spare and decide once it says.
            replanAway(keepSpare: true)
            let lead = loadLeadMs(blendMs: 0, fadeMs: fadeMs, lengthKnown: false)
            if loadedFor != entryKey(nextItem) && remaining <= lead && mayLoad(nextItem) { load(nextItem, nil) }
            schedule(remaining > lead + farCheckMs ? farCheckMs : nearCheckMs)
            return
        }
        if plain == 0 {
            // Played gaplessly, by the usual rules: nothing to plan.
            replanAway()
            schedule(farCheckMs)
            return
        }
        let key = keyFor(current, nextItem, currentSong, nextSong, repeatOne, stopAtEnd, speed, player.skipSilence)
        if registered >= 0 && key.core != planKey?.core {
            // What the blend was planned from changed before its handover (a speed, Skip silence,
            // a length): undone by the phase rule, and planned again.
            disarm(.edit)
            followUp()
            dropPlan()
        }
        if registered < 0 {
            scoutAhead(deck, current, nextItem, position, currentSong.durationMs)
            let fresh = keyFor(current, nextItem, currentSong, nextSong, repeatOne, stopAtEnd, speed, player.skipSilence)
            if fresh != planKey && readyToPlan(fresh, remaining) {
                makePlan(deck, fresh, currentSong, nextSong, position, repeatOne, stopAtEnd, speed, current, nextItem)
            }
        }
        guard let p = plan else {
            // Waiting on the scouts; they call back as soon as they are done.
            schedule(remaining > fadeMs + planLoadLeadMs * 2 ? farCheckMs : nearCheckMs)
            return
        }
        if p.kind == .gapless {
            unloadSpare()
            schedule(farCheckMs)
            return
        }
        let toStart = Int(Double(p.startMs - position) / Double(speed))
        if loadedFor != entryKey(nextItem) && toStart <= planLoadLeadMs && mayLoad(nextItem) && !upkeepPosted { load(nextItem, p) }
        if registered < 0 && loadedFor == entryKey(nextItem) && loadedPlan != p { repark(p) }
        if registered < 0 && loadedFor == entryKey(nextItem) && spare.item != nil {
            // Against the render's head, which on AirPlay is about 2 s ahead of what is heard.
            if Double(deck.headPositionMs) >= p.armAtMs(proposalLeadMs: mix.proposalLeadMs, speed: Double(speed)) - Double(nearCheckMs) {
                arm(p, deck, current, nextItem)
            }
        }
        schedule(registered >= 0 || toStart <= planLoadLeadMs + farCheckMs ? nearCheckMs : farCheckMs)
    }

    /// Whether the next song may go into the spare: not one whose spare failed for this transition.
    private func mayLoad(_ item: QueueItem) -> Bool { failed.mayLoad(entryKey(item)) }

    /// A queue entry's key: its entry id, or its song for an entry without one.
    private func entryKey(_ item: QueueItem) -> String { item.entryId ?? item.trackId }

    /// No blend for the coming transition: a registered start is undone by the phase rule, the plan
    /// forgotten and the spare unloaded.
    private func replanAway(keepSpare: Bool = false) {
        if registered >= 0 {
            disarm(.edit)
            followUp()
        }
        dropPlan()
        if !keepSpare { unloadSpare() }
    }

    /// How long the song is: what the deck measured, or the listed length (corrected where
    /// playing it before found it wrong) for a stream that has not said.
    private func lengthOf(_ deck: Deck, _ item: QueueItem) -> Int {
        songLengthMs(listedMs: lengths.lengthMs(item.trackId, listedMs: item.durationMs), playerMs: deck.durationMs)
    }

    /// How long the next song is: what the spare measured once it holds that song, else the
    /// listed length, corrected where it was found wrong.
    private func nextLengthOf(_ item: QueueItem) -> Int {
        let listed = lengths.lengthMs(item.trackId, listedMs: item.durationMs)
        let spareMs = loadedFor == entryKey(item) && spare.item.map(entryKey) == entryKey(item) ? spare.durationMs : nil
        return nextSongLengthMs(listedMs: listed, spareMs: spareMs)
    }

    /// Whether the song moved more than a plan can follow since the last check.
    private func jumped(_ position: Int, _ speed: Float) -> Bool {
        let now = nowMs
        let expected = Double(lastPosition) + Double(now - lastCheckAt) * Double(speed)
        let moved = lastPosition >= 0 && abs(Double(position) - expected) > Double(replanJumpMs)
        lastPosition = position
        lastCheckAt = now
        return moved
    }

    // MARK: Reading ahead

    /// Reads the end of the playing song once it has played a second, and the start of the next
    /// song as soon as it is known, each once per queue entry: from the server's profile of the
    /// song when it has one, else by scouting it.
    private func scoutAhead(_ deck: Deck, _ current: QueueItem, _ next: QueueItem, _ position: Int, _ lengthMs: Int) {
        guard let scout, settings.smart else { return }
        let context = player.streamContext()
        let currentEntry = current.entryId ?? current.trackId
        if tail?.entryId != currentEntry && position >= scoutAfterMs && lengthMs >= shortestAutomixSongMs {
            tail?.job?.cancel()
            let scouted = Scouted(currentEntry)
            tail = scouted
            withProfile(current, scouted, { $0.tailAnalysis() }) { [weak self] in
                let job = scout.read(current, context: context, fromMs: max(0, lengthMs - tailScoutMs), toMs: lengthMs,
                                     analyze: tailAnalyzer(current.tagTempo)) { analysis in
                    guard let self, self.tail === scouted else { return }
                    scouted.analysis = analysis
                    scouted.done = true
                    self.schedule(0)
                }
                scouted.job = job
            }
        }
        let nextEntry = next.entryId ?? next.trackId
        if head?.entryId != nextEntry {
            head?.job?.cancel()
            let scouted = Scouted(nextEntry)
            head = scouted
            withProfile(next, scouted, { $0.headAnalysis() }) { [weak self] in
                let job = scout.read(next, context: context, fromMs: 0, toMs: headScoutMs, analyze: headAnalyzer(next.tagTempo)) { analysis in
                    guard let self, self.head === scouted else { return }
                    scouted.analysis = analysis
                    scouted.done = true
                    self.schedule(0)
                }
                scouted.job = job
            }
        }
        // Once both are in, what the deck has heard of this song.
        if reading?.entryId != currentEntry && tail?.done == true && head?.done == true {
            let sound = deck.processor
            let tagBpm = current.tagTempo
            reading?.job?.cancel()
            let made = Reading(currentEntry)
            reading = made
            made.job = scout.run({ _ in sound.reading(currentEntry, tagBpm: tagBpm) }) { [weak self] value in
                guard let self, self.reading === made else { return }
                made.value = value
                made.done = true
                self.schedule(0)
            }
        }
    }

    /// Fills `scouted` from the server's profile of the song when it has one, at once when it is
    /// already here; otherwise, or once the server says it has none, runs `scoutIt`.
    private func withProfile(_ item: QueueItem, _ scouted: Scouted, _ part: @escaping (TransitionProfile) -> SectionAnalysis,
                             _ scoutIt: @escaping () -> Void) {
        guard let profiles else { return scoutIt() }
        let fill = { (profile: TransitionProfile) in
            scouted.profile = profile
            scouted.analysis = part(profile)
            scouted.done = true
        }
        if let cached = profiles.cached(item.uri) { return fill(cached) }
        let asked = profiles.request(item.uri) { [weak self] profile in
            guard let self, self.tail === scouted || self.head === scouted else { return }
            if let profile { fill(profile) } else { scoutIt() }
            self.schedule(0)
        }
        if !asked { scoutIt() }
    }

    private func keyFor(_ current: QueueItem, _ next: QueueItem, _ currentSong: FadeSong, _ nextSong: FadeSong,
                        _ repeatOne: Bool, _ stopAtEnd: Bool, _ speed: Float, _ skipSilence: Bool) -> PlanKey {
        let currentEntry = current.entryId ?? current.trackId
        let nextEntry = next.entryId ?? next.trackId
        return PlanKey(
            current: currentEntry, next: nextEntry, currentMs: currentSong.durationMs, nextMs: nextSong.durationMs,
            settings: settings, repeatOne: repeatOne, stopAtEnd: stopAtEnd, speed: speed, skipSilence: skipSilence,
            tail: tail?.entryId == currentEntry && tail?.done == true,
            head: head?.entryId == nextEntry && head?.done == true,
            reading: reading?.entryId == currentEntry && reading?.done == true,
            currentTags: Self.tagsRead(player.deck, current), nextTags: Self.tagsRead(spare, next))
    }

    /// A plan is made once everything it could use is in, or when there is no more time to
    /// wait; plain crossfades need nothing.
    private func readyToPlan(_ key: PlanKey, _ remaining: Int) -> Bool {
        if !settings.smart || scout == nil { return true }
        if key.tail && key.head && key.reading { return true }
        return remaining <= fadeMs + planLoadLeadMs + preRollMs + farCheckMs
    }

    private func makePlan(_ deck: Deck, _ key: PlanKey, _ currentSong: FadeSong, _ nextSong: FadeSong, _ position: Int,
                          _ repeatOne: Bool, _ stopAtEnd: Bool, _ speed: Float, _ current: QueueItem, _ nextItem: QueueItem) {
        let heard = reading?.entryId == key.current ? reading?.value : nil
        let served = tail?.entryId == key.current ? tail?.profile : nil
        let heardLevel = heard.flatMap { $0.heardMs >= levelHeardMs ? $0.bodyLevelDb : nil }
        let (level, tempoPrior) = heardFor(served, heardLevelDb: heardLevel, heardTempo: heard?.tempoPrior)
        // "Now" is where a start could still be decided: the song's time at the render's head
        // (never the heard position, about 2 s behind on AirPlay) plus the session's proposal lead.
        let now = Int(blendReplanNowMs(outgoingAtHeadMs: Double(deck.headPositionMs), proposalLeadFrames: mix.proposalLeadFrames,
                                       sampleRate: Int(blendBusRate), speed: Double(speed)))
        let context = TransitionContext(
            nowMs: now, playedMs: heard.map { Int(Double($0.heardMs) * Double(speed)) } ?? position,
            repeatOne: repeatOne, stopAtEndOfSong: stopAtEnd, pace: Double(speed), skipSilence: player.skipSilence,
            currentGenre: current.genre, nextGenre: nextItem.genre, bodyLevelDb: level, tempoPrior: tempoPrior,
            incomingGainDb: incomingGainDb(deck, current, nextItem))
        let tailAnalysis = tail?.entryId == key.current ? tail?.analysis : nil
        let headAnalysis = head?.entryId == key.next ? head?.analysis : nil
        let made = planTransition(current: currentSong, next: nextSong, tail: tailAnalysis, head: headAnalysis,
                                  settings: settings.atSpeed(speed), context: context)
        planKey = key
        plan = made
        if made.kind == .gapless { return }
        let measured = tailAnalysis.map { analysis in level.map(analysis.withBodyLevel) ?? analysis }
        outgoingGate = measured?.features.gateDb ?? level.map { max(silenceFloorDb, $0 - silenceBelowBodyDb) }
    }

    /// How much louder the decks will play the next song than the one on now, in dB: the one on
    /// now as its deck judged it (with its own tags once the deck has read them), the next as it
    /// will be judged following it. 0 without the sound shaping.
    private func incomingGainDb(_ deck: Deck, _ current: QueueItem, _ next: QueueItem) -> Double {
        guard let sound else { return 0 }
        let queue = player.queue
        let previous = queue.previousForSkip.map { soundSong(queue.items[$0]) }
        let currentSong = soundSong(current)
        let now = Self.ownLoudness(deck, current) ?? sound.queuedLoudness(currentSong, after: previous)
        let following = Self.ownLoudness(spare, next) ?? sound.queuedLoudness(soundSong(next), after: currentSong, previousLoudness: now)
        return blendIncomingGainDb(sound.settings.read().settings, current: now, next: following)
    }

    /// How `deck` judges `item`'s loudness when it holds it: as it was set up, with the file's own
    /// tags once the song has opened and they carry a gain.
    private static func ownLoudness(_ deck: Deck, _ item: QueueItem) -> SongLoudness? {
        guard let source = deck.current, source.item.entryId == item.entryId, source.item.uri == item.uri, let setUp = source.setUp else {
            return nil
        }
        return setUp.loudness(tags: source.loudness)
    }

    /// Whether `deck` holds `item` and has opened it (its own tags are known).
    private static func tagsRead(_ deck: Deck, _ item: QueueItem) -> Bool {
        guard let source = deck.current, source.item.entryId == item.entryId, source.item.uri == item.uri else { return false }
        return source.opened
    }

    // MARK: The spare

    /// The spare gets the next song, parked just before where it comes in (its start when there is
    /// no plan yet): silent and consuming nothing until the session starts it, at the player's
    /// level so its first frames are heard at their blend gain.
    private func load(_ next: QueueItem, _ plan: TransitionPlan?) {
        let from = plan?.parkAtMs() ?? 0
        spare.parked = true
        spare.setPace(pacedFor(plan, player.pace))
        spare.skipSilence = player.skipSilence
        spare.volume = player.sleepFade
        spare.playWhenReady = false
        spare.pauseAtEndOfSong = false
        spare.startAfterMs = player.deck.startAfterMs
        armIncoming(plan, next)
        spare.load(player.makeSource(next, startMs: from))
        loadedFor = entryKey(next)
        loadedUri = next.uri
        loadedPlan = plan
    }

    /// A new plan for a song already loaded: armed again and parked again where it now comes in,
    /// unless it is there already (a move would make it load again).
    private func repark(_ plan: TransitionPlan) {
        guard let item = spare.item else { return }
        armIncoming(plan, item)
        spare.setPace(pacedFor(plan, player.pace))
        if needsRepark(spare.positionMs, plan.parkAtMs()) { spare.seek(toMs: plan.parkAtMs()) }
        loadedPlan = plan
    }

    private func pacedFor(_ plan: TransitionPlan?, _ pace: Pace) -> Pace {
        guard let rate = plan?.beatMatchRate else { return pace }
        return Pace(speed: Float(rate) * pace.speed, pitch: pace.pitch)
    }

    private func armIncoming(_ plan: TransitionPlan?, _ item: QueueItem) {
        spare.processor.arm(plan.map { DeckTransition(entryId: item.entryId, plan: $0, incoming: true) })
    }

    /// Plays the parked spare (silent, consuming nothing until the start), then registers the blend
    /// with the session under a new generation, so the spare is never seen paused at its
    /// registration. The session's events are drained first, so none of an earlier generation is
    /// left behind the new one.
    private func arm(_ p: TransitionPlan, _ deck: Deck, _ current: QueueItem, _ nextItem: QueueItem) {
        drainEvents()
        // Main hears of an interrupt up to the proposal lead late: its window covers that too.
        let quiet = commitsBeforeAck(p, outputDelayMs: mix.outputDelayMs, speed: Double(player.pace.speed), lateMs: mix.proposalLeadMs)
        let entry = current.entryId ?? current.trackId
        deck.processor.arm(DeckTransition(entryId: entry, plan: p, incoming: false, gateDb: outgoingGate))
        let outgoingEndMs = Double(lengthOf(deck, current))
        let speed = Double(player.pace.speed)
        let g = main.register(from: deck.render, into: spare.render, entryMs: Double(p.entryMs)) { g in
            BlendArm(generation: g, startMs: Double(p.startMs), overlapMs: Double(p.overlapMs), latestEndMs: Double(p.latestEndMs),
                     outgoingEndMs: outgoingEndMs, lateMoveBars: p.lateMoveBars, barMs: p.barMs(), holdShare: p.holdShare,
                     speed: speed, bothHeardStartMs: Double(p.bothHeardStartMs), bothHeardEndMs: Double(p.bothHeardEndMs),
                     lockMs: p.lockMs, driftGap: p.driftGap, commitBeforeAck: quiet)
        }
        registeredPlan = p
        registeredNext = (entryId: nextItem.entryId, uri: nextItem.uri)
        if quiet { quietStarts.insert(g) }
        #if DEBUG
        MixTapReport.shared.planned(generation: g, startMs: Double(p.startMs), entryMs: Double(p.entryMs), rate: p.incomingRate(),
                                    pace: Double(player.pace.speed), outgoing: deck.render.index)
        #endif
        // The playing song's follower is there before the start, whatever its own rule.
        player.loadFollowingNow()
    }

    /// The session proposed the start and main agreed: the player hands over to the spare now, so
    /// everything from here lands on the song on screen. The handover is marked first, so a
    /// listener writing inside it (a sleep timer's end of song) never undoes it.
    private func handOver(_ g: Int64) {
        let p = registeredPlan
        let next = registeredNext
        registeredPlan = nil
        registeredNext = nil
        let from = player.deck
        let into = spare
        outgoing = from
        blending = p
        // Once the blend is over, the old deck is the spare for next time.
        spare = from
        loadedFor = nil
        loadedUri = nil
        loadedPlan = nil
        plan = nil
        planKey = nil
        if let p { playbackLog.info("automix: \(p.reason, privacy: .public)") }
        if let p, let rate = p.beatMatchRate { easing = Easing(deck: into, entryId: next?.entryId, plan: p, rate: rate) }
        player.handOver(into: into, from: from)
    }

    /// Eases a tempo-matched song back to its own speed on its own song time, toward the
    /// listener's pace read at every step, until the deck is no longer that song.
    private func easeRate() {
        guard let ease = easing else { return }
        let pace = player.pace
        if ease.deck !== player.deck {
            easing = nil
            return
        }
        if ease.deck.item?.entryId != ease.entryId {
            ease.deck.setPace(pace)
            easing = nil
            return
        }
        let rate = blendIncomingRate(rate: ease.rate, incomingMs: Double(ease.deck.positionMs), entryMs: Double(ease.plan.entryMs),
                                     easeFromMs: ease.easeFromMs)
        if rate == 1 {
            ease.applied = 1
            ease.deck.setPace(pace)
            easing = nil
            return
        }
        if abs(rate - ease.applied) >= rateStep {
            ease.applied = rate
            ease.deck.setPace(Pace(speed: Float(rate) * pace.speed, pitch: pace.pitch))
        }
    }

    /// The blend is over (the session said so, or the old song failed): the old deck stops and
    /// becomes the spare. `easeFromMs` is where the new song's easing starts; with none, it starts
    /// now.
    private func finishFade(easeFromMs: Double = .nan) {
        guard let from = outgoing else { return }
        let g = handedOver
        let plan = blending
        outgoing = nil
        blending = nil
        quietStarts.remove(g)
        if let easing { easing.easeFromMs = easeFromMs.isNaN ? easing.sinceEntryMs : easeFromMs }
        if let plan, plan.overlapMs > 0, !easeFromMs.isNaN {
            let progress = easeFromMs * plan.incomingRate() / Double(plan.overlapMs)
            if progress < 0.95 { playbackLog.info("automix: blend cut short at \(Int(min(max(progress, 0), 1) * 100))%") }
        }
        from.processor.arm(nil)
        from.clear()
        from.pauseAtEndOfSong = false
        from.parked = true
        main.finished()
        lastPosition = -1
        player.endFade()
        idle()
        schedule(0)
    }

    /// Forgets the plan.
    private func dropPlan() {
        plan = nil
        planKey = nil
    }

    private func unloadSpare() {
        guard loadedFor != nil else { return }
        loadedFor = nil
        loadedUri = nil
        loadedPlan = nil
        spare.processor.arm(nil)
        spare.clear()
        spare.parked = true
        idle()
    }

    /// The upkeep waiting for the blend goes once nothing is under way, in one message, checked
    /// again then.
    private func idle() {
        guard !busy, !upkeep.isEmpty, !upkeepPosted else { return }
        upkeepPosted = true
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { self?.applyUpkeep() }
        }
    }

    private func applyUpkeep() {
        upkeepPosted = false
        guard !busy else { return }
        let writes = upkeep
        upkeep.removeAll()
        for write in writes { write() }
        schedule(0)
    }

    private func schedule(_ delayMs: Int) {
        cancelCheck()
        // A song easing back to its own speed is looked at often.
        let delay = easing != nil ? min(delayMs, nearCheckMs * 5) : delayMs
        let work = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated { self?.step() }
        }
        check = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(delay), execute: work)
    }

    private func cancelCheck() {
        check?.cancel()
        check = nil
    }
}
