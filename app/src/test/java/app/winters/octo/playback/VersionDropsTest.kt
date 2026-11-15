package app.winters.octo.playback

import app.winters.octo.offline.SongCopies
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Every listed id's saved copies go, fenced first, and no other song's; a
// song playing keeps its copies until it stops or a new play of it is about
// to read; a pick's song goes at once.
class VersionDropsTest {
    private class Copies(private val video: List<String> = emptyList()) : SongCopies {
        val fenced = mutableListOf<String>()
        val dropped = mutableListOf<String>()

        override fun fence(song: String) {
            fenced += song
        }

        override fun dropSong(song: String) {
            fence(song)
            dropped += song
        }

        override fun dropSongs(songs: Collection<String>) {
            songs.sorted().forEach(::dropSong)
        }

        override fun videoSongs(sourceId: String): List<String> = video
    }

    private fun TestScope.drops(
        copies: SongCopies,
        timings: MutableList<String> = mutableListOf(),
        finds: List<String> = emptyList(),
        moved: Set<String> = emptySet(),
    ) = VersionDrops(copies, { finds }, { timings += it }, { moved }, StandardTestDispatcher(testScheduler), backgroundScope)

    @Test
    fun everyListedIdGoesWithItsLyricsTimingAndNoOther() = runTest {
        val copies = Copies()
        val timings = mutableListOf<String>()
        drops(copies, timings).drop("server:a", listOf("ext-1", "ext-2", "ext-1"))
        assertEquals(listOf("server:a|ext-1", "server:a|ext-2"), copies.dropped)
        assertEquals(listOf("find:ext-1", "find:ext-2"), timings)
    }

    @Test
    fun aSongPlayingIsFencedAtOnceAndItsCopiesGoOnceItStops() = runTest {
        val copies = Copies()
        val drops = drops(copies)
        drops.nowPlaying(setOf("server:a|ext-1"))
        drops.drop("server:a", listOf("ext-1", "ext-2"))
        assertEquals(listOf("server:a|ext-2"), copies.dropped)
        assertTrue("server:a|ext-1" in copies.fenced)
        drops.nowPlaying(setOf("server:a|ext-3"))
        runCurrent()
        assertEquals(listOf("server:a|ext-2", "server:a|ext-1"), copies.dropped)
    }

    @Test
    fun aNewPlayAboutToReadDropsWhatWaitedForItsSong() = runTest {
        val copies = Copies()
        val drops = drops(copies)
        drops.nowPlaying(setOf("server:a|ext-1"))
        drops.drop("server:a", listOf("ext-1"))
        assertTrue(copies.dropped.isEmpty())
        drops.opening("server:a|ext-1")
        assertEquals(listOf("server:a|ext-1"), copies.dropped)
        drops.opening("server:a|ext-1")
        assertEquals(1, copies.dropped.size)
    }

    @Test
    fun aPicksSongGoesAtOnceEvenWhilePlaying() = runTest {
        val copies = Copies()
        val drops = drops(copies)
        drops.nowPlaying(setOf("server:a|ext-1"))
        drops.drop("server:a", listOf("ext-1"), now = true)
        assertEquals(listOf("server:a|ext-1"), copies.dropped)
    }

    // Songs found online are told by the app's own marks: a saved copy made
    // from a named video, or a find in the catalog. A library song playing
    // (tr-5) is neither, and is left alone.
    @Test
    fun everySongFoundOnlineGoesSaveThosePlayingUntilTheyStopAndEveryProfileIsForgotten() = runTest {
        val copies = Copies(video = listOf("server:a|4fJk2mQx9ZbW7tYc1RnD0a", "server:a|8HqL0sVe3NcX5uPa2KmT7b"))
        val timings = mutableListOf<String>()
        val drops = drops(
            copies,
            timings,
            finds = listOf("8HqL0sVe3NcX5uPa2KmT7b", "1ZyR6wGd4JfB9oQe8LhS3c"),
            moved = setOf("find:4fJk2mQx9ZbW7tYc1RnD0a"),
        )
        val heard = mutableListOf<Set<String>?>()
        backgroundScope.launch { drops.dropped.collect { heard += it } }
        runCurrent()
        drops.nowPlaying(setOf("server:a|8HqL0sVe3NcX5uPa2KmT7b", "server:a|tr-5"))
        drops.dropOutside("server:a")
        runCurrent()
        assertEquals(listOf("server:a|1ZyR6wGd4JfB9oQe8LhS3c", "server:a|4fJk2mQx9ZbW7tYc1RnD0a"), copies.dropped)
        assertTrue("server:a|8HqL0sVe3NcX5uPa2KmT7b" in copies.fenced)
        assertTrue("server:a|tr-5" !in copies.fenced)
        // Only the timing that was moved is reset.
        assertEquals(listOf("find:4fJk2mQx9ZbW7tYc1RnD0a"), timings)
        assertEquals(listOf<Set<String>?>(null), heard)
        drops.nowPlaying(emptySet())
        runCurrent()
        assertEquals(
            setOf("server:a|1ZyR6wGd4JfB9oQe8LhS3c", "server:a|4fJk2mQx9ZbW7tYc1RnD0a", "server:a|8HqL0sVe3NcX5uPa2KmT7b"),
            copies.dropped.toSet(),
        )
    }
}
