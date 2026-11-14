import AllocCount
import Foundation
import Testing
@testable import OctoKit

/// A fake time-pitch stage in front of a deck: it pulls the deck's source frames in irregular
/// chunks (and sometimes nothing for a cycle, then more), always far enough that bus frame b is
/// fed by then. Source frame k comes out at bus frame `anchor + latency + k / rate`, which is the
/// truth the deck's clock must reproduce.
private struct FakeStage {
    var anchor: Double
    var latency: Double
    var rate: Double
    var seed: UInt64

    func trueBus(_ k: Int64) -> Double { anchor + latency + Double(k) / rate }

    /// The chunks it pulls next to be fed through bus frame `bus` plus a random lead, from source
    /// frame `from`.
    mutating func chunks(through bus: Double, from: Int64) -> [Int] {
        let lead = Double(next() % 600)
        let need = Int64(((bus + lead - anchor - latency) * rate).rounded(.up))
        var left = Int(max(0, need - from))
        var out: [Int] = []
        while left > 0 {
            let chunk = min(left, 1 + Int(next() % 700))
            out.append(chunk)
            left -= chunk
        }
        return out
    }

    /// Pulls those chunks into `clock`.
    mutating func pull(through bus: Double, _ clock: DeckClock) {
        for chunk in chunks(through: bus, from: clock.source) { clock.pulled(chunk) }
    }

    mutating func next() -> UInt64 {
        seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return seed >> 33
    }
}

private func plan(kind: TransitionKind = .blend, startMs: Int = 100_000, entryMs: Int = 2_000, overlapMs: Int = 8_000,
                  holdShare: Double = 0, riseShare: Double = 0, rate: Double? = nil, curve: FadeCurve? = nil) -> TransitionPlan {
    TransitionPlan(startMs: startMs, entryMs: entryMs, overlapMs: overlapMs, kind: kind, k: 0.5, filterStrength: 0,
                   beatMatchRate: rate, beatMs: nil, beatAnchorMs: nil, late: false, headroomDb: 1, reason: "test", curve: curve,
                   holdShare: holdShare, riseShare: riseShare, latestEndMs: startMs + overlapMs + 4_000, lateMoveBars: lateMoveUnbounded)
}

@Suite struct PhoneMixClockTests {
    /// Every case the device sees: rates 1 and 0.94, the stage's latency 0 and 50 ms, the
    /// hardware at 48 and 44.1 kHz.
    static let cases: [(rate: Double, latency: Double, hardware: Double)] = [
        (1, 0, 48_000), (1, 2_400, 48_000), (0.94, 0, 48_000), (0.94, 2_400, 48_000), (1, 2_400, 44_100), (0.94, 2_400, 44_100),
    ]

    @Test func theBusClockCountsHardwareFramesAsBusFramesAndOnlyGrows() {
        var clock = BusClock()
        for _ in 0..<100 { clock.passed(1_024, hardwareRate: 44_100) }
        #expect(abs(clock.exact - 102_400 * 48_000 / 44_100) < 1e-6)
        let before = clock.bus
        // A rebuilt unit at another rate carries on from there.
        clock.passed(480, hardwareRate: 48_000)
        #expect(clock.bus == before + 480)
    }

    @Test func eachDecksMappingPlacesEverySourceFrameOnTheBusWhereItIsHeard() {
        for (n, c) in Self.cases.enumerated() {
            let clock = DeckClock(bus: 1_000, rate: c.rate, latency: c.latency)
            var stage = FakeStage(anchor: 1_000, latency: c.latency, rate: c.rate, seed: UInt64(7 + n))
            var bus = BusClock()
            for _ in 0..<400 {
                stage.pull(through: 1_000 + bus.exact + 1_024 * BusClock.perHardwareFrame(c.hardware), clock)
                bus.passed(1_024, hardwareRate: c.hardware)
            }
            for k in stride(from: Int64(0), to: clock.source, by: 997) {
                #expect(abs(Double(clock.bus(ofSource: k)) - stage.trueBus(k)) <= 1, "case \(n) frame \(k)")
                let back = clock.source(ofBus: clock.bus(ofSource: k))
                #expect(abs(back - k) <= 1, "case \(n) frame \(k) back \(back)")
            }
        }
    }

    @Test func aRateChangeStartsANewPieceAndEarlierFramesKeepTheirPlace() {
        let clock = DeckClock(bus: 0, rate: 0.94, latency: 2_400)
        clock.pulled(48_000)
        let before = clock.bus(ofSource: 24_000)
        // The incoming song eased back to its own speed: the stage is bypassed, no latency.
        clock.set(rate: 1, latency: 0)
        clock.pulled(48_000)
        #expect(clock.bus(ofSource: 24_000) == before)
        let joint = 48_000 / 0.94
        #expect(abs(Double(clock.bus(ofSource: 48_000 + 4_800)) - (joint + 4_800)) <= 1)
        #expect(abs(clock.source(ofBus: clock.bus(ofSource: 60_000)) - 60_000) <= 1)
    }

    @Test func renderedSilenceCountsSoAParkedDecksMappingNeverStops() {
        let clock = DeckClock(bus: 0, rate: 1, latency: 0)
        // A parked deck hands the stage silence every cycle: the frames still count.
        for _ in 0..<10 { clock.pulled(1_024) }
        #expect(clock.renderedBus == 10_240)
    }

    @Test func aRestartAnchorsTheDeckAgainOnTheBus() {
        let clock = DeckClock(bus: 0, rate: 1, latency: 2_400)
        clock.pulled(96_000)
        // The engine stopped for a new output and started again at bus frame 200 000.
        clock.reset(atBus: 200_000, rate: 1, latency: 2_400)
        #expect(clock.bus(ofSource: 96_000) == 202_400)
        #expect(clock.source(ofBus: 202_400 + 480) == 96_480)
    }

    @Test func theIncomingSongStartsOnTheExactBusFrameThroughEitherLatencyAndRate() {
        for (n, c) in Self.cases.enumerated() {
            let stage = DeckBlendStage(clock: DeckClock(bus: 0, rate: c.rate, latency: c.latency))
            let session = BlendSession(word: CommitWord(), timing: phoneBlendTiming(latencyFrames: 2_400, cycleFrames: 1_024, tickerMs: 40,
                                                                                    mainLagMs: 50, ringLeadMs: 50, sinkBufferMs: 50))
            stage.armIncoming(session, entryMs: 2_000)
            var fake = FakeStage(anchor: 0, latency: c.latency, rate: c.rate, seed: UInt64(100 + n))
            let s: Int64 = 96_013
            var began: Int64 = -1
            var bus = 0.0
            var decided = false
            while began < 0 && bus < 200_000 {
                if !decided && bus > 30_000 {
                    stage.take(BlendAction(kind: .startB, frame: s, value: 0, ms: 0), as: .incoming)
                    stage.take(BlendAction(kind: .commitB, frame: s, value: 0, ms: 0), as: .incoming)
                    decided = true
                }
                for chunk in fake.chunks(through: bus + 1_024 * BusClock.perHardwareFrame(c.hardware), from: stage.clock.source) {
                    // The render crosses what is due at each frame it is about to hand on.
                    var left = chunk
                    while left > 0 {
                        stage.crossDue(nil)
                        if stage.startingNow {
                            if began < 0 { began = stage.clock.source }
                            stage.startingNow = false
                        }
                        let step = min(left, stage.framesUntilNext(left))
                        stage.clock.pulled(step)
                        left -= step
                    }
                }
                bus += 1_024 * BusClock.perHardwareFrame(c.hardware)
            }
            #expect(began >= 0, "case \(n)")
            #expect(abs(fake.trueBus(began) - Double(s)) <= 1, "case \(n): first frame heard at \(fake.trueBus(began)), S \(s)")
        }
    }

    @Test func anActionLandsOnTheSourceFrameHeardAtItsBusFrame() {
        let clock = DeckClock(bus: 0, rate: 0.94, latency: 2_400)
        let stage = DeckBlendStage(clock: clock)
        let session = BlendSession(word: CommitWord(), timing: phoneBlendTiming(latencyFrames: 2_400, cycleFrames: 1_024, tickerMs: 40,
                                                                                mainLagMs: 50, ringLeadMs: 50, sinkBufferMs: 50))
        stage.armIncoming(session, entryMs: 0)
        stage.take(BlendAction(kind: .startB, frame: 10_000, value: 0, ms: 0), as: .incoming)
        stage.take(BlendAction(kind: .commitB, frame: 10_000, value: 0, ms: 0), as: .incoming)
        stage.take(BlendAction(kind: .holdB, frame: 30_000, value: 0, ms: 0), as: .incoming)
        stage.take(BlendAction(kind: .placeB, frame: 50_000, value: 960, ms: 0), as: .incoming)
        var heldAt: Int64 = -1
        var placedAt: Int64 = -1
        while clock.source < 60_000 {
            stage.crossDue(nil)
            if stage.held && heldAt < 0 { heldAt = clock.source }
            if !stage.held && heldAt >= 0 && placedAt < 0 { placedAt = clock.source }
            clock.pulled(stage.framesUntilNext(37))
        }
        #expect(abs(Double(clock.bus(ofSource: heldAt)) - 30_000) <= 1)
        #expect(abs(Double(clock.bus(ofSource: placedAt)) - 50_000) <= 1)
    }

    @Test func thePhoneTimingIsTheHorizonTheCommitDeadlineAndTheProposalLead() {
        let t = phoneBlendTiming(latencyFrames: 2_400, cycleFrames: 1_024, tickerMs: 40, mainLagMs: 50, ringLeadMs: 70, sinkBufferMs: 30)
        #expect(t.placementHorizonFrames == 3_424)
        #expect(t.commitAtFrames == 4_448)
        // The commit deadline plus the ticker's 40 ms plus main's 50 ms.
        #expect(t.proposeAtFrames == 4_448 + 4_320)
        #expect(t.proposalLeadFrames == t.proposeAtFrames)
        #expect(t.sampleRate == 48_000)
    }
}

@Suite struct PhoneMixLimiterTests {
    func noise(_ frames: Int, seed: UInt64) -> [Float] {
        var s = seed
        return (0..<frames).map { _ in
            s = s &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Float(s >> 40) / Float(1 << 24) * 2 - 1
        }
    }

    func run(_ limiter: MixLimiter, _ left: inout [Float], _ right: inout [Float], firstBus: Double, chunk: Int = 512) {
        var at = 0
        while at < left.count {
            let n = min(chunk, left.count - at)
            left.withUnsafeMutableBufferPointer { l in
                right.withUnsafeMutableBufferPointer { r in
                    limiter.process([l.baseAddress! + at, r.baseAddress! + at], frames: n, firstBus: firstBus + Double(at), busPerFrame: 1)
                }
            }
            at += n
        }
    }

    @Test func theSumOfTwoFullScaleDecksStaysUnderTheCeilingInTheWindow() {
        let limiter = MixLimiter(sampleRate: 48_000, channels: 2)
        limiter.windowOn(fromBus: 0)
        let a = noise(48_000, seed: 1)
        let b = noise(48_000, seed: 2)
        var left = zip(a, b).map { $0 + $1 }
        var right = left.map { -$0 }
        run(limiter, &left, &right, firstBus: 0)
        let ceiling = Float(pow(10, -1.0 / 20))
        #expect(left.dropFirst(limiter.latencyFrames).allSatisfy { abs($0) <= ceiling + 1e-6 })
    }

    @Test func outsideTheWindowTheSumPassesAtUnityOnlyDelayed() {
        let limiter = MixLimiter(sampleRate: 48_000, channels: 2)
        // Registered: the window opens at the start, well after the run-up.
        limiter.windowOn(fromBus: 1_000_000)
        let song = noise(24_000, seed: 3).map { $0 * 1.5 }
        var left = song
        var right = song
        run(limiter, &left, &right, firstBus: 0)
        #expect(Array(left.dropFirst(limiter.latencyFrames)) == Array(song.prefix(song.count - limiter.latencyFrames)))
    }

    @Test func turningOffMidReductionNeverStepsAndLandsOnUnity() {
        let limiter = MixLimiter(sampleRate: 48_000, channels: 1)
        limiter.windowOn(fromBus: 0)
        limiter.windowOff(fromBus: 4_800)
        var levels: [Float] = []
        var x: [Float] = noise(48_000 * 2, seed: 4).map { $0 * 3 }
        for i in 0..<x.count {
            x.withUnsafeMutableBufferPointer { p in limiter.process([p.baseAddress! + i], frames: 1, firstBus: Double(i), busPerFrame: 1) }
            levels.append(limiter.level)
        }
        var rise: Float = 0
        for i in 4_900..<levels.count { rise = max(rise, levels[i] - levels[i - 1]) }
        // Back up by its release only, never a jump, and exactly unity within the release's snap.
        #expect(rise < 0.002, "\(rise)")
        #expect(levels.last == 1)
    }

    @Test func aRebuildAtANewRateKeepsTheWindowsFramesInBusTime() {
        let limiter = MixLimiter(sampleRate: 44_100, channels: 2)
        limiter.windowOn(fromBus: 48_000)
        // At 44.1 kHz a hardware frame lasts 48/44.1 bus frames: the window opens at hardware frame 44 100.
        #expect(!limiter.limits(atBus: 44_099 * 48_000 / 44_100.0))
        #expect(limiter.limits(atBus: 44_100 * 48_000 / 44_100.0))
        #expect(limiter.latencyFrames == 66)
    }
}

@Suite struct PhoneMixRulesTests {
    @Test func aStartSilentThroughTheExposureCommitsBeforeTheAckOnAirPlayAndALoudOneWaits() {
        // A blend whose incoming song is still in its silent lead-in for the first second (the
        // 2 s delay less the 1 s lead) with the outgoing song at its own level: committed early.
        var held = plan(kind: .blend, overlapMs: 12_000, holdShare: 0.5)
        held.headroomDb = 0
        #expect(commitsBeforeAck(held, outputDelayMs: 2_000))
        // A short crossfade: the incoming song is loud at once, so it waits for the ack.
        #expect(!commitsBeforeAck(fixedCrossfadePlan(lenA: 200_000, length: 2_000, why: "test"), outputDelayMs: 2_000))
        // An intro's incoming song rises to its own level over its first bar: it waits too.
        #expect(!commitsBeforeAck(plan(kind: .intro, overlapMs: 14_000, holdShare: 0.6, riseShare: 0.15), outputDelayMs: 2_000))
        // A hold too short for the exposure, and a route under a second late, wait.
        #expect(!commitsBeforeAck(held, outputDelayMs: 9_000))
        #expect(!commitsBeforeAck(held, outputDelayMs: 900))
    }

    @Test func mainActsOnAProposalAtOnceAndOnWhatTheScreenShowsOnceItIsHeard() {
        func event(_ kind: BlendEventKind, _ frame: Int64) -> BlendEvent {
            BlendEvent(kind: kind, generation: 1, frame: frame, ms: 0, ms2: 0, cause: .none)
        }
        #expect(blendEventDue(event(.proposed, 100_000), nowBus: 0, outputDelayFrames: 9_600, quietStart: false))
        // AirPlay's quiet start: acknowledged a second before it is heard.
        #expect(!blendEventDue(event(.proposed, 100_000), nowBus: 100_000 + 96_000 - 48_001, outputDelayFrames: 96_000, quietStart: true))
        #expect(blendEventDue(event(.proposed, 100_000), nowBus: 100_000 + 96_000 - 48_000, outputDelayFrames: 96_000, quietStart: true))
        #expect(!blendEventDue(event(.blendEnd, 100_000), nowBus: 105_000, outputDelayFrames: 9_600, quietStart: false))
        #expect(blendEventDue(event(.blendEnd, 100_000), nowBus: 109_600, outputDelayFrames: 9_600, quietStart: false))
        #expect(blendEventDue(event(.cancelled, 100_000), nowBus: 0, outputDelayFrames: 9_600, quietStart: false))
        #expect(blendEventDue(event(.incomingNotReady, 100_000), nowBus: 0, outputDelayFrames: 9_600, quietStart: false))
    }

    @Test func mainsLagHasA50MsFloorUntil40SamplesAndGrowsAtOnce() {
        var lag = LagWindow()
        #expect(lag.value(nowMs: 0) == 50)
        for i in 0..<39 { lag.add(nowMs: Int64(i * 40), lagMs: 5) }
        #expect(lag.value(nowMs: 1_600) == 50)
        lag.add(nowMs: 1_600, lagMs: 5)
        #expect(lag.value(nowMs: 1_600) == 15)
        // An 80 ms main stall grows it within one sample.
        lag.add(nowMs: 1_640, lagMs: 80)
        #expect(lag.value(nowMs: 1_640) == 90)
        // Ten minutes on it has aged out.
        #expect(lag.value(nowMs: 1_640 + 11 * 60_000) == 50)
    }

    @Test func wantedMusicLeavesOutAFinishedQueueAndASongStoppedAtItsEnd() {
        #expect(wantedMusic(playWhenReady: true, ended: false, stoppedAtEnd: false))
        // A call during buffering: playing was asked for, so it resumes.
        #expect(!wantedMusic(playWhenReady: true, ended: true, stoppedAtEnd: false))
        #expect(!wantedMusic(playWhenReady: false, ended: false, stoppedAtEnd: true))
        #expect(!wantedMusic(playWhenReady: false, ended: false, stoppedAtEnd: false))
    }

    @Test func aDryOutFadesOnlyWhileMoreIsTrulyLate() {
        // A gapless join, a song's own end and a ring the producer just refilled never dip.
        #expect(!DeckFlow.fadesOut(decodedToEnd: false, hasFollowing: true, deliveredSinceLast: false))
        #expect(!DeckFlow.fadesOut(decodedToEnd: true, hasFollowing: false, deliveredSinceLast: false))
        #expect(!DeckFlow.fadesOut(decodedToEnd: false, hasFollowing: false, deliveredSinceLast: true))
        #expect(DeckFlow.fadesOut(decodedToEnd: false, hasFollowing: false, deliveredSinceLast: false))
        #expect(DeckFlow.fadeOutGain(0, of: 960) < 1 && DeckFlow.fadeOutGain(959, of: 960) == 0)
        #expect(DeckFlow.rampInGain(959, of: 960) == 1 && DeckFlow.rampInGain(0, of: 960) > 0)
        #expect(DeckFlow.rampFrames() == 960)
    }

    /// A deck fed at exactly real time after a stall, in 2048-frame chunks handed over whenever
    /// the decoder happens to wake (some cycles none, then several), rendered 1024 frames a cycle:
    /// counts the times it runs dry again once it has rejoined, over 12 s, rejoining by `rule`.
    func dryOuts(rejoin rule: (Int) -> Bool) -> Int {
        var waiting = 0
        var produced = 0
        var playing = false
        var dry = 0
        var seed: UInt64 = 5
        for _ in 0..<(48_000 * 12 / 1_024) {
            produced += 1_024
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            if (seed >> 33) % 4 < 3 {
                while produced >= 2_048 {
                    produced -= 2_048
                    waiting += 2_048
                }
            }
            if !playing {
                if rule(waiting) { playing = true }
                continue
            }
            if waiting < 1_024 {
                dry += 1
                playing = false
            } else {
                waiting -= 1_024
            }
        }
        return dry
    }

    @Test func aRealTimeFeedAfterAStallPlaysOnContinuously() {
        let hysteresis = dryOuts { DeckFlow.rejoins(waitingFrames: $0, cycleFrames: 1_024, bufferFrames: 24_000, ended: false) }
        #expect(hysteresis == 0)
        // The guard: rejoining on one render cycle chops.
        #expect(dryOuts { $0 >= 1_024 } > 0)
    }

    @Test func theOutgoingSongsEndFollowsTheBlendsPhase() {
        // No blend: its own advance, or its end.
        #expect(outgoingEnd(registered: false, started: false, holdingForAnswer: false, follow: nil, followerSerial: 7) == .moveOn)
        #expect(outgoingEnd(registered: false, started: false, holdingForAnswer: false, follow: nil, followerSerial: nil) == .end)
        // Before the start: moving on is the plan's cancel; with nothing following, the engine's
        // own ended path plays the next song.
        #expect(outgoingEnd(registered: true, started: false, holdingForAnswer: false, follow: nil, followerSerial: 7) == .moveOn)
        #expect(outgoingEnd(registered: true, started: false, holdingForAnswer: false, follow: nil, followerSerial: nil) == .end)
        // From the start on it neither moves on nor ends by itself.
        #expect(outgoingEnd(registered: true, started: true, holdingForAnswer: false, follow: nil, followerSerial: 7) == .hold)
        #expect(outgoingEnd(registered: true, started: true, holdingForAnswer: false, follow: nil, followerSerial: nil) == .hold)
        // An undone start: it holds until main names what follows, and only that follower plays.
        #expect(outgoingEnd(registered: false, started: false, holdingForAnswer: true, follow: nil, followerSerial: 7) == .hold)
        #expect(outgoingEnd(registered: false, started: false, holdingForAnswer: false, follow: 7, followerSerial: 7) == .moveOn)
        #expect(outgoingEnd(registered: false, started: false, holdingForAnswer: false, follow: 7, followerSerial: 8) == .end)
        #expect(outgoingEnd(registered: false, started: false, holdingForAnswer: false, follow: blendPassNone, followerSerial: 7) == .end)
        #expect(outgoingEnd(registered: false, started: false, holdingForAnswer: false, follow: blendPassHeld, followerSerial: 7) == .moveOn)
    }

    @Test func theFollowUpNamesTheFollowerOnlyWhenItIsTheQueuesNext() {
        #expect(outgoingFollowUp(stopAtEnd: true, followerSerial: 7, followerMatchesNext: true) == blendPassNone)
        #expect(outgoingFollowUp(stopAtEnd: false, followerSerial: 7, followerMatchesNext: true) == 7)
        #expect(outgoingFollowUp(stopAtEnd: false, followerSerial: 7, followerMatchesNext: false) == blendPassNone)
        #expect(outgoingFollowUp(stopAtEnd: false, followerSerial: nil, followerMatchesNext: false) == blendPassNone)
    }

    @Test func anEditThatLeavesTheSongOnNowAndTheNextSongAsTheyWereKeepsTheBlend() {
        let next = (entryId: Optional("b"), uri: "octo://b")
        // Songs added, moved or taken out beyond the next song.
        #expect(editKeepsBlend(currentBefore: "a", currentAfter: "a", registeredNext: next, nextAfter: next))
        // Play next X: the next song changed.
        #expect(!editKeepsBlend(currentBefore: "a", currentAfter: "a", registeredNext: next, nextAfter: (entryId: "x", uri: "octo://x")))
        // The same entry with another file.
        #expect(!editKeepsBlend(currentBefore: "a", currentAfter: "a", registeredNext: next, nextAfter: (entryId: "b", uri: "octo://b2")))
        #expect(!editKeepsBlend(currentBefore: "a", currentAfter: "c", registeredNext: next, nextAfter: next))
        #expect(!editKeepsBlend(currentBefore: "a", currentAfter: "a", registeredNext: nil, nextAfter: next))
    }

    @Test func theIncomingSongsGainIsWhatTheDecksApplyToEachSong() {
        var settings = SoundSettings()
        settings.replayGain = .track
        // A tagged -8 dB, B untagged after it: B plays 8 dB louder than A.
        let a = SongLoudness(ReplayGainTags.stored(trackGain: -8, trackPeak: nil, albumGain: nil, albumPeak: nil), followsSameAlbum: false)
        let b = queuedSongLoudness(stored: nil, album: "b", afterAlbum: "a")
        #expect(abs(blendIncomingGainDb(settings, current: a, next: b) - 8) < 1e-9)
        // ReplayGain off: both at their own level.
        settings.replayGain = .off
        #expect(blendIncomingGainDb(settings, current: a, next: b) == 0)
        // A queued song follows the one on now from the same album only with a known album.
        #expect(queuedSongLoudness(stored: nil, album: "a", afterAlbum: "a").followsSameAlbum)
        #expect(!queuedSongLoudness(stored: nil, album: nil, afterAlbum: nil).followsSameAlbum)
        // The same song again (repeat one) keeps the answer it had, as the album run does.
        #expect(queuedSongLoudness(stored: nil, album: nil, afterAlbum: nil, songId: "s", afterSongId: "s", afterFollows: true).followsSameAlbum)
        #expect(!queuedSongLoudness(stored: nil, album: "a", afterAlbum: "a", songId: "s", afterSongId: "s", afterFollows: false).followsSameAlbum)
    }

    @Test func pauseAtTheEndOfTheSongSetInTheRunUpUndoesTheStartBeforeItIsWritten() {
        // Registered and not yet handed over: undone first, then written, then followed up.
        #expect(endOfSongWrite(old: false, new: true, registered: true) == .disarmThenWrite)
        #expect(endOfSongWrite(old: true, new: false, registered: true) == .disarmThenWrite)
        // No start registered (or handed over already): only the write.
        #expect(endOfSongWrite(old: false, new: true, registered: false) == .write)
        // The same value again touches nothing.
        #expect(endOfSongWrite(old: true, new: true, registered: true) == .skip)
    }

    @Test func onlyAnEditThatLetsGoOfTheSongOnNowDropsTheKeptFrames() {
        // Songs added, moved or taken out around the song on now: the kept frames stay.
        #expect(!editReleases(currentBefore: "a", currentAfter: "a"))
        // Another song on now, the song on now taken out, the queue emptied.
        #expect(editReleases(currentBefore: "a", currentAfter: "b"))
        #expect(editReleases(currentBefore: nil, currentAfter: "b"))
        #expect(editReleases(currentBefore: "a", currentAfter: nil))
    }

    @Test func theSpareIsParkedJustBeforeItsEntryAndArmedAheadOfItsLead() {
        let p = plan(startMs: 100_000, entryMs: 2_000)
        #expect(p.parkAtMs() == 1_900)
        #expect(plan(entryMs: 50).parkAtMs() == 0)
        // At 1x the run-up starts 2.5 s before the start; at 2x a 1 s lead plus 0.5 s needs 3 s.
        #expect(p.armAtMs(proposalLeadMs: 200, speed: 1) == 97_500)
        #expect(p.armAtMs(proposalLeadMs: 1_000, speed: 2) == 97_000)
    }
}

@Suite struct PhoneMixStageTests {
    func timing() -> BlendTiming {
        phoneBlendTiming(latencyFrames: 0, cycleFrames: 1_024, tickerMs: 40, mainLagMs: 50, ringLeadMs: 50, sinkBufferMs: 50)
    }

    /// One render cycle: both decks hand on 1024 frames (crossing what is due), then the session
    /// steps to the lower of their rendered bus frames and its actions go to both stages.
    func cycle(_ session: BlendSession, _ a: DeckBlendStage, _ b: DeckBlendStage, head: Int64, outgoingMs: Double) {
        for stage in [a, b] {
            var left = 1_024
            while left > 0 {
                stage.crossDue(nil)
                if stage.startingNow { stage.startingNow = false }
                let n = min(left, stage.framesUntilNext(left))
                stage.clock.pulled(n)
                left -= n
            }
        }
        session.advance(to: min(a.clock.renderedBus, b.clock.renderedBus), head: head, outgoingMs: outgoingMs)
        while let action = session.takeAction() {
            a.take(action, as: .outgoing)
            b.take(action, as: .incoming)
        }
        a.endOfActions()
        b.endOfActions()
    }

    func setUp(quiet: Bool = false) -> (BlendSession, CommitWord, DeckBlendStage, DeckBlendStage) {
        let word = CommitWord()
        let session = BlendSession(word: word, timing: timing())
        let a = DeckBlendStage(clock: DeckClock(bus: 0, rate: 1, latency: 0))
        let b = DeckBlendStage(clock: DeckClock(bus: 0, rate: 1, latency: 0))
        a.partner = b
        b.partner = a
        word.open(1)
        a.armOutgoing(session, startMs: 10_000, overlapMs: 8_000)
        b.armIncoming(session, entryMs: 0)
        session.arm(BlendArm(generation: 1, startMs: 10_000, overlapMs: 8_000, latestEndMs: 22_000, outgoingEndMs: 30_000,
                             lateMoveBars: lateMoveUnbounded, commitBeforeAck: quiet))
        return (session, word, a, b)
    }

    /// Runs cycles from bus frame 0, A at song time bus / 48; posts B ready and A's start at S
    /// (frame 480 000, 10 s) once it is within the lead; `ack` decides when main acknowledges.
    func run(_ session: BlendSession, _ word: CommitWord, _ a: DeckBlendStage, _ b: DeckBlendStage, until: Int64,
             ack: (BlendEvent) -> Bool, events: inout [BlendEvent]) {
        var posted = false
        b.readiness(true)
        while a.clock.source < until {
            let head = a.clock.source
            if !posted && 480_000 - head <= timing().proposalLeadFrames + 4_096 {
                a.post(.startAt, value: 480_000)
                posted = true
            }
            cycle(session, a, b, head: head, outgoingMs: Double(head) / 48)
            while let e = session.takeEvent(head: head) {
                events.append(e)
                if e.kind == .proposed && ack(e) { _ = word.ack(e.generation) }
            }
        }
    }

    @Test func mainsAckLetsTheIncomingSongStartOnItsFrame() {
        let (session, word, a, b) = setUp()
        var events: [BlendEvent] = []
        run(session, word, a, b, until: 490_000, ack: { _ in true }, events: &events)
        #expect(events.contains { $0.kind == .proposed })
        #expect(b.started)
        #expect(b.startIndex == 480_000)
    }

    @Test func withoutTheAckNoFrameOfTheIncomingSongIsEverRendered() {
        let (session, word, a, b) = setUp()
        var events: [BlendEvent] = []
        // Main held past the margin: the commit deadline cancels.
        run(session, word, a, b, until: 600_000, ack: { _ in false }, events: &events)
        #expect(!b.started && b.parked)
        #expect(events.contains { $0.kind == .incomingNotReady && $0.cause == .floor })
        #expect(word.state(of: 1) == .cancelled)
    }

    @Test func onAirPlayAQuietStartCommitsAtItsDeadlineWithoutTheAck() {
        let (session, word, a, b) = setUp(quiet: true)
        var events: [BlendEvent] = []
        run(session, word, a, b, until: 490_000, ack: { _ in false }, events: &events)
        #expect(b.started)
        // An interrupt then fades the incoming song's rendered frames out over 250 ms.
        session.disarm(.edit)
        var faded = false
        while let action = session.takeAction() {
            if action.kind == .fadeOutB { faded = action.value == 12_000 }
            b.take(action, as: .incoming)
        }
        #expect(faded)
    }

    @Test func theOutgoingSongMovingOnBeforeTheStartCancelsThePlan() {
        let (session, _, a, b) = setUp()
        var events: [BlendEvent] = []
        for i in 0..<10 {
            if i == 5 { a.post(.movedOn) }
            cycle(session, a, b, head: a.clock.source, outgoingMs: Double(a.clock.source) / 48)
            while let e = session.takeEvent(head: a.clock.source) { events.append(e) }
        }
        #expect(events.contains { $0.kind == .cancelled && $0.cause == .aMovedOn })
        #expect(session.state == .idle)
        // The spare never started.
        #expect(!b.started)
    }

    @Test func theOutgoingSongEndingInTheMixedStretchEndsTheBlendAndBGoesToItsOwnLevel() {
        let (session, word, a, b) = setUp()
        var events: [BlendEvent] = []
        run(session, word, a, b, until: 500_000, ack: { _ in true }, events: &events)
        #expect(b.started)
        // A's render reached its song's end with nothing following: it holds and tells.
        #expect(a.atSongEnd(followerSerial: nil) == .hold)
        #expect(a.atSongEnd(followerSerial: 9) == .hold)
        a.post(.ended)
        var disarmed = false
        for _ in 0..<4 {
            session.advance(to: a.clock.renderedBus + 1_024, head: a.clock.source, outgoingMs: .nan)
            while let action = session.takeAction() { if action.kind == .bDisarm { disarmed = true } }
            a.clock.pulled(1_024)
            b.clock.pulled(1_024)
            while let e = session.takeEvent(head: a.clock.source) { events.append(e) }
        }
        #expect(disarmed)
        #expect(events.contains { $0.kind == .blendEnd })
    }

    @Test func aPausePastTheHandoverKeepsTheOutgoingSongToTheStartThenTheIncomingSongComesIn() {
        let (session, word, a, b) = setUp()
        var events: [BlendEvent] = []
        // Acked, the start decided but not yet reached.
        run(session, word, a, b, until: 476_000, ack: { _ in true }, events: &events)
        #expect(word.state(of: 1) == .acked)
        #expect(!b.started)
        session.disarm(.pause)
        while let action = session.takeAction() {
            a.take(action, as: .outgoing)
            b.take(action, as: .incoming)
        }
        a.endOfActions()
        b.endOfActions()
        #expect(a.cutAtStart)
        #expect(b.startsAfterPartner)
        // Paused a while: the incoming song waits for where the kept frames end, then comes in.
        for _ in 0..<50 {
            b.crossDue(nil)
            b.clock.pulled(1_024)
            a.clock.pulled(1_024)
        }
        #expect(!b.started)
        a.cutBus = a.clock.renderedBus + 4_800
        while !b.started {
            b.crossDue(nil)
            if !b.started { b.clock.pulled(b.framesUntilNext(1_024)) }
        }
        #expect(b.clock.renderedBus == a.cutBus)
    }

    @Test func anIncomingStartPastTheCutDropsAsMuchOfItsSongToLandOnIt() {
        let (session, word, a, b) = setUp()
        var events: [BlendEvent] = []
        run(session, word, a, b, until: 476_000, ack: { _ in true }, events: &events)
        session.disarm(.pause)
        while let action = session.takeAction() {
            a.take(action, as: .outgoing)
            b.take(action, as: .incoming)
        }
        a.endOfActions()
        b.endOfActions()
        #expect(b.startsAfterPartner)
        // B renders on, silent, past where the cut turns out to be.
        for _ in 0..<5 {
            b.crossDue(nil)
            b.clock.pulled(1_024)
        }
        a.cutBus = b.clock.renderedBus - 600
        b.crossDue(nil)
        #expect(b.started)
        #expect(b.startLate == 600)
    }

    @Test func anUndoneStartWaitsForTheNamedFollowerAtTheOutgoingSongsEnd() {
        let (session, _, a, b) = setUp()
        // A disarm before the start: B never starts; A plays on.
        session.disarm(.edit)
        while let action = session.takeAction() {
            a.take(action, as: .outgoing)
            b.take(action, as: .incoming)
        }
        a.retire()
        b.retire()
        #expect(b.parked && !b.started)
        // A reaches its end after main named its follower 7: only that follower plays.
        a.take(BlendAction(kind: .aHoldNext, frame: 0, value: 0, ms: 0), as: .outgoing)
        #expect(a.atSongEnd(followerSerial: 7) == .hold)
        a.take(BlendAction(kind: .aFollow, frame: 0, value: 7, ms: 0), as: .outgoing)
        #expect(a.atSongEnd(followerSerial: 8) == .end)
        #expect(a.atSongEnd(followerSerial: 7) == .moveOn)
    }

    /// Runs `ramp` on one channel `x`, its first frame at bus frame `firstBus`.
    func run(_ ramp: DeckGainRamp, _ x: inout [Float], firstBus: Int64) {
        x.withUnsafeMutableBufferPointer { p in
            var channel = p.baseAddress!
            withUnsafeMutablePointer(to: &channel) { ramp.process($0, count: 1, frames: p.count, firstBus: firstBus) }
        }
    }

    @Test func theLevelAfterTheTimePitchLeavesOnItsExactBusFrame() {
        let ramp = DeckGainRamp()
        ramp.leave(atBus: 1_000, frames: 960)
        var x = [Float](repeating: 1, count: 4_096)
        run(ramp, &x, firstBus: 0)
        #expect(x[999] == 1)
        #expect(x[1_000] < 1)
        #expect(x[1_000 + 959] == 0)
        #expect(ramp.silent)
        ramp.open()
        var y = [Float](repeating: 1, count: 16)
        run(ramp, &y, firstBus: 5_000)
        #expect(y.allSatisfy { $0 == 1 })
    }
}

@Suite struct PhoneMixShapingTests {
    func silence(_ frames: Int) -> [Float] { [Float](repeating: 0, count: frames) }

    @Test func anIntroHoldsTheOutgoingSongThroughABreakAndTheGateActsOnlyAfterTheHold() {
        // A 14 s intro, its hold 60% of it; a 0.5 s silence at 40% of the blend.
        let intro = plan(kind: .intro, startMs: 0, overlapMs: 14_000, holdShare: 0.6, riseShare: 0.1)
        let transition = DeckTransition(entryId: nil, plan: intro, incoming: false, gateDb: -40)
        let shaper = TransitionShaper(sampleRate: 48_000, channels: 1)
        shaper.arm(transition, songMs: 5_000)
        var x = silence(48_000 / 2)
        x.withUnsafeMutableBufferPointer { p in shaper.process([p.baseAddress!], frames: 24_000, songMs: 5_600, msPerFrame: 1_000 / 48_000.0) }
        // Held at its own level (less the blend's 1 dB headroom), not faded.
        #expect(abs(shaper.level - pow(10, -1.0 / 20)) < 0.01)
        // The same silence after the hold: today's gate fades it.
        var y = silence(48_000)
        y.withUnsafeMutableBufferPointer { p in shaper.process([p.baseAddress!], frames: 48_000, songMs: 9_000, msPerFrame: 1_000 / 48_000.0) }
        #expect(shaper.level == 0)
    }

    @Test func aDisarmTakesTheShapingToTheSongsOwnLevelInAStraightLine() {
        let incoming = DeckTransition(entryId: nil, plan: plan(startMs: 0, entryMs: 0, overlapMs: 8_000), incoming: true)
        let shaper = TransitionShaper(sampleRate: 48_000, channels: 1)
        shaper.arm(incoming, songMs: 1_000)
        var x = [Float](repeating: 1, count: 4_800)
        x.withUnsafeMutableBufferPointer { p in shaper.process([p.baseAddress!], frames: 4_800, songMs: 1_000, msPerFrame: 1_000 / 48_000.0) }
        let from = shaper.level
        #expect(from < 1)
        shaper.disarm(12_000)
        var y = [Float](repeating: 1, count: 12_000)
        y.withUnsafeMutableBufferPointer { p in shaper.process([p.baseAddress!], frames: 12_000, songMs: 1_100, msPerFrame: 1_000 / 48_000.0) }
        #expect(shaper.level == 1)
        #expect(abs(Double(y[5_999]) - (from + (1 - from) * 6_000 / 12_000)) < 1e-3)
        #expect(!shaper.shaping)
    }

    @Test func theBlendTimeStandsStillWhileFrozenAndCarriesOnShiftedByTheExtension() {
        let processor = DeckProcessor(keepActive: { false }, tap: nil, stage: nil)
        processor.configure(sampleRate: 48_000, channels: 2)
        processor.flush(streamStartMs: 10_000, entryId: "a")
        processor.arm(DeckTransition(entryId: "a", plan: plan(startMs: 10_000, overlapMs: 8_000), incoming: false))
        var l = [Float](repeating: 0.5, count: 4_800)
        var r = l
        func run(_ frames: Int) {
            l.withUnsafeMutableBufferPointer { lp in
                r.withUnsafeMutableBufferPointer { rp in processor.process([lp.baseAddress!, rp.baseAddress!], frames: frames) }
            }
        }
        run(4_800)
        #expect(abs(processor.blendMs - 10_100) < 1e-6)
        processor.freezeBlend()
        run(4_800)
        #expect(abs(processor.blendMs - 10_100) < 1e-6)
        #expect(abs(processor.songMs - 10_200) < 1e-6)
        // Unfrozen with the 100 ms it stood still as its extension: the blend time carries on.
        processor.unfreezeBlend(extensionMs: 100)
        #expect(abs(processor.blendMs - 10_100) < 1e-6)
        processor.moveBlendStart(byMs: 2_400)
        #expect(abs(processor.blendMs - 7_700) < 1e-6)
    }

    @Test func aDisarmedTransitionIsNotTakenUpAgainUntilAnotherPlanIsArmed() {
        let processor = DeckProcessor(keepActive: { false }, tap: nil, stage: nil)
        processor.configure(sampleRate: 48_000, channels: 2)
        processor.flush(streamStartMs: 0, entryId: "b")
        processor.arm(DeckTransition(entryId: "b", plan: plan(startMs: 0, entryMs: 0, overlapMs: 8_000), incoming: true))
        var l = [Float](repeating: 1, count: 2_400)
        var r = l
        func run() {
            l.withUnsafeMutableBufferPointer { lp in
                r.withUnsafeMutableBufferPointer { rp in processor.process([lp.baseAddress!, rp.baseAddress!], frames: 2_400) }
            }
        }
        run()
        #expect(processor.shaping)
        processor.disarmTransition(frames: 0)
        for i in 0..<2_400 {
            l[i] = 1
            r[i] = 1
        }
        run()
        #expect(!processor.shaping)
        #expect(l.allSatisfy { $0 == 1 })
        processor.arm(DeckTransition(entryId: "b", plan: plan(startMs: 0, entryMs: 0, overlapMs: 8_000), incoming: true))
        run()
        #expect(processor.shaping)
    }
}

@Suite struct PhoneMixRingTests {
    func ring(_ chunks: [(songFrame: Int64, frames: Int, end: Bool)]) -> PCMRing {
        let ring = PCMRing(seconds: 2, sampleRate: 48_000)
        for chunk in chunks {
            _ = ring.freeChunk()
            ring.publish(frames: chunk.frames, songFrame: chunk.songFrame, end: chunk.end, gen: 0)
        }
        return ring
    }

    @Test func aSongFrameAheadIsFoundAcrossChunksAndSkippedSilence() {
        // Two chunks with 1 s of silence left out between them, then the end.
        let r = ring([(0, 2_048, false), (50_000, 2_048, false), (52_048, 0, true)])
        #expect(r.ahead(ofSongFrame: 1_000) == .at(1_000))
        #expect(r.ahead(ofSongFrame: 50_100) == .at(2_148))
        // Inside the stretch left out: its first frame kept after it.
        #expect(r.ahead(ofSongFrame: 10_000) == .at(2_048))
        #expect(r.ahead(ofSongFrame: 60_000) == .endsBefore(4_096))
        #expect(ring([(0, 2_048, false)]).ahead(ofSongFrame: 5_000) == .notYet)
    }

    @Test func theIncomingSongsFramesBeforeItsEntryAreDropped() {
        let r = ring([(91_200, 2_048, false), (93_248, 2_048, false)])
        #expect(r.drop(beforeSongFrame: 94_000) == 2_048 + 752)
        #expect(r.nextSongFrame() == 94_000)
        #expect(r.waitingFrames == 2_048 - 752)
    }
}

@Suite struct PhoneMixAllocationTests {
    /// The parts of a render cycle counted apart: the decks, the session's step, its actions, its
    /// events, the limiter on the sum, and the arm with what is posted to the session.
    static let parts = ["decks", "session step", "actions", "events", "limiter", "arm and posts"]

    /// A thousand render cycles of the session, both stages, their clocks, the rings and the mix
    /// limiter, through a blend from its arm past its start: nothing allocated. The same script
    /// runs once first on other objects, as the render thread's first cycles would, so the
    /// runtime's one-time type records are made before counting.
    @Test func theRenderThreadsBlendCodeAllocatesNothing() throws {
        try #require(octo_alloc_supported() == 1, "counting needs Linux and glibc")
        _ = scenario(counting: false)
        let counts = scenario(counting: true)
        for (part, calls) in zip(Self.parts, counts) { #expect(calls == 0, "\(part): \(calls) calls into the allocator") }

        // The interposer sees Swift's own allocations.
        octo_alloc_arm()
        let made = [Int](repeating: 7, count: Int.random(in: 100...200))
        let counted = octo_alloc_disarm()
        #expect(made.count >= 100)
        #expect(counted > 0)
    }

    /// Runs the script; with `counting`, answers the allocator calls of each part.
    func scenario(counting: Bool) -> [Int] {
        let tally = UnsafeMutablePointer<Int>.allocate(capacity: Self.parts.count)
        tally.initialize(repeating: 0, count: Self.parts.count)
        defer { tally.deallocate() }
        // Ends the count so far into `part` and starts the next.
        func mark(_ part: Int) {
            guard counting else { return }
            tally[part] += octo_alloc_disarm()
            octo_alloc_arm()
        }
        let word = CommitWord()
        let timing = phoneBlendTiming(latencyFrames: 2_400, cycleFrames: 1_024, tickerMs: 40, mainLagMs: 50, ringLeadMs: 50, sinkBufferMs: 50)
        let session = BlendSession(word: word, timing: timing)
        let a = DeckBlendStage(clock: DeckClock(bus: 0, rate: 1, latency: 0))
        let b = DeckBlendStage(clock: DeckClock(bus: 0, rate: 0.94, latency: 2_400))
        a.partner = b
        b.partner = a
        let inputs = BlendRing<BlendMainInput>(capacity: 64, filler: BlendMainInput(kind: .restarted))
        let events = BlendRing<BlendEvent>(capacity: blendEventRingSlots, filler: blendNoEvent)
        let limiter = MixLimiter(sampleRate: 48_000, channels: 2)
        let left = UnsafeMutablePointer<Float>.allocate(capacity: 1_024)
        let right = UnsafeMutablePointer<Float>.allocate(capacity: 1_024)
        defer {
            left.deallocate()
            right.deallocate()
        }
        let buffers = [left, right]
        let table = UnsafeMutablePointer<UnsafeMutablePointer<Float>>.allocate(capacity: 2)
        table[0] = left
        table[1] = right
        defer { table.deallocate() }
        let stages = [a, b]
        let arm = BlendArm(generation: 1, startMs: 10_000, overlapMs: 8_000, latestEndMs: 22_000, outgoingEndMs: 30_000,
                           lateMoveBars: lateMoveUnbounded, holdShare: 0.3)
        word.open(1)
        a.armOutgoing(session, startMs: 10_000, overlapMs: 8_000)
        b.armIncoming(session, entryMs: 0)
        _ = inputs.push(.arm(arm, outgoing: 0, entryMs: 0))

        if counting { octo_alloc_arm() }
        if let input = inputs.pop() { session.arm(input.arm) }
        b.readiness(true)
        var posted = false
        for cycle in 0..<1_000 {
            let head = Int64(cycle) * 1_024
            for i in 0..<1_024 {
                left[i] = Float(i % 64) / 32 - 1
                right[i] = -left[i]
            }
            if !posted && 480_000 - head <= timing.proposalLeadFrames + 4_096 {
                a.post(.startAt, value: 480_000)
                posted = true
            }
            if cycle == 520 { b.readiness(false) }
            if cycle == 560 { b.readiness(true) }
            mark(5)
            // Index loops, as on the render thread: an array's own iterator allocates in an
            // unoptimized build.
            for s in 0..<stages.count {
                let stage = stages[s]
                var n = 1_024
                while n > 0 {
                    stage.crossDue(nil)
                    if stage.startingNow { stage.startingNow = false }
                    let step = min(n, stage.framesUntilNext(n))
                    stage.applyRampIn(buffers, offset: 1_024 - n, frames: step)
                    stage.clock.pulled(step)
                    n -= step
                }
                stage.gain.process(table, count: 2, frames: 1_024, firstBus: head)
            }
            mark(0)
            session.advance(to: min(a.clock.renderedBus, b.clock.renderedBus), head: head, outgoingMs: Double(head) / 48)
            mark(1)
            while let action = session.takeAction() {
                switch action.kind {
                case .limiterOn: limiter.windowOn(fromBus: action.frame)
                case .limiterOff: limiter.windowOff(fromBus: action.frame)
                default:
                    a.take(action, as: .outgoing)
                    b.take(action, as: .incoming)
                }
            }
            a.endOfActions()
            b.endOfActions()
            mark(2)
            while events.room > 0, let event = session.takeEvent(head: head) {
                _ = events.push(event)
                if event.kind == .proposed { _ = word.ack(event.generation) }
            }
            _ = events.pop()
            mark(3)
            limiter.process(buffers, frames: 1_024, firstBus: Double(head), busPerFrame: 1)
            mark(4)
        }
        if counting { _ = octo_alloc_disarm() }
        precondition(b.started, "the script reaches the incoming song's start")
        return (0..<Self.parts.count).map { tally[$0] }
    }
}
