package app.winters.octo.desktop.audio

import app.winters.octo.audio.EngineEvent
import app.winters.octo.playback.TransitionProfile
import app.winters.octo.playback.TransitionProfiles
import app.winters.octo.subsonic.ProfileSection
import app.winters.octo.subsonic.ProfileTempo
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.TransitionProfileAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// The desktop hands the engine the server's transition profiles of the
// playing song and the next one, each asked for once, and never for a file
// on this computer.
class EngineProfilesTest {
    private val songs = listOf(Song("s1", "One", duration = 200), Song("s2", "Two", duration = 200), Song("s3", "Three", duration = 200))

    private fun section(startMs: Long) = ProfileSection(
        startMs = startMs,
        hopMs = 100,
        // Three hops at -15 dB.
        levels = "qqqq",
        bodyDb = -14.0,
        gateDb = -59.0,
        soundStartMs = startMs,
        soundEndMs = startMs + 300,
        outroStartMs = startMs + 300,
        boundariesMs = listOf(startMs + 100),
        tempo = ProfileTempo(bpm = 120.0, trusted = true, beatMs = 500.0, firstBeatMs = 10.0, downbeatMs = 510.0, confidence = 2.0, consistency = 0.9, steady = true),
    )

    private fun answer(id: String) = TransitionProfileAnswer(id, ready = id != "s3", version = 1, durationMs = 200_000, bodyDb = -14.0, head = section(0), tail = section(140_000))

    private fun setUp(asked: MutableList<String>): Pair<EnginePlayer, FakeEngine> {
        val engine = FakeEngine()
        val kept = TransitionProfiles(fetch = { id -> asked += id; answer(id) })
        // Unconfined: each answer is handed over before the call returns.
        val profiles = EngineProfiles(CoroutineScope(Dispatchers.Unconfined), { kept }, { serverSongId(it) { null } }, engine::setSongProfile)
        val player = EnginePlayer(engine, { SongAddress("file:///${it.id}.flac") }, Random(3), clock = { 0 }, profiles = profiles)
        return player to engine
    }

    private fun EnginePlayer.item(id: String) = itemId(state.value.queue.first { it.song.id == id }.key)

    @Test
    fun thePlayingSongAndTheNextGetTheirProfiles() {
        val asked = mutableListOf<String>()
        val (p, engine) = setUp(asked)
        p.play(songs, 0, shuffle = false)
        assertEquals(listOf("s1", "s2"), asked)
        val profile = engine.profiles.getValue(p.item("s1"))
        assertEquals(200_000L, profile.durationMs)
        assertEquals(140_000L, profile.tail.startMs)
        assertEquals(listOf(-15f, -15f, -15f), profile.tail.levels)
        assertEquals(listOf(140_100L), profile.tail.boundariesMs)
        assertEquals(510.0, profile.tail.tempo!!.downbeatMs, 0.0)
        assertTrue(engine.profiles.containsKey(p.item("s2")))

        // The next song starts: it had its profile already; the one after is asked for.
        engine.emit(EngineEvent.TrackStarted(p.item("s1"), 0u, null))
        engine.emit(EngineEvent.TrackStarted(p.item("s2"), 1u, null))
        assertEquals(listOf("s1", "s2", "s3"), asked)
        // The server has none for it yet: the engine reads it as before.
        assertNull(engine.profiles[p.item("s3")])
    }

    @Test
    fun aFileOnThisComputerIsNotAskedAbout() {
        val asked = mutableListOf<String>()
        val (p, engine) = setUp(asked)
        p.play(listOf(Song("${LOCAL_PREFIX}C:/Music/a.flac", "A", duration = 200), songs[0]), 0, shuffle = false)
        assertEquals(listOf("s1"), asked)
        assertEquals(setOf(p.item("s1")), engine.profiles.keys)
    }

    @Test
    fun theEnginesProfileIsTheSharedOne() {
        val shared = TransitionProfile.of(answer("s1"))!!
        val engine = shared.toEngine()
        assertEquals(shared.tail.levels.toList(), engine.tail.levels)
        assertEquals(shared.head.features.introEndMs, engine.head.introEndMs)
        assertNull(engine.tempo)
        assertEquals(100u, engine.head.hopMs)
    }
}
