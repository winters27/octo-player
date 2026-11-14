import Foundation
@testable import OctoKit

/// A made-up song built from layers, rendered the same way by every engine's tests from
/// automix-vectors.json. Each layer sounds from fromMs to toMs with its level moving in a
/// straight line (in dB) from db to endDb.
///
/// tone:  amplitude * sin(2 pi hz n / rate), n the sample index in the song.
/// pulse: a hit at firstMs + j * every * 60000 / bpm for every whole j with the hit inside
///        [fromMs, toMs); each hit adds amplitude(hit time) * exp(-d / decayMs) * sin(2 pi hz d / 1000)
///        for d = sample time minus hit time in ms, 0 <= d < 8 * decayMs.
/// The sample time of sample n is n * 1000 / rate ms. Every channel carries the same signal.
struct SongLayer: Equatable {
    var kind: String
    var fromMs: Double
    var toMs: Double
    var hz: Double
    var db: Double
    var endDb: Double
    var bpm: Double = 0
    var firstMs: Double = 0
    var every: Int = 1
    var decayMs: Double = 0

    init(_ kind: String, _ fromMs: Double, _ toMs: Double, hz: Double, db: Double, endDb: Double? = nil, bpm: Double = 0,
         firstMs: Double = 0, every: Int = 1, decayMs: Double = 0) {
        self.kind = kind
        self.fromMs = fromMs
        self.toMs = toMs
        self.hz = hz
        self.db = db
        self.endDb = endDb ?? db
        self.bpm = bpm
        self.firstMs = firstMs
        self.every = every
        self.decayMs = decayMs
    }

    func amplitudeAt(_ ms: Double) -> Double {
        let u = toMs > fromMs ? min(max((ms - fromMs) / (toMs - fromMs), 0), 1) : 0
        return pow(10, (db + (endDb - db) * u) / 20)
    }
}

struct SyntheticSong {
    var rate: Int
    var channels: Int
    var lengthMs: Int
    var layers: [SongLayer]

    /// Mono samples for song times [fromMs, toMs).
    func renderMono(_ fromMs: Double, _ toMs: Double) -> [Float] {
        let r = Double(rate)
        let first = Int((fromMs * r / 1000).rounded(.down))
        let end = Int((min(toMs, Double(lengthMs)) * r / 1000).rounded(.down))
        var out = [Double](repeating: 0, count: max(0, end - first))
        for layer in layers {
            switch layer.kind {
            case "tone":
                let from = max(first, Int((layer.fromMs * r / 1000).rounded(.up)))
                let to = min(end, Int((layer.toMs * r / 1000).rounded(.up)))
                if from < to {
                    for n in from..<to {
                        let ms = Double(n) * 1000.0 / r
                        out[n - first] += layer.amplitudeAt(ms) * sin(2 * Double.pi * layer.hz * Double(n) / r)
                    }
                }
            case "pulse":
                let gap = Double(layer.every) * 60_000.0 / layer.bpm
                var j = Int(((layer.fromMs - layer.firstMs) / gap).rounded(.up))
                while true {
                    let hit = layer.firstMs + Double(j) * gap
                    if hit >= layer.toMs { break }
                    j += 1
                    if hit < layer.fromMs { continue }
                    let tail = 8 * layer.decayMs
                    if hit + tail < fromMs || hit >= toMs { continue }
                    let amplitude = layer.amplitudeAt(hit)
                    let from = max(first, Int((hit * r / 1000).rounded(.up)))
                    let to = min(end, Int(((hit + tail) * r / 1000).rounded(.up)))
                    if from >= to { continue }
                    for n in from..<to {
                        let d = Double(n) * 1000.0 / r - hit
                        if d < 0 || d >= tail { continue }
                        out[n - first] += amplitude * exp(-d / layer.decayMs) * sin(2 * Double.pi * layer.hz * d / 1000)
                    }
                }
            default:
                fatalError("unknown layer \(layer.kind)")
            }
        }
        return out.map { Float($0) }
    }

    /// The envelope of [fromMs, toMs), fed to the builder in blocks of `block` interleaved samples
    /// so the streaming path is exercised.
    func envelope(_ fromMs: Double, _ toMs: Double, block: Int = 4_093) -> SectionEnvelope {
        let mono = renderMono(fromMs, toMs)
        let interleaved = channels == 1 ? mono : (0..<(mono.count * channels)).map { mono[$0 / channels] }
        let startMs = Int((fromMs * Double(rate) / 1000).rounded(.down)) * 1000 / rate
        let builder = EnvelopeBuilder(sampleRate: rate, channels: channels, startMs: startMs)
        var at = 0
        while at < interleaved.count {
            let length = min(block, interleaved.count - at)
            builder.push(interleaved, offset: at, length: length)
            at += length
        }
        return builder.build()
    }

    /// The last 60 s and the first 45 s, as the player scouts them.
    func tail() -> SectionAnalysis { analyzeTail(envelope(max(0, Double(lengthMs) - 60_000), Double(lengthMs))) }

    func head() -> SectionAnalysis { analyzeHead(headEnvelope()) }

    /// The whole song's body level, as a profile made from the whole file measures it.
    func wholeBodyDb() -> Double? { bodyLevelOf(envelope(0, Double(lengthMs))) }

    /// The first 45 s measured against the whole song's body, as a profile holds them; `bodyDb`
    /// stands in for that body.
    func profileHead(_ bodyDb: Double?) -> SectionAnalysis { analyzeHead(headEnvelope(), bodyLevelDb: bodyDb) }

    func profileHead() -> SectionAnalysis { profileHead(wholeBodyDb()) }

    private func headEnvelope() -> SectionEnvelope { envelope(0, min(Double(automixHeadMs), Double(lengthMs))) }
}

/// The made-up songs and song pairs the automix tests and automix-vectors.json share.
enum AutomixCases {
    private static func pad(_ fromMs: Double, _ toMs: Double, _ db: Double, _ endDb: Double? = nil) -> [SongLayer] {
        let end = endDb ?? db
        return [
            SongLayer("tone", fromMs, toMs, hz: 300, db: db, endDb: end),
            SongLayer("tone", fromMs, toMs, hz: 100, db: db - 3, endDb: end - 3),
        ]
    }

    /// A kick on every bar and a click on every beat over a quiet pad.
    private static func beat(_ lengthMs: Double, _ bpm: Double, _ firstMs: Double) -> [SongLayer] {
        [
            SongLayer("tone", firstMs, lengthMs, hz: 300, db: -26),
            SongLayer("pulse", firstMs, lengthMs, hz: 60, db: -6, bpm: bpm, firstMs: firstMs, every: 4, decayMs: 80),
            SongLayer("pulse", firstMs, lengthMs, hz: 2_000, db: -16, bpm: bpm, firstMs: firstMs, every: 1, decayMs: 10),
        ]
    }

    /// A kick on every bar from `barMs` and a click on every beat from `beatMs` over a pad at
    /// `padDb`, sounding from fromMs to toMs: a steady level with a clear beat. Pieces with the
    /// same grid join on the beat.
    private static func steady(_ fromMs: Double, _ toMs: Double, _ bpm: Double, _ beatMs: Double, barMs: Double? = nil,
                               padDb: Double = -14) -> [SongLayer] {
        pad(fromMs, toMs, padDb) + [
            SongLayer("pulse", fromMs, toMs, hz: 60, db: padDb + 4, bpm: bpm, firstMs: barMs ?? beatMs, every: 4, decayMs: 80),
            SongLayer("pulse", fromMs, toMs, hz: 2_000, db: padDb - 4, bpm: bpm, firstMs: beatMs, every: 1, decayMs: 10),
        ]
    }

    /// A steady beat from `beatMs` whose first `quietMs` are 24 dB under the rest.
    private static func quietStart(_ lengthMs: Double, _ bpm: Double, _ quietMs: Double, beatMs: Double = 0) -> [SongLayer] {
        steady(0, quietMs, bpm, beatMs, padDb: -38) + steady(quietMs, lengthMs, bpm, beatMs)
    }

    /// 100 BPM (2.4 s bars from 0), its pad at each level from each time on: `steps` are
    /// (fromMs, padDb), the first from 0, the last to lengthMs.
    private static func levels(_ lengthMs: Double, _ steps: Double...) -> [SongLayer] {
        stride(from: 0, to: steps.count, by: 2).flatMap { i in
            steady(steps[i], i + 2 < steps.count ? steps[i + 2] : lengthMs, 100, 0, padDb: steps[i + 1])
        }
    }

    /// A steady beat whose first 10 s are 8 dB under the rest: a quiet start that is still clearly
    /// heard.
    private static func audibleStart(_ bpm: Double) -> [SongLayer] {
        steady(0, 10_000, bpm, 0, padDb: -22) + steady(10_000, 45_000, bpm, 0)
    }

    private static func song(_ rate: Int, _ lengthMs: Int, _ layers: [SongLayer]) -> SyntheticSong {
        SyntheticSong(rate: rate, channels: 1, lengthMs: lengthMs, layers: layers)
    }

    static let songs: [String: SyntheticSong] = [
        // 8 s of silence after the music stops.
        "silence-tail": SyntheticSong(rate: 44_100, channels: 2, lengthMs: 200_000, layers: pad(0, 192_000, -14)),
        // Full level until 180 s, then a 40 s fade to nothing.
        "fade-out": SyntheticSong(rate: 48_000, channels: 1, lengthMs: 220_000, layers: pad(0, 180_000, -14) + pad(180_000, 220_000, -14, -74)),
        // Full level to the last sample.
        "hot-end": SyntheticSong(rate: 44_100, channels: 2, lengthMs: 200_000, layers: pad(0, 200_000, -14)),
        "beat-120": SyntheticSong(rate: 48_000, channels: 2, lengthMs: 200_000, layers: beat(200_000, 120, 500)),
        "beat-120-b": SyntheticSong(rate: 22_050, channels: 1, lengthMs: 180_000, layers: beat(180_000, 120, 1_000)),
        "beat-100": SyntheticSong(rate: 44_100, channels: 1, lengthMs: 180_000, layers: beat(180_000, 100, 600)),
        "beat-118": SyntheticSong(rate: 44_100, channels: 1, lengthMs: 180_000, layers: beat(180_000, 118, 400)),
        "beat-128": SyntheticSong(rate: 44_100, channels: 1, lengthMs: 60_000, layers: beat(60_000, 128, 300)),
        // 1.5 s of silence, then full level.
        "plain-b": SyntheticSong(rate: 44_100, channels: 2, lengthMs: 180_000, layers: pad(1_500, 180_000, -14)),
        // 6 s of a quiet pad 24 dB under the body, then full level.
        "quiet-intro": SyntheticSong(rate: 44_100, channels: 2, lengthMs: 180_000, layers: pad(0, 6_000, -38) + pad(6_000, 180_000, -14)),
        // 35 s of the quiet pad, then full level: most of a head is intro.
        "long-intro": song(44_100, 180_000, pad(0, 35_000, -38) + pad(35_000, 180_000, -14)),
        // A minute of the quiet pad, past the end of any head.
        "endless-intro": song(44_100, 200_000, pad(0, 60_000, -38) + pad(60_000, 200_000, -14)),
        // A steady 100 BPM beat (2.4 s bars from 0) at full level from the first sample to the last.
        "steady-100": song(44_100, 202_000, steady(0, 202_000, 100, 0)),
        // 100 BPM at full level to the end but for one stretch 14 dB under it, where it is not
        // clearly heard: 3 s from 72.6 s, 16.4 s from 70 s and 13.6 s from 82.4 s, ending 24.15 s,
        // 13.35 s and 3.75 s before the latest end a blend may reach (99.75 s).
        "dip-far": song(22_050, 100_000, levels(100_000, 0, -14, 72_600, -28, 75_600, -14)),
        "dip-mid": song(22_050, 100_000, levels(100_000, 0, -14, 70_000, -28, 86_400, -14)),
        "dip-near": song(22_050, 100_000, levels(100_000, 0, -14, 82_400, -28, 96_000, -14)),
        // 100 BPM, 14 dB under its body from 80 s to 96 s, then 8 dB under it (clearly heard, under
        // full level) until 99.1 s, then full to the end: a blend ending before that cuts under a
        // second of full-level music.
        "dip-short": song(22_050, 100_000, levels(100_000, 0, -14, 80_000, -28, 96_000, -22, 99_100, -14)),
        // Tempos 25% and 12.3% from 100 BPM at full level from the first sample.
        "steady-133": song(22_050, 45_000, steady(0, 45_000, 400.0 / 3, 0)),
        "steady-114": song(22_050, 45_000, steady(0, 45_000, 114, 0)),
        // Tempos 23.6%, 20% and 18.3% from 100 BPM with a quiet start that is still clearly heard.
        "audible-131": song(22_050, 45_000, audibleStart(130.9)),
        "audible-83": song(22_050, 45_000, audibleStart(250.0 / 3)),
        "audible-85": song(22_050, 45_000, audibleStart(84.5)),
        // 100 BPM with a quiet start of 11 s, 11 dB and 13 dB under its own body but 5 dB and 7 dB
        // under steady-100: just over and just under the line an intro must keep under the
        // outgoing song.
        "near-100": song(22_050, 45_000, steady(0, 11_000, 100, 0, padDb: -19) + steady(11_000, 45_000, 100, 0, padDb: -8)),
        "under-100": song(22_050, 45_000, steady(0, 11_000, 100, 0, padDb: -21) + steady(11_000, 45_000, 100, 0, padDb: -8)),
        // 100 BPM with a quiet start of 11 s, 8 dB under its own body but at steady-100's level: an
        // intro lowers it about 6 dB through the hold.
        "level-100": song(22_050, 45_000, steady(0, 11_000, 100, 0, padDb: -14) + steady(11_000, 45_000, 100, 0, padDb: -6)),
        // 100 BPM 28 dB under steady-100 throughout: next to it level-100's quiet start would have to
        // come down 34 dB.
        "faint-100": song(22_050, 100_000, steady(0, 100_000, 100, 0, padDb: -42)),
        // The same at 101 BPM, 1% faster.
        "steady-101": song(44_100, 202_000, steady(0, 202_000, 101, 0)),
        // 101 BPM at full level until 190 s, then 12 dB under it: an outro where the song is no
        // longer clearly heard.
        "steady-101-outro": song(44_100, 202_000, steady(0, 190_000, 101, 0) + steady(190_000, 202_000, 101, 0, padDb: -26)),
        // 100 BPM at full level until 180 s, 14 dB under it until 192 s, then 7 dB under it to the end.
        "steady-100-steps": song(44_100, 202_000, steady(0, 180_000, 100, 0) + steady(180_000, 192_000, 100, 0, padDb: -28)
            + steady(192_000, 202_000, 100, 0, padDb: -21)),
        // 100 BPM, 4.5 dB louder from 170.4 s to 189.6 s: two section boundaries well before the
        // last bars, full level throughout.
        "steady-100-sections": song(44_100, 201_850, steady(0, 170_400, 100, 0, padDb: -18.5) + steady(170_400, 189_600, 100, 0)
            + steady(189_600, 201_850, 100, 0, padDb: -18.5)),
        // 100 BPM with a quiet start of about 4.4 s and of about 10.75 s.
        "quiet-100-short": song(44_100, 180_000, quietStart(180_000, 100, 4_650)),
        "quiet-100": song(44_100, 180_000, quietStart(180_000, 100, 11_000)),
        // 106 BPM, 5.7% from 100, with a quiet start of about 9.5 s.
        "quiet-106": song(44_100, 180_000, quietStart(180_000, 106, 9_750)),
        // 100 BPM from 1.5 s, its beats at 1150 + 600 n and its bars at 1750 + 2400 n: its first
        // sound comes 350 ms after a beat, and its last bar line before that falls before the
        // song's start.
        "steady-100-late": song(44_100, 180_000, steady(1_500, 180_000, 100, 1_150, barMs: 1_750)),
        // 100 BPM from the first sample, its beats at 300 + 600 n: its last beat before its first
        // sound falls before the song's start.
        "steady-100-early": song(44_100, 180_000, steady(0, 180_000, 100, 300)),
        // Short songs at tempos 0.3%, 3%, 7.1%, 10%, 20% and 40% slower than 100 BPM, full level
        // throughout.
        "steady-99.7": song(22_050, 40_000, steady(0, 40_000, 100 / 1.003, 0)),
        "steady-97": song(22_050, 40_000, steady(0, 40_000, 100 / 1.03, 0)),
        "steady-93": song(22_050, 40_000, steady(0, 40_000, 100 / 1.071, 0)),
        "steady-91": song(22_050, 40_000, steady(0, 40_000, 100 / 1.1, 0)),
        "steady-83": song(22_050, 40_000, steady(0, 40_000, 100 / 1.2, 0)),
        "steady-71": song(22_050, 40_000, steady(0, 40_000, 100 / 1.4, 0)),
        // 10% slower than 100 BPM at full level from the first sample, its beats from 600 ms: B is
        // heard well before its beats meet A's.
        "steady-91-lead": song(22_050, 40_000, steady(0, 40_000, 100 / 1.1, 600)),
        // 100 BPM with a quiet start of about 10.75 s, its beats from 100 ms.
        "quiet-100-lead": song(44_100, 180_000, quietStart(180_000, 100, 11_000, beatMs: 100)),
        // 100 BPM from 0.5 s, its beats at 300 + 600 n and its bars at 900 + 2400 n: with a rate it
        // enters at 0, silent until 0.5 s.
        "steady-100-lead": song(44_100, 180_000, steady(500, 180_000, 100, 300, barMs: 900)),
        // 100 BPM sounding from 1.6 s, its beats from 1.65 s.
        "steady-100-late-sound": song(22_050, 40_000, steady(1_600, 40_000, 100, 1_650)),
        // 35 s at 95.24 BPM (beats every 630 ms from 15.9 s, bars from 15.27 s): silent for 16 s,
        // quiet until 27 s, then full level. Too short to ease back from a 5% rate after a long
        // intro.
        "short-quiet": song(44_100, 35_000, steady(16_000, 27_000, 60_000.0 / 630, 15_900, barMs: 15_270, padDb: -38)
            + steady(27_000, 35_000, 60_000.0 / 630, 15_900, barMs: 15_270)),
    ]

    struct Pair {
        var name: String
        var a: String
        var b: String
        var settings: AutomixSettings
        var sameAlbumInOrder = false
        var context = TransitionContext()
        var tailMissing = false
        var headMissing = false
        /// The outgoing song as the queue lists it, when shorter than its sound.
        var aLengthMs: Int?
        /// The next song's head measured against its whole body, as a profile holds it.
        var headAgainstBody = false

        var current: FadeSong {
            FadeSong(albumId: sameAlbumInOrder ? "album" : "a", albumOrder: 1, durationMs: aLengthMs ?? songs[a]!.lengthMs)
        }

        var next: FadeSong { FadeSong(albumId: sameAlbumInOrder ? "album" : "b", albumOrder: 2, durationMs: songs[b]!.lengthMs) }
    }

    private static let eight = AutomixSettings(maxOverlapMs: 8_000)
    private static let matched = AutomixSettings(maxOverlapMs: 8_000, beatMatch: true)

    static let pairs: [Pair] = [
        Pair(name: "trailing-silence", a: "silence-tail", b: "plain-b", settings: eight),
        Pair(name: "fade-out", a: "fade-out", b: "plain-b", settings: eight),
        Pair(name: "hot-end", a: "hot-end", b: "plain-b", settings: eight),
        Pair(name: "quiet-intro", a: "hot-end", b: "quiet-intro", settings: eight),
        Pair(name: "quiet-intro-capped", a: "hot-end", b: "quiet-intro", settings: AutomixSettings(maxOverlapMs: 4_000, longestIntroMs: 6_000)),
        Pair(name: "same-tempo", a: "beat-120", b: "beat-120-b", settings: eight),
        Pair(name: "same-tempo-long", a: "beat-120", b: "beat-120-b", settings: AutomixSettings(maxOverlapMs: 16_000)),
        Pair(name: "same-tempo-short", a: "beat-120", b: "beat-120-b", settings: AutomixSettings(maxOverlapMs: 5_000)),
        Pair(name: "tempo-clash", a: "beat-120", b: "beat-100", settings: eight),
        Pair(name: "beat-match", a: "beat-120", b: "beat-118", settings: AutomixSettings(maxOverlapMs: 8_000, beatMatch: true)),
        Pair(name: "beat-match-off", a: "beat-120", b: "beat-118", settings: eight),
        Pair(name: "no-sweeps", a: "beat-120", b: "beat-120-b", settings: AutomixSettings(maxOverlapMs: 8_000, filterSweeps: false)),
        Pair(name: "no-analysis", a: "hot-end", b: "plain-b", settings: eight, tailMissing: true, headMissing: true),
        Pair(name: "no-tail", a: "fade-out", b: "plain-b", settings: eight, tailMissing: true),
        Pair(name: "no-head", a: "beat-120", b: "plain-b", settings: eight, headMissing: true),
        Pair(name: "smart-off", a: "beat-120", b: "beat-120-b", settings: AutomixSettings(maxOverlapMs: 8_000, smart: false)),
        Pair(name: "album-in-order", a: "beat-120", b: "beat-120-b", settings: eight, sameAlbumInOrder: true),
        Pair(name: "repeat-one", a: "beat-120", b: "beat-120-b", settings: eight, context: TransitionContext(repeatOne: true)),
        Pair(name: "stop-at-end", a: "beat-120", b: "beat-120-b", settings: eight, context: TransitionContext(stopAtEndOfSong: true)),
        Pair(name: "crossfade-off", a: "beat-120", b: "beat-120-b", settings: AutomixSettings(maxOverlapMs: 0)),
        Pair(name: "classical", a: "beat-120", b: "beat-120-b", settings: eight, context: TransitionContext(nextGenre: "Classical; Baroque")),
        Pair(name: "podcast", a: "hot-end", b: "plain-b", settings: eight, context: TransitionContext(currentGenre: "Podcast")),
        Pair(name: "pace", a: "beat-120", b: "beat-120-b", settings: AutomixSettings(maxOverlapMs: 8_000, beatMatch: true),
             context: TransitionContext(pace: 1.25)),
        Pair(name: "skip-silence", a: "beat-120", b: "beat-118", settings: AutomixSettings(maxOverlapMs: 8_000, beatMatch: true),
             context: TransitionContext(skipSilence: true)),
        Pair(name: "late", a: "beat-120", b: "beat-120-b", settings: eight, context: TransitionContext(nowMs: 190_000)),
        Pair(name: "late-short", a: "beat-120", b: "beat-120-b", settings: eight, context: TransitionContext(nowMs: 197_000)),
        Pair(name: "too-late", a: "beat-120", b: "beat-120-b", settings: eight, context: TransitionContext(nowMs: 199_500)),
        Pair(name: "pre-roll", a: "fade-out", b: "plain-b", settings: eight, context: TransitionContext(nowMs: 183_000)),
        Pair(name: "seeked-forward", a: "fade-out", b: "plain-b", settings: eight, context: TransitionContext(nowMs: 170_000, playedMs: 60_000)),
        Pair(name: "whole-song-level", a: "fade-out", b: "plain-b", settings: eight, context: TransitionContext(bodyLevelDb: -24)),
        Pair(name: "tempo-prior", a: "beat-120", b: "beat-120-b", settings: eight, context: TransitionContext(tempoPrior: 60)),
        Pair(name: "tempo-prior-off", a: "beat-120", b: "beat-120-b", settings: eight, context: TransitionContext(tempoPrior: 100)),
        // Kept music: early exits at section boundaries lose full-level music.
        Pair(name: "kept-music", a: "steady-100-sections", b: "steady-100", settings: eight),
        // Off-grid ends: the latest end 1.9 s (and 1.5 s, 1.1 s) past the last bar line a
        // whole-bar plan can end on.
        Pair(name: "off-grid-end", a: "steady-100", b: "steady-100", settings: AutomixSettings(maxOverlapMs: 9_200), aLengthMs: 201_350),
        Pair(name: "off-grid-end-over", a: "steady-100", b: "steady-100", settings: eight, aLengthMs: 201_350),
        Pair(name: "off-grid-intro", a: "steady-100", b: "quiet-100", settings: eight, aLengthMs: 200_950),
        Pair(name: "off-grid-intro-capped", a: "steady-100", b: "quiet-100", settings: eight, aLengthMs: 201_350),
        Pair(name: "off-grid-intro-short-fade", a: "steady-100", b: "quiet-100-short",
             settings: AutomixSettings(maxOverlapMs: 2_400, longestIntroMs: 6_000), aLengthMs: 201_750),
        Pair(name: "off-grid-cut", a: "steady-100", b: "steady-93", settings: eight, aLengthMs: 201_350),
        // Rate first and the entry on the next song's grid.
        Pair(name: "rate-small-gap", a: "steady-100", b: "steady-99.7", settings: matched),
        Pair(name: "entry-on-beat", a: "steady-101", b: "steady-100-late", settings: eight),
        Pair(name: "entry-on-downbeat", a: "steady-101", b: "steady-100-late", settings: matched),
        Pair(name: "entry-before-start", a: "steady-101", b: "steady-100-early", settings: eight),
        // The drift cap with both songs at full level.
        Pair(name: "drift-1", a: "steady-101", b: "steady-100", settings: eight),
        Pair(name: "drift-3", a: "steady-100", b: "steady-97", settings: eight),
        Pair(name: "drift-7", a: "steady-100", b: "steady-93", settings: eight),
        Pair(name: "drift-10", a: "steady-100", b: "steady-91", settings: eight),
        Pair(name: "drift-20", a: "steady-100", b: "steady-83", settings: eight),
        Pair(name: "drift-40", a: "steady-100", b: "steady-71", settings: eight),
        Pair(name: "drift-outro", a: "steady-101-outro", b: "steady-93", settings: eight),
        Pair(name: "drift-desktop-pace", a: "steady-100", b: "steady-97", settings: eight, context: TransitionContext(pace: 1.25, lockAtPace: true)),
        Pair(name: "drift-phone-pace", a: "steady-101-outro", b: "steady-100", settings: matched, context: TransitionContext(pace: 1.25)),
        Pair(name: "pace-steady", a: "steady-101", b: "steady-100", settings: matched, context: TransitionContext(skipSilence: true)),
        Pair(name: "drift-late-matched", a: "steady-100", b: "steady-97", settings: matched, context: TransitionContext(nowMs: 196_000)),
        Pair(name: "drift-late-capped", a: "steady-100", b: "steady-93", settings: eight, context: TransitionContext(nowMs: 196_000)),
        Pair(name: "drift-late-cut", a: "steady-100", b: "steady-97", settings: eight, context: TransitionContext(nowMs: 200_500)),
        Pair(name: "drift-short-window", a: "steady-100", b: "steady-100", settings: eight, context: TransitionContext(nowMs: 191_450)),
        Pair(name: "late-move-count", a: "steady-100-steps", b: "steady-97", settings: eight),
        // The next song heard before the lock point decides the bound.
        Pair(name: "drift-before-lock", a: "steady-100", b: "steady-91-lead", settings: eight),
        // A lead-in: an intro's fade still centered on a bar line, and a matched cut held until
        // the next song's first sound.
        Pair(name: "intro-lead", a: "steady-100", b: "quiet-100-lead", settings: eight, aLengthMs: 199_950),
        Pair(name: "lead-held-cut", a: "steady-101", b: "steady-100-lead", settings: AutomixSettings(maxOverlapMs: 2_400, beatMatch: true)),
        // Rate room: a 5% rate the next song is too short to ease back from.
        Pair(name: "rate-room", a: "steady-100", b: "short-quiet", settings: matched),
        // Intros.
        Pair(name: "intro-matched", a: "steady-100", b: "quiet-106", settings: matched),
        Pair(name: "intro-unmatched", a: "steady-100", b: "quiet-106", settings: eight),
        Pair(name: "intro-short-drift", a: "steady-101", b: "quiet-100-short", settings: AutomixSettings(maxOverlapMs: 2_400)),
        Pair(name: "intro-drift", a: "steady-101", b: "quiet-100", settings: eight),
        Pair(name: "intro-later-start", a: "steady-101-outro", b: "quiet-100", settings: eight),
        Pair(name: "intro-tempo-dropped", a: "steady-100", b: "quiet-100", settings: eight, context: TransitionContext(tempoPrior: 140)),
        Pair(name: "intro-phone-pace", a: "beat-120", b: "quiet-intro", settings: matched, context: TransitionContext(pace: 1.25)),
        Pair(name: "intro-phone-pace-confident", a: "steady-100", b: "quiet-100", settings: matched, context: TransitionContext(pace: 1.25)),
        Pair(name: "intro-cap", a: "hot-end", b: "long-intro", settings: eight, headAgainstBody: true),
        // Kept music over the beats: tempos 12% to 25% apart with the outgoing song at full level to
        // the end, where only places in its quiet stretch keep the beats apart. Each cuts seconds of
        // music, so the songs join gaplessly; one cutting under a second is planned.
        Pair(name: "kept-cut-25", a: "dip-far", b: "steady-133", settings: eight),
        Pair(name: "kept-intro-24", a: "dip-mid", b: "audible-131", settings: eight),
        Pair(name: "kept-intro-20", a: "dip-mid", b: "audible-83", settings: eight),
        Pair(name: "kept-blend-12", a: "dip-near", b: "steady-114", settings: eight),
        Pair(name: "kept-intro-18", a: "dip-near", b: "audible-85", settings: eight),
        Pair(name: "kept-short-cut", a: "dip-short", b: "steady-114", settings: eight),
        // An intro's quiet start kept 6 dB under the outgoing song: 7 dB under plays at its own
        // level, 5 dB under is lowered about 1 dB and one at the outgoing song's level about 6 dB;
        // one that would need over `introTrimCapDb` gives the blend.
        Pair(name: "intro-under-line", a: "steady-100", b: "under-100", settings: eight),
        Pair(name: "intro-over-line", a: "steady-100", b: "near-100", settings: eight),
        Pair(name: "intro-trimmed", a: "steady-100", b: "level-100", settings: eight),
        Pair(name: "intro-trim-capped", a: "faint-100", b: "level-100", settings: eight),
    ]

    private static let cache = AnalysisCache()

    static func tail(_ song: String) -> SectionAnalysis { cache.get("tail:\(song)") { songs[song]!.tail() } }

    static func head(_ song: String) -> SectionAnalysis { cache.get("head:\(song)") { songs[song]!.head() } }

    /// The whole song's body level as the vectors file holds it, to four places.
    static func wholeBodyDb(_ song: String) -> Double { ((songs[song]!.wholeBodyDb()! * 10_000) + 0.5).rounded(.down) / 10_000 }

    /// The head measured against the whole song's body, as a profile holds it.
    static func profileHead(_ song: String) -> SectionAnalysis {
        cache.get("profile-head:\(song)") { songs[song]!.profileHead(wholeBodyDb(song)) }
    }

    static func plan(_ pair: Pair) -> TransitionPlan {
        let start: SectionAnalysis? = pair.headMissing ? nil : pair.headAgainstBody ? profileHead(pair.b) : head(pair.b)
        return planTransition(current: pair.current, next: pair.next, tail: pair.tailMissing ? nil : tail(pair.a), head: start,
                              settings: pair.settings, context: pair.context)
    }

    static func pair(_ name: String) -> Pair { pairs.first { $0.name == name }! }
}

/// Analyses worked out once and shared by every test that needs them.
final class AnalysisCache: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: SectionAnalysis] = [:]

    func get(_ key: String, _ make: () -> SectionAnalysis) -> SectionAnalysis {
        if let found = lock.withLock({ values[key] }) { return found }
        let made = make()
        return lock.withLock {
            if let found = values[key] { return found }
            values[key] = made
            return made
        }
    }
}
