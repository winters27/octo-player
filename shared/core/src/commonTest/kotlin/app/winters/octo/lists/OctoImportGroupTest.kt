package app.winters.octo.lists

import app.winters.octo.subsonic.Playlist
import kotlin.test.Test
import kotlin.test.assertNull

// An imported playlist's copy is the listener's own playlist, never one of
// From Octo's groups, whatever the server says its group is.
class OctoImportGroupTest {
    @Test
    fun anImportedCopyIsNeverInFromOcto() {
        val copy = Playlist("oi1234567890123456789A", "Imported", owner = "winters", readonly = true, octoGroup = "more", octoImport = true)
        assertNull(octoListGroup(copy, "winters"))
        assertNull(octoListGroup(copy, "winters", guess = true))
        assertNull(octoListGroups(listOf(copy), "winters").firstOrNull())
    }
}
