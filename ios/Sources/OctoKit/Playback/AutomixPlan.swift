import Foundation

/// Songs shorter than this get the plain crossfade, never a planned one.
public let shortestAutomixSongMs = 35_000

/// The shortest planned blend, and the longest that still counts as a cut.
public let shortestBlendMs = 1_800
public let cutBlendMs = 2_400

/// The latest a blend may end: this long before the outgoing song's end.
public let endMarginMs = 250

/// The earliest a blend may start: this far into the song, this share of it, once the song has
/// played long enough to count as played (half of it or `countsAsPlayedMs`, whichever is less),
/// and no more than `longestEarlyExitMs` before the latest end.
public let earliestStartMs = 30_000
public let earliestStartShare = 0.5
public let countsAsPlayedMs = 240_000
public let longestEarlyExitMs = 45_000

/// The incoming song needs this long to get ready before the blend starts.
public let preRollMs = 3_000

/// Without a beat grid the start is tried every this many milliseconds.
public let startStepMs = 250

/// Both songs play this much quieter through the overlap, so their sum does not clip.
public let overlapHeadroomDb = 1.0

/// Automix's longest blend, and its longest intro played under the end of the song before, both
/// in song time.
public let automixLongestBlendMs = 8_000
public let automixLongestIntroMs = 16_000

/// An intro plays under the outgoing song only when its quiet stretch lasts at least this long.
public let introShortestMs = 4_000

/// The outgoing song fades out under an intro over this many bars when the blend is locked to the
/// bar, else over `introFadeMs`.
public let introFadeBars = 2
public let introFadeMs = 3_000

/// The incoming song rises to its own level over this long at the start of an intro (one bar
/// when locked).
public let introRiseMs = 1_000

/// Through an intro's hold the incoming song, with its rise, is lowered (the plan's `introLevel`)
/// until it stays at least `introUnderOutgoingDb` under the outgoing song held at its own level, at
/// the same moments, for `introUnderOutgoingShare` of the hold where it sounds: a short break in the
/// outgoing song, where the incoming one plays alone, does not count. Then, over the whole overlap
/// with the planned gains and the overlap's headroom, the two together must at every step stay
/// under the louder of the two songs alone, by `-introExcessLimitDb` at least (the flat power sum's
/// excess), and it is lowered further until they do. The levels are flat ones of the channels' mean,
/// each song's as its deck plays it (`TransitionContext.incomingGainDb`); heard with the ear's
/// weighting and in stereo the incoming song can sit a few dB nearer the outgoing one, which the
/// margins leave room for. An intro that would need it lowered by over `introTrimCapDb` is not
/// planned.
public let introUnderOutgoingDb = 8.0
public let introUnderOutgoingShare = 0.95
public let introExcessLimitDb = -0.5
public let introTrimCapDb = 30.0

/// How many times the trim the overlap needs is halved between the hold's and `introTrimCapDb`.
private let introTrimSteps = 14

/// Music at full level the outgoing song would lose after a blend's end: above
/// `keptMusicLimitMs` the start is left out, and each second below it costs `keptMusicWeight`.
/// Full level is within `keptMusicBelowBodyDb` of the body.
public let keptMusicLimitMs = 1_000
public let keptMusicWeight = 0.5
public let keptMusicBelowBodyDb = 6.0

/// Match tempo reaches tempos this far apart, as a share.
public let beatMatchLargestGap = 0.06

/// Wherever both songs are clearly heard together, their beats drift apart by at most
/// `blendDriftLimitMs`; clearly heard is within `bothHeardBelowBodyDb` of a song's body.
public let bothHeardBelowBodyDb = 10.0

/// How finely the planner walks a blend to find where both songs are heard.
private let heardStepMs = 10.0

/// A song's grid point this close before its start counts as at its start.
private let gridAtStartMs = 1.0

/// Songs in these genres get the plain crossfade, never a planned one.
public func isPlainCrossfadeGenre(_ genre: String) -> Bool {
    let lower = genre.lowercased()
    return ["classical", "opera", "spoken", "audiobook", "podcast", "comedy"].contains { lower.contains($0) }
}

/// How the two songs meet.
public enum TransitionKind: String, Sendable {
    /// One song straight after the other, no overlap.
    case gapless = "GAPLESS"
    /// The fixed crossfade at the end of the song.
    case crossfade = "CROSSFADE"
    /// A short planned swap.
    case cut = "CUT"
    /// A planned blend into a louder song.
    case lift = "LIFT"
    /// A planned blend.
    case blend = "BLEND"
    /// The next song's quiet start played under the end of this one: the outgoing song holds its
    /// level, then fades out around the moment the incoming one reaches its full level.
    case intro = "INTRO"
}

/// What the player sets for planned transitions.
public struct AutomixSettings: Sendable, Equatable, Hashable {
    /// The longest blend: Crossfade's length, or `automixLongestBlendMs` for Automix; 0 plays
    /// every song gapless.
    public var maxOverlapMs: Int
    /// The longest intro played under the end of the song before.
    public var longestIntroMs: Int
    /// Off plays the fixed crossfade at the end of each song.
    public var smart: Bool
    public var filterSweeps: Bool
    /// Nudges the incoming song's speed to the outgoing song's tempo.
    public var beatMatch: Bool
    /// The curve of the fixed crossfade, with smart transitions off. Planned transitions keep
    /// their own curves.
    public var fadeCurve: FadeCurve

    public init(maxOverlapMs: Int, longestIntroMs: Int = automixLongestIntroMs, smart: Bool = true, filterSweeps: Bool = true,
                beatMatch: Bool = false, fadeCurve: FadeCurve = .equalPower) {
        self.maxOverlapMs = maxOverlapMs
        self.longestIntroMs = longestIntroMs
        self.smart = smart
        self.filterSweeps = filterSweeps
        self.beatMatch = beatMatch
        self.fadeCurve = fadeCurve
    }
}

/// Where the player is when it plans, and what it knows besides the scouted sections. `nowMs` is
/// the outgoing song's position and `playedMs` how much of it has actually been heard (less than
/// nowMs after a seek forward). `bodyLevelDb` and `tempoPrior` come from a LiveTap of the
/// outgoing song. Skip-silence moves media time away from the beat, and so does a pace other
/// than 1 unless `lockAtPace` says the player changes the speed of both songs together after
/// they are mixed: either turns off bar lock and tempo matching. `pace` is also the speed the
/// beats are heard at. `incomingGainDb` is the incoming song's deck gain less the outgoing song's,
/// in dB, as the decks apply them (ReplayGain, preamp, the untagged fallback): an intro's trim
/// compares the two songs as they are heard.
public struct TransitionContext: Sendable, Equatable {
    public var nowMs: Int
    public var playedMs: Int
    public var repeatOne: Bool
    public var stopAtEndOfSong: Bool
    public var pace: Double
    public var skipSilence: Bool
    public var currentGenre: String?
    public var nextGenre: String?
    public var bodyLevelDb: Double?
    public var tempoPrior: Double?
    public var lockAtPace: Bool
    public var incomingGainDb: Double

    public init(nowMs: Int = 0, playedMs: Int? = nil, repeatOne: Bool = false, stopAtEndOfSong: Bool = false, pace: Double = 1,
                skipSilence: Bool = false, currentGenre: String? = nil, nextGenre: String? = nil, bodyLevelDb: Double? = nil,
                tempoPrior: Double? = nil, lockAtPace: Bool = false, incomingGainDb: Double = 0) {
        self.nowMs = nowMs
        self.playedMs = playedMs ?? nowMs
        self.repeatOne = repeatOne
        self.stopAtEndOfSong = stopAtEndOfSong
        self.pace = pace
        self.skipSilence = skipSilence
        self.currentGenre = currentGenre
        self.nextGenre = nextGenre
        self.bodyLevelDb = bodyLevelDb
        self.tempoPrior = tempoPrior
        self.lockAtPace = lockAtPace
        self.incomingGainDb = incomingGainDb
    }
}

/// How to move from one song to the next. At startMs into the outgoing song the incoming song
/// starts playing from entryMs, and over overlapMs the volumes follow `automixOutGain` /
/// `automixInGain` with curve weight k (or, for a fixed crossfade's chosen curve, `fadeGains`,
/// and for an intro `introOutGain` / `introInGain`), both lowered by headroomDb. With a
/// filterStrength above 0 the filter sweeps run through the overlap and are bypassed once it
/// ends. beatMatchRate, when set, is the incoming song's playback rate through the blend. beatMs
/// and beatAnchorMs, when set, are the outgoing song's beat length and one of its beats in song
/// time: the blend is locked to the bar and the low-pass steps on the beat. `late` marks a blend
/// placed at the end because there was no time or data to choose a better place. `reason` says
/// why this plan was chosen.
///
/// An intro's shape is in shares of the overlap: the outgoing song holds its level until
/// holdShare, then fades out; the incoming one rises over riseShare (never past holdShare) to
/// introLevel (1, or lower so it stays under the outgoing song), holds there, and comes up to its
/// own level as the outgoing song fades. Any other kind
/// whose incoming song enters before its first sound holds too: the outgoing song at its own
/// level and the incoming one silent until holdShare, where that sound comes, then the curve
/// over the rest. Both are 0 otherwise. `latestEndMs` is the latest the blend may end in the
/// outgoing song's time: where its sound ends (at most `endMarginMs` before its end) for a
/// planned blend, the end a late blend was placed against, the song's length for a fixed
/// crossfade and for a gapless join. `lateMoveBars` is how many whole bars of the outgoing song a
/// player that starts the plan late may move it later as it is, `lateMoveUnbounded` when any
/// number may (a plan with no bar grid, a matched rate or two equal tempos, never an intro, whose
/// trim must hold where it moves to: with no bar grid it is 1 when any move to the latest end may
/// be made, else 0); past that the player plans again from where it is. Every plan sets it.
///
/// Counted from startMs in the outgoing song's time, both songs are clearly heard together from
/// `bothHeardStartMs` to `bothHeardEndMs` (both 0 when they never are), and their beats meet at
/// `lockMs`. `driftGap` is how fast the beats part from there, the share their tempos are apart,
/// 0 when they cannot drift (a matched rate, equal tempos) or are not lined up; `driftAtEndMs` is
/// how far apart they get in song time while both are heard. Heard at a speed s, the beats are
/// that far apart over s: `dropCutAtMs` says where a speed lowered to s puts them too far apart.
public struct TransitionPlan: Sendable, Equatable {
    public var startMs: Int
    public var entryMs: Int
    public var overlapMs: Int
    public var kind: TransitionKind
    public var k: Double
    public var filterStrength: Double
    public var beatMatchRate: Double?
    public var beatMs: Double?
    public var beatAnchorMs: Double?
    public var late: Bool
    public var headroomDb: Double
    public var reason: String
    /// A fixed crossfade's chosen curve, followed in place of the curve weight k when set.
    public var curve: FadeCurve?
    public var holdShare: Double
    public var riseShare: Double
    /// The incoming song's gain through an intro's hold (1 for every other kind).
    public var introLevel: Double
    public var latestEndMs: Int
    public var lateMoveBars: Int
    public var bothHeardStartMs: Int
    public var bothHeardEndMs: Int
    public var lockMs: Double
    public var driftGap: Double
    public var driftAtEndMs: Double

    /// `latestEndMs` left out is the plan's own end.
    public init(startMs: Int, entryMs: Int, overlapMs: Int, kind: TransitionKind, k: Double, filterStrength: Double,
                beatMatchRate: Double?, beatMs: Double?, beatAnchorMs: Double?, late: Bool, headroomDb: Double, reason: String,
                curve: FadeCurve? = nil, holdShare: Double = 0, riseShare: Double = 0, introLevel: Double = 1, latestEndMs: Int? = nil,
                lateMoveBars: Int, bothHeardStartMs: Int = 0, bothHeardEndMs: Int = 0, lockMs: Double = 0, driftGap: Double = 0,
                driftAtEndMs: Double = 0) {
        self.startMs = startMs
        self.entryMs = entryMs
        self.overlapMs = overlapMs
        self.kind = kind
        self.k = k
        self.filterStrength = filterStrength
        self.beatMatchRate = beatMatchRate
        self.beatMs = beatMs
        self.beatAnchorMs = beatAnchorMs
        self.late = late
        self.headroomDb = headroomDb
        self.reason = reason
        self.curve = curve
        self.holdShare = holdShare
        self.riseShare = riseShare
        self.introLevel = introLevel
        self.latestEndMs = latestEndMs ?? startMs + overlapMs
        self.lateMoveBars = lateMoveBars
        self.bothHeardStartMs = bothHeardStartMs
        self.bothHeardEndMs = bothHeardEndMs
        self.lockMs = lockMs
        self.driftGap = driftGap
        self.driftAtEndMs = driftAtEndMs
    }

    public var barLocked: Bool { beatMs != nil }

    public func progressAt(_ outgoingMs: Double) -> Double {
        overlapMs <= 0 ? 1 : min(max((outgoingMs - Double(startMs)) / Double(overlapMs), 0), 1)
    }

    /// Each song's volume at `t` through the blend: an intro's hold and rise, else the curve
    /// after any held start.
    public func outGain(_ t: Double) -> Double {
        if kind == .intro { return introOutGain(t, holdShare) }
        let along = pastHold(t, holdShare)
        if let curve { return fadeGains(curve, along, overlapMs: Double(overlapMs) * (1 - holdShare)).out }
        return automixOutGain(along, k)
    }

    public func inGain(_ t: Double) -> Double {
        if kind == .intro { return introInGain(t, riseShare, holdShare, introLevel) }
        let along = pastHold(t, holdShare)
        if let curve { return fadeGains(curve, along, overlapMs: Double(overlapMs) * (1 - holdShare)).incoming }
        return automixInGain(along, k)
    }

    /// The outgoing song's beats inside the blend as places through it (0 to 1). A beat within a
    /// twentieth of a beat of either end counts as the end itself and is left out.
    public func beatProgress() -> [Double] {
        guard let beat = beatMs, let anchor = beatAnchorMs, overlapMs > 0, beat > 0 else { return [] }
        let grid = Tempo(bpm: 60_000.0 / beat, confidence: tempoConfident, consistency: 1, beatMs: beat, firstBeatMs: anchor, downbeatMs: anchor)
        let margin = beat / 20
        return grid.beatsBetween(Double(startMs) + margin, Double(startMs + overlapMs) - margin)
            .map { ($0 - Double(startMs)) / Double(overlapMs) }
    }

    /// The outgoing low-pass: stepped on the beat when the blend is locked to the bar, gliding
    /// over an eighth of a bar.
    public func outgoingLowPassAt(_ t: Double) -> Double {
        guard let beat = beatMs, overlapMs > 0 else { return outgoingLowPassHz(t, filterStrength) }
        return steppedOutgoingLowPassHz(t, filterStrength, beatProgress(), beat * Double(beatsPerBar) / 8 / Double(overlapMs))
    }

    public func outgoingHighPassAt(_ t: Double) -> Double { outgoingHighPassHz(t, filterStrength) }

    public func incomingHighPassAt(_ t: Double) -> Double { incomingHighPassHz(t, filterStrength) }

    /// The incoming song's playback rate `sinceEntryMs` after it came in.
    public func incomingRateAt(_ sinceEntryMs: Double) -> Double {
        beatMatchRate.map { beatMatchRateAt($0, sinceEntryMs, Double(overlapMs)) } ?? 1
    }
}

/// Where, counted from the plan's start, a speed lowered to `speed` while the blend is `fromMs`
/// along first puts its two songs' beats heard over `blendDriftLimitMs` apart while both are still
/// clearly heard: the first moment at or after `fromMs` in [bothHeardStartMs, bothHeardEndMs]
/// where driftGap * |t - lockMs| passes `blendDriftLimitMs` * speed (`fromMs` itself when they
/// are already that far apart there, else the moment after the lock point where the offset
/// reaches the limit, when the stretch goes on past it). The outgoing song keeps its own level
/// through a hold (an intro's, or the incoming song's silent lead-in), so the moment is never
/// before the hold's end. Nil when no such moment comes, and the blend plays on as planned.
public func dropCutAtMs(_ plan: TransitionPlan, _ speed: Double, _ fromMs: Double) -> Double? {
    if plan.bothHeardEndMs <= 0 { return nil }
    return blendDropCutAtMs(bothHeardStartMs: Double(plan.bothHeardStartMs), bothHeardEndMs: Double(plan.bothHeardEndMs),
                            holdEndMs: plan.holdShare * Double(plan.overlapMs), lockMs: plan.lockMs,
                            driftGap: plan.driftGap, pace: speed, fromMs: fromMs)
}

/// Plans the move from the playing song to the next one. `tail` is the analysis of the end of
/// the playing song (analyzeTail) and `head` of the start of the next one (analyzeHead), either
/// nil when it is not ready. Every rule of crossfadeLength still holds: when it gives no
/// crossfade the songs play gaplessly. With smart transitions off the plan is the fixed crossfade
/// (fixedCrossfadePlan) on the settings' curve; for a song under 35 s or in a genre that should
/// not be mixed, the fixed crossfade with the plain curve. Without the tail, or when no start is
/// left at least `preRollMs` ahead, the plan is a late one: the blend at the end.
///
/// When both songs' tempos are trusted (each confident, the outgoing one agreeing with the whole
/// song's, and the beats able to stay locked at this pace) every plan is locked to the outgoing
/// song's bars, the incoming song entering on its own grid, and a rate matches tempos up to
/// `beatMatchLargestGap` apart. Without a rate, wherever both songs are clearly heard their beats
/// stay within `blendDriftLimitMs` times the pace of each other: a blend starts later or becomes a
/// short cut until they do, or the songs join gaplessly. A place that would cut over
/// `keptMusicLimitMs` of the outgoing song's full-level music is never planned; with none left the
/// songs join gaplessly too. With both tempos confident but no lock, a
/// blend where both songs are clearly heard cannot be kept on the beat at all, so only one where
/// they never are is planned. A plan with a rate whose next song is too short to ease back to its
/// own speed is planned again without the rate.
public func planTransition(current: FadeSong, next: FadeSong?, tail: SectionAnalysis?, head: SectionAnalysis?,
                           settings: AutomixSettings, context: TransitionContext = TransitionContext()) -> TransitionPlan {
    let plain = crossfadeLength(current, next, fadeMs: settings.maxOverlapMs, repeatOne: context.repeatOne,
                                stopAtEndOfSong: context.stopAtEndOfSong)
    guard plain != 0, let next else {
        return gaplessPlan(current.durationMs, gaplessReason(current, next, settings.maxOverlapMs, context.repeatOne, context.stopAtEndOfSong))
    }
    let lenA = current.durationMs
    let lenB = next.durationMs
    if !settings.smart { return fixedCrossfadePlan(lenA: lenA, length: plain, why: "smart transitions off", curve: settings.fadeCurve) }
    if let genre = [context.currentGenre, context.nextGenre].compactMap({ $0 }).first(where: isPlainCrossfadeGenre) {
        return crossfadePlan(lenA, plain, "genre \(genre)")
    }
    if lenA < shortestAutomixSongMs || lenB < shortestAutomixSongMs {
        return crossfadePlan(lenA, plain, "a song under \(shortestAutomixSongMs / 1000) s")
    }
    let matched = planPass(lenA, lenB, tail, head, settings, context, withRate: true)
    guard let rate = matched.beatMatchRate else { return matched }
    // The incoming song spends overlap * rate of its own time in the blend and needs the settle
    // after it to ease back to its own speed.
    if Double(lenB - matched.entryMs) > Double(matched.overlapMs) * rate + beatMatchSettleMs + 1_000 { return matched }
    return planPass(lenA, lenB, tail, head, settings, context, withRate: false)
}

/// One pass of the planner, with a rate when `withRate` allows one.
private func planPass(_ lenA: Int, _ lenB: Int, _ tail: SectionAnalysis?, _ head: SectionAnalysis?, _ settings: AutomixSettings,
                      _ context: TransitionContext, withRate: Bool) -> TransitionPlan {
    let limit = min(Double(settings.maxOverlapMs), Double(min(lenA, lenB)) / 2.0)
    let sweeps = settings.filterSweeps ? filterStrength : 0.0
    let headFeatures = head?.features.soundStartMs != nil ? head?.features : nil
    let headEntry = headFeatures?.soundStartMs ?? 0
    let fixedEnd = Double(lenA - endMarginMs)

    guard let tail else {
        return loosePlan(lenA, fixedEnd, limit, Double(context.nowMs), headEntry, nil, head, sweeps, "no analysis of this song's end")
    }
    let measured = context.bodyLevelDb.map(tail.withBodyLevel) ?? tail
    let a = measured.features
    guard let soundEndA = a.soundEndMs, let outroA = a.outroStartMs else {
        return loosePlan(lenA, fixedEnd, limit, Double(context.nowMs), headEntry, nil, head, sweeps, "this song's end is silent")
    }

    let latestEnd = Double(min(soundEndA, lenA - endMarginMs))
    let outroStart = min(Double(outroA), latestEnd)
    var tempoA = a.tempo?.confident == true ? a.tempo : nil
    var dropped = false
    if let found = tempoA, let prior = context.tempoPrior, prior > 0 {
        // The end must keep the tempo the whole song has.
        let moved = found.inOctaveOf(prior)
        if abs(moved.bpm / prior - 1) <= 0.04 {
            tempoA = moved
        } else {
            tempoA = nil
            dropped = true
        }
    }
    let tempoB = headFeatures?.tempo?.confident == true ? headFeatures?.tempo : nil
    let canLock = (context.pace == 1.0 || context.lockAtPace) && !context.skipSilence
    let confident = tempoA != nil && tempoB != nil
    let lockA = confident && canLock ? tempoA : nil
    let lockB = lockA != nil ? tempoB : nil
    let ratio: Double? = if let lockA, let lockB { foldTempoRatio(lockA.bpm / lockB.bpm) } else { nil }
    let gap = ratio.map { abs($0 - 1) } ?? 0
    var rate: Double?
    if let ratio, withRate, settings.beatMatch, gap > 0, gap <= beatMatchLargestGap { rate = min(max(ratio, 0.94), 1.06) }

    // The entry of a locked plan is on the incoming song's own grid, at or before its first sound.
    let soundStartB = Double(headEntry)
    let (entry, leadIn) = lockB.map { gridEntry($0, soundStartB, downbeats: rate != nil) } ?? (soundStartB, 0)
    return PlanPass(
        lenA: lenA, limit: limit, sweeps: sweeps, measured: measured, head: head, headFeatures: headFeatures, settings: settings,
        context: context, latestEnd: latestEnd, outroStart: outroStart, dropped: dropped, lockA: lockA, ratio: ratio, gap: gap,
        rate: rate, entry: entry, lead: leadIn / (rate ?? 1), leadHold: leadIn > 0 ? soundStartB / (rate ?? 1) : 0,
        blind: confident && !canLock
    ).plan()
}

/// The incoming song's entry on its own grid: its last downbeat (with a rate) or beat at or
/// before its first sound, so none of its sound is cut. When that falls before the song's start
/// the song enters at 0, and the second value is how far into the song its first grid point
/// lies: the outgoing song's start moves that much earlier, so that point lands on the outgoing
/// song's line.
private func gridEntry(_ b: Tempo, _ soundStartMs: Double, downbeats: Bool) -> (Double, Double) {
    let step = downbeats ? b.barMs : b.beatMs
    let anchor = downbeats ? b.downbeatMs : b.firstBeatMs
    let last = anchor + ((soundStartMs - anchor) / step + 1e-9).rounded(.down) * step
    return last >= -gridAtStartMs ? (max(0, last), 0) : (0, last + step)
}

/// A place a blend or intro may go: from startMs over overlapMs in the outgoing song's time, its
/// beats meeting the incoming song's at lockMs (the start, or later by the incoming song's
/// lead-in). An intro holds and rises over its shares, the incoming song at `level` through the
/// hold; any other shape holds until holdShare, then follows curve weight `k`, or when that is nil
/// the curve its own length takes.
private struct Shape {
    var startMs: Double
    var lockMs: Double
    var overlapMs: Double
    var onBar: Bool
    var intro = false
    var holdShare = 0.0
    var riseShare = 0.0
    var k: Double?
    var level = 1.0

    var endMs: Double { startMs + overlapMs }

    func shifted(_ ms: Double) -> Shape {
        var moved = self
        moved.startMs += ms
        moved.lockMs += ms
        return moved
    }
}

private struct PlanPass {
    let lenA: Int
    let limit: Double
    let sweeps: Double
    let measured: SectionAnalysis
    let head: SectionAnalysis?
    let headFeatures: SectionFeatures?
    let settings: AutomixSettings
    let context: TransitionContext
    let latestEnd: Double
    let outroStart: Double
    /// The outgoing song's tempo was not the whole song's.
    let dropped: Bool
    /// The outgoing song's grid when the plan is locked to it.
    let lockA: Tempo?
    let ratio: Double?
    let gap: Double
    let rate: Double?
    let entry: Double
    /// From a locked shape's start to where the two grids meet, in the outgoing song's time.
    let lead: Double
    /// With a lead, from a shape's start to the incoming song's first sound, in the outgoing
    /// song's time: the blend holds until then.
    let leadHold: Double
    /// Both tempos confident with no lock: only a shape where both songs are never clearly heard
    /// together is planned.
    let blind: Bool

    let a: SectionFeatures
    /// How fast the incoming song plays through the blend.
    let speedB: Double
    /// The beats drift apart through the blend and must be kept within the limit wherever both
    /// songs are clearly heard.
    let bounded: Bool
    let driftLimit: Double
    /// Where the song will count as played, at the current pace of listening.
    let lo: Double
    let phraseAnchor: Double?

    init(lenA: Int, limit: Double, sweeps: Double, measured: SectionAnalysis, head: SectionAnalysis?, headFeatures: SectionFeatures?,
         settings: AutomixSettings, context: TransitionContext, latestEnd: Double, outroStart: Double, dropped: Bool, lockA: Tempo?,
         ratio: Double?, gap: Double, rate: Double?, entry: Double, lead: Double, leadHold: Double, blind: Bool) {
        self.lenA = lenA
        self.limit = limit
        self.sweeps = sweeps
        self.measured = measured
        self.head = head
        self.headFeatures = headFeatures
        self.settings = settings
        self.context = context
        self.latestEnd = latestEnd
        self.outroStart = outroStart
        self.dropped = dropped
        self.lockA = lockA
        self.ratio = ratio
        self.gap = gap
        self.rate = rate
        self.entry = entry
        self.lead = lead
        self.leadHold = leadHold
        self.blind = blind
        a = measured.features
        speedB = rate ?? 1
        bounded = lockA != nil && rate == nil && gap > 0
        driftLimit = blendDriftLimitMs * context.pace
        let countsAsPlayed = context.nowMs + max(0, min(lenA / 2, countsAsPlayedMs) - context.playedMs)
        lo = max(
            max(earliestStartShare * Double(lenA), Double(earliestStartMs), Double(countsAsPlayed)),
            latestEnd - Double(longestEarlyExitMs),
            Double(measured.envelope.startMs),
            Double(context.nowMs + preRollMs))
        phraseAnchor = lockA.map { $0.downbeatMs + roundHalfUp((outroStart - $0.downbeatMs) / $0.barMs) * $0.barMs }
    }

    /// A blend from `start` over `overlap`, its beats meeting at `lock`, holding through the
    /// incoming song's silent lead-in.
    func blend(_ start: Double, _ lock: Double, _ overlap: Double, onBar: Bool) -> Shape {
        Shape(startMs: start, lockMs: lock, overlapMs: overlap, onBar: onBar, holdShare: leadHold > 0 ? min(1, leadHold / overlap) : 0)
    }

    func plan() -> TransitionPlan {
        var bars = 0
        var overlap: Double
        if let lockA, ratio != nil {
            bars = gap > 0.08 ? 2 : 4
            overlap = Double(bars) * lockA.barMs
        } else {
            overlap = latestEnd - outroStart
        }
        // The next song's quiet start, in the outgoing song's time.
        let quiet = headFeatures?.introEndMs.map { max(0, Double($0) - entry) / speedB } ?? 0
        if quiet > fitted(overlap, bars) && quiet >= Double(introShortestMs) && !dropped, let intro = planIntro(quiet) {
            return intro
        }
        // Without an intro the blend lasts at least until the next song is near its full level.
        let lengthened = quiet > overlap
        if lengthened {
            if let lockA, bars > 0 {
                bars = Int((quiet / lockA.barMs - 1e-9).rounded(.up))
                overlap = Double(bars) * lockA.barMs
            } else {
                overlap = quiet
            }
        }
        overlap = fitted(overlap, bars)

        let hi = latestEnd - overlap
        if hi < lo { return latePlan(Double(context.nowMs), overlap, "no start left before \(automixSeconds(Int(hi)))") }

        // Bar lines when the blend is locked to the bar, otherwise a fixed step back from the
        // latest start.
        let shapes: [Shape] = if let lockA {
            lockA.barsBetween(lo + lead, hi + lead).map { blend($0 - lead, $0, overlap, onBar: true) }
        } else {
            steps(lo, hi).map { blend($0, $0, overlap, onBar: false) }
        }
        guard let last = shapes.last else { return latePlan(lo, overlap, "no bar line left before \(automixSeconds(Int(hi)))") }
        var all = shapes
        if lockA != nil, !shapes.contains(where: { kept($0) <= Double(keptMusicLimitMs) }), let longer = stretched(last) { all.append(longer) }
        let fading = all.filter { fades($0) }
        if fading.isEmpty { return gaplessPlan(lenA, "the next song's silent start leaves no time to fade") }
        let allowed = fading.compactMap { allow($0, lo) }
        if allowed.isEmpty { return gaplessPlan(lenA, unheardReason()) }
        guard let best = bestOf(allowed, { blendScore($0) }) else { return gaplessPlan(lenA, "every blend left would cut the song short") }
        return blendPlan(best, lengthened)
    }

    /// A blend's overlap kept between `shortestBlendMs` and the limit, in whole bars when it was
    /// planned in `bars` of them and whole bars fit.
    func fitted(_ overlap: Double, _ bars: Int) -> Double {
        var length = min(max(overlap, Double(shortestBlendMs)), limit)
        if let lockA, bars > 0, length < Double(bars) * lockA.barMs {
            // Cut back to whole bars that fit, when that is still long enough.
            let fit = Int((limit / lockA.barMs + 1e-9).rounded(.down))
            let whole = Double(fit) * lockA.barMs
            if fit >= 1 && whole >= Double(shortestBlendMs) { length = whole }
        }
        return (length + 0.5).rounded(.down)
    }

    func steps(_ from: Double, _ to: Double) -> [Double] {
        var out: [Double] = []
        var at = to
        while at >= from - 1e-6 {
            out.append(at)
            at -= Double(startStepMs)
        }
        return out.reversed()
    }

    /// Full-level music the outgoing song loses after the shape ends.
    func kept(_ s: Shape) -> Double { measured.msAtLeast(s.endMs, latestEnd, a.bodyDb - keptMusicBelowBodyDb) }

    /// Whether a blend fades the outgoing song out over at least `shortestFadeMs` after any hold
    /// through the incoming song's silent lead-in, rather than holding it to the end and dropping
    /// it there.
    func fades(_ s: Shape) -> Bool { s.intro || (1 - s.holdShare) * s.overlapMs >= Double(shortestFadeMs) - 1e-6 }

    /// The last bar-line blend stretched to end exactly at the latest end, from its bar line or,
    /// over the limit, from the next one.
    func stretched(_ last: Shape) -> Shape? {
        guard let bar = lockA?.barMs else { return nil }
        var shape = blend(last.startMs, last.lockMs, latestEnd - last.startMs, onBar: true)
        if shape.overlapMs > limit {
            let lock = last.lockMs + bar
            shape = blend(lock - lead, lock, latestEnd - (lock - lead), onBar: true)
        }
        return shape.overlapMs >= Double(shortestBlendMs) ? shape : nil
    }

    /// A blend candidate as it may be planned: capped to the drift bound, only with both songs
    /// never heard together when the beats cannot be lined up, or as it is.
    func allow(_ s: Shape, _ earliest: Double) -> Shape? {
        if bounded { return cap(s, earliest) }
        if blind { return stretch(s) == nil ? s : nil }
        return s
    }

    /// The candidate's end kept and its start moved later by whole bars until the beats stay
    /// within the bound; once that would leave under `shortestBlendMs`, a cut from the earliest
    /// beat of A that keeps them within it. Every one fades A out (`fades`). Nil when no cut does.
    func cap(_ s: Shape, _ earliest: Double) -> Shape? {
        guard let bar = lockA?.barMs else { return s }
        var shape = s
        while true {
            if meets(shape) && fades(shape) { return shape }
            let lock = shape.lockMs + bar
            let start = lock - lead
            if shape.endMs - start < Double(shortestBlendMs) { break }
            shape = blend(start, lock, s.endMs - start, onBar: shape.onBar)
        }
        return cut(s.endMs, earliest)
    }

    /// A cut ending at `end`, starting on a beat of A no earlier than `earliest`, from
    /// `shortestBlendMs` down to `shortestFadeMs` long: the longest whose beats stay within the
    /// bound.
    func cut(_ end: Double, _ earliest: Double) -> Shape? {
        guard let grid = lockA else { return nil }
        let from = max(earliest, end - Double(shortestBlendMs))
        let to = end - Double(shortestFadeMs)
        for beat in grid.beatsBetween(from + lead, to + lead) {
            let shape = blend(beat - lead, beat, end - (beat - lead), onBar: onBarLine(beat))
            if meets(shape) && fades(shape) { return shape }
        }
        return nil
    }

    func onBarLine(_ ms: Double) -> Bool {
        guard let grid = lockA else { return false }
        let bars = (ms - grid.downbeatMs) / grid.barMs
        return abs(bars - roundHalfUp(bars)) < 1e-6
    }

    func meets(_ s: Shape) -> Bool {
        if bounded { return offset(s) <= driftLimit }
        if blind { return stretch(s) == nil }
        return true
    }

    /// How far apart the beats are, in song time, where they are furthest apart while both songs
    /// are clearly heard: they meet at the lock point and drift apart by the tempo gap either side
    /// of it.
    func offset(_ s: Shape) -> Double {
        guard let heard = stretch(s) else { return 0 }
        return gap * max(0, max(heard.1 - s.lockMs, s.lockMs - heard.0))
    }

    /// The first and last moments, in the outgoing song's time, where both songs are within
    /// `bothHeardBelowBodyDb` of their bodies through the shape, with its gains and the overlap's
    /// headroom; nil when they never are.
    func stretch(_ s: Shape) -> (Double, Double)? {
        guard let b = head, headFeatures != nil, s.overlapMs > 0 else { return nil }
        let k = s.k ?? curveOf(kindOf(s.overlapMs, measured, head, s.startMs, entry))
        let room = pow(10, -overlapHeadroomDb / 20)
        let aFloor = a.bodyDb - bothHeardBelowBodyDb
        let bFloor = b.features.bodyDb - bothHeardBelowBodyDb
        var first = Double.nan
        var last = Double.nan
        let count = Int((s.overlapMs / heardStepMs).rounded(.down))
        for i in 0...count {
            let since = Double(i) * heardStepMs
            let t = since / s.overlapMs
            let out = s.intro ? introOutGain(t, s.holdShare) : automixOutGain(pastHold(t, s.holdShare), k)
            let into = s.intro ? introInGain(t, s.riseShare, s.holdShare, s.level) : automixInGain(pastHold(t, s.holdShare), k)
            if out <= 0 || into <= 0 { continue }
            let at = s.startMs + since
            if measured.levelAt(at) + 20 * log10(out * room) < aFloor { continue }
            if b.levelAt(entry + since * speedB) + 20 * log10(into * room) < bFloor { continue }
            if first.isNaN { first = at }
            last = at
        }
        return first.isNaN ? nil : (first, last)
    }

    /// The level an intro's incoming song plays at through the hold: the hold's own (`holdLevel`),
    /// lowered further while the mix's excess over the louder song alone passes
    /// `introExcessLimitDb` somewhere in the overlap (`excess`), to the highest level that keeps it
    /// within that, found by halving the trim between the hold's and `introTrimCapDb`
    /// `introTrimSteps` times. Nil when that takes over `introTrimCapDb`.
    func underOutgoing(_ s: Shape) -> Double? {
        guard let held = holdLevel(s) else { return nil }
        if excess(s, held) <= introExcessLimitDb { return held }
        var lo = -20 * log10(held)
        var hi = introTrimCapDb
        if excess(s, pow(10, -hi / 20)) > introExcessLimitDb { return nil }
        for _ in 0..<introTrimSteps {
            let mid = (lo + hi) / 2
            if excess(s, pow(10, -mid / 20)) > introExcessLimitDb { lo = mid } else { hi = mid }
        }
        return pow(10, -hi / 20)
    }

    /// How far an intro's mix stands over the louder of its two songs alone, the incoming one at
    /// `level` through the hold: at each step of the overlap where either song sounds above its
    /// silence gate, the flat power sum of both with their planned gains and the overlap's headroom,
    /// less the louder song's own level there, each as its deck plays it; the loudest step. Negative
    /// infinity when no step counts.
    func excess(_ s: Shape, _ level: Double) -> Double {
        guard let b = head else { return -.infinity }
        let room = pow(10, -overlapHeadroomDb / 20)
        var most = -Double.infinity
        let count = Int((s.overlapMs / heardStepMs).rounded(.down))
        for i in 0...max(0, count) {
            let since = Double(i) * heardStepMs
            let t = since / s.overlapMs
            let outgoing = measured.levelAt(s.startMs + since)
            let own = b.levelAt(entry + since * speedB)
            if outgoing <= a.gateDb && own <= b.features.gateDb { continue }
            let incoming = own + context.incomingGainDb
            let out = introOutGain(t, s.holdShare) * room
            let into = introInGain(t, s.riseShare, s.holdShare, level) * room
            let sum = pow(10, outgoing / 10) * out * out + pow(10, incoming / 10) * into * into
            most = max(most, 10 * log10(max(sum, 1e-30)) - max(outgoing, incoming))
        }
        return most
    }

    /// The level that keeps an intro's incoming song `introUnderOutgoingDb` under the outgoing song
    /// through the hold, walked as the both-heard stretch is: at each step where the incoming song,
    /// at its own level with its rise, sounds above its silence gate, its 400 ms level as its deck
    /// plays it less the outgoing song's at the same moment; the `introUnderOutgoingShare` percentile
    /// of those (the lower of two neighbors) sets how far it is lowered, 1 when it is already that
    /// far under or never sounds there. Nil when that takes over `introTrimCapDb`.
    func holdLevel(_ s: Shape) -> Double? {
        guard let b = head else { return 1 }
        let hold = s.holdShare * s.overlapMs
        let gate = b.features.gateDb
        var apart: [Double] = []
        let count = Int((hold / heardStepMs).rounded(.down))
        for i in 0..<max(0, count) {
            let since = Double(i) * heardStepMs
            let into = introInGain(since / s.overlapMs, s.riseShare, s.holdShare, 1)
            if into <= 0 { continue }
            let level = b.levelAt(entry + since * speedB) + 20 * log10(into)
            if level <= gate { continue }
            apart.append(level + context.incomingGainDb - measured.levelAt(s.startMs + since))
        }
        if apart.isEmpty { return 1 }
        apart.sort()
        let trim = apart[Int((introUnderOutgoingShare * Double(apart.count - 1)).rounded(.down))] + introUnderOutgoingDb
        if trim <= 0 { return 1 }
        return trim > introTrimCapDb ? nil : pow(10, -trim / 20)
    }

    /// The best scoring shape; the later one wins a tie. Shapes that cut over `keptMusicLimitMs`
    /// of the outgoing song's full-level music are left out; nil when no other is left.
    func bestOf(_ shapes: [Shape], _ score: (Shape) -> Double) -> Shape? {
        let pool = shapes.filter { kept($0) <= Double(keptMusicLimitMs) }
        if pool.isEmpty { return nil }
        var best = pool[pool.count - 1]
        var bestScore = -Double.infinity
        for s in pool {
            let value = score(s)
            if value >= bestScore {
                bestScore = value
                best = s
            }
        }
        return best
    }

    /// The terms every shape shares: ending early, music cut, a section boundary where the beats
    /// meet, and the bar and phrase it starts on.
    func sharedScore(_ s: Shape) -> Double {
        var score = -0.08 * (latestEnd - s.endMs) / 1000
        if a.boundariesMs.contains(where: { abs(Double($0) - s.lockMs) <= 250 }) { score += 1 }
        if let grid = lockA, let phraseAnchor, s.onBar {
            score += 0.6
            let barsBack = Int(roundHalfUp((phraseAnchor - s.lockMs) / grid.barMs))
            if barsBack % 8 == 0 { score += 0.8 }
        }
        return score - keptMusicWeight * kept(s) / 1000
    }

    func blendScore(_ s: Shape) -> Double {
        var score = sharedScore(s)
        if outroStart < latestEnd - 1000 { score -= 0.15 * abs(s.lockMs - outroStart) / 1000 }
        return score - 1.5 * measured.shareAtLeast(s.startMs, s.endMs, a.bodyDb - 3)
    }

    func blendPlan(_ s: Shape, _ lengthened: Bool) -> TransitionPlan {
        let kind = kindOf(s.overlapMs, measured, head, s.startMs, entry)
        let startMs = Int(roundHalfUp(s.startMs))
        let overlapMs = Int(roundHalfUp(s.endMs)) - startMs
        let entryMs = Int(roundHalfUp(entry))
        var reason = kind.rawValue.lowercased()
        reason += " at \(automixSeconds(startMs)) over \(automixSeconds(overlapMs))"
        reason += barsWords(overlapMs)
        reason += ", next song from \(automixSeconds(entryMs))"
        if lengthened, let full = headFeatures?.introEndMs { reason += " (full from \(automixSeconds(full)))" }
        reason += ", outro at \(automixSeconds(Int(outroStart)))"
        reason += ", latest end \(automixSeconds(Int(latestEnd)))"
        if head == nil { reason += ", no analysis of the next song's start" }
        if let rate { reason += ", rate \(automixDecimals(rate, 3))" }
        return finish(
            TransitionPlan(startMs: startMs, entryMs: entryMs, overlapMs: overlapMs, kind: kind, k: curveOf(kind), filterStrength: sweeps,
                           beatMatchRate: rate, beatMs: lockA?.beatMs, beatAnchorMs: lockA?.firstBeatMs, late: false,
                           headroomDb: overlapHeadroomDb, reason: reason, holdShare: leadHoldShare(overlapMs),
                           latestEndMs: Int(roundHalfUp(latestEnd)), lateMoveBars: 0),
            s.lockMs)
    }

    /// The share of a blend `overlapMs` long held through the incoming song's silent lead-in.
    func leadHoldShare(_ overlapMs: Int) -> Double { leadHold > 0 ? min(1, leadHold / Double(overlapMs)) : 0 }

    /// " (N bars of X BPM)" when the overlap is whole bars of a locked plan.
    func barsWords(_ overlapMs: Int) -> String {
        guard let grid = lockA else { return "" }
        let bars = roundHalfUp(Double(overlapMs) / grid.barMs)
        guard bars >= 1 && abs(Double(overlapMs) - bars * grid.barMs) <= 1 else { return "" }
        return " (\(Int(bars)) bars of \(automixDecimals(grid.displayBpm, 1)) BPM)"
    }

    /// The next song's quiet start played under the end of this one: the outgoing song holds,
    /// then fades over two bars (or `introFadeMs` unlocked, at most half the quiet start) centered
    /// on where the next song is full, a bar line from where the beats meet when locked; the next
    /// song rises over a bar (`introRiseMs` unlocked; a quarter of the overlap or the hold when
    /// shorter), lowered through the hold as far as each start
    /// needs to stay under the outgoing song (`underOutgoing`). Nil when no start is left, the beats
    /// or levels allow none or every one left would cut the outgoing song short, and the lengthened
    /// blend is planned instead.
    func planIntro(_ quiet: Double) -> TransitionPlan? {
        let bar = lockA?.barMs
        let rise = bar ?? Double(introRiseMs)
        let fade = min(bar.map { Double(introFadeBars) * $0 } ?? Double(introFadeMs), quiet / 2)
        let longest = Double(settings.longestIntroMs)
        let fullAt: Double = if let bar {
            lead + min(((quiet - lead) / bar - 1e-9).rounded(.up), ((longest - fade / 2 - lead) / bar + 1e-9).rounded(.down)) * bar
        } else {
            min(quiet, longest - fade / 2)
        }
        let hold = fullAt - fade / 2
        if hold <= 0 { return nil }
        let capped = fullAt < quiet - 1e-6
        let overlap = (fullAt + fade / 2 + 0.5).rounded(.down)
        func intro(_ start: Double, _ lock: Double, _ length: Double) -> Shape {
            Shape(startMs: start, lockMs: lock, overlapMs: length, onBar: bar != nil, intro: true,
                  holdShare: hold / length, riseShare: min(rise, length / 4, hold) / length)
        }
        let hi = latestEnd - overlap
        if hi < lo { return nil }
        let shapes: [Shape] = if let lockA {
            lockA.barsBetween(lo + lead, hi + lead).map { intro($0 - lead, $0, overlap) }
        } else {
            steps(lo, hi).map { intro($0, $0, overlap) }
        }
        guard let last = shapes.last else { return nil }
        var all = shapes
        if let bar, !shapes.contains(where: { kept($0) <= Double(keptMusicLimitMs) }) {
            // Only the outgoing song's fade end moves, to the latest end.
            let length = latestEnd - last.startMs
            if length <= longest {
                all.append(intro(last.startMs, last.lockMs, length))
            } else {
                // Over the longest intro: one bar later, keeping where the next song is full from
                // the start, when the fade left is still at least half the planned one.
                let lock = last.lockMs + bar
                let start = lock - lead
                if latestEnd - start - hold >= fade / 2 { all.append(intro(start, lock, latestEnd - start)) }
            }
        }
        let allowed = all.compactMap { s -> Shape? in
            guard let level = underOutgoing(s) else { return nil }
            var lowered = s
            lowered.level = level
            return lowered
        }.filter { meets($0) }
        if allowed.isEmpty { return nil }
        guard let best = bestOf(allowed, { s in
            var score = sharedScore(s)
            if outroStart < latestEnd - 1000 { score -= 0.15 * abs(s.startMs + hold - outroStart) / 1000 }
            return score
        }) else { return nil }
        let startMs = Int(roundHalfUp(best.startMs))
        let overlapMs = Int(roundHalfUp(best.endMs)) - startMs
        let entryMs = Int(roundHalfUp(entry))
        var reason = "intro at \(automixSeconds(startMs)) over \(automixSeconds(overlapMs))"
        reason += barsWords(overlapMs)
        reason += ", next song from \(automixSeconds(entryMs))"
        reason += " (full from \(automixSeconds(headFeatures?.introEndMs ?? entryMs))"
        if capped { reason += ", capped" }
        reason += "), outro at \(automixSeconds(Int(outroStart)))"
        reason += ", latest end \(automixSeconds(Int(latestEnd)))"
        if best.level < 1 { reason += ", next song lowered \(automixDecimals(-20 * log10(best.level), 1)) dB" }
        if let rate { reason += ", rate \(automixDecimals(rate, 3))" }
        return finish(
            TransitionPlan(startMs: startMs, entryMs: entryMs, overlapMs: overlapMs, kind: .intro, k: curveOf(.intro), filterStrength: 0,
                           beatMatchRate: rate, beatMs: lockA?.beatMs, beatAnchorMs: lockA?.firstBeatMs, late: false,
                           headroomDb: overlapHeadroomDb, reason: reason, holdShare: hold / Double(overlapMs),
                           riseShare: min(rise, Double(overlapMs) / 4, hold) / Double(overlapMs), introLevel: best.level,
                           latestEndMs: Int(roundHalfUp(latestEnd)), lateMoveBars: 0),
            best.lockMs)
    }

    /// The blend at the end, ending at the latest end, when no planned start is left after
    /// `earliest`. Locked: from A's first bar line at or after the later of `earliest` and a full
    /// `overlap` before the end, capped to the drift bound like a planned blend. Otherwise as it
    /// always was, and with the beats unable to line up only when both songs are never heard
    /// together.
    func latePlan(_ earliest: Double, _ overlap: Double, _ why: String) -> TransitionPlan {
        guard let grid = lockA else {
            var plan = loosePlan(lenA, latestEnd, overlap, earliest, Int(roundHalfUp(entry)), measured, head, sweeps, why)
            if plan.kind == .gapless {
                plan.lateMoveBars = lateMoveUnbounded
                return plan
            }
            if blind && stretch(shapeOf(plan, Double(plan.startMs))) != nil { return gaplessPlan(lenA, unheardReason()) }
            return finish(plan, Double(plan.startMs))
        }
        let from = max(earliest, latestEnd - overlap)
        let lock = grid.downbeatFrom(from + lead)
        let start = lock - lead
        let length = latestEnd - start
        let placed: Shape? = if !bounded {
            Optional(blend(start, lock, length, onBar: true)).flatMap { fades($0) ? $0 : nil }
        } else if length >= Double(shortestBlendMs) {
            cap(blend(start, lock, length, onBar: true), from)
        } else {
            cut(latestEnd, from)
        }
        guard let shape = placed else { return gaplessPlan(lenA, "too late to blend: \(why)") }
        let kind = kindOf(shape.overlapMs, measured, head, shape.startMs, entry)
        let startMs = Int(roundHalfUp(shape.startMs))
        let overlapMs = Int(roundHalfUp(shape.endMs)) - startMs
        let entryMs = Int(roundHalfUp(entry))
        let matched = rate.map { ", rate \(automixDecimals($0, 3))" } ?? ""
        let reason = "late \(kind.rawValue.lowercased()) at \(automixSeconds(startMs)) over \(automixSeconds(overlapMs)), next song from "
            + "\(automixSeconds(entryMs))\(matched): \(why)"
        return finish(
            TransitionPlan(startMs: startMs, entryMs: entryMs, overlapMs: overlapMs, kind: kind, k: curveOf(kind), filterStrength: sweeps,
                           beatMatchRate: rate, beatMs: grid.beatMs, beatAnchorMs: grid.firstBeatMs, late: true,
                           headroomDb: overlapHeadroomDb, reason: reason, holdShare: leadHoldShare(overlapMs),
                           latestEndMs: Int(roundHalfUp(latestEnd)), lateMoveBars: 0),
            shape.lockMs)
    }

    func unheardReason() -> String { bounded ? "the beats would drift apart" : "the beats cannot be lined up" }

    func shapeOf(_ plan: TransitionPlan, _ lock: Double) -> Shape {
        Shape(startMs: Double(plan.startMs), lockMs: lock, overlapMs: Double(plan.overlapMs), onBar: false, intro: plan.kind == .intro,
              holdShare: plan.holdShare, riseShare: plan.riseShare, k: plan.k, level: plan.introLevel)
    }

    /// The plan with how far a late start may move it, where both songs are heard together, where
    /// its beats meet and how far they part.
    func finish(_ plan: TransitionPlan, _ lock: Double) -> TransitionPlan {
        let shape = shapeOf(plan, lock)
        let heard = stretch(shape)
        var done = plan
        done.lateMoveBars = lateMoveBarsOf(shape, plan)
        done.bothHeardStartMs = heard.map { Int(roundHalfUp($0.0 - Double(plan.startMs))) } ?? 0
        done.bothHeardEndMs = heard.map { Int(roundHalfUp($0.1 - Double(plan.startMs))) } ?? 0
        done.lockMs = lock - Double(plan.startMs)
        done.driftGap = bounded ? gap : 0
        done.driftAtEndMs = bounded ? offset(shape) : 0
        return done
    }

    /// The most whole bars the plan may move later and still end by the latest end with its beats
    /// within the bound there; unbounded when the bound does not depend on where it sits. None
    /// for a plan whose beats could not be lined up, kept only where both songs are never heard
    /// together, which a move could change. An intro is never unbounded: a move must also leave
    /// its incoming song needing no deeper trim than planned (`trimHolds`). With a bar grid it
    /// counts those bars; with none, where the player moves it any amount up to the latest end, it
    /// is 1 when there is room and every `startStepMs` step there (and the latest end itself) keeps
    /// that, else 0.
    func lateMoveBarsOf(_ shape: Shape, _ plan: TransitionPlan) -> Int {
        if blind { return 0 }
        let end = Double(plan.startMs + plan.overlapMs)
        let latest = Double(plan.latestEndMs)
        if shape.intro {
            func keeps(_ ms: Double) -> Bool {
                let moved = shape.shifted(ms)
                return meets(moved) && trimHolds(moved)
            }
            guard let bar = lockA?.barMs else {
                let room = latest - end
                if room <= 1e-6 { return 0 }
                var ms = Double(startStepMs)
                while ms < room - 1e-6 {
                    if !keeps(ms) { return 0 }
                    ms += Double(startStepMs)
                }
                return keeps(room) ? 1 : 0
            }
            var bars = 0
            while end + Double(bars + 1) * bar <= latest + 1e-6 && keeps(Double(bars + 1) * bar) { bars += 1 }
            return bars
        }
        guard bounded, let bar = lockA?.barMs else { return lateMoveUnbounded }
        var bars = 0
        while end + Double(bars + 1) * bar <= latest + 1e-6 && meets(shape.shifted(Double(bars + 1) * bar)) { bars += 1 }
        return bars
    }

    /// Whether an intro's shape, at the level it was planned with, needs no deeper trim where it
    /// sits: the hold's level is no lower, and the mix's excess over the louder song alone is within
    /// `introExcessLimitDb`.
    func trimHolds(_ s: Shape) -> Bool {
        guard let held = holdLevel(s) else { return false }
        return held >= s.level - 1e-9 && excess(s, s.level) <= introExcessLimitDb
    }
}

/// A tempo ratio folded by halving or doubling into 0.707 to 1.414, so a song at half or double
/// the tempo counts as the same tempo.
public func foldTempoRatio(_ ratio: Double) -> Double {
    if ratio <= 0 { return ratio }
    var r = ratio
    while r > 1.4142135623730951 { r /= 2 }
    while r < 0.7071067811865476 { r *= 2 }
    return r
}

/// A cut for a short overlap; a lift when the next song's first 8 s are at least 3 dB louder than
/// this song through the overlap; else a blend.
private func kindOf(_ overlap: Double, _ tail: SectionAnalysis?, _ head: SectionAnalysis?, _ start: Double, _ entry: Double) -> TransitionKind {
    if overlap <= Double(cutBlendMs) { return .cut }
    if let tail, let head, head.levelDb(entry, entry + 8_000) >= tail.levelDb(start, start + overlap) + 3 { return .lift }
    return .blend
}

private func curveOf(_ kind: TransitionKind) -> Double {
    switch kind {
    case .cut: cutCurve
    case .lift: liftCurve
    case .blend: blendCurve
    // An intro's shape has no curve weight.
    case .intro: 0
    default: crossfadeCurve
    }
}

/// The blend at the end with no bar lock: ending at latestEnd, over `overlap` or whatever is left
/// after `earliest`, with the curve and filters. Too little left to blend plays gaplessly.
private func loosePlan(_ lenA: Int, _ latestEnd: Double, _ overlap: Double, _ earliest: Double, _ entryMs: Int, _ tail: SectionAnalysis?,
                       _ head: SectionAnalysis?, _ sweeps: Double, _ why: String) -> TransitionPlan {
    let start = max(earliest, latestEnd - overlap)
    let length = (latestEnd - start + 0.5).rounded(.down)
    if length < Double(shortestFadeMs) { return gaplessPlan(lenA, "too late to blend: \(why)") }
    let kind = kindOf(length, tail, head, start, Double(entryMs))
    let startMs = Int((start + 0.5).rounded(.down))
    return TransitionPlan(
        startMs: startMs, entryMs: entryMs, overlapMs: Int(length), kind: kind, k: curveOf(kind), filterStrength: sweeps,
        beatMatchRate: nil, beatMs: nil, beatAnchorMs: nil, late: true, headroomDb: overlapHeadroomDb,
        reason: "late \(kind.rawValue.lowercased()) at \(automixSeconds(startMs)) over \(automixSeconds(Int(length))), next song from \(automixSeconds(entryMs)): \(why)",
        latestEndMs: Int((latestEnd + 0.5).rounded(.down)), lateMoveBars: lateMoveUnbounded)
}

private func gaplessPlan(_ lenA: Int, _ reason: String) -> TransitionPlan {
    TransitionPlan(startMs: max(0, lenA), entryMs: 0, overlapMs: 0, kind: .gapless, k: crossfadeCurve, filterStrength: 0,
                   beatMatchRate: nil, beatMs: nil, beatAnchorMs: nil, late: false, headroomDb: 0, reason: "gapless: \(reason)",
                   latestEndMs: max(0, lenA), lateMoveBars: lateMoveUnbounded)
}

/// The fixed crossfade at the end of the song: no filters, on the chosen curve. Equal power is
/// the crossfade as it was before planned transitions, with no curve weight and no headroom;
/// mix only plays both songs a little quieter through the overlap.
public func fixedCrossfadePlan(lenA: Int, length: Int, why: String, curve: FadeCurve = .equalPower) -> TransitionPlan {
    let start = max(0, lenA - length)
    let named = curve == .equalPower ? "" : ", \(curve.label.lowercased()) fade"
    return TransitionPlan(startMs: start, entryMs: 0, overlapMs: length, kind: .crossfade, k: 0, filterStrength: 0,
                          beatMatchRate: nil, beatMs: nil, beatAnchorMs: nil, late: false,
                          headroomDb: curve == .mixOnly ? mixOnlyHeadroomDb : 0,
                          reason: "crossfade at \(automixSeconds(start)) over \(automixSeconds(length)): \(why)\(named)",
                          curve: curve == .equalPower ? nil : curve, latestEndMs: lenA, lateMoveBars: lateMoveUnbounded)
}

private func crossfadePlan(_ lenA: Int, _ length: Int, _ why: String) -> TransitionPlan {
    TransitionPlan(startMs: lenA - length, entryMs: 0, overlapMs: length, kind: .crossfade, k: crossfadeCurve, filterStrength: 0,
                   beatMatchRate: nil, beatMs: nil, beatAnchorMs: nil, late: false, headroomDb: overlapHeadroomDb,
                   reason: "crossfade at \(automixSeconds(lenA - length)) over \(automixSeconds(length)): \(why)",
                   latestEndMs: lenA, lateMoveBars: lateMoveUnbounded)
}

private func gaplessReason(_ current: FadeSong, _ next: FadeSong?, _ fadeMs: Int, _ repeatOne: Bool, _ stopAtEndOfSong: Bool) -> String {
    guard fadeMs > 0 else { return "crossfade off" }
    guard let next else { return "nothing next" }
    if repeatOne { return "repeat one" }
    if stopAtEndOfSong { return "stopping at the end of the song" }
    if current.durationMs <= 0 || next.durationMs <= 0 { return "a song's length is unknown" }
    if let album = current.albumId, album == next.albumId { return "the album plays in order" }
    return "a song too short to blend"
}

/// Milliseconds as seconds with two decimals, like "182.50 s".
func automixSeconds(_ ms: Int) -> String {
    let sign = ms < 0 ? "-" : ""
    let magnitude = abs(ms)
    let hundredths = (magnitude % 1000) / 10
    return "\(sign)\(magnitude / 1000).\(hundredths < 10 ? "0" : "")\(hundredths) s"
}

/// A positive number with a fixed count of decimals, like "120.0".
func automixDecimals(_ x: Double, _ places: Int) -> String {
    var scale = 1
    for _ in 0..<places { scale *= 10 }
    let scaled = Int((x * Double(scale) + 0.5).rounded(.down))
    var part = "\(scaled % scale)"
    while part.count < places { part = "0" + part }
    return "\(scaled / scale).\(part)"
}
