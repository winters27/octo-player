package app.winters.octo.playback

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Runs blend-session-vectors.json, the file the OctoKit Swift session runs
// too. Its parts:
// - profiles: named platform numbers, BlendTiming's fields.
// - preludes: named step lists. A step {"op": "prelude", "name": n} stands
//   for list n; preludes may use preludes.
// - vectors: {name, about, profile, steps}. Each step runs one op on a fresh
//   session and commit word per vector; then every action the session gives
//   (takeAction until empty) and every event it lets out at the head of the
//   latest advance (takeEvent until empty) must equal the step's "actions"
//   and "events" lists, in order. A missing list means none. The ops:
//   - advance {to, head, aSongMs}: BlendSession.advance.
//   - arm {gen, startMs, overlapMs, latestEndMs, outgoingEndMs,
//     lateMoveBars?, barMs?, holdShare?, speed?, bothHeardStartMs?,
//     bothHeardEndMs?, lockMs?, driftGap?, commitBeforeAck?}: opens gen on
//     the commit word, as the registering thread does, then arms.
//     lateMoveBars is a number or "unbounded" (LATE_MOVE_UNBOUNDED) and
//     defaults to 0; commitBeforeAck defaults to false, speed to 1, barMs to
//     none, the rest to 0.
//   - paceChanged {pace, frame}: BlendSession.paceChanged.
//   - post {deck: "A" | "B", input, frame, value?}: BlendSession.post.
//   - ack and cancel {gen, ok}: main's compare-and-set on the word, which
//     must return ok.
//   - word {gen, state}: the word's state for gen.
//   - disarm {reason: "edit" | "pause", frame, settledHead?}, failed {deck,
//     pass?}, followUp {gen, pass}, stopped {gen}, cleared {from}.
//   Kinds, inputs, states and causes are the enum names in lower camel case
//   (START_B is "startB"). An action is {kind, frame, value?, ms?}, an event
//   {kind, gen, frame, ms?, ms2?, cause?}: a missing value or ms is 0, a
//   missing cause "none". A pass is a number, "none" (-1) or "held" (-2).
//   Times in ms match to 1e-6.
// - armAt, replanNow, ease, dropCut, commitWords: cases for blendArmAtMs,
//   blendReplanNowMs, blendIncomingRate, blendDropCutAtMs (expect null for
//   none) and the commit word's packing.
// The expected values were worked out from the rules by hand, never written
// from a run. A failure lists every step that is off, not only the first.
class BlendSessionVectorsTest {
    private val file: JsonObject =
        Json.parseToJsonElement(javaClass.classLoader!!.getResource("blend-session-vectors.json")!!.readText()).jsonObject

    @Test
    fun everyScriptGivesItsActionsAndEvents() {
        val profiles = file.getValue("profiles").jsonObject
        val preludes = file.getValue("preludes").jsonObject
        val failures = mutableListOf<String>()
        val names = mutableSetOf<String>()
        for (element in file.getValue("vectors").jsonArray) {
            val vector = element.jsonObject
            val name = vector.text("name")
            if (!names.add(name)) failures += "$name: the name is used twice"
            val steps = mutableListOf<JsonObject>()
            expand(vector.getValue("steps").jsonArray, preludes, steps)
            failures += run(name, profiles.getValue(vector.text("profile")).jsonObject, steps)
        }
        assertTrue(names.size >= 40)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // The copy OctoKit's tests embed (BlendSessionVectorsData.swift) holds the
    // same JSON as this file.
    @Test
    fun theSwiftCopyHoldsTheSameVectors() {
        val swift = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "ios/Tests/OctoKitTests/Playback/BlendSessionVectorsData.swift") }
            .firstOrNull(File::isFile)
            ?: error("BlendSessionVectorsData.swift not found above ${File("").absolutePath}")
        val embedded = swift.readText().substringAfter("##\"\"\"").substringBefore("\"\"\"##")
        assertEquals(file, Json.parseToJsonElement(embedded))
    }

    // The runner itself: a step whose action is one frame off, and one that
    // waits for an event that never comes, are both reported.
    @Test
    fun aWrongExpectationIsReported() {
        val profile = file.getValue("profiles").jsonObject.getValue("android").jsonObject
        val steps = listOf(
            """{"op": "advance", "to": 360000, "head": 336000, "aSongMs": 177500}""",
            """{"op": "arm", "gen": 1, "startMs": 180000, "overlapMs": 2000, "latestEndMs": 185000, "outgoingEndMs": 185250}""",
            """{"op": "post", "deck": "B", "input": "ready", "frame": 380000}""",
            """{"op": "post", "deck": "A", "input": "startAt", "frame": 451200, "value": 480000}""",
            """{"op": "advance", "to": 456000, "head": 446400, "aSongMs": 179500, "actions": [{"kind": "startB", "frame": 480001}, {"kind": "limiterOn", "frame": 480000}]}""",
            """{"op": "advance", "to": 460800, "head": 451200, "aSongMs": 179600, "events": [{"kind": "proposed", "gen": 1, "frame": 480000}]}""",
        ).map { Json.parseToJsonElement(it).jsonObject }
        val failures = run("wrong", profile, steps)
        assertEquals(failures.joinToString("\n"), 2, failures.size)
    }

    @Test
    fun armTimes() {
        for (element in file.getValue("armAt").jsonArray) {
            val case = element.jsonObject
            val at = blendArmAtMs(case.double("startMs"), case.double("preRollLeadMs"), case.double("proposalLeadMs"), case.double("readyLeadMs"), case.double("speed"))
            assertEquals("at ${case.double("speed")}x", case.double("expect"), at, 1e-9)
        }
    }

    @Test
    fun replanNow() {
        for (element in file.getValue("replanNow").jsonArray) {
            val case = element.jsonObject
            val now = blendReplanNowMs(case.double("outgoingAtHeadMs"), case.long("proposalLeadFrames"), case.long("sampleRate").toInt(), case.double("speed"))
            assertEquals(case.double("expect"), now, 1e-9)
        }
    }

    @Test
    fun easing() {
        for (element in file.getValue("ease").jsonArray) {
            val case = element.jsonObject
            val rate = blendIncomingRate(case.double("rate"), case.double("incomingMs"), case.double("entryMs"), case.double("easeFromMs"))
            assertEquals("at ${case.double("incomingMs")}", case.double("expect"), rate, 1e-9)
        }
    }

    @Test
    fun dropCuts() {
        for (element in file.getValue("dropCut").jsonArray) {
            val case = element.jsonObject
            val at = blendDropCutAtMs(
                case.double("bothHeardStartMs"),
                case.double("bothHeardEndMs"),
                case.double("holdEndMs"),
                case.double("lockMs"),
                case.double("driftGap"),
                case.double("pace"),
                case.double("fromMs"),
            )
            val expect = case.present("expect")?.jsonPrimitive?.double
            if (expect == null) assertTrue("from ${case.double("fromMs")}: expected none, got $at", at.isNaN()) else assertEquals(expect, at, 1e-9)
        }
    }

    @Test
    fun commitWords() {
        for (element in file.getValue("commitWords").jsonArray) {
            val case = element.jsonObject
            val state = named(CommitState.entries, case.text("state"))
            val word = commitWordOf(case.long("gen"), state)
            assertEquals(case.long("word"), word)
            assertEquals(case.long("gen"), commitGenerationOf(word))
            assertEquals(state, commitStateOf(word))
        }
    }

    private fun expand(steps: JsonArray, preludes: JsonObject, into: MutableList<JsonObject>) {
        for (element in steps) {
            val step = element.jsonObject
            if (step.text("op") == "prelude") expand(preludes.getValue(step.text("name")).jsonArray, preludes, into) else into += step
        }
    }

    private fun run(name: String, profile: JsonObject, steps: List<JsonObject>): List<String> {
        val word = CommitWord()
        val timing = BlendTiming(
            sampleRate = profile.long("sampleRate").toInt(),
            placementHorizonFrames = profile.long("placementHorizonFrames"),
            proposalLeadFrames = profile.long("proposalLeadFrames"),
            proposeAtFrames = profile.long("proposeAtFrames"),
            commitAtFrames = profile.long("commitAtFrames"),
            ringLeadMs = profile.double("ringLeadMs"),
            sinkBufferMs = profile.double("sinkBufferMs"),
        )
        val session = BlendSession(word, timing)
        val action = BlendAction()
        val event = BlendEvent()
        val failures = mutableListOf<String>()
        var head = 0L
        steps.forEachIndexed { index, step ->
            val op = step.text("op")
            val where = "$name, step ${index + 1} ($op)"
            when (op) {
                "advance" -> {
                    head = step.long("head")
                    session.advance(step.long("to"), head, step.double("aSongMs"))
                }
                "arm" -> {
                    val gen = step.long("gen")
                    word.open(gen)
                    session.arm(
                        BlendArm(
                            generation = gen,
                            startMs = step.double("startMs"),
                            overlapMs = step.double("overlapMs"),
                            latestEndMs = step.double("latestEndMs"),
                            outgoingEndMs = step.double("outgoingEndMs"),
                            lateMoveBars = step.moveBars("lateMoveBars"),
                            barMs = step.doubleOr("barMs", Double.NaN),
                            holdShare = step.doubleOr("holdShare", 0.0),
                            speed = step.doubleOr("speed", 1.0),
                            bothHeardStartMs = step.doubleOr("bothHeardStartMs", 0.0),
                            bothHeardEndMs = step.doubleOr("bothHeardEndMs", 0.0),
                            lockMs = step.doubleOr("lockMs", 0.0),
                            driftGap = step.doubleOr("driftGap", 0.0),
                            commitBeforeAck = step.boolOr("commitBeforeAck", false),
                        ),
                    )
                }
                "post" -> {
                    val posted = session.post(BlendDeck.valueOf(step.text("deck")), named(BlendInput.entries, step.text("input")), step.long("frame"), step.longOr("value", 0))
                    if (!posted) failures += "$where: the input buffer is full"
                }
                "ack" -> if (word.ack(step.long("gen")) != step.bool("ok")) failures += "$where: the ack should give ${step.bool("ok")}"
                "cancel" -> if (word.cancel(step.long("gen")) != step.bool("ok")) failures += "$where: the cancel should give ${step.bool("ok")}"
                "word" -> {
                    val state = word.stateOf(step.long("gen"))?.let { camel(it.name) }
                    if (state != step.text("state")) failures += "$where: the word is $state, expected ${step.text("state")}"
                }
                "disarm" -> session.disarm(named(BlendDisarm.entries, step.text("reason")), step.long("frame"), step.longOr("settledHead", step.long("frame")))
                "failed" -> session.failed(BlendDeck.valueOf(step.text("deck")), step["pass"]?.let(::pass) ?: BLEND_NO_ANSWER)
                "followUp" -> session.followUp(step.long("gen"), pass(step.getValue("pass")))
                "paceChanged" -> session.paceChanged(step.double("pace"), step.long("frame"))
                "stopped" -> session.outgoingStopped(step.long("gen"))
                "cleared" -> session.cleared(step.long("from"))
                else -> failures += "$where: no such op"
            }
            val actions = mutableListOf<String>()
            while (session.takeAction(action)) actions += describe(camel(action.kind.name), action.frame, action.value, action.ms)
            val wantedActions = step["actions"]?.jsonArray?.map {
                val wanted = it.jsonObject
                describe(wanted.text("kind"), wanted.long("frame"), wanted.longOr("value", 0), wanted.doubleOr("ms", 0.0))
            } ?: emptyList()
            if (actions != wantedActions) failures += "$where: actions $actions, expected $wantedActions"
            val events = mutableListOf<String>()
            while (session.takeEvent(head, event)) {
                events += describe(camel(event.kind.name), event.generation, event.frame, event.ms, event.ms2, camel(event.cause.name))
            }
            val wantedEvents = step["events"]?.jsonArray?.map {
                val wanted = it.jsonObject
                describe(
                    wanted.text("kind"),
                    wanted.long("gen"),
                    wanted.long("frame"),
                    wanted.doubleOr("ms", 0.0),
                    wanted.doubleOr("ms2", 0.0),
                    wanted["cause"]?.jsonPrimitive?.content ?: "none",
                )
            } ?: emptyList()
            if (events != wantedEvents) failures += "$where: events $events, expected $wantedEvents"
        }
        if (session.lost != 0) failures += "$name: ${session.lost} inputs, actions or events did not fit"
        return failures
    }

    private fun describe(kind: String, frame: Long, value: Long, ms: Double): String =
        "$kind@$frame" + (if (value != 0L) " value=$value" else "") + (if (abs(ms) > 5e-7) " ms=${format(ms)}" else "")

    private fun describe(kind: String, gen: Long, frame: Long, ms: Double, ms2: Double, cause: String): String =
        "$kind gen=$gen @$frame" + (if (abs(ms) > 5e-7) " ms=${format(ms)}" else "") +
            (if (abs(ms2) > 5e-7) " ms2=${format(ms2)}" else "") + (if (cause != "none") " cause=$cause" else "")

    private fun format(x: Double): String = String.format(Locale.ROOT, "%.6f", x)

    private fun pass(element: JsonElement): Long = when (element.jsonPrimitive.content) {
        "none" -> BLEND_PASS_NONE
        "held" -> BLEND_PASS_HELD
        else -> element.jsonPrimitive.long
    }

    private fun camel(name: String): String {
        val parts = name.lowercase().split('_')
        return parts.first() + parts.drop(1).joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    }

    private fun <T : Enum<T>> named(values: List<T>, text: String): T =
        values.firstOrNull { camel(it.name) == text } ?: error("no ${values.first().javaClass.simpleName} named $text")

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

    private fun JsonObject.longOr(key: String, default: Long): Long = present(key)?.jsonPrimitive?.long ?: default

    private fun JsonObject.doubleOr(key: String, default: Double): Double = present(key)?.jsonPrimitive?.double ?: default

    private fun JsonObject.boolOr(key: String, default: Boolean): Boolean = present(key)?.jsonPrimitive?.boolean ?: default

    // A bar budget: a number, "unbounded", or 0 when missing.
    private fun JsonObject.moveBars(key: String): Int = when (val value = present(key)?.jsonPrimitive) {
        null -> 0
        else -> if (value.content == "unbounded") LATE_MOVE_UNBOUNDED else value.long.toInt()
    }
}
