package app.winters.octo.data

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.health.FixStep
import app.winters.octo.subsonic.LibraryActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Which of the library's songs the signed-in server has, and the steps
// sent to it by its own ids.
class LibraryFilesTest {
    private val server = "server:home"

    private fun copy(nativeId: String, merged: String, source: String = server, title: String = "Song $nativeId") = SourceTrackEntity(
        id = "$source/$nativeId", sourceId = source, nativeId = nativeId, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Artist", artistId = "ar", album = "Album", albumId = "al", trackNo = 1, discNo = 1, year = 2020,
        durationMs = 200_000, addedAt = 0, mimeType = "audio/flac", sizeBytes = null, artwork = null, uri = null,
        albumOrder = 0, relinkKey = "", genre = "", mergedId = merged,
    )

    private val everything = LibraryActions(
        enabled = true,
        allowed = true,
        dryRun = false,
        actions = listOf("remove", "restore", "retag", "undo", "joinAlbum", "lookup", "cover", "upgrade"),
    )

    @Test
    fun anyVersionOfTheExtensionCounts() {
        assertTrue(listsLibraryActions(setOf("octoLibraryActions:1")))
        assertTrue(listsLibraryActions(setOf("octoLibraryActions:1", "octoLibraryActions:2", "octoLibraryActions:3")))
        assertFalse(listsLibraryActions(setOf("octoLibraryActionsMore:1", "apiKeyAuthentication:1")))
        assertFalse(listsLibraryActions(emptySet()))
    }

    @Test
    fun eachLibrarySongMapsToItsCopyOnTheSignedInServerOnly() {
        val copies = listOf(
            copy("b2", merged = "t1"),
            copy("b1", merged = "t1"),
            copy("p", merged = "t2", source = "device"),
            copy("o", merged = "t3", source = "server:other"),
            copy("x", merged = "t4"),
            copy("loose", merged = ""),
        )

        // Two copies there: the lowest id, as Find higher quality picks.
        assertEquals(mapOf("t1" to "b1", "t4" to "x"), serverSongIds(copies, server))
        assertEquals(emptyMap<String, String>(), serverSongIds(copies, null))
    }

    @Test
    fun deletingASongTakesEveryCopyItHasOnThatServer() {
        val copies = listOf(copy("b2", merged = "t1"), copy("b1", merged = "t1"), copy("p", merged = "t1", source = "device"), copy("c", merged = "t2"))

        assertEquals(
            listOf(FixStep.Remove("b1", "Song b1"), FixStep.Remove("b2", "Song b2"), FixStep.Remove("c", "Song c")),
            deleteSteps(listOf("t1", "t2", "t1", "t9"), copies, server),
        )
        assertTrue(deleteSteps(listOf("t1"), copies, null).isEmpty())
    }

    @Test
    fun stepsPlannedOnLibrarySongsCarryTheServersIds() {
        val ids = mapOf("a" to "na", "b" to "nb")
        val steps = listOf(
            FixStep.Retag("a", "One", mapOf("year" to "1998")),
            FixStep.JoinAlbum("b", "Two", like = "a"),
            // Its lead is not on the server, so it cannot be joined.
            FixStep.JoinAlbum("b", "Two", like = "z"),
            // Not on the server at all.
            FixStep.AddCover("z", "Three"),
            FixStep.Remove("a", "One"),
        )

        assertEquals(
            listOf(FixStep.Retag("na", "One", mapOf("year" to "1998")), FixStep.JoinAlbum("nb", "Two", like = "na"), FixStep.Remove("na", "One")),
            steps.onServer(ids),
        )
    }

    @Test
    fun eachStepNeedsWhatTheServerAllows() {
        val removeOnly = LibraryActions(enabled = true, allowed = true, dryRun = false, actions = listOf("remove"))

        assertTrue(removeOnly.canRun(FixStep.Remove("a", "A")))
        assertFalse(removeOnly.canRun(FixStep.Restore("a", "A")))
        assertFalse(removeOnly.canRun(FixStep.Retag("a", "A", emptyMap())))
        assertTrue(everything.canRun(FixStep.Undo("a", "A")))
        assertTrue(everything.canRun(FixStep.JoinAlbum("a", "A", "b")))
        assertTrue(everything.canRun(FixStep.AddCover("a", "A")))
        // An Undo shows only when all of it can be done.
        assertTrue(everything.canRunAll(listOf(FixStep.Restore("a", "A"), FixStep.Undo("b", "B"))))
        assertFalse(removeOnly.canRunAll(listOf(FixStep.Restore("a", "A"))))
        assertFalse(everything.copy(admin = false).canRunAll(listOf(FixStep.Restore("a", "A"))))
        assertFalse((null as LibraryActions?).canRunAll(listOf(FixStep.Restore("a", "A"))))
        assertFalse(everything.canRunAll(emptyList()))
    }
}
