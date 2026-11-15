package app.winters.octo.desktop.audio

import app.winters.octo.audio.EngineEvent
import app.winters.octo.audio.ErrorKind
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.playback.TransitionProfiles
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.PLAY_TOKEN_PARAM
import app.winters.octo.subsonic.ProfileSection
import app.winters.octo.subsonic.ProfileTempo
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.TransitionProfileAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// A version picked for the song playing: every address the engine is given
// names its play, a restart is a new play with nothing kept of the old file,
// and a file that changes under a play opens the song again, once a play.
class StreamRestartTest {
    private val client = SubsonicClient("http://music.test/".toHttpUrl(), Credentials("u", "p"), OkHttpClient())
    private val songs = listOf(Song("ext-1", "Found", isExternal = true, duration = 200), Song("s2", "Next", duration = 200))
    private var now = 0L

    private fun setUp(profiles: (FakeEngine) -> EngineProfiles? = { null }): Pair<EnginePlayer, FakeEngine> {
        val engine = FakeEngine()
        val sources = LocalOrServer(ServerSongs(client = { client }, session = "run"))
        return EnginePlayer(engine, sources, Random(3), clock = { now }, profiles = profiles(engine)) to engine
    }

    private fun FakeEngine.token(at: Int) = queue[at].source.toHttpUrl().queryParameter(PLAY_TOKEN_PARAM)

    private fun section(startMs: Long) = ProfileSection(
        startMs = startMs,
        hopMs = 100,
        levels = "qqqq",
        bodyDb = -14.0,
        gateDb = -59.0,
        soundStartMs = startMs,
        soundEndMs = startMs + 300,
        outroStartMs = startMs + 300,
        boundariesMs = listOf(startMs + 100),
        tempo = ProfileTempo(bpm = 120.0, trusted = true, beatMs = 500.0, firstBeatMs = 10.0, downbeatMs = 510.0, confidence = 2.0, consistency = 0.9, steady = true),
    )

    private fun ready(id: String) = TransitionProfileAnswer(id, ready = true, version = 1, durationMs = 200_000, bodyDb = -14.0, head = section(0), tail = section(140_000))

    // Red when: ServerSongs leaves the token out, or a song's own address
    // gets one.
    @Test
    fun aSongsOwnAddressNamesNoPlayAndAnEntrysNamesItsOwn() {
        val songs = ServerSongs(client = { client }, session = "run")
        assertNull(songs.addressOf(Song("ext-1", "Found"))!!.source.toHttpUrl().queryParameter(PLAY_TOKEN_PARAM))
        val address = songs.addressOf(QueueEntry(7, Song("ext-1", "Found")), 2)!!.source.toHttpUrl()
        assertEquals("run-7.2", address.queryParameter(PLAY_TOKEN_PARAM))
        assertEquals("ext-1", address.queryParameter("id"))
    }

    // Red when: restartCurrent seeks instead of loading anew, or leaves the
    // restart count as it was.
    @Test
    fun aRestartIsANewPlayOfTheSameEntry() {
        val (p, engine) = setUp()
        p.play(songs, 0, shuffle = false)
        val first = p.state.value.current!!.key
        val next = p.state.value.upcoming.first().key
        assertEquals(listOf("run-$first.0", "run-$next.0"), listOf(engine.token(0), engine.token(1)))
        p.seekTo(60_000)
        p.pause()
        p.restartCurrent()
        assertEquals("load 2 at 0 from 0 playing", engine.calls.last())
        assertEquals(first, p.state.value.current?.key)
        assertTrue(p.state.value.playing)
        assertEquals(listOf("run-$first.1", "run-$next.0"), listOf(engine.token(0), engine.token(1)))
        p.restartCurrent()
        assertEquals("run-$first.2", engine.token(0))
    }

    // Red when: the FILE_CHANGED branch is removed (the song fails at once),
    // the reopen count is not kept per play, or restartCurrent keeps it.
    @Test
    fun anotherFileMidSongOpensTheSongAgainOnceAPlay() {
        val (p, engine) = setUp()
        p.play(songs, 0, shuffle = false)
        // Long enough after the load that the engine's own place counts.
        now = 5_000
        engine.heard = Heard(engine.queue[0].id, 30_000.0, 200_000)
        engine.calls.clear()
        engine.emit(EngineEvent.Error(ErrorKind.FILE_CHANGED, "another file", engine.queue[0].id))
        assertEquals(listOf("load 2 at 0 from 30000 playing"), engine.calls)
        assertNull(p.state.value.problem)
        // Again in the same play: the song cannot play, as any other.
        engine.calls.clear()
        engine.emit(EngineEvent.Error(ErrorKind.FILE_CHANGED, "another file", engine.queue[0].id))
        assertTrue(engine.calls.isEmpty())
        assertEquals("ext-1", p.state.value.problem?.song?.id)
        // A restart is a new play: it may be opened again once more.
        p.restartCurrent()
        now = 10_000
        engine.calls.clear()
        engine.emit(EngineEvent.Error(ErrorKind.FILE_CHANGED, "another file", engine.queue[0].id))
        assertEquals(listOf("load 2 at 0 from 30000 playing"), engine.calls)
    }

    // Red when: restartCurrent or filesChanged leaves the old profile in the
    // engine or in the kept ones, or a song is asked about before the
    // change feed is read.
    @Test
    fun aRestartAndChangedFilesTakeTheProfilesBack() {
        val asked = mutableListOf<String>()
        val before = mutableListOf<String>()
        val kept = TransitionProfiles(fetch = { id -> asked += id; ready(id) })
        val (p, engine) = setUp { engine ->
            EngineProfiles(
                CoroutineScope(Dispatchers.Unconfined),
                { kept },
                { serverSongId(it) { null } },
                engine::setSongProfile,
                unsend = { engine.setSongProfile(it, null) },
                beforeAsk = { before += it.id },
            )
        }
        p.play(songs, 0, shuffle = false)
        val item = engine.queue[0].id
        assertEquals(listOf("ext-1", "s2"), asked)
        assertEquals(listOf("ext-1", "s2"), before)
        assertTrue(engine.profiles.containsKey(item))
        // A restart asks for the song's profile afresh: the kept ones went.
        p.restartCurrent()
        assertEquals(listOf("ext-1", "s2", "ext-1"), asked)
        assertTrue(engine.profiles.containsKey(item))
        // A song given another file elsewhere: its profile goes and is asked for again.
        p.filesChanged(setOf("s2"))
        assertEquals(listOf("ext-1", "s2", "ext-1", "s2"), asked)
        // Nothing listed: nothing goes.
        p.filesChanged(emptySet())
        assertEquals(4, asked.size)
    }
}
