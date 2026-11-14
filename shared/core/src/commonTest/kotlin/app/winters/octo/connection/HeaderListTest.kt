package app.winters.octo.connection

import app.winters.octo.data.HeaderDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

// The ids kept beside the header rows: what lets each app draw the rows
// keyed, so focus and a row's Show stay on the row they belong to.
class HeaderListTest {
    private val filled = HeaderList.empty.replaced(listOf(HeaderDraft("A", "1"), HeaderDraft("B", "", saved = true), HeaderDraft("C", "3")))

    @Test
    fun renamingOrTypingKeepsTheRowsId() {
        val id = filled.ids[1]
        assertEquals(filled.ids, filled.renamed(id, "X").ids)
        assertEquals(filled.ids, filled.valued(id, "v").ids)
    }

    @Test
    fun anAddedRowGetsAnIdOfItsOwnAtTheEnd() {
        val (added, id) = filled.added()
        assertEquals(filled.ids + id, added.ids)
        assertFalse(id in filled.ids)
        assertEquals(HeaderDraft("", ""), added.rows.last())
    }

    @Test
    fun removingKeepsEveryOtherRowsId() {
        val gone = filled.remove(filled.ids[1])
        assertEquals(listOf(filled.ids[0], filled.ids[2]), gone.ids)
        assertEquals(listOf("A", "C"), gone.rows.map { it.name })
    }

    @Test
    fun keptKeepsTheIdsOfTheRowsThatStay() {
        val kept = filled.kept { !it.saved }
        assertEquals(listOf(filled.ids[0], filled.ids[2]), kept.ids)
        assertEquals(listOf("A", "C"), kept.rows.map { it.name })
    }

    @Test
    fun anIdIsNeverUsedTwice() {
        val seen = filled.ids.toMutableSet()
        val (afterRemove, id) = filled.remove(filled.ids[2]).added()
        assertFalse("a new row never takes a removed row's id", id in seen)
        seen += id
        val refilled = afterRemove.replaced(listOf(HeaderDraft("D", "4"), HeaderDraft("E", "5")))
        refilled.ids.forEach { assertFalse("a refill never takes an old id", it in seen) }
        assertEquals(2, refilled.ids.toSet().size)
    }

    @Test
    fun anOpOnAnIdNotThereChangesNothing() {
        val missing = filled.ids.max() + 100
        assertSame(filled, filled.renamed(missing, "X"))
        assertSame(filled, filled.valued(missing, "v"))
        assertSame(filled, filled.remove(missing))
    }
}
