package app.winters.octo.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The library songs adopted finds became, remembered for rows drawn again.
class RecentRowsTest {
    @Test
    fun pastTheCapTheRowUsedLongestAgoGoes() {
        val rows = RecentRows<String>(kept = 2)
        rows["a"] = "A"
        rows["b"] = "B"
        // Read again, so "b" is the one used longest ago.
        assertEquals("A", rows["a"])
        rows["c"] = "C"
        assertEquals(2, rows.size)
        assertNull(rows["b"])
        assertEquals("A", rows["a"])
        assertEquals("C", rows["c"])
    }

    @Test
    fun manyRowsDrawnStayWithinTheCap() {
        val rows = RecentRows<Int>(kept = ADOPTED_SEEN_KEPT)
        repeat(ADOPTED_SEEN_KEPT * 3) { rows["find:$it"] = it }
        assertEquals(ADOPTED_SEEN_KEPT, rows.size)
        rows.remove("find:${ADOPTED_SEEN_KEPT * 3 - 1}")
        assertEquals(ADOPTED_SEEN_KEPT - 1, rows.size)
    }
}
