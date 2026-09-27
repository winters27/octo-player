package app.winters.octo.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassMenuTest {
    private fun looks(count: Int, selected: Int) =
        menuRowLooks(count, selected).map { (if (it.selected) "pill" else "plain") + if (it.lineAbove) " line" else "" }

    @Test
    fun theChosenOptionHasThePillAndNoLinesTouchIt() {
        assertEquals(listOf("plain", "pill", "plain", "plain line"), looks(4, 1))
    }

    @Test
    fun withNothingChosenEveryOptionAfterTheFirstHasALine() {
        assertEquals(listOf("plain", "plain line", "plain line"), looks(3, -1))
    }

    @Test
    fun theFirstAndLastOptionsCanBeChosen() {
        assertEquals(listOf("pill", "plain", "plain line"), looks(3, 0))
        assertEquals(listOf("plain", "plain line", "pill"), looks(3, 2))
    }

    @Test
    fun onlyOneOptionIsEverChosen() {
        assertEquals(1, menuRowLooks(6, 3).count { it.selected })
        assertEquals(0, menuRowLooks(6, 9).count { it.selected })
        assertEquals(emptyList<MenuRowLook>(), menuRowLooks(0, 0))
    }
}
