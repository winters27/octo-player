import Foundation

/// The curve weight of each kind of transition: how much of the S-shaped curve is mixed into the
/// equal-power one. Higher swaps the songs faster around the middle.
public let cutCurve = 0.75
public let liftCurve = 0.55
public let blendCurve = 0.2
public let crossfadeCurve = 0.4

/// How deep the filter sweeps go, 0 for none and 1 for the full range.
public let filterStrength = 0.7

/// The outgoing song's low-pass runs from `openLowPassHz` down toward `lowPassFloorHz`.
public let openLowPassHz = 18_000.0
public let lowPassFloorHz = 450.0

/// The outgoing song's bass cut rises from `openHighPassHz` toward `bassSwapHz` by the middle of
/// the blend.
public let openHighPassHz = 10.0
public let bassSwapHz = 300.0
public let bassSwapAt = 0.5

/// The incoming song's high-pass starts at `incomingHighPassHz` and opens to `incomingOpenHz` by
/// `incomingOpenAt` through the blend.
public let incomingHighPassHz = 675.0
public let incomingOpenHz = 20.0
public let incomingOpenAt = 0.6

/// After the blend a tempo-matched incoming song eases back to its own speed over this long.
public let beatMatchSettleMs = 5_000.0

private func sigmoid12(_ t: Double) -> Double { 1 / (1 + exp(-12 * (t - 0.5))) }

private let sigmoidLow = sigmoid12(0)
private let sigmoidHigh = sigmoid12(1)

private func clamp01(_ x: Double) -> Double { min(max(x, 0), 1) }

/// The S curve scaled to run exactly from 0 at the start to 1 at the end.
public func sCurve(_ t: Double) -> Double { (sigmoid12(clamp01(t)) - sigmoidLow) / (sigmoidHigh - sigmoidLow) }

/// The outgoing song's volume at `t` through the blend (0 to 1): an equal-power fade mixed with
/// an S curve by weight `k`.
public func automixOutGain(_ t: Double, _ k: Double) -> Double {
    let p = clamp01(t)
    return clamp01(cos(p * Double.pi / 2) * (1 - k) + (1 - sCurve(p)) * k)
}

/// The incoming song's volume: whatever keeps the two volumes' squares at one.
public func automixInGain(_ t: Double, _ k: Double) -> Double {
    let out = automixOutGain(t, k)
    return max(0, 1 - out * out).squareRoot()
}

/// Where a blend whose start is held is along its curve at `t` (0 to 1): the outgoing song alone
/// at its own level until `holdShare`, while the incoming one's first sound is still to come,
/// then the curve over the rest of the overlap. Below 0 while held, where every curve gives the
/// outgoing song 1 and the incoming one 0.
public func pastHold(_ t: Double, _ holdShare: Double) -> Double {
    if holdShare <= 0 { return t }
    if holdShare >= 1 { return -1 }
    return (t - holdShare) / (1 - holdShare)
}

/// The outgoing song's volume at `t` through an intro (0 to 1): full until `holdShare`, then an
/// equal-power fade to nothing at the end.
public func introOutGain(_ t: Double, _ holdShare: Double) -> Double {
    let p = clamp01(t)
    let hold = clamp01(holdShare)
    if p <= hold { return 1 }
    return clamp01(cos((p - hold) / (1 - hold) * Double.pi / 2))
}

/// The incoming song's volume through an intro: rising as sin(pi/2 * t / rise) to `level` by
/// `rise` (`riseShare`, ending by `holdShare` at the latest), then `level` until `holdShare`, so its
/// quiet start plays under the outgoing song at that much of its own level; past the hold it comes
/// up as the outgoing song's power leaves, sqrt(level^2 + (1 - level^2) * (1 - out^2)) with `out`
/// the outgoing song's `introOutGain`, to its own level at the end. At level 1 it is full from the
/// rise's end on.
public func introInGain(_ t: Double, _ riseShare: Double, _ holdShare: Double, _ level: Double) -> Double {
    let p = clamp01(t)
    let hold = clamp01(holdShare)
    let rise = min(clamp01(riseShare), hold)
    let g = clamp01(level)
    if p < rise { return clamp01(sin(Double.pi / 2 * p / rise) * g) }
    if p <= hold { return g }
    let out = introOutGain(p, holdShare)
    return (g * g + (1 - g * g) * (1 - out * out)).squareRoot()
}

/// How the two volumes cross in a plain crossfade, the one played with smart transitions off.
public enum FadeCurve: String, StoredChoice, Codable {
    /// Steady loudness between two different songs: the squares of the volumes add up to one.
    case equalPower = "EqualPower"
    /// Straight lines, with a slight dip in the middle.
    case linear = "Linear"
    /// Holds each song longer, then swaps them quickly around the middle.
    case sCurve = "SCurve"
    /// The same step down in decibels every moment, to -60 dB, then silence; mirrored coming in.
    case evenLoudness = "EvenLoudness"
    /// Both songs at full volume, the outgoing one cut quickly at the end and the incoming one
    /// brought in quickly at the start, with headroom against overs.
    case mixOnly = "MixOnly"

    public var label: String {
        switch self {
        case .equalPower: "Equal power"
        case .linear: "Linear"
        case .sCurve: "S-curve"
        case .evenLoudness: "Even in loudness"
        case .mixOnly: "Mix only"
        }
    }

    public var detail: String {
        switch self {
        case .equalPower: "Steady loudness through the fade."
        case .linear: "A straight fade, softer midway."
        case .sCurve: "Holds each song, then swaps fast."
        case .evenLoudness: "Steps down the same each second."
        case .mixOnly: "Both play full, like a DJ mix."
        }
    }
}

/// How far an even-in-loudness fade falls, at `evenLoudnessFloorAt` through it, before it ends
/// in silence.
public let evenLoudnessFloorDb = -60.0
public let evenLoudnessFloorAt = 0.95

/// The outgoing volume of an even-in-loudness fade at `t`: the same number of decibels down
/// every moment until the floor, then a straight line to silence at the end.
func evenLoudnessGain(_ t: Double) -> Double {
    if t <= evenLoudnessFloorAt { return pow(10, evenLoudnessFloorDb * t / evenLoudnessFloorAt / 20) }
    return pow(10, evenLoudnessFloorDb / 20) * (1 - t) / (1 - evenLoudnessFloorAt)
}

/// How long a mix-only fade takes to cut the outgoing song at the end, and to bring in the
/// incoming one at the start.
public let mixOnlyRampMs = 300.0

/// How much quieter both songs play through a mix-only overlap.
public let mixOnlyHeadroomDb = 3.0

/// The outgoing and incoming volumes at `t` through a crossfade (0 to 1) of `overlapMs`, on
/// `curve`. Every curve starts at (1, 0), ends at (0, 1) and never goes above 1. Equal power is
/// the same as the planned curves with no S weight.
public func fadeGains(_ curve: FadeCurve, _ t: Double, overlapMs: Int) -> (out: Double, incoming: Double) {
    fadeGains(curve, t, overlapMs: Double(overlapMs))
}

/// The same over an overlap that need not be whole milliseconds, such as what is left of a blend
/// after its held start.
public func fadeGains(_ curve: FadeCurve, _ t: Double, overlapMs: Double) -> (out: Double, incoming: Double) {
    let p = clamp01(t)
    switch curve {
    case .equalPower:
        return (automixOutGain(p, 0), automixInGain(p, 0))
    case .linear:
        return (1 - p, p)
    case .sCurve:
        let out = 1 - sCurve(p)
        return (out, max(0, 1 - out * out).squareRoot())
    case .evenLoudness:
        return (clamp01(evenLoudnessGain(p)), clamp01(evenLoudnessGain(1 - p)))
    case .mixOnly:
        // The quick fades take their real length, up to half the overlap each.
        let ramp = overlapMs > 0 ? min(0.5, mixOnlyRampMs / overlapMs) : 0.5
        let out = p >= 1 ? 0 : p <= 1 - ramp ? 1 : cos((p - (1 - ramp)) / ramp * Double.pi / 2)
        let into = p >= ramp ? 1 : sin(p / ramp * Double.pi / 2)
        return (clamp01(out), clamp01(into))
    }
}

/// Moves from `from` to `to` on a log scale as u goes from 0 to 1.
private func logBetween(_ from: Double, _ to: Double, _ u: Double) -> Double { from * pow(to / from, clamp01(u)) }

/// The outgoing song's low-pass cutoff in Hz at `t`, falling smoothly.
public func outgoingLowPassHz(_ t: Double, _ strength: Double) -> Double {
    let end = openLowPassHz * pow(lowPassFloorHz / openLowPassHz, clamp01(strength))
    return logBetween(openLowPassHz, end, t)
}

/// The same low-pass moving in steps on the beat: it holds each beat's cutoff until `glide`
/// before the next beat, then glides to the next one. `beats` are the beats' places through the
/// blend (0 to 1, rising) and `glide` is in the same units.
public func steppedOutgoingLowPassHz(_ t: Double, _ strength: Double, _ beats: [Double], _ glide: Double) -> Double {
    let p = clamp01(t)
    var from = 0.0
    var to = 1.0
    for beat in beats {
        if beat <= 0 || beat >= 1 { continue }
        if beat <= p { from = beat } else { to = beat; break }
    }
    let g = min(max(0, glide), to - from)
    let glideStart = to - g
    let at = (p < glideStart || g <= 0) ? from : from + (p - glideStart) / g * (to - from)
    return outgoingLowPassHz(at, strength)
}

/// The outgoing song's bass cut in Hz at `t`: rises to its top by the middle of the blend and
/// holds there.
public func outgoingHighPassHz(_ t: Double, _ strength: Double) -> Double {
    let top = max(openHighPassHz, bassSwapHz * clamp01(strength))
    return logBetween(openHighPassHz, top, t / bassSwapAt)
}

/// The incoming song's high-pass cutoff in Hz at `t`: thin at first, fully open by
/// `incomingOpenAt`.
public func incomingHighPassHz(_ t: Double, _ strength: Double) -> Double {
    let start = max(incomingOpenHz, incomingHighPassHz * clamp01(strength))
    return logBetween(start, incomingOpenHz, t / incomingOpenAt)
}

/// The incoming song's playback rate `sinceEntryMs` after it came in: `rate` through the blend,
/// then eased back to 1 over `beatMatchSettleMs`.
public func beatMatchRateAt(_ rate: Double, _ sinceEntryMs: Double, _ overlapMs: Double) -> Double {
    if sinceEntryMs <= overlapMs { return rate }
    let u = (sinceEntryMs - overlapMs) / beatMatchSettleMs
    if u >= 1 { return 1 }
    return rate + (1 - rate) * (1 - cos(Double.pi * u)) / 2
}
