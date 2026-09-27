package app.winters.octo.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class SortArrowTest {
    @Test
    fun ascendingShowsTheUpArrowAsDrawn() {
        assertEquals(0f, sortArrowTurn(descending = false))
    }

    @Test
    fun descendingTurnsItHalfRound() {
        assertEquals(180f, sortArrowTurn(descending = true))
    }

    @Test
    fun flippingTheDirectionTurnsTheArrowOver() {
        assertEquals(180f, sortArrowTurn(descending = true) - sortArrowTurn(descending = false))
    }
}
