package app.winters.octo.desktop.queue

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.playback.QueueSource
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AutoplayTest {
    // Two songs of 100 seconds each.
    private val songs = (1..2).map { Song("s$it", "Song $it", duration = 100) }
    private val more = listOf(Song("m1", "More 1", duration = 100), Song("m2", "More 2", duration = 100))

    private class Setup(val player: SilentPlayer, val on: MutableStateFlow<Boolean>, val asked: MutableList<String>)

    private fun TestScope.setUp(on: Boolean = true, found: List<Song> = more): Setup {
        val player = SilentPlayer(clock = { testScheduler.currentTime }, random = Random(1))
        val enabled = MutableStateFlow(on)
        val asked = mutableListOf<String>()
        Autoplay(player, enabled, backgroundScope, pick = { seed, exclude ->
            asked += seed.id
            found.filter { it.id !in exclude }
        }).start()
        runCurrent()
        return Setup(player, enabled, asked)
    }

    private fun TestScope.elapse(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun Setup.coming() = player.state.value.upcoming.map { it.song.id to it.source }

    @Test
    fun nearTheEndOfTheLastSongSongsLikeItFollow() = runTest {
        val s = setUp()
        s.player.play(songs, 1)
        runCurrent()
        // Not yet: most of the song is still to play.
        elapse(60_000)
        assertEquals(emptyList<String>(), s.asked)
        elapse(25_000)
        assertEquals(listOf("s2"), s.asked)
        assertEquals(listOf("m1" to QueueSource.Autoplay, "m2" to QueueSource.Autoplay), s.coming())
    }

    @Test
    fun songsStillToComeMeanNothingIsAdded() = runTest {
        val s = setUp()
        s.player.play(songs, 0)
        elapse(95_000)
        assertEquals(emptyList<String>(), s.asked)
    }

    @Test
    fun switchedOffRepeatingOrPausedItStandsDown() = runTest {
        val s = setUp(on = false)
        s.player.play(songs, 1)
        elapse(90_000)
        s.player.setRepeat(RepeatMode.All)
        s.on.value = true
        elapse(5_000)
        s.player.setRepeat(RepeatMode.Off)
        s.player.pause()
        elapse(5_000)
        assertEquals(emptyList<String>(), s.asked)
        // Playing again, it adds.
        s.player.resume()
        elapse(2_000)
        assertEquals(listOf("s2"), s.asked)
    }

    @Test
    fun itTriesOncePerSong() = runTest {
        val s = setUp(found = emptyList())
        s.player.play(songs, 1)
        elapse(90_000)
        s.player.pause()
        runCurrent()
        s.player.resume()
        elapse(5_000)
        assertEquals(listOf("s2"), s.asked)
    }

    @Test
    fun theListenersOwnSongsKeepItAway() = runTest {
        val s = setUp()
        s.player.play(songs, 1)
        elapse(10_000)
        s.player.addToQueue(listOf(Song("x", "X", duration = 100)))
        elapse(85_000)
        assertEquals(emptyList<String>(), s.asked)
    }

    @Test
    fun aSongOfUnknownLengthIsNeverTakenForEnding() = runTest {
        val s = setUp()
        s.player.play(listOf(Song("stream", "A stream")))
        elapse(600_000)
        assertEquals(emptyList<String>(), s.asked)
    }

    // The library's picks when the server has nothing like the song.

    private val library = LibraryIndex(
        listOf(
            Song("a1", "A1", artistId = "r1", genre = "Rock"),
            Song("a2", "A2", artistId = "r1", genre = "Rock"),
            Song("g1", "G1", artistId = "r2", genre = "Rock"),
            Song("o1", "O1", artistId = "r3", genre = "Jazz"),
        ),
        emptyList(),
        emptyList(),
    )

    @Test
    fun withoutTheServerTheRadioPicksFromTheGenreAndNeverTheSameArtistBackToBack() = runBlocking {
        val seed = Song("seed", "Seed", artistId = "r1", genre = "Rock")
        val picks = autoplaySongs(seed, setOf("a2"), client = null, index = library).map { it.id }
        assertEquals(listOf("g1", "a1"), picks)
    }

    @Test
    fun theListenersOwnSongsPullAutoplayBackToTheirTaste() = runBlocking {
        val rock = (1..3).map { Song("r$it", "R$it", artist = "Rock $it", genre = "Rock", duration = 200) }
        val jazz = (1..3).map { Song("j$it", "J$it", artist = "Jazz $it", genre = "Jazz", duration = 200) }
        val index = LibraryIndex(rock + jazz, emptyList(), emptyList())
        val drifted = jazz.first()
        val without = autoplaySongs(drifted, emptySet(), client = null, index = index, random = Random(1)).map { it.id }
        assertTrue(without.none { it.startsWith("r") })
        val with = autoplaySongs(drifted, emptySet(), client = null, index = index, anchors = rock.take(2), random = Random(1)).map { it.id }
        assertTrue(with.any { it.startsWith("r") })
    }

    @Test
    fun aLibrarySongTheServerSuggested_KeepsTheServersSource() = runBlocking {
        FakeServer().use { server ->
            server.answer("getSimilarSongs2", """"similarSongs2":{"song":[{"id":"g1","title":"G1","genre":"Rock","octoSuggestedBy":"ListenBrainz"}]}""")
            val seed = Song("seed", "Seed", artistId = "r1", genre = "Rock")
            val picks = autoplaySongs(seed, setOf("a1", "a2"), client = server.connection().client, index = library)
            assertEquals("ListenBrainz", picks.single { it.id == "g1" }.octoSuggestedBy)
        }
    }

    // "Library songs only": the server's songs found online are left out.
    @Test
    fun libraryOnlyLeavesOutTheServersFinds() = runBlocking {
        FakeServer().use { server ->
            server.answer(
                "getSimilarSongs2",
                """"similarSongs2":{"song":[{"id":"ext1","title":"Out","genre":"Rock","isExternal":true},{"id":"g1","title":"G1","genre":"Rock"}]}""",
            )
            val seed = Song("seed", "Seed", artistId = "r1", genre = "Rock")
            val client = server.connection().client
            val all = autoplaySongs(seed, setOf("a1", "a2"), client = client, index = library).map { it.id }
            assertTrue("ext1" in all)
            val mine = autoplaySongs(seed, setOf("a1", "a2"), client = client, index = library, libraryOnly = true).map { it.id }
            assertTrue("ext1" !in mine)
            assertTrue("g1" in mine)
        }
    }

    @Test
    fun withNothingAlikeAnySongFromTheLibraryWillDo() = runBlocking {
        val seed = Song("seed", "Seed", artistId = "r9", genre = "Folk")
        val picks = autoplaySongs(seed, setOf("a1", "a2", "g1"), client = null, index = library).map { it.id }
        assertEquals(listOf("o1"), picks)
        assertTrue(autoplaySongs(seed, emptySet(), client = null, index = null).isEmpty())
    }
}
