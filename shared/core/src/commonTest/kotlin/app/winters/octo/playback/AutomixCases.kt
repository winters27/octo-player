package app.winters.octo.playback

import kotlin.math.floor

// The made-up songs and song pairs the automix tests and
// automix-vectors.json share.
object AutomixCases {
    private fun pad(fromMs: Double, toMs: Double, db: Double, endDb: Double = db) = listOf(
        SongLayer("tone", fromMs, toMs, hz = 300.0, db = db, endDb = endDb),
        SongLayer("tone", fromMs, toMs, hz = 100.0, db = db - 3, endDb = endDb - 3),
    )

    // A kick on every bar and a click on every beat over a quiet pad.
    private fun beat(lengthMs: Double, bpm: Double, firstMs: Double) =
        listOf(SongLayer("tone", firstMs, lengthMs, hz = 300.0, db = -26.0)) + listOf(
            SongLayer("pulse", firstMs, lengthMs, hz = 60.0, db = -6.0, bpm = bpm, firstMs = firstMs, every = 4, decayMs = 80.0),
            SongLayer("pulse", firstMs, lengthMs, hz = 2_000.0, db = -16.0, bpm = bpm, firstMs = firstMs, every = 1, decayMs = 10.0),
        )

    // A kick on every bar from `barMs` and a click on every beat from
    // `beatMs` over a pad at `padDb`, sounding from fromMs to toMs: a steady
    // level with a clear beat. Pieces with the same grid join on the beat.
    private fun steady(fromMs: Double, toMs: Double, bpm: Double, beatMs: Double, barMs: Double = beatMs, padDb: Double = -14.0) =
        pad(fromMs, toMs, padDb) + listOf(
            SongLayer("pulse", fromMs, toMs, hz = 60.0, db = padDb + 4, bpm = bpm, firstMs = barMs, every = 4, decayMs = 80.0),
            SongLayer("pulse", fromMs, toMs, hz = 2_000.0, db = padDb - 4, bpm = bpm, firstMs = beatMs, every = 1, decayMs = 10.0),
        )

    // A steady beat from `beatMs` whose first `quietMs` are 24 dB under the
    // rest.
    private fun quietStart(lengthMs: Double, bpm: Double, quietMs: Double, beatMs: Double = 0.0) =
        steady(0.0, quietMs, bpm, beatMs, padDb = -38.0) + steady(quietMs, lengthMs, bpm, beatMs)

    // 100 BPM (2.4 s bars from 0), its pad at each level from each time on:
    // `steps` are (fromMs, padDb), the first from 0, the last to lengthMs.
    private fun levels(lengthMs: Double, vararg steps: Double): List<SongLayer> =
        (steps.indices step 2).flatMap { i ->
            val to = if (i + 2 < steps.size) steps[i + 2] else lengthMs
            steady(steps[i], to, 100.0, 0.0, padDb = steps[i + 1])
        }

    // A steady beat whose first 10 s are 8 dB under the rest: a quiet start
    // that is still clearly heard.
    private fun audibleStart(bpm: Double) = steady(0.0, 10_000.0, bpm, 0.0, padDb = -22.0) + steady(10_000.0, 45_000.0, bpm, 0.0)

    val songs: Map<String, SyntheticSong> = linkedMapOf(
        // 8 s of silence after the music stops.
        "silence-tail" to SyntheticSong(44_100, 2, 200_000, pad(0.0, 192_000.0, -14.0)),
        // Full level until 180 s, then a 40 s fade to nothing.
        "fade-out" to SyntheticSong(48_000, 1, 220_000, pad(0.0, 180_000.0, -14.0) + pad(180_000.0, 220_000.0, -14.0, -74.0)),
        // Full level to the last sample.
        "hot-end" to SyntheticSong(44_100, 2, 200_000, pad(0.0, 200_000.0, -14.0)),
        "beat-120" to SyntheticSong(48_000, 2, 200_000, beat(200_000.0, 120.0, 500.0)),
        "beat-120-b" to SyntheticSong(22_050, 1, 180_000, beat(180_000.0, 120.0, 1_000.0)),
        "beat-100" to SyntheticSong(44_100, 1, 180_000, beat(180_000.0, 100.0, 600.0)),
        "beat-118" to SyntheticSong(44_100, 1, 180_000, beat(180_000.0, 118.0, 400.0)),
        "beat-128" to SyntheticSong(44_100, 1, 60_000, beat(60_000.0, 128.0, 300.0)),
        // 1.5 s of silence, then full level.
        "plain-b" to SyntheticSong(44_100, 2, 180_000, pad(1_500.0, 180_000.0, -14.0)),
        // 6 s of a quiet pad 24 dB under the body, then full level.
        "quiet-intro" to SyntheticSong(44_100, 2, 180_000, pad(0.0, 6_000.0, -38.0) + pad(6_000.0, 180_000.0, -14.0)),
        // 35 s of the quiet pad, then full level: most of a head is intro.
        "long-intro" to SyntheticSong(44_100, 1, 180_000, pad(0.0, 35_000.0, -38.0) + pad(35_000.0, 180_000.0, -14.0)),
        // A minute of the quiet pad, past the end of any head.
        "endless-intro" to SyntheticSong(44_100, 1, 200_000, pad(0.0, 60_000.0, -38.0) + pad(60_000.0, 200_000.0, -14.0)),
        // A steady 100 BPM beat (2.4 s bars from 0) at full level from the
        // first sample to the last.
        "steady-100" to SyntheticSong(44_100, 1, 202_000, steady(0.0, 202_000.0, 100.0, 0.0)),
        // The same at 101 BPM, 1% faster.
        "steady-101" to SyntheticSong(44_100, 1, 202_000, steady(0.0, 202_000.0, 101.0, 0.0)),
        // 101 BPM at full level until 190 s, then 12 dB under it: an outro
        // where the song is no longer clearly heard.
        "steady-101-outro" to SyntheticSong(
            44_100, 1, 202_000,
            steady(0.0, 190_000.0, 101.0, 0.0) + steady(190_000.0, 202_000.0, 101.0, 0.0, padDb = -26.0),
        ),
        // 100 BPM at full level until 180 s, 14 dB under it until 192 s,
        // then 7 dB under it to the end.
        "steady-100-steps" to SyntheticSong(
            44_100, 1, 202_000,
            steady(0.0, 180_000.0, 100.0, 0.0) + steady(180_000.0, 192_000.0, 100.0, 0.0, padDb = -28.0) +
                steady(192_000.0, 202_000.0, 100.0, 0.0, padDb = -21.0),
        ),
        // 100 BPM, 4.5 dB louder from 170.4 s to 189.6 s: two section
        // boundaries well before the last bars, full level throughout.
        "steady-100-sections" to SyntheticSong(
            44_100, 1, 201_850,
            steady(0.0, 170_400.0, 100.0, 0.0, padDb = -18.5) + steady(170_400.0, 189_600.0, 100.0, 0.0) +
                steady(189_600.0, 201_850.0, 100.0, 0.0, padDb = -18.5),
        ),
        // 100 BPM with a quiet start of about 4.4 s and of about 10.75 s.
        "quiet-100-short" to SyntheticSong(44_100, 1, 180_000, quietStart(180_000.0, 100.0, 4_650.0)),
        "quiet-100" to SyntheticSong(44_100, 1, 180_000, quietStart(180_000.0, 100.0, 11_000.0)),
        // 106 BPM, 5.7% from 100, with a quiet start of about 9.5 s.
        "quiet-106" to SyntheticSong(44_100, 1, 180_000, quietStart(180_000.0, 106.0, 9_750.0)),
        // 100 BPM from 1.5 s, its beats at 1150 + 600 n and its bars at
        // 1750 + 2400 n: its first sound comes 350 ms after a beat, and its
        // last bar line before that falls before the song's start.
        "steady-100-late" to SyntheticSong(44_100, 1, 180_000, steady(1_500.0, 180_000.0, 100.0, 1_150.0, barMs = 1_750.0)),
        // 100 BPM from the first sample, its beats at 300 + 600 n: its last
        // beat before its first sound falls before the song's start.
        "steady-100-early" to SyntheticSong(44_100, 1, 180_000, steady(0.0, 180_000.0, 100.0, 300.0)),
        // Short songs at tempos 0.3%, 3%, 7.1%, 10%, 20% and 40% slower
        // than 100 BPM, full level throughout.
        "steady-99.7" to SyntheticSong(22_050, 1, 40_000, steady(0.0, 40_000.0, 100.0 / 1.003, 0.0)),
        "steady-97" to SyntheticSong(22_050, 1, 40_000, steady(0.0, 40_000.0, 100.0 / 1.03, 0.0)),
        "steady-93" to SyntheticSong(22_050, 1, 40_000, steady(0.0, 40_000.0, 100.0 / 1.071, 0.0)),
        "steady-91" to SyntheticSong(22_050, 1, 40_000, steady(0.0, 40_000.0, 100.0 / 1.1, 0.0)),
        "steady-83" to SyntheticSong(22_050, 1, 40_000, steady(0.0, 40_000.0, 100.0 / 1.2, 0.0)),
        "steady-71" to SyntheticSong(22_050, 1, 40_000, steady(0.0, 40_000.0, 100.0 / 1.4, 0.0)),
        // 10% slower than 100 BPM at full level from the first sample, its
        // beats from 600 ms: B is heard well before its beats meet A's.
        "steady-91-lead" to SyntheticSong(22_050, 1, 40_000, steady(0.0, 40_000.0, 100.0 / 1.1, 600.0)),
        // 100 BPM with a quiet start of about 10.75 s, its beats from 100 ms.
        "quiet-100-lead" to SyntheticSong(44_100, 1, 180_000, quietStart(180_000.0, 100.0, 11_000.0, beatMs = 100.0)),
        // 100 BPM from 0.5 s, its beats at 300 + 600 n and its bars at
        // 900 + 2400 n: with a rate it enters at 0, silent until 0.5 s.
        "steady-100-lead" to SyntheticSong(44_100, 1, 180_000, steady(500.0, 180_000.0, 100.0, 300.0, barMs = 900.0)),
        // 100 BPM from 1.9 s, its beats at 300 + 600 n and its bars at
        // 2100 + 2400 n: with a rate it enters at 0, silent until 1.9 s.
        "steady-100-long-lead" to SyntheticSong(22_050, 1, 40_000, steady(1_900.0, 40_000.0, 100.0, 300.0, barMs = 2_100.0)),
        // 35 s at 95.24 BPM (beats every 630 ms from 15.9 s, bars from 15.27
        // s): silent for 16 s, quiet until 27 s, then full level. Too short
        // to ease back from a 5% rate after a long intro.
        "short-quiet" to SyntheticSong(
            44_100, 1, 35_000,
            steady(16_000.0, 27_000.0, 60_000.0 / 630, 15_900.0, barMs = 15_270.0, padDb = -38.0) +
                steady(27_000.0, 35_000.0, 60_000.0 / 630, 15_900.0, barMs = 15_270.0),
        ),
        // 100 BPM at full level to the end but for one stretch 14 dB under
        // it, where it is not clearly heard: 3 s from 72.6 s, 16.4 s from 70
        // s and 13.6 s from 82.4 s, ending 24.15 s, 13.35 s and 3.75 s before
        // the latest end a blend may reach (99.75 s).
        "dip-far" to SyntheticSong(22_050, 1, 100_000, levels(100_000.0, 0.0, -14.0, 72_600.0, -28.0, 75_600.0, -14.0)),
        "dip-mid" to SyntheticSong(22_050, 1, 100_000, levels(100_000.0, 0.0, -14.0, 70_000.0, -28.0, 86_400.0, -14.0)),
        "dip-near" to SyntheticSong(22_050, 1, 100_000, levels(100_000.0, 0.0, -14.0, 82_400.0, -28.0, 96_000.0, -14.0)),
        // 100 BPM, 14 dB under its body from 80 s to 96 s, then 8 dB under
        // it (clearly heard, under full level) until 99.1 s, then full to
        // the end: a blend ending before that cuts under a second of
        // full-level music.
        "dip-short" to SyntheticSong(
            22_050, 1, 100_000, levels(100_000.0, 0.0, -14.0, 80_000.0, -28.0, 96_000.0, -22.0, 99_100.0, -14.0),
        ),
        // Tempos 25% and 12.3% from 100 BPM at full level from the first
        // sample.
        "steady-133" to SyntheticSong(22_050, 1, 45_000, steady(0.0, 45_000.0, 400.0 / 3, 0.0)),
        "steady-114" to SyntheticSong(22_050, 1, 45_000, steady(0.0, 45_000.0, 114.0, 0.0)),
        // Tempos 23.6%, 20% and 18.3% from 100 BPM with a quiet start that
        // is still clearly heard.
        "audible-131" to SyntheticSong(22_050, 1, 45_000, audibleStart(130.9)),
        "audible-83" to SyntheticSong(22_050, 1, 45_000, audibleStart(250.0 / 3)),
        "audible-85" to SyntheticSong(22_050, 1, 45_000, audibleStart(84.5)),
        // 100 BPM with a quiet start of 11 s, 11 dB and 13 dB under its own
        // body but 5 dB and 7 dB under steady-100: an intro lowers each to
        // INTRO_UNDER_OUTGOING_DB under it.
        "near-100" to SyntheticSong(
            22_050, 1, 45_000, steady(0.0, 11_000.0, 100.0, 0.0, padDb = -19.0) + steady(11_000.0, 45_000.0, 100.0, 0.0, padDb = -8.0),
        ),
        "under-100" to SyntheticSong(
            22_050, 1, 45_000, steady(0.0, 11_000.0, 100.0, 0.0, padDb = -21.0) + steady(11_000.0, 45_000.0, 100.0, 0.0, padDb = -8.0),
        ),
        // 100 BPM with a quiet start of 11 s, 8 dB under its own body but at
        // steady-100's level: an intro lowers it by INTRO_UNDER_OUTGOING_DB
        // through the hold.
        "level-100" to SyntheticSong(
            22_050, 1, 45_000, steady(0.0, 11_000.0, 100.0, 0.0, padDb = -14.0) + steady(11_000.0, 45_000.0, 100.0, 0.0, padDb = -6.0),
        ),
        // 100 BPM 28 dB under steady-100 throughout: next to it level-100's
        // quiet start would have to come down 34 dB.
        "faint-100" to SyntheticSong(22_050, 1, 100_000, steady(0.0, 100_000.0, 100.0, 0.0, padDb = -42.0)),
        // 100 BPM with a quiet start of 11 s, 7 dB under its own body and
        // 2 dB over steady-100; then the same silent from 0.3 s to 9.2 s.
        "loud-100" to SyntheticSong(
            22_050, 1, 45_000, steady(0.0, 11_000.0, 100.0, 0.0, padDb = -12.0) + steady(11_000.0, 45_000.0, 100.0, 0.0, padDb = -5.0),
        ),
        "loud-100-gap" to SyntheticSong(
            22_050, 1, 45_000,
            steady(0.0, 300.0, 100.0, 0.0, padDb = -12.0) + steady(9_200.0, 11_000.0, 100.0, 0.0, padDb = -12.0) +
                steady(11_000.0, 45_000.0, 100.0, 0.0, padDb = -5.0),
        ),
        // steady-100 dropping 10 dB at 190 s to its end: its outro.
        "steady-100-outro" to SyntheticSong(
            44_100, 1, 202_000,
            steady(0.0, 190_000.0, 100.0, 0.0) + steady(190_000.0, 202_000.0, 100.0, 0.0, padDb = -24.0),
        ),
        // steady-100 10 dB down for 300 ms (a dropout) or 1.5 s (a break)
        // from 192 s, inside the hold of intro-trimmed.
        "steady-100-dropout" to SyntheticSong(
            44_100, 1, 202_000,
            steady(0.0, 192_000.0, 100.0, 0.0) + steady(192_000.0, 192_300.0, 100.0, 0.0, padDb = -24.0) +
                steady(192_300.0, 202_000.0, 100.0, 0.0),
        ),
        "steady-100-break" to SyntheticSong(
            44_100, 1, 202_000,
            steady(0.0, 192_000.0, 100.0, 0.0) + steady(192_000.0, 193_500.0, 100.0, 0.0, padDb = -24.0) +
                steady(193_500.0, 202_000.0, 100.0, 0.0),
        ),
        // steady-100 until 186 s, then a pad fading 10 dB to 192 s (its
        // outro, no step sharp enough to count as a boundary), held there,
        // and 24 dB down from 197 s to its end.
        "steady-100-outro-steps" to SyntheticSong(
            44_100, 1, 202_000,
            steady(0.0, 186_000.0, 100.0, 0.0) + pad(186_000.0, 192_000.0, -14.0, -24.0) + pad(192_000.0, 197_000.0, -24.0) +
                pad(197_000.0, 202_000.0, -38.0),
        ),
        // No beat (no tempo): a quiet start of 11 s, then a body 20 dB (or
        // 36 dB) under steady-100: the first sits above steady-100-outro-steps
        // once it drops to -38 dB, the second stays 12 dB under even that.
        "under-pad" to SyntheticSong(22_050, 1, 45_000, pad(0.0, 11_000.0, -45.0) + pad(11_000.0, 45_000.0, -34.0)),
        "quiet-pad" to SyntheticSong(22_050, 1, 45_000, pad(0.0, 11_000.0, -57.0) + pad(11_000.0, 45_000.0, -50.0)),
        // 82 BPM at full level throughout; 71.4 BPM sounding from 0.56 s,
        // its beats from 0.6 s: without a rate it enters at 0 and holds the
        // outgoing song through those 0.56 s.
        "steady-82" to SyntheticSong(44_100, 1, 202_000, steady(0.0, 202_000.0, 82.0, 0.0)),
        "steady-71-late" to SyntheticSong(22_050, 1, 40_000, steady(560.0, 40_000.0, 100.0 / 1.4, 600.0)),
        // 100 BPM sounding from 1.6 s, its beats from 1.65 s.
        "steady-100-late-sound" to SyntheticSong(22_050, 1, 40_000, steady(1_600.0, 40_000.0, 100.0, 1_650.0)),
    )

    data class Pair(
        val name: String,
        val a: String,
        val b: String,
        val settings: AutomixSettings,
        val sameAlbumInOrder: Boolean = false,
        val context: TransitionContext = TransitionContext(),
        val tailMissing: Boolean = false,
        val headMissing: Boolean = false,
        // The outgoing song as the queue lists it, when shorter than its sound.
        val aLengthMs: Long? = null,
        // The next song's head measured against its whole body, as a
        // profile holds it.
        val headAgainstBody: Boolean = false,
    ) {
        val current: FadeSong get() = FadeSong(if (sameAlbumInOrder) "album" else "a", 1, aLengthMs ?: songs.getValue(a).lengthMs)
        val next: FadeSong get() = FadeSong(if (sameAlbumInOrder) "album" else "b", 2, songs.getValue(b).lengthMs)
    }

    private val eight = AutomixSettings(maxOverlapMs = 8_000)
    private val matched = AutomixSettings(maxOverlapMs = 8_000, beatMatch = true)

    val pairs: List<Pair> = listOf(
        Pair("trailing-silence", "silence-tail", "plain-b", eight),
        Pair("fade-out", "fade-out", "plain-b", eight),
        Pair("hot-end", "hot-end", "plain-b", eight),
        Pair("quiet-intro", "hot-end", "quiet-intro", eight),
        Pair("quiet-intro-capped", "hot-end", "quiet-intro", AutomixSettings(maxOverlapMs = 4_000, longestIntroMs = 6_000)),
        Pair("same-tempo", "beat-120", "beat-120-b", eight),
        Pair("same-tempo-long", "beat-120", "beat-120-b", AutomixSettings(maxOverlapMs = 16_000)),
        Pair("same-tempo-short", "beat-120", "beat-120-b", AutomixSettings(maxOverlapMs = 5_000)),
        Pair("tempo-clash", "beat-120", "beat-100", eight),
        Pair("beat-match", "beat-120", "beat-118", matched),
        Pair("beat-match-off", "beat-120", "beat-118", eight),
        Pair("no-sweeps", "beat-120", "beat-120-b", AutomixSettings(maxOverlapMs = 8_000, filterSweeps = false)),
        Pair("no-analysis", "hot-end", "plain-b", eight, tailMissing = true, headMissing = true),
        Pair("no-tail", "fade-out", "plain-b", eight, tailMissing = true),
        Pair("no-head", "beat-120", "plain-b", eight, headMissing = true),
        Pair("smart-off", "beat-120", "beat-120-b", AutomixSettings(maxOverlapMs = 8_000, smart = false)),
        Pair("album-in-order", "beat-120", "beat-120-b", eight, sameAlbumInOrder = true),
        Pair("repeat-one", "beat-120", "beat-120-b", eight, context = TransitionContext(repeatOne = true)),
        Pair("stop-at-end", "beat-120", "beat-120-b", eight, context = TransitionContext(stopAtEndOfSong = true)),
        Pair("crossfade-off", "beat-120", "beat-120-b", AutomixSettings(maxOverlapMs = 0)),
        Pair("classical", "beat-120", "beat-120-b", eight, context = TransitionContext(nextGenre = "Classical; Baroque")),
        Pair("podcast", "hot-end", "plain-b", eight, context = TransitionContext(currentGenre = "Podcast")),
        Pair("pace", "beat-120", "beat-120-b", matched, context = TransitionContext(pace = 1.25)),
        Pair("skip-silence", "beat-120", "beat-118", matched, context = TransitionContext(skipSilence = true)),
        Pair("late", "beat-120", "beat-120-b", eight, context = TransitionContext(nowMs = 190_000)),
        Pair("late-short", "beat-120", "beat-120-b", eight, context = TransitionContext(nowMs = 197_000)),
        Pair("too-late", "beat-120", "beat-120-b", eight, context = TransitionContext(nowMs = 199_500)),
        Pair("pre-roll", "fade-out", "plain-b", eight, context = TransitionContext(nowMs = 183_000)),
        Pair("seeked-forward", "fade-out", "plain-b", eight, context = TransitionContext(nowMs = 170_000, playedMs = 60_000)),
        Pair("whole-song-level", "fade-out", "plain-b", eight, context = TransitionContext(bodyLevelDb = -24.0)),
        Pair("tempo-prior", "beat-120", "beat-120-b", eight, context = TransitionContext(tempoPrior = 60.0)),
        Pair("tempo-prior-off", "beat-120", "beat-120-b", eight, context = TransitionContext(tempoPrior = 100.0)),
        // Kept music: early exits at section boundaries lose full-level music.
        Pair("kept-music", "steady-100-sections", "steady-100", eight),
        // Kept music over the beats: tempos 12% to 25% apart with the
        // outgoing song at full level to the end, where only places in its
        // quiet stretch keep the beats apart. Each cuts seconds of music, so
        // the songs join gaplessly; one cutting under a second is planned.
        Pair("kept-cut-25", "dip-far", "steady-133", eight),
        Pair("kept-intro-24", "dip-mid", "audible-131", eight),
        Pair("kept-intro-20", "dip-mid", "audible-83", eight),
        Pair("kept-blend-12", "dip-near", "steady-114", eight),
        Pair("kept-intro-18", "dip-near", "audible-85", eight),
        Pair("kept-short-cut", "dip-short", "steady-114", eight),
        // Off-grid ends: the latest end 1.9 s (and 1.5 s, 1.1 s) past the
        // last bar line a whole-bar plan can end on.
        Pair("off-grid-end", "steady-100", "steady-100", AutomixSettings(maxOverlapMs = 9_200), aLengthMs = 201_350),
        Pair("off-grid-end-over", "steady-100", "steady-100", eight, aLengthMs = 201_350),
        Pair("off-grid-intro", "steady-100", "quiet-100", eight, aLengthMs = 200_950),
        Pair("off-grid-intro-capped", "steady-100", "quiet-100", eight, aLengthMs = 201_350),
        Pair(
            "off-grid-intro-short-fade", "steady-100", "quiet-100-short",
            AutomixSettings(maxOverlapMs = 2_400, longestIntroMs = 6_000), aLengthMs = 201_750,
        ),
        Pair("off-grid-cut", "steady-100", "steady-93", eight, aLengthMs = 201_350),
        // Rate first and the entry on the next song's grid.
        Pair("rate-small-gap", "steady-100", "steady-99.7", matched),
        Pair("entry-on-beat", "steady-101", "steady-100-late", eight),
        Pair("entry-on-downbeat", "steady-101", "steady-100-late", matched),
        Pair("entry-before-start", "steady-101", "steady-100-early", eight),
        // The drift cap with both songs at full level.
        Pair("drift-1", "steady-101", "steady-100", eight),
        Pair("drift-3", "steady-100", "steady-97", eight),
        Pair("drift-7", "steady-100", "steady-93", eight),
        Pair("drift-10", "steady-100", "steady-91", eight),
        Pair("drift-20", "steady-100", "steady-83", eight),
        Pair("drift-40", "steady-100", "steady-71", eight),
        Pair("drift-outro", "steady-101-outro", "steady-93", eight),
        Pair("drift-desktop-pace", "steady-100", "steady-97", eight, context = TransitionContext(pace = 1.25, lockAtPace = true)),
        Pair("drift-phone-pace", "steady-101-outro", "steady-100", matched, context = TransitionContext(pace = 1.25)),
        Pair("pace-steady", "steady-101", "steady-100", matched, context = TransitionContext(skipSilence = true)),
        Pair("drift-late-matched", "steady-100", "steady-97", matched, context = TransitionContext(nowMs = 196_000)),
        Pair("drift-late-capped", "steady-100", "steady-93", eight, context = TransitionContext(nowMs = 196_000)),
        Pair("drift-late-cut", "steady-100", "steady-97", eight, context = TransitionContext(nowMs = 200_500)),
        Pair("drift-short-window", "steady-100", "steady-100", eight, context = TransitionContext(nowMs = 191_450)),
        Pair("late-move-count", "steady-100-steps", "steady-97", eight),
        // The next song heard before the lock point decides the bound.
        Pair("drift-before-lock", "steady-100", "steady-91-lead", eight),
        // A lead-in: an intro's fade still centered on a bar line, and a
        // matched cut held until the next song's first sound.
        Pair("intro-lead", "steady-100", "quiet-100-lead", eight, aLengthMs = 199_950),
        Pair("lead-held-cut", "steady-101", "steady-100-lead", AutomixSettings(maxOverlapMs = 2_400, beatMatch = true)),
        // A silent lead-in longer than the longest blend allowed: no blend
        // would fade the outgoing song out.
        Pair("lead-fills-the-cut", "steady-101", "steady-100-long-lead", AutomixSettings(maxOverlapMs = 1_800, beatMatch = true)),
        // Rate room: a 5% rate the next song is too short to ease back from.
        Pair("rate-room", "steady-100", "short-quiet", matched),
        // Intros.
        Pair("intro-matched", "steady-100", "quiet-106", matched),
        Pair("intro-unmatched", "steady-100", "quiet-106", eight),
        Pair("intro-short-drift", "steady-101", "quiet-100-short", AutomixSettings(maxOverlapMs = 2_400)),
        Pair("intro-drift", "steady-101", "quiet-100", eight),
        Pair("intro-later-start", "steady-101-outro", "quiet-100", eight),
        Pair("intro-tempo-dropped", "steady-100", "quiet-100", eight, context = TransitionContext(tempoPrior = 140.0)),
        Pair("intro-phone-pace", "beat-120", "quiet-intro", matched, context = TransitionContext(pace = 1.25)),
        Pair("intro-phone-pace-confident", "steady-100", "quiet-100", matched, context = TransitionContext(pace = 1.25)),
        Pair("intro-cap", "hot-end", "long-intro", eight, headAgainstBody = true),
        // An intro's quiet start kept INTRO_UNDER_OUTGOING_DB under the
        // outgoing song: one 7 dB under (intro-under-line), one 5 dB under
        // (intro-over-line) and one at its level are each lowered by what
        // they lack of that; one that would need over INTRO_TRIM_CAP_DB gives
        // the blend.
        Pair("intro-under-line", "steady-100", "under-100", eight),
        Pair("intro-over-line", "steady-100", "near-100", eight),
        Pair("intro-trimmed", "steady-100", "level-100", eight),
        Pair("intro-trim-capped", "faint-100", "level-100", eight),
        // The same starts with the decks' gains apart: the 7 dB under one
        // played 6 dB hotter than the outgoing song is trimmed by what it
        // then lacks, the one at its level played 6 dB softer by less.
        Pair("intro-under-gain-up", "steady-100", "under-100", eight, context = TransitionContext(incomingGainDb = 6.0)),
        Pair("intro-trimmed-gain-down", "steady-100", "level-100", eight, context = TransitionContext(incomingGainDb = -6.0)),
        // The hold's percentile against a short dropout and a long break in
        // the outgoing song, and a quiet start mostly silent through the hold.
        Pair("intro-trim-dropout", "steady-100-dropout", "level-100", eight),
        Pair("intro-trim-break", "steady-100-break", "level-100", eight),
        Pair("intro-trim-loud", "steady-100", "loud-100", eight),
        Pair("intro-trim-gap", "steady-100", "loud-100-gap", eight),
        // An intro whose hold ends where the outgoing song drops 10 dB, its
        // tempo equal: lowered, it may not move onto the drop; 24 dB under,
        // it may move as far as the room allows.
        Pair("intro-trimmed-at-outro", "steady-100-outro", "level-100", eight),
        Pair("intro-quiet-at-outro", "steady-100-outro", "quiet-100", eight),
        // No bar grid (the next song has no beat) and room past the end,
        // where the outgoing song drops 26 dB partway along it: a start that
        // would sit over the drop is not moved to at all; one far under
        // stays under, and may move.
        Pair("intro-unlocked-drop", "steady-100-outro-steps", "under-pad", eight),
        Pair("intro-unlocked-quiet", "steady-100-outro-steps", "quiet-pad", eight),
        // Bounded, no rate, the next song sounding just before its first
        // beat: only a cut too short to fade the outgoing song out meets the
        // bound. Matched, late, with a lead-in longer than the blend left:
        // the late blend could not fade it out either.
        Pair("drift-lead-cut", "steady-82", "steady-71-late", eight),
        Pair("late-lead-fills", "steady-101", "steady-100-long-lead", matched, context = TransitionContext(nowMs = 199_000)),
    )

    private val tails = HashMap<String, SectionAnalysis>()
    private val heads = HashMap<String, SectionAnalysis>()
    private val profileHeads = HashMap<String, SectionAnalysis>()

    fun tail(song: String): SectionAnalysis = synchronized(tails) { tails.getOrPut(song) { songs.getValue(song).tail() } }

    fun head(song: String): SectionAnalysis = synchronized(heads) { heads.getOrPut(song) { songs.getValue(song).head() } }

    // The whole song's body level as the vectors file holds it, to four places.
    fun wholeBodyDb(song: String): Double = floor(songs.getValue(song).wholeBodyDb()!! * 10_000 + 0.5) / 10_000

    // The head measured against the whole song's body, as a profile holds it.
    fun profileHead(song: String): SectionAnalysis =
        synchronized(profileHeads) { profileHeads.getOrPut(song) { songs.getValue(song).profileHead(wholeBodyDb(song)) } }

    fun plan(pair: Pair): TransitionPlan = planTransition(
        current = pair.current,
        next = pair.next,
        tail = if (pair.tailMissing) null else tail(pair.a),
        head = when {
            pair.headMissing -> null
            pair.headAgainstBody -> profileHead(pair.b)
            else -> head(pair.b)
        },
        settings = pair.settings,
        context = pair.context,
    )

    fun pair(name: String): Pair = pairs.first { it.name == name }
}
