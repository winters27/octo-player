package app.winters.octo.discovery

import app.winters.octo.subsonic.Album
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumShareTest {
    // As the client reads the server: fields it does not know are passed over.
    private val subsonicJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private fun outside(name: String, songs: Int, owned: Int?) =
        Album(id = name, name = name, artist = "Drake", songCount = songs, isExternal = true, ownedCount = owned)

    @Test
    fun anAlbumHeldInPartSaysHowMuch() {
        val album = outside("What A Time To Be Alive", 11, 2)
        assertEquals(AlbumShare.Part, albumShare(album))
        assertEquals("2 of 11 in your library", albumShareLine(album))
    }

    @Test
    fun thePhonesOwnAlbumsSayItFromTheCounts() {
        assertEquals("20 of 21 in your library", albumShareLine(20, 21))
        assertNull(albumShareLine(0, 14))
        assertNull(albumShareLine(11, 11))
        assertNull(albumShareLine(null, 11))
    }

    @Test
    fun noneAndWholeHaveNoLine() {
        assertEquals(AlbumShare.None, albumShare(outside("MAID OF HONOUR", 14, 0)))
        assertNull(albumShareLine(outside("MAID OF HONOUR", 14, 0)))
        assertEquals(AlbumShare.Whole, albumShare(outside("HABIBTI", 11, 11)))
        assertNull(albumShareLine(outside("HABIBTI", 11, 11)))
    }

    @Test
    fun uncountedAndLibraryAlbumsHaveNoShare() {
        assertNull(albumShare(outside("\$ome \$exy \$ongs 4 U", 21, null)))
        assertNull(albumShare(Album(id = "al-1", name = "HABIBTI (FOMO)", songCount = 15, ownedCount = 15)))
    }

    @Test
    fun theServersFieldsAreRead_AndAnOlderServerLeavesThemOut() {
        val counted = subsonicJson.decodeFromString(Album.serializer(),
            """{"id":"x","name":"What A Time To Be Alive","songCount":11,"isExternal":true,"ownedCount":2}""")
        assertEquals("2 of 11 in your library", albumShareLine(counted))
        val older = subsonicJson.decodeFromString(Album.serializer(), """{"id":"x","name":"What A Time To Be Alive","songCount":11,"isExternal":true}""")
        assertNull(albumShare(older))
    }
}
