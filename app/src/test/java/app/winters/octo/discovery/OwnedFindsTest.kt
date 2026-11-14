package app.winters.octo.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedFindsTest {
    @Test
    fun aSongNotedOwnedStaysUntilTheServerMarksItOutside() {
        val owned = OwnedFinds(kept = 10)
        owned.note(listOf("find:a", "find:b"))
        assertTrue("find:a" in owned)
        assertEquals(listOf("a", "b"), owned.serverIds())

        // A later read marks "a" as found online: it is owned no more.
        owned.note(emptyList(), outside = listOf("find:a"))
        assertFalse("find:a" in owned)
        assertEquals(setOf("find:b"), owned.ids.value)
    }

    @Test
    fun pastTheCapTheOldestGoFirst_AndOneSeenAgainIsNewest() {
        val owned = OwnedFinds(kept = 2)
        owned.note(listOf("find:a", "find:b"))
        // Seen again, so "b" is the oldest now.
        owned.note(listOf("find:a"))
        owned.note(listOf("find:c"))
        assertEquals(setOf("find:a", "find:c"), owned.ids.value)
    }
}
