package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesTest {
    // Relinking favourites and pins after a rebuild

    @Test
    fun aFavouriteWhoseIdVanishedFollowsItsKey() {
        // The album was only on the server; now the phone has it too, and the
        // phone's id wins the merge.
        val held = listOf(Held("server:x:al1", "ok computer radiohead"))
        val byKey = mapOf("ok computer radiohead" to listOf("device:album:7"))
        assertEquals(listOf(Relink("server:x:al1", "device:album:7")), relinks(held, setOf("device:album:7"), byKey))
    }

    @Test
    fun aFavouriteStillInTheLibraryStaysPut() {
        val held = listOf(Held("device:album:7", "ok computer radiohead"))
        val byKey = mapOf("ok computer radiohead" to listOf("device:album:7", "server:x:al1"))
        assertTrue(relinks(held, setOf("device:album:7", "server:x:al1"), byKey).isEmpty())
    }

    @Test
    fun nothingWithTheKeyMeansItWaitsAsItIs() {
        // Signed out, say: the server's album is gone for now, and the
        // favourite waits for it to come back.
        val held = listOf(Held("server:x:al1", "ok computer radiohead"), Held("server:x:al2", ""))
        assertTrue(relinks(held, emptySet(), mapOf("" to listOf("device:album:1"))).isEmpty())
    }

    @Test
    fun itNeverMovesOntoSomethingAlreadyHeld() {
        val held = listOf(Held("gone", "k"), Held("device:album:1", "k"))
        assertTrue(relinks(held, setOf("device:album:1"), mapOf("k" to listOf("device:album:1"))).isEmpty())
    }

    @Test
    fun twoVanishedFavouritesDoNotBothTakeOneId() {
        val held = listOf(Held("old:b", "k"), Held("old:a", "k"))
        val byKey = mapOf("k" to listOf("new:2", "new:1"))
        // The first by id takes the first by id, the other the next.
        assertEquals(listOf(Relink("old:a", "new:1"), Relink("old:b", "new:2")), relinks(held, setOf("new:1", "new:2"), byKey))
        assertEquals(listOf(Relink("old:a", "new:1")), relinks(held, setOf("new:1"), mapOf("k" to listOf("new:1"))))
    }

    // Pins

    private fun pin(id: String, position: Int, kind: PinKind = PinKind.Album) = PinnedItemEntity(kind.id, id, id, position)

    private fun List<PinnedItemEntity>.ids() = map { it.itemId }

    @Test
    fun aNewPinGoesAtTheEnd() {
        val pins = listOf(pin("b", 1), pin("a", 0))
        val after = withPin(pins, pin("c", 99, PinKind.Playlist), showing = 2)!!
        assertEquals(listOf("a", "b", "c"), after.ids())
        assertEquals(listOf(0, 1, 2), after.map { it.position })
    }

    @Test
    fun pinningTwiceChangesNothing() {
        val pins = listOf(pin("a", 0), pin("b", 1))
        assertEquals(pins, withPin(pins, pin("a", 5), showing = 2))
    }

    @Test
    fun theSameIdOfAnotherKindIsAnotherPin() {
        val pins = listOf(pin("x", 0))
        assertEquals(2, withPin(pins, pin("x", 0, PinKind.Artist), showing = 1)!!.size)
    }

    @Test
    fun homeHoldsTwelve() {
        val pins = (0 until PIN_LIMIT).map { pin("p$it", it) }
        assertNull(withPin(pins, pin("one more", 0), showing = PIN_LIMIT))
        // Something already pinned is still fine to pin again.
        assertEquals(pins, withPin(pins, pin("p3", 0), showing = PIN_LIMIT))
    }

    @Test
    fun pinsHomeCannotShowDoNotCountAgainstTheLimit() {
        // Twelve rows, but two point at albums of a server that is signed out.
        val pins = (0 until PIN_LIMIT).map { pin("p$it", it) }
        val after = withPin(pins, pin("new", 0), showing = PIN_LIMIT - 2)!!
        assertEquals("new", after.last().itemId)
    }

    @Test
    fun unpinningClosesTheGap() {
        val pins = listOf(pin("a", 0), pin("b", 1), pin("c", 2))
        val after = withoutPin(pins, PinKind.Album.id, "b")
        assertEquals(listOf("a", "c"), after.ids())
        assertEquals(listOf(0, 1), after.map { it.position })
        // Something not pinned leaves the row as it was.
        assertEquals(pins, withoutPin(pins, PinKind.Artist.id, "a"))
    }

    @Test
    fun moveToFrontKeepsTheRestInOrder() {
        val pins = listOf(pin("a", 0), pin("b", 1), pin("c", 2), pin("d", 3))
        val after = movedToFront(pins, PinKind.Album.id, "c")
        assertEquals(listOf("c", "a", "b", "d"), after.ids())
        assertEquals(listOf(0, 1, 2, 3), after.map { it.position })
        assertEquals(pins, movedToFront(pins, PinKind.Album.id, "a"))
        assertEquals(pins, movedToFront(pins, PinKind.Album.id, "missing"))
    }

    @Test
    fun pinKindsReadBackFromWhatIsSaved() {
        PinKind.entries.forEach { assertEquals(it, PinKind.of(it.id)) }
        assertNull(PinKind.of("song"))
    }
}
