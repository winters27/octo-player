package app.winters.octo.desktop.discord

import app.winters.octo.desktop.system.NowPlaying
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordArtTest {
    private fun now(artist: String = "Daft Punk", album: String = "Random Access Memories") = NowPlaying(
        entryKey = 1,
        songId = "s1",
        title = "Instant Crush",
        artist = artist,
        album = album,
        albumArtist = artist,
        durationMs = 337_000,
        coverId = "al-1",
        trackNumber = 5,
        discNumber = 1,
        genres = emptyList(),
        playing = true,
        canPrevious = true,
        canNext = true,
        speed = 1f,
    )

    private val prefs = DiscordPrefs(on = true)

    @Test
    fun itunesTakesTheAlbumWhoseArtistAndNameAgree() {
        val body = """
            {"results":[
              {"artistName":"Tribute Band","collectionName":"Random Access Memories","artworkUrl100":"https://is1.example/wrong/100x100bb.jpg"},
              {"artistName":"Daft Punk","collectionName":"Random Access Memories - Single","artworkUrl100":"https://is1.example/ram/100x100bb.jpg"}
            ]}
        """.trimIndent()
        assertEquals("https://is1.example/ram/1024x1024bb.jpg", itunesCover(body, "Daft Punk", "Random Access Memories"))
        assertNull(itunesCover(body, "Daft Punk", "Discovery"))
        assertNull("an answer that is not JSON finds nothing", itunesCover("<html>", "Daft Punk", "Random Access Memories"))
    }

    @Test
    fun deezerTakesTheAlbumWhoseArtistAndNameAgree() {
        val body = """{"data":[{"title":"Random Access Memories","cover_xl":"https://e-cdns.example/ram/1000x1000.jpg","artist":{"name":"Daft Punk"}}]}"""
        assertEquals("https://e-cdns.example/ram/1000x1000.jpg", deezerCover(body, "Daft Punk", "Random Access Memories"))
        assertNull(deezerCover(body, "Justice", "Random Access Memories"))
    }

    @Test
    fun deezersEmptyArtistPlaceholderIsNotAPhoto() {
        val placeholder = """{"data":[{"name":"Daft Punk","picture_big":"https://e-cdns-images.dzcdn.net/images/artist//500x500-000000-80-0-0.jpg"}]}"""
        assertNull(deezerArtistPhoto(placeholder, "Daft Punk"))
        val real = """{"data":[{"name":"Daft Punk Tribute","picture_big":"https://x/a.jpg"},{"name":"Daft Punk","picture_big":"https://e-cdns-images.dzcdn.net/images/artist/f3/500x500.jpg"}]}"""
        assertEquals("https://e-cdns-images.dzcdn.net/images/artist/f3/500x500.jpg", deezerArtistPhoto(real, "Daft Punk"))
    }

    @Test
    fun eachPictureIsLookedForOnceAndAMissIsKept() = runBlocking {
        val asked = mutableListOf<String>()
        val art = DiscordArt(fetch = { url -> asked += url; null })
        assertTrue(art.wants(now(), prefs))
        art.look(now(), prefs)
        // iTunes then Deezer for the cover, Deezer for the artist; nothing found.
        assertEquals(3, asked.size)
        assertFalse(art.wants(now(), prefs))
        art.look(now(), prefs)
        assertEquals(3, asked.size)
        assertEquals(DiscordArtwork(), art.known(now()))
    }

    @Test
    fun aFoundPictureIsKnownAfterwards() = runBlocking {
        val art = DiscordArt(fetch = { url ->
            when {
                url.startsWith("https://itunes.apple.com/") ->
                    """{"results":[{"artistName":"Daft Punk","collectionName":"Random Access Memories","artworkUrl100":"https://is1.example/ram/100x100bb.jpg"}]}"""
                url.startsWith("https://api.deezer.com/search/artist") ->
                    """{"data":[{"name":"Daft Punk","picture_big":"https://e-cdns.example/artist/f3/500x500.jpg"}]}"""
                else -> null
            }
        })
        art.look(now(), prefs)
        assertEquals(DiscordArtwork("https://is1.example/ram/1024x1024bb.jpg", "https://e-cdns.example/artist/f3/500x500.jpg"), art.known(now()))
    }

    @Test
    fun nothingIsLookedForThatTheSettingsDoNotShow() {
        val art = DiscordArt(fetch = { error("no lookups") })
        assertFalse(art.wants(now(), prefs.copy(picture = DiscordPicture.Icon, badge = DiscordBadge.Off)))
    }
}
