package app.winters.octo.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LyricsSearchTest {
    private fun synced(source: LyricsSource) =
        Lyrics(synced = true, lines = listOf(LyricLine(startMs = 0, text = source.name)), source = source)

    private fun plain(source: LyricsSource) =
        Lyrics(synced = false, lines = listOf(LyricLine(text = source.name)), source = source)

    // Steps that answer as given and count how often they are asked.
    private class Steps(vararg answers: Pair<LyricsSource, () -> Lyrics?>) {
        val asked = mutableListOf<LyricsSource>()
        val list = answers.map { (source, answer) ->
            LyricsStep(source) {
                asked += source
                answer()
            }
        }
    }

    @Test
    fun theServerWinsWhenItHasSyncedLyrics() = runTest {
        val steps = Steps(
            LyricsSource.Server to { synced(LyricsSource.Server) },
            LyricsSource.SongFile to { synced(LyricsSource.SongFile) },
            LyricsSource.Online to { synced(LyricsSource.Online) },
        )
        val search = searchInOrder(steps.list)
        assertEquals(LyricsSource.Server, search.lyrics?.source)
        // Nothing after it is asked, so nothing goes online.
        assertEquals(listOf(LyricsSource.Server), steps.asked)
        assertFalse(search.askedOnline)
    }

    @Test
    fun sourcesAreAskedInOrderUntilOneHasLyrics() = runTest {
        val steps = Steps(
            LyricsSource.Server to { null },
            LyricsSource.SongFile to { null },
            LyricsSource.LyricsFile to { synced(LyricsSource.LyricsFile) },
            LyricsSource.Online to { synced(LyricsSource.Online) },
        )
        val search = searchInOrder(steps.list)
        assertEquals(LyricsSource.LyricsFile, search.lyrics?.source)
        assertEquals(listOf(LyricsSource.Server, LyricsSource.SongFile, LyricsSource.LyricsFile), steps.asked)
    }

    @Test
    fun syncedLyricsFurtherDownBeatPlainOnesFirst() = runTest {
        val steps = Steps(
            LyricsSource.Server to { plain(LyricsSource.Server) },
            LyricsSource.SongFile to { synced(LyricsSource.SongFile) },
        )
        assertEquals(LyricsSource.SongFile, searchInOrder(steps.list).lyrics?.source)
    }

    @Test
    fun theFirstPlainLyricsWinWhenNoneAreSynced() = runTest {
        val steps = Steps(
            LyricsSource.Server to { null },
            LyricsSource.SongFile to { plain(LyricsSource.SongFile) },
            LyricsSource.LyricsFile to { plain(LyricsSource.LyricsFile) },
            LyricsSource.Online to { null },
        )
        val search = searchInOrder(steps.list)
        assertEquals(LyricsSource.SongFile, search.lyrics?.source)
        assertTrue(search.complete)
        assertTrue(search.askedOnline)
    }

    @Test
    fun emptyLyricsCountAsNone() = runTest {
        val steps = Steps(
            LyricsSource.Server to { Lyrics(synced = true, lines = listOf(LyricLine(text = "")), source = LyricsSource.Server) },
            LyricsSource.SongFile to { plain(LyricsSource.SongFile) },
        )
        assertEquals(LyricsSource.SongFile, searchInOrder(steps.list).lyrics?.source)
    }

    @Test
    fun aFailingSourceIsSkippedAndTheSearchIsNotComplete() = runTest {
        val steps = Steps(
            LyricsSource.Server to { throw IOException("offline") },
            LyricsSource.SongFile to { null },
        )
        val search = searchInOrder(steps.list)
        assertNull(search.lyrics)
        assertFalse(search.complete)
    }

    @Test
    fun aSourceCutOffByItsOwnTimeLimitMakesTheSearchIncomplete() = runTest {
        val steps = Steps(
            LyricsSource.Server to { null },
            LyricsSource.SongFile to { null },
        )
        val slow = LyricsStep(LyricsSource.Online) { withTimeout(4_000) { awaitCancellation() } }
        val search = searchInOrder(steps.list + slow)
        assertNull(search.lyrics)
        assertFalse(search.complete)
    }

    @Test
    fun aStoppedSearchStops() = runTest {
        val gate = CompletableDeferred<Lyrics?>()
        val search = async { searchInOrder(listOf(LyricsStep(LyricsSource.Server) { gate.await() })) }
        runCurrent()
        search.cancel()
        assertTrue(runCatching { search.await() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun aNoneFromAServerThatLooksLyricsUpItselfIsUnsure() = runTest {
        val unsure = searchInOrder(lyricsSteps({ null }, serverDecides = true, songFile = null, lyricsFile = null, online = null))
        assertNull(unsure.lyrics)
        assertTrue(unsure.complete)
        assertTrue(unsure.unsure)

        val sure = searchInOrder(lyricsSteps({ null }, serverDecides = false, songFile = null, lyricsFile = null, online = null))
        assertFalse(sure.unsure)
    }
}
