package app.winters.octo.testing

import app.winters.octo.subsonic.AlbumListType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HarnessTest {
    @Test
    fun generatedLibraryIsStableAndServesEveryField() = runTest {
        val shape = GenShape(songs = 1_200, albums = 120, artists = 40)
        val a = LibraryGen.server(1, shape)
        assertEquals(a.songs, LibraryGen.server(1, shape).songs)
        val fake = FakeSubsonic(a)
        val client = fake.client()
        // Pages come back exactly as generated, every field intact.
        assertEquals(a.songs.take(500), client.songPage(500, 0))
        assertEquals(a.albums.take(500), client.albumList(AlbumListType.ALPHABETICAL, 500, 0))
        fake.close()
    }

    @Test
    fun oldSqliteIs321() {
        OldSqlite.open().use { assertEquals("3.21.0", it.metaData.databaseProductVersion.take(6)) }
    }
}
