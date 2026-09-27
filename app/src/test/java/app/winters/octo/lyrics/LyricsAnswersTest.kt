package app.winters.octo.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

// What a lyrics lookup saves: "none" only when every source answered, and
// nothing at all when the lookup could not finish.
class LyricsAnswersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000L
    private val cache by lazy { LyricsCache(folder.newFolder("lyrics")) }
    private val answers by lazy { LyricsAnswers(cache) { now } }
    private var searches = 0

    private val words = Lyrics(synced = true, lines = listOf(LyricLine(startMs = 0, text = "Hello")), source = LyricsSource.Server)

    private suspend fun ask(
        resumed: Boolean = false,
        away: Boolean = false,
        search: suspend () -> LyricsSearch,
    ): LyricsAnswer = answers.answer("song", null, onlineAllowed = false, resumed = resumed, away = { away }) {
        searches++
        search()
    }

    private val none = LyricsSearch(null, complete = true, askedOnline = false)

    @Test
    fun anUnreachableSourceSavesNoNone() = runTest {
        assertEquals(LyricsAnswer.Failed, ask { throw IOException("Server unreachable") })
        assertNull(cache.read("song"))
        // Nothing was kept in memory either: the next look asks again.
        ask { none }
        assertEquals(2, searches)
    }

    @Test
    fun aSearchWithASourceThatFailedSavesNoNone() = runTest {
        assertEquals(LyricsAnswer.Failed, ask { LyricsSearch(null, complete = false, askedOnline = true) })
        assertNull(cache.read("song"))
    }

    @Test
    fun aTimedOutLookupSavesNoNone() = runTest {
        assertEquals(LyricsAnswer.Failed, ask { withTimeout(4_000) { awaitCancellation() } })
        assertNull(cache.read("song"))
    }

    @Test
    fun aCancelledLookupSavesNothing() = runTest {
        val gate = CompletableDeferred<LyricsSearch>()
        val lookup = launch { ask { gate.await() } }
        runCurrent()
        lookup.cancel()
        gate.complete(none)
        lookup.join()
        assertTrue(lookup.isCancelled)
        assertNull(cache.read("song"))
    }

    @Test
    fun aFailureLeavesTheOlderAnswerAsItWas() = runTest {
        ask { none }
        val first = cache.read("song")!!
        now += NONE_FOUND_FOR_MS + 1
        assertEquals(LyricsAnswer.Failed, ask { throw IOException("offline") })
        assertEquals(first, cache.read("song"))
    }

    @Test
    fun aRealNoneIsStillSavedForADay() = runTest {
        assertEquals(LyricsAnswer.None, ask { none })
        val saved = cache.read("song")!!
        assertNull(saved.lyrics)
        assertFalse(saved.unsure)
        now += NONE_FOUND_FOR_MS - 1
        assertEquals(LyricsAnswer.None, ask { error("not asked") })
        assertEquals(1, searches)
        now += 2
        ask { none }
        assertEquals(2, searches)
    }

    @Test
    fun foundLyricsAreSaved() = runTest {
        assertEquals(LyricsAnswer.Found(words), ask { LyricsSearch(words, complete = true, askedOnline = false) })
        assertEquals(words, cache.read("song")?.lyrics)
    }

    @Test
    fun aServerNoneThatMayHaveRunOutOfTimeIsBelievedBriefly() = runTest {
        assertEquals(LyricsAnswer.None, ask { none.copy(unsure = true) })
        assertTrue(cache.read("song")!!.unsure)
        now += SHORT_NONE_FOR_MS - 1
        ask { error("not asked") }
        assertEquals(1, searches)
        now += 2
        ask { LyricsSearch(words, complete = true, askedOnline = false) }
        assertEquals(2, searches)
    }

    @Test
    fun aNoneFoundWhileAwayIsAskedAgainOnReturn() = runTest {
        ask(away = true) { none }
        assertTrue(cache.read("song")!!.away)
        // Still off screen: it stands.
        ask { error("not asked") }
        assertEquals(1, searches)
        // Back on screen: asked again at once.
        assertEquals(LyricsAnswer.Found(words), ask(resumed = true) { LyricsSearch(words, complete = true, askedOnline = false) })
        assertEquals(2, searches)
    }

    @Test
    fun aNoneFoundOnScreenIsNotAskedAgainOnReturn() = runTest {
        ask { none }
        ask(resumed = true) { error("not asked") }
        assertEquals(1, searches)
    }

    @Test
    fun lyricsFoundWhileAwayStandOnReturn() = runTest {
        ask(away = true) { LyricsSearch(words, complete = true, askedOnline = false) }
        assertEquals(LyricsAnswer.Found(words), ask(resumed = true) { error("not asked") })
        assertEquals(1, searches)
    }
}
