package app.winters.octo.desktop.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Where rows land when they are dragged or moved from the menu.
class PlaylistOrderTest {
    private val five = listOf(0, 1, 2, 3, 4)

    @Test
    fun oneRowDraggedUpLandsBeforeTheDropRow() {
        assertEquals(listOf(0, 3, 1, 2, 4), movedPositions(five, listOf(3), to = 1))
    }

    @Test
    fun oneRowDraggedDownLandsBeforeTheDropRowToo() {
        // Dropped before row 4: after 3, which was under it.
        assertEquals(listOf(0, 2, 3, 1, 4), movedPositions(five, listOf(1), to = 4))
        // Dropped past the last row.
        assertEquals(listOf(0, 2, 3, 4, 1), movedPositions(five, listOf(1), to = 5))
    }

    @Test
    fun severalRowsGoTogetherInTheirOrder() {
        assertEquals(listOf(1, 4, 0, 2, 3), movedPositions(five, listOf(4, 1), to = 0))
        assertEquals(listOf(0, 2, 1, 3, 4), movedPositions(five, listOf(1, 3), to = 3))
        assertEquals(listOf(2, 4, 0, 1, 3), movedPositions(listOf(2, 4, 0, 1, 3), listOf(2, 4), to = 1))
    }

    @Test
    fun droppingRowsOnThemselvesChangesNothing() {
        assertEquals(five, movedPositions(five, listOf(2), to = 2))
        assertEquals(five, movedPositions(five, listOf(2), to = 3))
        assertEquals(five, movedPositions(five, listOf(1, 2), to = 1))
    }

    @Test
    fun aDropOutsideTheListLandsAtItsEnds() {
        assertEquals(listOf(4, 0, 1, 2, 3), movedPositions(five, listOf(4), to = -3))
        assertEquals(listOf(1, 2, 3, 4, 0), movedPositions(five, listOf(0), to = 99))
    }

    @Test
    fun theMenuMovesGoOneStepOrToTheEnds() {
        assertEquals(listOf(0, 2, 1, 3, 4), movedBy(PlaylistMove.Up, 5, listOf(2)))
        assertEquals(listOf(0, 1, 3, 2, 4), movedBy(PlaylistMove.Down, 5, listOf(2)))
        assertEquals(listOf(2, 0, 1, 3, 4), movedBy(PlaylistMove.Top, 5, listOf(2)))
        assertEquals(listOf(0, 1, 3, 4, 2), movedBy(PlaylistMove.Bottom, 5, listOf(2)))
        // A block of rows moves as one.
        assertEquals(listOf(1, 2, 0, 3, 4), movedBy(PlaylistMove.Up, 5, listOf(1, 2)))
    }

    @Test
    fun aMoveThatCannotGoFurtherIsDim() {
        assertFalse(movesAnything(PlaylistMove.Up, 5, listOf(0)))
        assertFalse(movesAnything(PlaylistMove.Top, 5, listOf(0, 1)))
        assertFalse(movesAnything(PlaylistMove.Down, 5, listOf(4)))
        assertFalse(movesAnything(PlaylistMove.Bottom, 5, listOf(3, 4)))
        assertTrue(movesAnything(PlaylistMove.Top, 5, listOf(0, 2)))
        // Places no longer in the list are left out.
        assertFalse(movesAnything(PlaylistMove.Up, 3, listOf(7)))
    }
}
