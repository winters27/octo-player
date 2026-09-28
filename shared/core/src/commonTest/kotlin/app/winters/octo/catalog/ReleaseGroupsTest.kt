package app.winters.octo.catalog

import app.winters.octo.subsonic.Album
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseGroupsTest {
    private fun album(id: String, vararg types: String, compilation: Boolean = false, artistId: String = "r1") =
        Album(id, name = id, artistId = artistId, releaseTypes = types.toList(), isCompilation = compilation)

    @Test
    fun eachKindGoesOnItsShelfInThePagesOrder() {
        val albums = listOf(
            album("appears", "Album", artistId = "other"),
            album("live", "Album", "Live"),
            album("single", "Single"),
            album("ep", "ep"),
            album("best", "Album", "Compilation"),
            album("ok", "Album"),
            album("soundtrack", "Album", "Soundtrack"),
        )
        val groups = groupAlbums(albums) { it.artistId == "r1" }
        assertEquals(
            listOf(
                ReleaseGroup.Albums to listOf("ok", "soundtrack"),
                ReleaseGroup.SinglesAndEps to listOf("single", "ep"),
                ReleaseGroup.Compilations to listOf("best"),
                ReleaseGroup.Live to listOf("live"),
                ReleaseGroup.AppearsOn to listOf("appears"),
            ),
            groups.map { (group, items) -> group to items.map { it.id } },
        )
    }

    @Test
    fun aCompilationFlagWinsOverAnyType() {
        assertEquals(ReleaseGroup.Compilations, releaseGroupOf(listOf("Single"), compilation = true))
        assertEquals(ReleaseGroup.Live, releaseGroupOf(listOf("single", "LIVE")))
    }

    @Test
    fun withNoTypesEverythingOwnIsAnAlbum() {
        val albums = listOf(album("a"), album("b", compilation = true), album("c", artistId = "other"))
        val groups = groupAlbums(albums) { it.artistId == "r1" }
        assertEquals(
            listOf(ReleaseGroup.Albums to listOf("a", "b"), ReleaseGroup.AppearsOn to listOf("c")),
            groups.map { (group, items) -> group to items.map { it.id } },
        )
    }

    @Test
    fun nothingGivesNoShelves() {
        assertEquals(emptyList<Pair<ReleaseGroup, List<Album>>>(), groupAlbums(emptyList()))
    }

    @Test
    fun theShelvesHaveThePhonesWords() {
        assertEquals(listOf("Albums", "Singles and EPs", "Compilations", "Live", "Appears on"), ReleaseGroup.entries.map { it.title })
    }
}
