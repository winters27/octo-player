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
    fun aHeartFollowsTheServersStarAndGoesWithItsSong() {
        val owned = OwnedFinds(kept = 2)
        owned.note(listOf("find:a", "find:b"), starred = mapOf("find:a" to true, "find:b" to false))
        assertEquals(setOf("find:a"), owned.liked.value)
        assertEquals(true, owned.heart("find:b"))
        assertEquals(null, owned.heart("find:x"))
        // A later answer says "a" is no longer starred.
        owned.note(listOf("find:a"), starred = mapOf("find:a" to false))
        assertEquals(setOf("find:b"), owned.liked.value)
        // Past the cap "b" goes, and its heart with it.
        owned.note(listOf("find:c"))
        assertEquals(emptySet<String>(), owned.liked.value)
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
