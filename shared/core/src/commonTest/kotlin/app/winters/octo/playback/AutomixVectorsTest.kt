package app.winters.octo.playback

import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

// Every case in automix-vectors.json, the file the audio engine's Rust copy
// of the automix code runs too. The songs are rebuilt from their layers
// (SyntheticSong), scouted, analyzed and planned, and every result must
// match within the file's tolerances. A failure lists every case that is
// off, not only the first.
//
// OCTO_WRITE_AUTOMIX_VECTORS=<path> writes a fresh file from the cases in
// AutomixCases instead of checking.
class AutomixVectorsTest {
    private val pretty = Json { prettyPrint = true }

    @Test
    fun everyVectorMatches() {
        val target = System.getenv("OCTO_WRITE_AUTOMIX_VECTORS")
        if (!target.isNullOrBlank()) {
            File(target).writeText(pretty.encodeToString(JsonElement.serializer(), write()) + "\n")
            return
        }
        val vectors = javaClass.classLoader!!.getResource("automix-vectors.json")!!.readText()
            .let { Json.parseToJsonElement(it).jsonObject }
        val failures = check(vectors)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // Writing

    private fun round(x: Double, places: Int = 4): Double {
        var scale = 1.0
        repeat(places) { scale *= 10 }
        return floor(x * scale + 0.5) / scale
    }

    private fun write(): JsonObject = buildJsonObject {
        put("about", ABOUT)
        put("version", 1)
        putJsonObject("tolerances") {
            put("db", 0.05)
            put("onset", 0.01)
            put("sample", 1e-9)
            put("ms", 20.0)
            put("bpm", 0.05)
            put("confidenceShare", 0.02)
            put("curve", 1e-9)
            put("hzShare", 1e-9)
            put("rate", 1e-4)
            put("level", 1e-3)
        }
        putJsonObject("songs") {
            for ((name, song) in AutomixCases.songs) put(name, songJson(song))
        }
        putJsonArray("sections") {
            val tagged = listOf(
                Section("beat-120", "head", tag = 61.0),
                Section("beat-100", "tail", tag = 198.0),
                Section("beat-128", "head", tag = 70.0),
                Section("beat-120", "head", tag = 125.0),
                Section("beat-120", "head", tag = 0.0),
            )
            // Heads measured against the whole song's body, as a profile
            // holds them.
            val againstBody = listOf("quiet-intro", "long-intro", "endless-intro", "beat-120").map { name ->
                Section(name, "head", bodyDb = AutomixCases.wholeBodyDb(name))
            }
            val sections = AutomixCases.songs.keys.flatMap { name -> listOf("tail", "head").map { Section(name, it) } } + tagged + againstBody
            for ((name, part, tag, bodyDb) in sections) {
                run {
                    val song = AutomixCases.songs.getValue(name)
                    val analysis = when {
                        tag != null || bodyDb != null -> sectionOf(song, part, tag, bodyDb)
                        part == "tail" -> AutomixCases.tail(name)
                        else -> AutomixCases.head(name)
                    }
                    addJsonObject {
                        put("song", name)
                        put("part", part)
                        put("tagBpm", tag)
                        put("bodyLevelDb", bodyDb)
                        put("fromMs", if (part == "tail") maxOf(0L, song.lengthMs - 60_000) else 0L)
                        put("toMs", if (part == "tail") song.lengthMs else minOf(AUTOMIX_HEAD_MS, song.lengthMs))
                        put("hops", analysis.envelope.size)
                        put("startMs", analysis.envelope.startMs)
                        putJsonArray("probes") {
                            for (index in probes(analysis.envelope)) addJsonObject {
                                put("index", index)
                                put("db", round(analysis.envelope.db[index].toDouble()))
                                put("lowDb", round(analysis.envelope.lowDb[index].toDouble()))
                                put("onset", round(analysis.envelope.onset[index].toDouble()))
                                put("lowOnset", round(analysis.envelope.lowOnset[index].toDouble()))
                            }
                        }
                        put("features", featuresJson(analysis.features))
                    }
                }
            }
        }
        putJsonArray("plans") {
            for (pair in AutomixCases.pairs) addJsonObject {
                put("name", pair.name)
                put("a", pair.a)
                put("b", pair.b)
                put("current", fadeSongJson(pair.current))
                put("next", fadeSongJson(pair.next))
                putJsonObject("settings") {
                    put("maxOverlapMs", pair.settings.maxOverlapMs)
                    put("longestIntroMs", pair.settings.longestIntroMs)
                    put("smart", pair.settings.smart)
                    put("filterSweeps", pair.settings.filterSweeps)
                    put("beatMatch", pair.settings.beatMatch)
                }
                putJsonObject("context") {
                    val c = pair.context
                    put("nowMs", c.nowMs)
                    put("playedMs", c.playedMs)
                    put("repeatOne", c.repeatOne)
                    put("stopAtEndOfSong", c.stopAtEndOfSong)
                    put("pace", c.pace)
                    put("skipSilence", c.skipSilence)
                    put("currentGenre", c.currentGenre)
                    put("nextGenre", c.nextGenre)
                    put("bodyLevelDb", c.bodyLevelDb)
                    put("tempoPrior", c.tempoPrior)
                    put("lockAtPace", c.lockAtPace)
                    put("incomingGainDb", c.incomingGainDb)
                }
                put("tailMissing", pair.tailMissing)
                put("headMissing", pair.headMissing)
                put("headBodyLevelDb", if (pair.headAgainstBody) AutomixCases.wholeBodyDb(pair.b) else null)
                put("expected", planJson(AutomixCases.plan(pair)))
            }
        }
        putJsonArray("gains") {
            for (k in listOf(0.0, BLEND_CURVE, CROSSFADE_CURVE, LIFT_CURVE, CUT_CURVE)) {
                for (t in listOf(-0.1, 0.0, 0.1, 0.25, 0.4, 0.5, 0.6, 0.75, 0.9, 1.0, 1.2)) addJsonObject {
                    put("t", t)
                    put("k", k)
                    put("out", automixOutGain(t, k))
                    put("in", automixInGain(t, k))
                }
            }
        }
        putJsonArray("introGains") {
            for ((hold, rise) in INTRO_SHAPES) {
                for (level in INTRO_LEVELS) {
                    for (t in listOf(-0.1, 0.0, 0.02, 0.05, 0.1, 0.25, 0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0, 1.2)) addJsonObject {
                        put("t", t)
                        put("holdShare", hold)
                        put("riseShare", rise)
                        put("level", level)
                        put("out", introOutGain(t, hold))
                        put("in", introInGain(t, rise, hold, level))
                    }
                }
            }
        }
        putJsonArray("filters") {
            for (strength in listOf(0.0, 0.5, FILTER_STRENGTH, 1.0)) {
                for (t in listOf(0.0, 0.1, 0.3, 0.5, 0.6, 0.8, 1.0)) addJsonObject {
                    put("t", t)
                    put("strength", strength)
                    put("outgoingLowPassHz", outgoingLowPassHz(t, strength))
                    put("outgoingHighPassHz", outgoingHighPassHz(t, strength))
                    put("incomingHighPassHz", incomingHighPassHz(t, strength))
                }
            }
        }
        putJsonArray("steppedLowPass") {
            val beats = listOf(0.0625, 0.125, 0.25, 0.5, 0.75)
            for (t in listOf(0.0, 0.05, 0.1, 0.11, 0.124, 0.125, 0.2, 0.24, 0.3, 0.49, 0.6, 0.74, 0.8, 0.99, 1.0)) addJsonObject {
                put("t", t)
                put("strength", FILTER_STRENGTH)
                putJsonArray("beats") { beats.forEach { add(it) } }
                put("glide", 0.03125)
                put("hz", steppedOutgoingLowPassHz(t, FILTER_STRENGTH, beats.toDoubleArray(), 0.03125))
            }
        }
        putJsonArray("biquads") {
            val cases = listOf(
                Triple("lowPass", 44_100, 450.0),
                Triple("lowPass", 48_000, outgoingLowPassHz(1.0, FILTER_STRENGTH)),
                Triple("lowPass", 44_100, 18_000.0),
                Triple("lowPass", 22_050, 18_000.0),
                Triple("lowPass", 48_000, BASS_CUTOFF_HZ),
                Triple("highPass", 48_000, 210.0),
                Triple("highPass", 44_100, 472.5),
                Triple("highPass", 22_050, 20.0),
            )
            for ((type, rate, hz) in cases) addJsonObject {
                put("type", type)
                put("rate", rate)
                put("hz", hz)
                put("q", BUTTERWORTH_Q)
                val filter = biquadOf(type, rate, hz)
                putJsonArray("coefficients") { listOf(filter.b0, filter.b1, filter.b2, filter.a1, filter.a2).forEach { add(it) } }
                putJsonArray("impulse") { impulseOf(filter).forEach { add(it) } }
                putJsonArray("sine") { sineOf(biquadOf(type, rate, hz), rate).forEach { add(it) } }
            }
        }
        putJsonArray("tempoTrust") {
            for ((confidence, steady) in TRUST_CASES) addJsonObject {
                put("confidence", confidence)
                put("steady", steady)
                put("confident", trusted(confidence, steady))
            }
        }
        putJsonArray("rates") {
            for (since in listOf(0.0, 4_000.0, 8_000.0, 9_000.0, 10_500.0, 12_999.0, 13_000.0, 20_000.0)) addJsonObject {
                put("rate", 1.03)
                put("sinceEntryMs", since)
                put("overlapMs", 8_000.0)
                put("value", beatMatchRateAt(1.03, since, 8_000.0))
            }
        }
        putJsonArray("dropCuts") {
            for (c in DROP_CASES) addJsonObject {
                put("bothHeardStartMs", c.startMs)
                put("bothHeardEndMs", c.endMs)
                put("holdEndMs", c.holdEndMs)
                put("lockMs", c.lockMs)
                put("driftGap", c.gap)
                put("speed", c.speed)
                put("fromMs", c.fromMs)
                put("cutAtMs", dropCutAtMs(dropPlan(c), c.speed, c.fromMs))
            }
        }
    }

    // A both-heard stretch, where the beats meet, how fast they part, the
    // speed lowered to, how far along the blend is and where its hold ends
    // (the plan's holdShare of the 8 s overlap).
    private data class DropCase(
        val startMs: Long,
        val endMs: Long,
        val lockMs: Double,
        val gap: Double,
        val speed: Double,
        val fromMs: Double,
        val holdEndMs: Double = 0.0,
    )

    // One section the file holds: a song's tail or head, with the song's
    // tag tempo when it has one, and for a head the whole song's body level
    // when it is measured against it.
    private data class Section(val song: String, val part: String, val tag: Double? = null, val bodyDb: Double? = null)

    private fun sectionOf(song: SyntheticSong, part: String, tag: Double?, bodyDb: Double?): SectionAnalysis {
        val envelope = if (part == "tail") {
            song.envelope(maxOf(0.0, song.lengthMs - 60_000.0), song.lengthMs.toDouble())
        } else {
            song.envelope(0.0, minOf(AUTOMIX_HEAD_MS.toDouble(), song.lengthMs.toDouble()))
        }
        return if (part == "tail") analyzeTail(envelope, tag) else analyzeHead(envelope, tag, bodyDb)
    }

    private fun biquadOf(type: String, rate: Int, hz: Double) =
        if (type == "lowPass") Biquad.lowPass(rate, hz) else Biquad.highPass(rate, hz)

    // The filter's answer to a 1 at sample 0, over FILTER_VECTOR_SAMPLES samples.
    private fun impulseOf(filter: Biquad) = DoubleArray(FILTER_VECTOR_SAMPLES) { filter.process(if (it == 0) 1.0 else 0.0) }

    // The filter's answer to 0.5 * sin(2 pi 1000 n / rate).
    private fun sineOf(filter: Biquad, rate: Int) =
        DoubleArray(FILTER_VECTOR_SAMPLES) { filter.process(0.5 * kotlin.math.sin(2 * kotlin.math.PI * 1_000.0 * it / rate)) }

    // Every 500th hop, and the five hops from the loudest onset on.
    private fun probes(envelope: SectionEnvelope): List<Int> {
        var loudest = 0
        for (i in envelope.onset.indices) if (envelope.onset[i] > envelope.onset[loudest]) loudest = i
        return ((0 until envelope.size step 500) + (loudest until minOf(envelope.size, loudest + 5))).distinct().sorted()
    }

    private fun songJson(song: SyntheticSong) = buildJsonObject {
        put("rate", song.rate)
        put("channels", song.channels)
        put("lengthMs", song.lengthMs)
        putJsonArray("layers") {
            for (layer in song.layers) addJsonObject {
                put("kind", layer.kind)
                put("fromMs", layer.fromMs)
                put("toMs", layer.toMs)
                put("hz", layer.hz)
                put("db", layer.db)
                put("endDb", layer.endDb)
                if (layer.kind == "pulse") {
                    put("bpm", layer.bpm)
                    put("firstMs", layer.firstMs)
                    put("every", layer.every)
                    put("decayMs", layer.decayMs)
                }
            }
        }
    }

    private fun featuresJson(f: SectionFeatures) = buildJsonObject {
        put("bodyDb", round(f.bodyDb))
        put("gateDb", round(f.gateDb))
        put("soundStartMs", f.soundStartMs)
        put("soundEndMs", f.soundEndMs)
        put("outroStartMs", f.outroStartMs)
        put("introEndMs", f.introEndMs)
        putJsonArray("boundariesMs") { f.boundariesMs.forEach { add(it) } }
        val tempo = f.tempo
        if (tempo == null) put("tempo", JsonNull) else putJsonObject("tempo") {
            put("bpm", round(tempo.bpm))
            put("confidence", round(tempo.confidence))
            put("consistency", round(tempo.consistency))
            put("steady", tempo.steady)
            put("confident", tempo.confident)
            put("beatMs", round(tempo.beatMs))
            put("firstBeatMs", round(tempo.firstBeatMs))
            put("downbeatMs", round(tempo.downbeatMs))
        }
    }

    private fun fadeSongJson(song: FadeSong) = buildJsonObject {
        put("albumId", song.albumId)
        put("albumOrder", song.albumOrder)
        put("durationMs", song.durationMs)
    }

    private fun planJson(plan: TransitionPlan) = buildJsonObject {
        put("startMs", plan.startMs)
        put("entryMs", plan.entryMs)
        put("overlapMs", plan.overlapMs)
        put("kind", plan.kind.name.lowercase())
        put("k", plan.k)
        put("filterStrength", plan.filterStrength)
        put("beatMatchRate", plan.beatMatchRate?.let { round(it, 6) })
        put("beatMs", plan.beatMs?.let { round(it) })
        put("beatAnchorMs", plan.beatAnchorMs?.let { round(it) })
        put("late", plan.late)
        put("headroomDb", plan.headroomDb)
        put("reason", plan.reason)
        put("holdShare", plan.holdShare)
        put("riseShare", plan.riseShare)
        put("introLevel", round(plan.introLevel, 6))
        put("latestEndMs", plan.latestEndMs)
        put("lateMoveBars", plan.lateMoveBars)
        put("bothHeardStartMs", plan.bothHeardStartMs)
        put("bothHeardEndMs", plan.bothHeardEndMs)
        put("lockMs", round(plan.lockMs))
        put("driftGap", round(plan.driftGap, 6))
        put("driftAtEndMs", round(plan.driftAtEndMs))
        put("dropCutAtHalfSpeedMs", dropCutAtMs(plan, 0.5, 0.0)?.let { round(it) })
    }

    // A plan carrying only what dropCutAtMs reads.
    private fun dropPlan(c: DropCase) = fixedCrossfadePlan(200_000, 8_000, "test").copy(
        bothHeardStartMs = c.startMs, bothHeardEndMs = c.endMs, lockMs = c.lockMs, driftGap = c.gap, holdShare = c.holdEndMs / 8_000,
    )

    // Checking

    private fun check(vectors: JsonObject): List<String> {
        val failures = ArrayList<String>()
        val tol = vectors.getValue("tolerances").jsonObject
        fun t(name: String) = tol.getValue(name).jsonPrimitive.double
        fun near(what: String, expected: Double?, actual: Double?, within: Double) {
            if (expected == null || actual == null) {
                if (expected != actual) failures += "$what: expected $expected, got $actual"
            } else if (abs(expected - actual) > within) {
                failures += "$what: expected $expected, got $actual (within $within)"
            }
        }

        val songs = vectors.getValue("songs").jsonObject.mapValues { songOf(it.value.jsonObject) }
        val analyses = HashMap<String, SectionAnalysis>()
        // Heads measured against a whole song's body, by song and body level.
        val againstBody = HashMap<String, SectionAnalysis>()

        for (section in vectors.getValue("sections").jsonArray.map { it.jsonObject }) {
            val name = section.str("song")
            val part = section.str("part")
            val tag = section.numOrNull("tagBpm")
            val bodyDb = section.numOrNull("bodyLevelDb")
            val what = when {
                tag != null -> "$name $part tagged $tag"
                bodyDb != null -> "$name $part against a body of $bodyDb dB"
                else -> "$name $part"
            }
            val song = songs.getValue(name)
            val envelopeIn = song.envelope(section.num("fromMs"), section.num("toMs"))
            val analysis = if (part == "tail") analyzeTail(envelopeIn, tag) else analyzeHead(envelopeIn, tag, bodyDb)
            if (tag == null && bodyDb == null) analyses["$name/$part"] = analysis
            if (tag == null && bodyDb != null && part == "head") againstBody["$name@$bodyDb"] = analysis
            val envelope = analysis.envelope
            if (envelope.size != section.getValue("hops").jsonPrimitive.int) failures += "$what: ${envelope.size} hops"
            if (envelope.startMs != section.getValue("startMs").jsonPrimitive.long) failures += "$what: starts at ${envelope.startMs}"
            for (probe in section.getValue("probes").jsonArray.map { it.jsonObject }) {
                val i = probe.getValue("index").jsonPrimitive.int
                near("$what hop $i db", probe.num("db"), envelope.db.getOrNull(i)?.toDouble(), t("db"))
                near("$what hop $i lowDb", probe.num("lowDb"), envelope.lowDb.getOrNull(i)?.toDouble(), t("db"))
                near("$what hop $i onset", probe.num("onset"), envelope.onset.getOrNull(i)?.toDouble(), t("onset"))
                near("$what hop $i lowOnset", probe.num("lowOnset"), envelope.lowOnset.getOrNull(i)?.toDouble(), t("onset"))
            }
            val expected = section.getValue("features").jsonObject
            val f = analysis.features
            near("$what bodyDb", expected.num("bodyDb"), f.bodyDb, t("db"))
            near("$what gateDb", expected.num("gateDb"), f.gateDb, t("db"))
            near("$what soundStartMs", expected.numOrNull("soundStartMs"), f.soundStartMs?.toDouble(), t("ms"))
            near("$what soundEndMs", expected.numOrNull("soundEndMs"), f.soundEndMs?.toDouble(), t("ms"))
            near("$what outroStartMs", expected.numOrNull("outroStartMs"), f.outroStartMs?.toDouble(), t("ms"))
            near("$what introEndMs", expected.numOrNull("introEndMs"), f.introEndMs?.toDouble(), t("ms"))
            val boundaries = expected.getValue("boundariesMs").jsonArray.map { it.jsonPrimitive.double }
            if (boundaries.size != f.boundariesMs.size) {
                failures += "$what boundaries: expected $boundaries, got ${f.boundariesMs}"
            } else {
                boundaries.zip(f.boundariesMs).forEach { (e, a) -> near("$what boundary", e, a.toDouble(), t("ms")) }
            }
            val tempo = expected["tempo"]
            if (tempo == null || tempo is JsonNull) {
                if (f.tempo != null) failures += "$what: expected no tempo, got ${f.tempo}"
            } else {
                val e = tempo.jsonObject
                val a = f.tempo
                if (a == null) {
                    failures += "$what: expected a tempo, got none"
                } else {
                    near("$what bpm", e.num("bpm"), a.bpm, t("bpm"))
                    near("$what confidence", e.num("confidence"), a.confidence, e.num("confidence") * t("confidenceShare"))
                    near("$what consistency", e.num("consistency"), a.consistency, 0.02)
                    if (e.getValue("steady").jsonPrimitive.boolean != a.steady) failures += "$what: steady ${a.steady}"
                    if (e.getValue("confident").jsonPrimitive.boolean != a.confident) failures += "$what: confident ${a.confident}"
                    near("$what beatMs", e.num("beatMs"), a.beatMs, t("ms") / 10)
                    near("$what firstBeatMs", e.num("firstBeatMs"), a.firstBeatMs, t("ms"))
                    near("$what downbeatMs", e.num("downbeatMs"), a.downbeatMs, t("ms"))
                }
            }
        }

        for (case in vectors.getValue("plans").jsonArray.map { it.jsonObject }) {
            val name = case.str("name")
            val settings = case.getValue("settings").jsonObject
            val headBody = case.numOrNull("headBodyLevelDb")
            val plan = planTransition(
                current = fadeSongOf(case.getValue("current").jsonObject),
                next = fadeSongOf(case.getValue("next").jsonObject),
                tail = if (case.bool("tailMissing")) null else analyses.getValue("${case.str("a")}/tail"),
                head = when {
                    case.bool("headMissing") -> null
                    headBody != null -> againstBody.getValue("${case.str("b")}@$headBody")
                    else -> analyses.getValue("${case.str("b")}/head")
                },
                settings = AutomixSettings(
                    maxOverlapMs = settings.getValue("maxOverlapMs").jsonPrimitive.long,
                    longestIntroMs = settings.getValue("longestIntroMs").jsonPrimitive.long,
                    smart = settings.bool("smart"),
                    filterSweeps = settings.bool("filterSweeps"),
                    beatMatch = settings.bool("beatMatch"),
                ),
                context = contextOf(case.getValue("context").jsonObject),
            )
            val e = case.getValue("expected").jsonObject
            val what = "plan $name"
            if (e.str("kind") != plan.kind.name.lowercase()) failures += "$what: kind ${plan.kind}, expected ${e.str("kind")}"
            near("$what startMs", e.num("startMs"), plan.startMs.toDouble(), t("ms"))
            near("$what entryMs", e.num("entryMs"), plan.entryMs.toDouble(), t("ms"))
            near("$what overlapMs", e.num("overlapMs"), plan.overlapMs.toDouble(), t("ms"))
            near("$what k", e.num("k"), plan.k, t("curve"))
            near("$what filterStrength", e.num("filterStrength"), plan.filterStrength, t("curve"))
            near("$what beatMatchRate", e.numOrNull("beatMatchRate"), plan.beatMatchRate, t("rate") * 10)
            near("$what beatMs", e.numOrNull("beatMs"), plan.beatMs, t("ms") / 10)
            near("$what beatAnchorMs", e.numOrNull("beatAnchorMs"), plan.beatAnchorMs, t("ms"))
            near("$what headroomDb", e.num("headroomDb"), plan.headroomDb, t("curve"))
            if (e.bool("late") != plan.late) failures += "$what: late ${plan.late}"
            near("$what holdShare", e.num("holdShare"), plan.holdShare, t("curve"))
            near("$what riseShare", e.num("riseShare"), plan.riseShare, t("curve"))
            near("$what introLevel", e.num("introLevel"), plan.introLevel, t("level"))
            near("$what latestEndMs", e.num("latestEndMs"), plan.latestEndMs.toDouble(), t("ms"))
            val bars = e.getValue("lateMoveBars").jsonPrimitive.int
            if (bars != plan.lateMoveBars) failures += "$what: lateMoveBars ${plan.lateMoveBars}, expected $bars"
            near("$what bothHeardStartMs", e.num("bothHeardStartMs"), plan.bothHeardStartMs.toDouble(), t("ms"))
            near("$what bothHeardEndMs", e.num("bothHeardEndMs"), plan.bothHeardEndMs.toDouble(), t("ms"))
            near("$what lockMs", e.num("lockMs"), plan.lockMs, t("ms") / 10)
            near("$what driftGap", e.num("driftGap"), plan.driftGap, t("rate"))
            near("$what driftAtEndMs", e.num("driftAtEndMs"), plan.driftAtEndMs, t("ms") / 10)
            near("$what dropCutAtHalfSpeedMs", e.numOrNull("dropCutAtHalfSpeedMs"), dropCutAtMs(plan, 0.5, 0.0), t("ms"))
            if (e.str("reason") != plan.reason) failures += "$what: reason \"${plan.reason}\", expected \"${e.str("reason")}\""
        }

        for (g in vectors.getValue("gains").jsonArray.map { it.jsonObject }) {
            val (time, k) = g.num("t") to g.num("k")
            near("out gain at $time k $k", g.num("out"), automixOutGain(time, k), t("curve"))
            near("in gain at $time k $k", g.num("in"), automixInGain(time, k), t("curve"))
        }
        for (g in vectors.getValue("introGains").jsonArray.map { it.jsonObject }) {
            val time = g.num("t")
            val (hold, rise) = g.num("holdShare") to g.num("riseShare")
            val level = g.num("level")
            near("intro out gain at $time holding to $hold", g.num("out"), introOutGain(time, hold), t("curve"))
            near("intro in gain at $time rising to $rise at $level", g.num("in"), introInGain(time, rise, hold, level), t("curve"))
        }
        for (f in vectors.getValue("filters").jsonArray.map { it.jsonObject }) {
            val (time, s) = f.num("t") to f.num("strength")
            near("low-pass at $time strength $s", f.num("outgoingLowPassHz"), outgoingLowPassHz(time, s), f.num("outgoingLowPassHz") * t("hzShare"))
            near("bass cut at $time strength $s", f.num("outgoingHighPassHz"), outgoingHighPassHz(time, s), f.num("outgoingHighPassHz") * t("hzShare"))
            near("incoming high-pass at $time strength $s", f.num("incomingHighPassHz"), incomingHighPassHz(time, s), f.num("incomingHighPassHz") * t("hzShare"))
        }
        for (f in vectors.getValue("steppedLowPass").jsonArray.map { it.jsonObject }) {
            val beats = f.getValue("beats").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
            val actual = steppedOutgoingLowPassHz(f.num("t"), f.num("strength"), beats, f.num("glide"))
            near("stepped low-pass at ${f.num("t")}", f.num("hz"), actual, f.num("hz") * t("hzShare"))
        }
        for (f in vectors.getValue("biquads").jsonArray.map { it.jsonObject }) {
            val type = f.str("type")
            val rate = f.getValue("rate").jsonPrimitive.int
            val hz = f.num("hz")
            val what = "$type at $hz Hz, $rate Hz"
            val filter = biquadOf(type, rate, hz)
            val expected = f.getValue("coefficients").jsonArray.map { it.jsonPrimitive.double }
            listOf(filter.b0, filter.b1, filter.b2, filter.a1, filter.a2).forEachIndexed { i, c -> near("$what coefficient $i", expected[i], c, t("sample")) }
            val impulse = impulseOf(filter)
            f.getValue("impulse").jsonArray.forEachIndexed { i, x -> near("$what impulse $i", x.jsonPrimitive.double, impulse[i], t("sample")) }
            val sine = sineOf(biquadOf(type, rate, hz), rate)
            f.getValue("sine").jsonArray.forEachIndexed { i, x -> near("$what sine $i", x.jsonPrimitive.double, sine[i], t("sample")) }
        }
        for (c in vectors.getValue("tempoTrust").jsonArray.map { it.jsonObject }) {
            val (confidence, steady) = c.num("confidence") to c.bool("steady")
            val actual = trusted(confidence, steady)
            if (actual != c.bool("confident")) failures += "tempo scoring $confidence, steady $steady: confident $actual"
        }
        for (r in vectors.getValue("rates").jsonArray.map { it.jsonObject }) {
            val actual = beatMatchRateAt(r.num("rate"), r.num("sinceEntryMs"), r.num("overlapMs"))
            near("rate at ${r.num("sinceEntryMs")}", r.num("value"), actual, t("curve"))
        }
        for (d in vectors.getValue("dropCuts").jsonArray.map { it.jsonObject }) {
            val c = DropCase(
                d.getValue("bothHeardStartMs").jsonPrimitive.long, d.getValue("bothHeardEndMs").jsonPrimitive.long,
                d.num("lockMs"), d.num("driftGap"), d.num("speed"), d.num("fromMs"), d.num("holdEndMs"),
            )
            near("drop cut for $c", d.numOrNull("cutAtMs"), dropCutAtMs(dropPlan(c), c.speed, c.fromMs), t("curve"))
        }
        return failures
    }

    // Whether a 120 BPM grid with this period score, steady or not, is
    // trusted for bar lock.
    private fun trusted(confidence: Double, steady: Boolean) =
        Tempo(120.0, confidence, 1.0, 500.0, 0.0, 0.0, steady).confident

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.numOrNull(key: String): Double? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double

    private fun songOf(o: JsonObject) = SyntheticSong(
        rate = o.getValue("rate").jsonPrimitive.int,
        channels = o.getValue("channels").jsonPrimitive.int,
        lengthMs = o.getValue("lengthMs").jsonPrimitive.long,
        layers = o.getValue("layers").jsonArray.map { it.jsonObject }.map { l ->
            SongLayer(
                kind = l.str("kind"),
                fromMs = l.num("fromMs"),
                toMs = l.num("toMs"),
                hz = l.num("hz"),
                db = l.num("db"),
                endDb = l.num("endDb"),
                bpm = l.numOrNull("bpm") ?: 0.0,
                firstMs = l.numOrNull("firstMs") ?: 0.0,
                every = l["every"]?.jsonPrimitive?.int ?: 1,
                decayMs = l.numOrNull("decayMs") ?: 0.0,
            )
        },
    )

    private fun contextOf(o: JsonObject) = TransitionContext(
        nowMs = o.getValue("nowMs").jsonPrimitive.long,
        playedMs = o.getValue("playedMs").jsonPrimitive.long,
        repeatOne = o.bool("repeatOne"),
        stopAtEndOfSong = o.bool("stopAtEndOfSong"),
        pace = o.num("pace"),
        skipSilence = o.bool("skipSilence"),
        currentGenre = o["currentGenre"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        nextGenre = o["nextGenre"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        bodyLevelDb = o.numOrNull("bodyLevelDb"),
        tempoPrior = o.numOrNull("tempoPrior"),
        lockAtPace = o["lockAtPace"]?.jsonPrimitive?.boolean ?: false,
        incomingGainDb = o.num("incomingGainDb"),
    )

    private fun fadeSongOf(o: JsonObject) = FadeSong(
        albumId = o["albumId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        albumOrder = o["albumOrder"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
        durationMs = o.getValue("durationMs").jsonPrimitive.long,
    )

    private companion object {
        const val FILTER_VECTOR_SAMPLES = 512

        // Intro shapes as (holdShare, riseShare): a 12 s quiet start with a
        // 3 s fade and a 1 s rise; 6 bars at 120 BPM with a 2-bar fade and
        // a 1-bar rise; a 4 s quiet start with a 2 s fade; a capped 16 s one;
        // a rise asked to end past the hold, which ends there.
        val INTRO_SHAPES = listOf(
            10_500.0 / 13_500 to 1_000.0 / 13_500,
            10_000.0 / 14_000 to 2_000.0 / 14_000,
            0.6 to 0.2,
            13_000.0 / 16_000 to 1_000.0 / 16_000,
            0.1 to 0.25,
        )

        // The incoming song's level through each intro's hold: its own, and
        // lowered 6 dB.
        val INTRO_LEVELS = listOf(1.0, 0.5)

        // Lowered speeds against both-heard stretches: a cut later in an
        // intro's stretch, none within the limit, at once past the moment,
        // before the lock point, after it, at once past it, past the
        // stretch, a stretch ending where the limit is reached, with no
        // stretch, with no drift, and a stretch starting inside a hold: at
        // once with no hold, at the hold's end when the beats are still
        // apart there, none when the hold ends within the limit.
        val DROP_CASES = listOf(
            DropCase(1_900, 3_500, 0.0, 0.01, 0.5, 500.0),
            DropCase(1_900, 3_500, 0.0, 0.01, 1.0, 500.0),
            DropCase(1_900, 3_500, 0.0, 0.01, 0.5, 2_500.0),
            DropCase(100, 650, 400.0, 0.1, 0.5, 0.0),
            DropCase(100, 650, 400.0, 0.1, 0.5, 300.0),
            DropCase(100, 650, 400.0, 0.1, 0.5, 620.0),
            DropCase(100, 650, 400.0, 0.1, 0.5, 700.0),
            DropCase(0, 2_000, 0.0, 0.02, 1.0, 0.0),
            DropCase(0, 0, 0.0, 0.02, 0.5, 0.0),
            DropCase(500, 3_000, 200.0, 0.0, 0.5, 0.0),
            DropCase(500, 7_500, 6_000.0, 0.0025, 0.25, 0.0),
            DropCase(500, 7_500, 6_000.0, 0.0025, 0.25, 0.0, holdEndMs = 1_500.0),
            DropCase(500, 7_500, 6_000.0, 0.0025, 0.25, 0.0, holdEndMs = 3_000.0),
        )

        // Period scores on and either side of the threshold a tempo must
        // reach to be trusted, steady or not.
        val TRUST_CASES = listOf(
            0.4 to true,
            0.399 to true,
            0.401 to true,
            9.0 to false,
            0.4 to false,
            0.401 to false,
        )

        val ABOUT = JsonArray(
            listOf(
                "Shared automix cases: the Kotlin tests (AutomixVectorsTest) and the audio engine's Rust tests both run them.",
                "songs: made-up songs. Each layer sounds from fromMs to toMs, its level moving in a straight line in dB from db to endDb (amplitude 10^(dB/20), taken at the sample time for a tone and at the hit time for a pulse).",
                "tone: amplitude * sin(2 pi hz n / rate) for sample n of the song with fromMs <= n*1000/rate < toMs (n from ceil(fromMs*rate/1000) to ceil(toMs*rate/1000) exclusive).",
                "pulse: hits at firstMs + j * every * 60000 / bpm (whole j, fromMs <= hit < toMs); each adds amplitude(hit) * exp(-d / decayMs) * sin(2 pi hz d / 1000) where d = n*1000/rate - hit, for 0 <= d < 8 * decayMs.",
                "Layers add up; every channel carries the same signal; samples are rounded to 32-bit floats.",
                "sections: render samples floor(fromMs*rate/1000) to floor(min(toMs, lengthMs)*rate/1000) exclusive, interleave, feed the envelope builder in blocks of 4093 samples with startMs = floor(fromMs*rate/1000)*1000/rate (integer division), then analyze. probes are envelope values at hop indexes.",
                "sections are analyzed as a tail (analyzeTail: last 60 s) or a head (analyzeHead: first 45 s); tagBpm, when set, is the song's tag tempo.",
                "bodyLevelDb, when set on a head, is the whole song's body level (the 75th percentile body of the whole song, as a profile measures it); the head is measured against it instead of its own body. A head whose sound starts but never comes within 6 dB of its body has introEndMs at the head's end.",
                "plans: run the planner with the tail section of song a and the head section of song b that have neither tagBpm nor bodyLevelDb (null when tailMissing / headMissing; when headBodyLevelDb is set, the head section of song b measured against that body instead) and the given context; settings.longestIntroMs is the longest intro; context.lockAtPace says the beats stay locked at any pace (the player changes the speed of both songs together after mixing them).",
                "plan fields: holdShare and riseShare are an intro's hold and rise as shares of the overlap; any other kind holds until holdShare (the outgoing song at 1, the incoming one at 0, while the incoming song's lead-in is silent), then follows its curve over the rest; both 0 otherwise. introLevel is the incoming song's gain through an intro's hold (1 for every other kind). First the hold's level: at each 10 ms step of the hold where the incoming song, at its own level with its rise, is above its gate, its 400 ms level plus context.incomingGainDb less the outgoing song's at the same moment; with p the 95th percentile of those (sorted, index floor(0.95 * (n - 1))), 10^(-(p + 8) / 20) when p + 8 > 0, else 1. Then the overlap's: at each 10 ms step from 0 to floor(overlapMs / 10) * 10 where either song's own 400 ms level is above its gate, with a the outgoing song's level and b the incoming song's plus incomingGainDb, out and in the intro gains there (in at the level tried) each times 10^(-1/20), e = 10 * log10(10^(a/10) * out^2 + 10^(b/10) * in^2) - max(a, b); when the largest e at the hold's level is over -0.5, the trim in dB is halved 14 times between the hold's and 30, keeping the upper end whose largest e is at most -0.5, and the level is 10^(-trim / 20). An intro needing over 30 dB is not planned. latestEndMs is the latest the blend may end in the outgoing song's time (the song's length for a fixed crossfade and a gapless join); lateMoveBars is how many whole bars a late start may move the plan later as it is (2147483647 for any number, never for an intro: an intro counts a move only while its shape there at its introLevel keeps both rules, the hold's level no lower and the largest e at most -0.5; with no bar grid it is 1 when every 250 ms step to the latest end and the latest end itself does, 0 when one does not or there is no room). From startMs: bothHeardStartMs and bothHeardEndMs are where both songs are within 10 dB of their bodies (with their gains and the 1 dB headroom, walked in 10 ms steps; both 0 when they never are), lockMs where the beats meet; driftGap is the share the tempos are apart (0 with a rate, equal tempos or no lock), driftAtEndMs the largest beat offset in song time while both are heard, dropCutAtHalfSpeedMs what dropCuts gives for a drop to speed 0.5 from the start, with holdShare times overlapMs as holdEndMs.",
                "dropCuts: the first moment t >= max(fromMs, holdEndMs) in [bothHeardStartMs, bothHeardEndMs] where driftGap * |t - lockMs| > 40 * speed (the moment the offset passes that, lockMs + 40 * speed / driftGap when it comes after the lock point), or null; null when driftGap is 0 or bothHeardEndMs is 0.",
                "introGains: the outgoing song's gain is 1 up to holdShare, then out = cos(pi/2 * (t - holdShare) / (1 - holdShare)); the incoming song's is level * sin(pi/2 * t / rise) below rise = min(riseShare, holdShare), then level up to holdShare, then sqrt(level^2 + (1 - level^2) * (1 - out^2)); t is clamped to 0..1.",
                "tempoTrust: whether a beat grid whose period scores this (confidence: the onsets' autocorrelation at one, two, four and eight beat periods plus half of it at half a period) and whose two halves agree on the beat (steady) is trusted for bar lock.",
                "biquads: cookbook second-order low-pass / high-pass at the given rate, cutoff and Q; coefficients are b0, b1, b2, a1, a2 (a0 = 1); impulse is the answer to a 1 at sample 0, sine the answer to 0.5 * sin(2 pi 1000 n / rate), 512 samples each, from a fresh filter.",
                "Times are ms, levels dBFS, rates as multiples of normal speed. tolerances: db for levels, ms for times, bpm, confidenceShare as a share of the expected confidence, curve for gains, k and rates, hzShare as a share of the expected cutoff, onset for onset values, sample for filter coefficients and samples, level for introLevel.",
            ).map(::JsonPrimitive),
        )
    }
}
