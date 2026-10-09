package app.winters.octo.playback

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
        // 1.5 s of silence, then full level.
        "plain-b" to SyntheticSong(44_100, 2, 180_000, pad(1_500.0, 180_000.0, -14.0)),
        // 6 s of a quiet pad 24 dB under the body, then full level.
        "quiet-intro" to SyntheticSong(44_100, 2, 180_000, pad(0.0, 6_000.0, -38.0) + pad(6_000.0, 180_000.0, -14.0)),
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
    ) {
        val current: FadeSong get() = FadeSong(if (sameAlbumInOrder) "album" else "a", 1, songs.getValue(a).lengthMs)
        val next: FadeSong get() = FadeSong(if (sameAlbumInOrder) "album" else "b", 2, songs.getValue(b).lengthMs)
    }

    private val eight = AutomixSettings(maxOverlapMs = 8_000)

    val pairs: List<Pair> = listOf(
        Pair("trailing-silence", "silence-tail", "plain-b", eight),
        Pair("fade-out", "fade-out", "plain-b", eight),
        Pair("hot-end", "hot-end", "plain-b", eight),
        Pair("quiet-intro", "hot-end", "quiet-intro", eight),
        Pair("quiet-intro-capped", "hot-end", "quiet-intro", AutomixSettings(maxOverlapMs = 4_000)),
        Pair("same-tempo", "beat-120", "beat-120-b", eight),
        Pair("same-tempo-long", "beat-120", "beat-120-b", AutomixSettings(maxOverlapMs = 16_000)),
        Pair("same-tempo-short", "beat-120", "beat-120-b", AutomixSettings(maxOverlapMs = 5_000)),
        Pair("tempo-clash", "beat-120", "beat-100", eight),
        Pair("beat-match", "beat-120", "beat-118", AutomixSettings(maxOverlapMs = 8_000, beatMatch = true)),
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
        Pair("pace", "beat-120", "beat-120-b", AutomixSettings(maxOverlapMs = 8_000, beatMatch = true), context = TransitionContext(pace = 1.25)),
        Pair("skip-silence", "beat-120", "beat-118", AutomixSettings(maxOverlapMs = 8_000, beatMatch = true), context = TransitionContext(skipSilence = true)),
        Pair("late", "beat-120", "beat-120-b", eight, context = TransitionContext(nowMs = 190_000)),
        Pair("late-short", "beat-120", "beat-120-b", eight, context = TransitionContext(nowMs = 197_000)),
        Pair("too-late", "beat-120", "beat-120-b", eight, context = TransitionContext(nowMs = 199_500)),
        Pair("pre-roll", "fade-out", "plain-b", eight, context = TransitionContext(nowMs = 183_000)),
        Pair("seeked-forward", "fade-out", "plain-b", eight, context = TransitionContext(nowMs = 170_000, playedMs = 60_000)),
        Pair("whole-song-level", "fade-out", "plain-b", eight, context = TransitionContext(bodyLevelDb = -24.0)),
        Pair("tempo-prior", "beat-120", "beat-120-b", eight, context = TransitionContext(tempoPrior = 60.0)),
        Pair("tempo-prior-off", "beat-120", "beat-120-b", eight, context = TransitionContext(tempoPrior = 100.0)),
    )

    private val tails = HashMap<String, SectionAnalysis>()
    private val heads = HashMap<String, SectionAnalysis>()

    fun tail(song: String): SectionAnalysis = synchronized(tails) { tails.getOrPut(song) { songs.getValue(song).tail() } }

    fun head(song: String): SectionAnalysis = synchronized(heads) { heads.getOrPut(song) { songs.getValue(song).head() } }

    fun plan(pair: Pair): TransitionPlan = planTransition(
        current = pair.current,
        next = pair.next,
        tail = if (pair.tailMissing) null else tail(pair.a),
        head = if (pair.headMissing) null else head(pair.b),
        settings = pair.settings,
        context = pair.context,
    )

    fun pair(name: String): Pair = pairs.first { it.name == name }
}
