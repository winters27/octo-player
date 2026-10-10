package app.winters.octo.playback

import app.winters.octo.subsonic.TransitionProfileAnswer
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// A song's transition profile as Octo sends it (transition-profile.json, the
// server's own output for the song below), what the player reads from it,
// and the plan it makes: the one a scout of the same song would make.
class AutomixProfileTest {
    // 75 s at 120 BPM: a quiet 4 s intro, a kick on every bar and a click on
    // every beat, then a 15 s fade from 60 s. The server's fixture song.
    private val song = SyntheticSong(
        22_050,
        1,
        75_000,
        listOf(
            SongLayer("tone", 0.0, 4_000.0, hz = 300.0, db = -40.0),
            SongLayer("tone", 4_000.0, 60_000.0, hz = 300.0, db = -22.0),
            SongLayer("tone", 60_000.0, 75_000.0, hz = 300.0, db = -22.0, endDb = -70.0),
            SongLayer("pulse", 4_000.0, 60_000.0, hz = 60.0, db = -6.0, bpm = 120.0, firstMs = 4_000.0, every = 4, decayMs = 80.0),
            SongLayer("pulse", 4_000.0, 60_000.0, hz = 2_000.0, db = -16.0, bpm = 120.0, firstMs = 4_000.0, every = 1, decayMs = 10.0),
        ),
    )

    private fun fixture(): TransitionProfileAnswer {
        val text = javaClass.classLoader!!.getResource("transition-profile.json")!!.readText()
        return TransitionProfileAnswer.parse(text).copy(id = "nd-1", ready = true)
    }

    @Test
    fun theServersProfileReadsWhole() {
        val profile = assertNotNull(TransitionProfile.of(fixture()))
        assertEquals(75_000L, profile.durationMs)
        assertEquals(0L, profile.head.startMs)
        assertEquals(100, profile.head.hopMs)
        assertEquals(300, profile.head.levels.size)
        assertEquals(15_000L, profile.tail.startMs)
        assertEquals(600, profile.tail.levels.size)
        assertTrue(abs(profile.tempoPrior!! - 120.0) < 0.5)
        val intro = profile.head.features.introEndMs!!
        assertTrue(intro in 3_400L..4_300L, "intro end $intro")
        // Its levels are where the song is: full in the body, faded at the end.
        assertTrue(profile.tail.levels[200] > -30f, "${profile.tail.levels[200]}")
        assertTrue(profile.tail.levels[590] < -50f, "${profile.tail.levels[590]}")
    }

    @Test
    fun theFeaturesAreTheOnesTheAppFindsInTheSameSong() {
        val profile = assertNotNull(TransitionProfile.of(fixture()))
        val whole = song.envelope(0.0, 75_000.0)
        val body = assertNotNull(bodyLevelOf(whole))
        val scouted = song.tail().withBodyLevel(body).features
        val served = profile.tail.features
        assertEquals(body, profile.bodyDb!!, 0.01)
        for ((name, pair) in listOf(
            "sound start" to (scouted.soundStartMs to served.soundStartMs),
            "sound end" to (scouted.soundEndMs to served.soundEndMs),
            "outro" to (scouted.outroStartMs to served.outroStartMs),
        )) {
            val (mine, theirs) = pair
            assertTrue(abs(mine!! - theirs!!) <= 100, "$name: scouted $mine, served $theirs")
        }
        val head = song.head().features
        assertTrue(abs(head.introEndMs!! - profile.head.features.introEndMs!!) <= 20)
        assertTrue(abs(head.tempo!!.bpm - profile.head.features.tempo!!.bpm) < 0.1)
    }

    @Test
    fun aProfilePlansTheBlendAScoutWould() {
        val profile = assertNotNull(TransitionProfile.of(fixture()))
        val whole = song.envelope(0.0, 75_000.0)
        val current = FadeSong(albumId = null, albumOrder = null, durationMs = 75_000)
        val next = FadeSong(albumId = null, albumOrder = null, durationMs = 75_000)
        val settings = AutomixSettings(maxOverlapMs = 8_000)
        val heard = TransitionContext(bodyLevelDb = bodyLevelOf(whole), tempoPrior = analyzeHead(whole).features.tempo?.takeIf { it.confident }?.bpm)
        val scouted = planTransition(current, next, song.tail(), song.head(), settings, heard)
        val served = planTransition(
            current,
            next,
            profile.tailAnalysis(),
            profile.headAnalysis(),
            settings,
            TransitionContext(tempoPrior = profile.tempoPrior),
        )
        assertEquals(scouted.kind, served.kind, "$served / $scouted")
        assertTrue(abs(scouted.startMs - served.startMs) <= 300, "$served / $scouted")
        assertTrue(abs(scouted.overlapMs - served.overlapMs) <= 300, "$served / $scouted")
        assertTrue(abs(scouted.entryMs - served.entryMs) <= 20, "$served / $scouted")
        assertEquals(scouted.barLocked, served.barLocked)
    }

    @Test
    fun aProfileThePlayerCannotReadIsNone() {
        val ready = fixture()
        assertNull(TransitionProfile.of(ready.copy(ready = false)))
        assertNull(TransitionProfile.of(ready.copy(version = PROFILE_VERSION + 1)))
        assertNull(TransitionProfile.of(ready.copy(tail = null)))
        assertNull(TransitionProfile.of(ready.copy(head = ready.head!!.copy(levels = "not base64!"))))
        assertNull(TransitionProfile.of(ready.copy(head = ready.head!!.copy(hopMs = 0))))
    }

    @Test
    fun levelsComeInHalfDecibelSteps() {
        assertEquals(SILENT_DB, levelOfCode(0))
        assertEquals(-14.5f, levelOfCode(171))
        assertEquals(27.5f, levelOfCode(255))
    }

    @Test
    fun aProfileIsAskedForOnceAndANotReadyAnswerRestsAMinute() = runTest {
        var clock = 0L
        val asked = ArrayList<String>()
        val answers = mapOf("ready" to fixture(), "later" to TransitionProfileAnswer("later", ready = false, version = 1))
        val profiles = TransitionProfiles({ id -> asked += id; answers[id] ?: error("no server") }, now = { clock })

        val first = assertNotNull(profiles.profile("ready"))
        assertSame(first, profiles.profile("ready"))
        assertSame(first, profiles.cached("ready"))
        assertNull(profiles.profile("later"))
        assertNull(profiles.profile("later"))
        assertNull(profiles.profile("broken"))
        assertEquals(listOf("ready", "later", "broken"), asked)

        clock += PROFILE_RETRY_MS
        assertNull(profiles.profile("later"))
        assertEquals(listOf("ready", "later", "broken", "later"), asked)

        profiles.clear()
        assertNull(profiles.cached("ready"))
    }
}
