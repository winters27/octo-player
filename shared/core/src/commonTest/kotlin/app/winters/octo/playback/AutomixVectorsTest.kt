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
        }
        putJsonObject("songs") {
            for ((name, song) in AutomixCases.songs) put(name, songJson(song))
        }
        putJsonArray("sections") {
            val tagged = listOf(Triple("beat-120", "head", 62.0), Triple("beat-100", "tail", 190.0))
            val sections = AutomixCases.songs.keys.flatMap { name -> listOf("tail", "head").map { Triple(name, it, null as Double?) } } + tagged
            for ((name, part, tag) in sections) {
                run {
                    val song = AutomixCases.songs.getValue(name)
                    val analysis = when {
                        tag != null -> sectionOf(song, part, tag)
                        part == "tail" -> AutomixCases.tail(name)
                        else -> AutomixCases.head(name)
                    }
                    addJsonObject {
                        put("song", name)
                        put("part", part)
                        put("tagBpm", tag)
                        put("fromMs", if (part == "tail") maxOf(0L, song.lengthMs - 60_000) else 0L)
                        put("toMs", if (part == "tail") song.lengthMs else minOf(30_000L, song.lengthMs))
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
                }
                put("tailMissing", pair.tailMissing)
                put("headMissing", pair.headMissing)
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
            for ((confidence, consistency) in TRUST_CASES) addJsonObject {
                put("confidence", confidence)
                put("consistency", consistency)
                put("confident", trusted(confidence, consistency))
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
    }

    private fun sectionOf(song: SyntheticSong, part: String, tag: Double?): SectionAnalysis {
        val envelope = if (part == "tail") {
            song.envelope(maxOf(0.0, song.lengthMs - 60_000.0), song.lengthMs.toDouble())
        } else {
            song.envelope(0.0, minOf(30_000.0, song.lengthMs.toDouble()))
        }
        return if (part == "tail") analyzeTail(envelope, tag) else analyzeHead(envelope, tag)
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
        putJsonArray("boundariesMs") { f.boundariesMs.forEach { add(it) } }
        val tempo = f.tempo
        if (tempo == null) put("tempo", JsonNull) else putJsonObject("tempo") {
            put("bpm", round(tempo.bpm))
            put("confidence", round(tempo.confidence))
            put("consistency", round(tempo.consistency))
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
    }

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

        for (section in vectors.getValue("sections").jsonArray.map { it.jsonObject }) {
            val name = section.str("song")
            val part = section.str("part")
            val tag = section.numOrNull("tagBpm")
            val what = if (tag == null) "$name $part" else "$name $part tagged $tag"
            val song = songs.getValue(name)
            val envelopeIn = song.envelope(section.num("fromMs"), section.num("toMs"))
            val analysis = if (part == "tail") analyzeTail(envelopeIn, tag) else analyzeHead(envelopeIn, tag)
            if (tag == null) analyses["$name/$part"] = analysis
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
            val plan = planTransition(
                current = fadeSongOf(case.getValue("current").jsonObject),
                next = fadeSongOf(case.getValue("next").jsonObject),
                tail = if (case.bool("tailMissing")) null else analyses.getValue("${case.str("a")}/tail"),
                head = if (case.bool("headMissing")) null else analyses.getValue("${case.str("b")}/head"),
                settings = AutomixSettings(
                    maxOverlapMs = settings.getValue("maxOverlapMs").jsonPrimitive.long,
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
        }

        for (g in vectors.getValue("gains").jsonArray.map { it.jsonObject }) {
            val (time, k) = g.num("t") to g.num("k")
            near("out gain at $time k $k", g.num("out"), automixOutGain(time, k), t("curve"))
            near("in gain at $time k $k", g.num("in"), automixInGain(time, k), t("curve"))
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
            val (confidence, consistency) = c.num("confidence") to c.num("consistency")
            val actual = trusted(confidence, consistency)
            if (actual != c.bool("confident")) failures += "tempo with peak ratio $confidence and consistency $consistency: confident $actual"
        }
        for (r in vectors.getValue("rates").jsonArray.map { it.jsonObject }) {
            val actual = beatMatchRateAt(r.num("rate"), r.num("sinceEntryMs"), r.num("overlapMs"))
            near("rate at ${r.num("sinceEntryMs")}", r.num("value"), actual, t("curve"))
        }
        return failures
    }

    // Whether a 120 BPM grid with this autocorrelation peak ratio and beat
    // consistency is trusted for bar lock.
    private fun trusted(confidence: Double, consistency: Double) =
        Tempo(120.0, confidence, consistency, 500.0, 0.0, 0.0).confident

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
    )

    private fun fadeSongOf(o: JsonObject) = FadeSong(
        albumId = o["albumId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        albumOrder = o["albumOrder"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
        durationMs = o.getValue("durationMs").jsonPrimitive.long,
    )

    private companion object {
        const val FILTER_VECTOR_SAMPLES = 512

        // Peak ratios and beat consistencies on and either side of the
        // thresholds a tempo must reach to be trusted.
        val TRUST_CASES = listOf(
            1.5 to 0.55,
            1.499 to 0.9,
            1.501 to 0.9,
            9.0 to 0.549,
            9.0 to 0.551,
            1.499 to 0.55,
            1.5 to 0.549,
            1.501 to 0.551,
        )

        val ABOUT = JsonArray(
            listOf(
                "Shared automix cases: the Kotlin tests (AutomixVectorsTest) and the audio engine's Rust tests both run them.",
                "songs: made-up songs. Each layer sounds from fromMs to toMs, its level moving in a straight line in dB from db to endDb (amplitude 10^(dB/20), taken at the sample time for a tone and at the hit time for a pulse).",
                "tone: amplitude * sin(2 pi hz n / rate) for sample n of the song with fromMs <= n*1000/rate < toMs (n from ceil(fromMs*rate/1000) to ceil(toMs*rate/1000) exclusive).",
                "pulse: hits at firstMs + j * every * 60000 / bpm (whole j, fromMs <= hit < toMs); each adds amplitude(hit) * exp(-d / decayMs) * sin(2 pi hz d / 1000) where d = n*1000/rate - hit, for 0 <= d < 8 * decayMs.",
                "Layers add up; every channel carries the same signal; samples are rounded to 32-bit floats.",
                "sections: render samples floor(fromMs*rate/1000) to floor(min(toMs, lengthMs)*rate/1000) exclusive, interleave, feed the envelope builder in blocks of 4093 samples with startMs = floor(fromMs*rate/1000)*1000/rate (integer division), then analyze. probes are envelope values at hop indexes.",
                "sections are analyzed as a tail (analyzeTail: last 60 s) or a head (analyzeHead: first 30 s); tagBpm, when set, is the song's tag tempo.",
                "plans: run the planner with the untagged tail section of song a and head section of song b (null when tailMissing / headMissing) and the given context.",
                "tempoTrust: whether a beat grid with this autocorrelation peak ratio (confidence) and share of beats on an onset peak (consistency) is trusted for bar lock.",
                "biquads: cookbook second-order low-pass / high-pass at the given rate, cutoff and Q; coefficients are b0, b1, b2, a1, a2 (a0 = 1); impulse is the answer to a 1 at sample 0, sine the answer to 0.5 * sin(2 pi 1000 n / rate), 512 samples each, from a fresh filter.",
                "Times are ms, levels dBFS, rates as multiples of normal speed. tolerances: db for levels, ms for times, bpm, confidenceShare as a share of the expected confidence, curve for gains, k and rates, hzShare as a share of the expected cutoff, onset for onset values, sample for filter coefficients and samples.",
            ).map(::JsonPrimitive),
        )
    }
}
