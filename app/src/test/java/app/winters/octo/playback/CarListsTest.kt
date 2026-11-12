package app.winters.octo.playback

import app.winters.octo.catalog.WorkOwners
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

    // A list read into the database keeps only its owner and head here: a
    // read not asked anew reuses the kept owner; a fresh one takes its
    // place and the old owner's rows go; so do those of an owner pushed out
    // by newer lists, of a failed read, and every one when the account
    // changes.
    @Test
    fun aListReadIntoTheDatabaseKeepsItsOwnerAndLetsReplacedOnesGo() = runTest {
        val gone = mutableListOf<String>()
        val lists = CarLists({ account }, backgroundScope) { gone += it }
        var n = 0
        val newOwner = { WorkOwners.marked("car:${++n}") }
        val album = CarNode.Album("al1")
        val first = lists.owned(album, fresh = true, newOwner) { owner -> "head of $owner" }
        assertEquals(CarOwned(WorkOwners.marked("car:1"), "head of ${WorkOwners.marked("car:1")}"), first)
        assertEquals(first, lists.owned(album, fresh = false, newOwner) { error("not asked again") })
        assertEquals(WorkOwners.marked("car:2"), lists.owned(album, fresh = true, newOwner) { "again" }.owner)
        runCurrent()
        assertEquals(listOf(WorkOwners.marked("car:1")), gone)
        runCatching { lists.owned(CarNode.Album("al2"), fresh = true, newOwner) { error("server down") } }
        runCurrent()
        assertEquals(listOf(WorkOwners.marked("car:1"), WorkOwners.marked("car:3")), gone)
        repeat(20) { lists.owned(CarNode.Album("x$it"), fresh = true, newOwner) { it } }
        runCurrent()
        assertTrue("at most 16 kept", lists.ownedCount() <= 16)
        assertTrue("the oldest let go", WorkOwners.marked("car:2") in gone && WorkOwners.marked("car:4") in gone)
        account = "a2"
        lists.clear()
        runCurrent()
        assertEquals(0, lists.ownedCount())
        assertEquals("every owner let go once", gone.size, gone.toSet().size)
        assertEquals(n, gone.size)
    }

    // A read whose kept place is swapped out before it could be pinned
    // (here every kept answer let go, as another account's sign-in does,
    // the moment each read lands) reads again, and its last try reads under
    // the lock, so nothing can take its place before it is pinned: swaps on
    // the first two tries still end in an answer, pinned, whose rows stay
    // until the answer lets them go, while the two swapped out are let go.
    @Test
    fun swapsOnTheFirstTwoTriesStillEndInAnAnswer() = runTest {
        val owners = WorkOwners()
        val gone = mutableListOf<String>()
        val lists = CarLists({ account }, backgroundScope, letGo = { owner -> owners.clear(owner) { gone += owner } }, pin = owners::pin)
        var n = 0
        val album = CarNode.Album("al1")
        val answer = runCatching {
            lists.owned(album, fresh = true, { WorkOwners.marked("car:${++n}") }) { owner ->
                backgroundScope.launch { lists.clear() }
                "read by $owner"
            }
        }
        assertTrue("an answer, not ${answer.exceptionOrNull()}", answer.isSuccess)
        assertEquals(CarOwned(WorkOwners.marked("car:3"), "read by ${WorkOwners.marked("car:3")}"), answer.getOrThrow())
        runCurrent()
        assertTrue("pinned for the answer", owners.pinned(WorkOwners.marked("car:3")))
        assertEquals(listOf(WorkOwners.marked("car:1"), WorkOwners.marked("car:2")), gone)
        owners.unpin(WorkOwners.marked("car:3"))
        runCurrent()
        assertEquals(listOf(WorkOwners.marked("car:1"), WorkOwners.marked("car:2"), WorkOwners.marked("car:3")), gone)
    }

    // An answer holds the owner it took: a newer read that takes its place
    // while the answer still reads it lets its rows go only once the answer
    // is done with it, kept or read anew alike.
    @Test
    fun anOwnerAnAnswerTookStaysUntilTheAnswerLetsItGo() = runTest {
        val owners = WorkOwners()
        val gone = mutableListOf<String>()
        val lists = CarLists({ account }, backgroundScope, letGo = { owner -> owners.clear(owner) { gone += owner } }, pin = owners::pin)
        var n = 0
        val newOwner = { WorkOwners.marked("car:${++n}") }
        val album = CarNode.Album("al1")
        // A read anew, then a second answer taking the one kept.
        val read = lists.owned(album, fresh = true, newOwner) { "first" }
        val kept = lists.owned(album, fresh = false, newOwner) { error("not asked again") }
        assertEquals(WorkOwners.marked("car:1"), kept.owner)
        // The car opens it again of its own accord, and a new read takes
        // its place while both answers still read car:1.
        lists.owned(album, fresh = true, newOwner) { "second" }.also { owners.unpin(it.owner) }
        runCurrent()
        assertEquals("rows let go under an answer still reading them", emptyList<String>(), gone)
        owners.unpin(read.owner)
        runCurrent()
        assertEquals(emptyList<String>(), gone)
        owners.unpin(kept.owner)
        runCurrent()
        assertEquals(listOf(WorkOwners.marked("car:1")), gone)
    }
}
