package app.winters.octo.playback

import app.winters.octo.discovery.Discovered
import app.winters.octo.discovery.RankedTrack
import app.winters.octo.discovery.RankedTracks
import app.winters.octo.ui.search.NamedArtist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarSearchTest {
    private inner class FakeSearch : CarSearchSource {
        var account: String? = "a1"
        var signedIn = true
        var libraryOnly = false
        val libraryReads = mutableListOf<String>()
        var outsideAsks = 0
        var outsideHold: CompletableDeferred<Unit>? = null
        var outsideFails = false
        var libraryFails = false
        var tookIn: Boolean? = false
        var library = LibraryFound(songs = listOf(track("device:1", "Love Song")))
        var found = Discovered(listOf(track("find:9", "Love Song Outside")), listOf(onlineAlbum("ext-a")), listOf(onlineArtist("ext-r")))
        var top: RankedTracks? = null

        override fun account() = account
        override fun signedIn() = signedIn
        override suspend fun libraryOnly() = libraryOnly
        override suspend fun library(key: String): LibraryFound {
            libraryReads += key
            if (libraryFails) error("database")
            return library
        }
        override suspend fun outside(query: String): Discovered? {
            outsideAsks++
            outsideHold?.await()
            if (outsideFails) error("server down")
            return found
        }
        override suspend fun takeIn(found: Discovered): Boolean? = tookIn
        override fun adoptions(): Map<String, String> = emptyMap()
        override suspend fun top(named: NamedArtist): RankedTracks? = top
    }

    private fun TestScope.search(source: FakeSearch) = CarSearch(source, backgroundScope)

    @Test
    fun theLibraryAnswersFirstThenWhatTheServerFound() = runTest {
        val source = FakeSearch()
        val answer = search(source).answer("Love")
        assertEquals(listOf("device:1"), answer.library.songs.map { it.id })
        assertEquals(listOf("find:9"), answer.outside?.songs?.map { it.id })
        assertEquals(listOf("love"), source.libraryReads)
    }

    @Test
    fun twoAsksForOneSearchOnItsWayShareIt() = runTest {
        val source = FakeSearch().apply { outsideHold = CompletableDeferred() }
        val search = search(source)
        val first = async { search.answer("Love") }
        val second = async { search.answer("Love") }
        runCurrent()
        source.outsideHold!!.complete(Unit)
        assertEquals(first.await().token, second.await().token)
        assertEquals(1, source.outsideAsks)
    }

    @Test
    fun afterTheServerFailedTheSameWordsAskAgain() = runTest {
        val source = FakeSearch().apply { outsideFails = true }
        val search = search(source)
        val failed = search.answer("Love")
        assertTrue(failed.outsideFailed)
        assertEquals(listOf("device:1"), failed.library.songs.map { it.id })
        source.outsideFails = false
        val again = search.answer("Love")
        assertTrue(!again.outsideFailed)
        assertEquals(2, source.outsideAsks)
    }

    @Test
    fun aLibraryThatFailsLeavesNoSearchHangingOn() = runTest {
        val source = FakeSearch().apply { libraryFails = true }
        val search = search(source)
        assertTrue(runCatching { search.answer("Love") }.isFailure)
        source.libraryFails = false
        assertEquals(listOf("device:1"), search.answer("Love").library.songs.map { it.id })
    }

    @Test
    fun aSearchThatFailedLeavesNoOlderAnswerForItsWords() = runTest {
        val source = FakeSearch()
        val search = search(source)
        search.answer("Love")
        source.libraryFails = true
        assertTrue(runCatching { search.answer("Love") }.isFailure)
        assertNull(search.latest("Love"))
    }

    @Test
    fun songsTakenInShowAmongTheLibrarys() = runTest {
        val source = FakeSearch().apply { tookIn = true }
        search(source).answer("Love")
        assertEquals(2, source.libraryReads.size)
    }

    @Test
    fun withLibrarySongsOnlyNothingOutsideIsAskedOrShown() = runTest {
        val source = FakeSearch().apply {
            libraryOnly = true
            top = RankedTracks("Artist", "lastfm", listOf(RankedTrack(1, track("find:7"), null), RankedTrack(2, track("device:1"), null)))
        }
        val answer = search(source).answer("Artist")
        assertNull(answer.outside)
        assertEquals(0, source.outsideAsks)
        assertTrue(answer.top?.songs.orEmpty().none { it.track.id.startsWith("find:") })
    }

    @Test
    fun aKeptAnswerIsForItsOwnAccountOnly() = runTest {
        val source = FakeSearch()
        val search = search(source)
        val answer = search.answer("Love")
        assertEquals(answer.token, search.kept(answer.token)?.token)
        assertEquals(answer.token, search.latest("Love")?.token)
        source.account = "a2"
        assertNull(search.kept(answer.token))
        assertNull(search.latest("Love"))
    }
}
