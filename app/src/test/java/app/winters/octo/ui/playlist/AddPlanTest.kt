package app.winters.octo.ui.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

// The add plan's own tests live with it in shared/core (PlaylistAddsTest).
class AddPlanTest {
    @Test
    fun holdingsAreGroupedByPlaylist() {
        assertEquals(
            mapOf("p1" to setOf("a", "b"), "p2" to setOf("a")),
            holdingsByPlaylist(listOf("p1" to "a", "p2" to "a", "p1" to "b")),
        )
    }
}
