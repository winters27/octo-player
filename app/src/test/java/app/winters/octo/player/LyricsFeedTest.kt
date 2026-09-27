package app.winters.octo.player

import app.winters.octo.lyrics.LyricLine
import app.winters.octo.lyrics.Lyrics
import app.winters.octo.lyrics.LyricsAnswer
import app.winters.octo.lyrics.LyricsSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// What the lyrics view shows as songs change and the app goes to the
// background and back.
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsFeedTest {
    private fun words(text: String) =
        Lyrics(synced = true, lines = listOf(LyricLine(startMs = 0, text = text)), source = LyricsSource.Server)

    // Stands in for the lyrics sources and the phone's network: each
    // lookup answers what `answers` holds for the song, or waits for it.
    private class Sources {
        val answers = mutableMapOf<String, LyricsAnswer>()
        val waiting = mutableMapOf<String, CompletableDeferred<LyricsAnswer>>()
        val asked = mutableListOf<Pair<String, Boolean>>()
        var network = CompletableDeferred<Unit>()
        var networkWaits = 0

        suspend fun lookUp(id: String, resumed: Boolean): LyricsAnswer {
            asked += id to resumed
            waiting[id]?.let { return it.await() }
            return answers.getValue(id)
        }

        suspend fun networkBack() {
            networkWaits++
            network.await()
        }
    }

    private class Feed(scope: TestScope, val sources: Sources, first: String?, watching: Boolean) {
        val wanted = MutableStateFlow(first?.let { LyricsWanted(it) })
        val watched = MutableStateFlow(watching)
        val states = mutableListOf<LyricsState>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                lyricsStates(wanted, watched, sources::lookUp, sources::networkBack).toList(states)
            }
        }

        val shown get() = states.last()
    }

    @Test
    fun aSongChangeWhileAwayShowsLoadingUntilTheViewIsBack() = runTest {
        val sources = Sources()
        sources.answers["a"] = LyricsAnswer.Found(words("a"))
        sources.answers["b"] = LyricsAnswer.Found(words("b"))
        val feed = Feed(this, sources, "a", watching = true)
        runCurrent()
        assertEquals(LyricsState.Found("a", words("a")), feed.shown)

        // The phone is locked, and the next song starts.
        feed.watched.value = false
        runCurrent()
        feed.wanted.value = LyricsWanted("b")
        runCurrent()
        // Neither the last song's lyrics nor "none": loading, and nothing asked.
        assertEquals(LyricsState.Loading, feed.shown)
        assertFalse(feed.states.contains(LyricsState.None))
        assertEquals(listOf("a" to true), sources.asked)

        // Back on screen: looked up at once.
        feed.watched.value = true
        runCurrent()
        assertEquals("b" to true, sources.asked.last())
        assertEquals(LyricsState.Found("b", words("b")), feed.shown)
    }

    @Test
    fun aSongChangeOnScreenShowsLoadingThenTheNewSong() = runTest {
        val sources = Sources()
        sources.answers["a"] = LyricsAnswer.Found(words("a"))
        sources.waiting["b"] = CompletableDeferred()
        val feed = Feed(this, sources, "a", watching = true)
        runCurrent()
        feed.wanted.value = LyricsWanted("b")
        runCurrent()
        assertEquals(LyricsState.Loading, feed.shown)
        // Only the first lookup each time the view comes back is "resumed".
        assertEquals("b" to false, sources.asked.last())
        sources.waiting.getValue("b").complete(LyricsAnswer.None)
        runCurrent()
        assertEquals(LyricsState.None, feed.shown)
    }

    // Lets the quiet retries after a failure run out.
    private fun TestScope.passQuietRetries() {
        advanceTimeBy(QUIET_RETRIES * QUIET_RETRY_MS + 1)
        runCurrent()
    }

    @Test
    fun aFailureIsAskedAgainQuietlyBeforeItShows() = runTest {
        val sources = Sources()
        sources.answers["a"] = LyricsAnswer.Failed
        val feed = Feed(this, sources, "a", watching = true)
        runCurrent()
        // Still loading while the server may be finishing its lookup.
        assertEquals(LyricsState.Loading, feed.shown)
        sources.answers["a"] = LyricsAnswer.Found(words("a"))
        advanceTimeBy(QUIET_RETRY_MS + 1)
        runCurrent()
        assertEquals(LyricsState.Found("a", words("a")), feed.shown)
        assertFalse(feed.states.any { it is LyricsState.Failed })
    }

    @Test
    fun aFailureShowsAsFailedAndResumingLooksUpAgain() = runTest {
        val sources = Sources()
        sources.answers["a"] = LyricsAnswer.Failed
        val feed = Feed(this, sources, "a", watching = true)
        runCurrent()
        passQuietRetries()
        assertEquals(LyricsState.Failed("a"), feed.shown)
        assertFalse(feed.states.contains(LyricsState.None))

        sources.answers["a"] = LyricsAnswer.Found(words("a"))
        feed.watched.value = false
        runCurrent()
        // Off screen it stays as it was.
        assertEquals(LyricsState.Failed("a"), feed.shown)
        feed.watched.value = true
        runCurrent()
        assertEquals("a" to true, sources.asked.last())
        assertEquals(LyricsState.Found("a", words("a")), feed.shown)
    }

    @Test
    fun aFailureIsTriedOnceMoreWhenTheNetworkComesBack() = runTest {
        val sources = Sources()
        sources.answers["a"] = LyricsAnswer.Failed
        val feed = Feed(this, sources, "a", watching = true)
        runCurrent()
        passQuietRetries()
        assertEquals(LyricsState.Failed("a"), feed.shown)
        assertEquals(1 + QUIET_RETRIES, sources.asked.size)

        sources.network.complete(Unit)
        runCurrent()
        // Tried once more, failed again, and left there.
        assertEquals(2 + QUIET_RETRIES, sources.asked.size)
        assertEquals(1, sources.networkWaits)
        assertEquals(LyricsState.Failed("a"), feed.shown)
    }

    @Test
    fun theRetryAfterTheNetworkComesBackCanFindThem() = runTest {
        val sources = Sources()
        sources.answers["a"] = LyricsAnswer.Failed
        val feed = Feed(this, sources, "a", watching = true)
        runCurrent()
        passQuietRetries()
        sources.answers["a"] = LyricsAnswer.Found(words("a"))
        sources.network.complete(Unit)
        runCurrent()
        assertEquals(LyricsState.Found("a", words("a")), feed.shown)
    }

    @Test
    fun resumingChecksANoneAgainWithoutBlankingIt() = runTest {
        val sources = Sources()
        sources.answers["a"] = LyricsAnswer.None
        val feed = Feed(this, sources, "a", watching = true)
        runCurrent()
        assertEquals(LyricsState.None, feed.shown)

        feed.watched.value = false
        runCurrent()
        sources.waiting["a"] = CompletableDeferred()
        feed.watched.value = true
        runCurrent()
        assertEquals("a" to true, sources.asked.last())
        assertEquals(LyricsState.None, feed.shown)
        sources.waiting.getValue("a").complete(LyricsAnswer.Found(words("a")))
        runCurrent()
        assertEquals(LyricsState.Found("a", words("a")), feed.shown)
    }

    @Test
    fun aLookupThatThrowsIsAFailureNotNone() = runTest {
        val sources = Sources()
        val feed = Feed(this, sources, "missing", watching = true)
        runCurrent()
        passQuietRetries()
        assertEquals(LyricsState.Failed("missing"), feed.shown)
    }

    @Test
    fun nothingIsLookedUpWhileTheViewIsAway() = runTest {
        val sources = Sources()
        sources.answers["a"] = LyricsAnswer.Found(words("a"))
        val feed = Feed(this, sources, "a", watching = false)
        runCurrent()
        assertEquals(LyricsState.Loading, feed.shown)
        assertEquals(emptyList<Pair<String, Boolean>>(), sources.asked)
    }

    @Test
    fun closedAndHiddenLyricsAreNotLookedUp() = runTest {
        val sources = Sources()
        val feed = Feed(this, sources, null, watching = true)
        runCurrent()
        assertEquals(LyricsState.Hidden, feed.shown)
        feed.wanted.value = LyricsWanted("a", hidden = true)
        runCurrent()
        assertEquals(LyricsState.HiddenForSong, feed.shown)
        assertEquals(emptyList<Pair<String, Boolean>>(), sources.asked)
    }
}
