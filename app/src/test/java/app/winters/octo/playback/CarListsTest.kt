package app.winters.octo.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarListsTest {
    private var account: String? = "a1"
    private val node = CarNode.OctoList("og1", "Deep Cuts")

    private fun TestScope.lists() = CarLists({ account }, backgroundScope)

    @Test
    fun everyReadAsksAgainAndTwoAtOnceShareOneAsk() = runTest {
        val lists = lists()
        var asks = 0
        val held = CompletableDeferred<List<String>>()
        val first = async { lists.fetch(node) { asks++; held.await() } }
        val second = async { lists.fetch(node) { asks++; held.await() } }
        runCurrent()
        held.complete(listOf("a", "b"))
        assertEquals(listOf("a", "b"), first.await())
        assertEquals(listOf("a", "b"), second.await())
        assertEquals(1, asks)
        lists.fetch(node) { asks++; listOf("c") }
        assertEquals(2, asks)
        assertEquals(listOf("c"), lists.keptFor<List<String>>(node))
    }

    @Test
    fun aFailedAskLeavesNothingKept() = runTest {
        val lists = lists()
        lists.fetch(node) { listOf("a") }
        runCatching { lists.fetch(node) { error("server down") } }
        assertNull(lists.keptFor<List<String>>(node))
    }

    @Test
    fun anAnswerIsKeptUnderTheAccountItWasAskedFor() = runTest {
        val lists = lists()
        val held = CompletableDeferred<List<String>>()
        val ask = async { lists.fetch(node) { held.await() } }
        runCurrent()
        account = "a2"
        held.complete(listOf("a"))
        ask.await()
        assertNull(lists.keptFor<List<String>>(node))
    }

    @Test
    fun partsBehindManyListsHoldNoMoreThanTheAnswersKept() = runTest {
        val lists = lists()
        repeat(40) { n -> lists.behind(CarNode.Album("a$n"), { listOf("s$n") }) {} }
        runCurrent()
        assertTrue(lists.builtCount() <= 16)
    }

    @Test
    fun aPartBehindAListTellsOnceWhenItChangesWhatWasShown() = runTest {
        val lists = lists()
        var server = listOf("whole-1")
        var changes = 0
        // First open: nothing kept yet, the ask goes out behind.
        assertNull(lists.behind(node, { server }) { changes++ })
        runCurrent()
        assertEquals(1, changes)
        // The read the change brings serves what came, and asks again; the
        // same answer comes, so nothing more is told.
        assertEquals(server, lists.behind(node, { server }) { changes++ })
        runCurrent()
        assertEquals(1, changes)
        // The server changes; the next open tells once more.
        server = listOf("whole-2")
        lists.behind(node, { server }) { changes++ }
        runCurrent()
        assertEquals(2, changes)
        assertTrue(lists.keptFor<Behind<List<String>>>(node)?.value == server)
    }
}
