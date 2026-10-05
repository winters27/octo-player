package app.winters.octo.desktop.discord

import app.winters.octo.desktop.system.NowPlaying
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

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
        assertEquals("https://is1.example/ram/512x512bb.jpg", itunesCover(body, "Daft Punk", "Random Access Memories"))
        assertNull(itunesCover(body, "Daft Punk", "Discovery"))
        assertNull("an answer that is not JSON finds nothing", itunesCover("<html>", "Daft Punk", "Random Access Memories"))
    }

    @Test
    fun deezerTakesTheAlbumWhoseArtistAndNameAgree() {
        val body = """{"data":[{"title":"Random Access Memories","cover_big":"https://e-cdns.example/ram/1000x1000.jpg","artist":{"name":"Daft Punk"}}]}"""
        assertEquals("https://e-cdns.example/ram/1000x1000.jpg", deezerCover(body, "Daft Punk", "Random Access Memories"))
        assertNull(deezerCover(body, "Justice", "Random Access Memories"))
    }

    @Test
    fun aSongsCoverIsTheTrackWithItsVersionWhateverTheGuestCredit() {
        val body = """{"data":[
            {"title":"Get Lucky","artist":{"name":"Daft Punk"},"album":{"cover_big":"https://e-cdns.example/ram/1000x1000.jpg"}},
            {"title":"Get Lucky (Radio Edit) [feat. Pharrell Williams and Nile Rodgers]","artist":{"name":"Daft Punk"},"album":{"cover_big":"https://e-cdns.example/single/1000x1000.jpg"}}
        ]}"""
        // The credit inside the version's bracket goes; the radio edit stays,
        // so the single's cover is the one taken and not the album's.
        assertEquals("https://e-cdns.example/single/1000x1000.jpg",
            deezerTrackCover(body, "Daft Punk", "Get Lucky (Radio Edit - feat. Pharrell Williams and Nile Rodgers)"))
        assertEquals("https://e-cdns.example/ram/1000x1000.jpg", deezerTrackCover(body, "Daft Punk", "Get Lucky feat. Pharrell Williams"))
    }

    @Test
    fun deezersEmptyArtistPlaceholderIsNotAPhoto() {
        val placeholder = """{"data":[{"name":"Daft Punk","picture_medium":"https://e-cdns-images.dzcdn.net/images/artist//500x500-000000-80-0-0.jpg"}]}"""
        assertNull(deezerArtistPhoto(placeholder, "Daft Punk"))
        val real = """{"data":[{"name":"Daft Punk Tribute","picture_medium":"https://x/a.jpg"},{"name":"Daft Punk","picture_medium":"https://e-cdns-images.dzcdn.net/images/artist/f3/500x500.jpg"}]}"""
        assertEquals("https://e-cdns-images.dzcdn.net/images/artist/f3/500x500.jpg", deezerArtistPhoto(real, "Daft Punk"))
    }

    // Answers as the catalogues would, from what the test lays out by
    // address; anything else finds nothing. The cover and the photo are
    // looked for at once, so what was asked goes in a list safe for both.
    private fun catalogue(vararg answers: Pair<String, String>, asked: MutableList<String> = CopyOnWriteArrayList()): (String) -> String? = { url ->
        asked += url
        answers.firstOrNull { url.startsWith(it.first) }?.second
            ?: if ("itunes" in url) """{"results":[]}""" else """{"data":[]}"""
    }

    @Test
    fun eachPictureIsLookedForOnceAndAMissIsKept() = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        val art = DiscordArt(fetch = catalogue(asked = asked))
        assertTrue(art.wants(now(), prefs))
        art.look(now(), prefs)
        // Deezer then iTunes by the album, the same by the song, Deezer for the artist; nothing found.
        assertEquals(5, asked.size)
        assertFalse(art.wants(now(), prefs))
        assertFalse(art.pending(artQueryOf(now()), prefs))
        art.look(now(), prefs)
        assertEquals(5, asked.size)
        assertEquals(DiscordArtwork(), art.known(now()))
    }

    @Test
    fun aLookupThatCouldNotConnectRestsThenTriesAgain() = runBlocking {
        var clock = 0L
        val asked = CopyOnWriteArrayList<String>()
        val art = DiscordArt(fetch = { url -> asked += url; null }, clock = { clock })
        art.look(now(), prefs)
        // Deezer gave no answer, so the cover waits; the artist's one try failed too.
        assertEquals(2, asked.size)
        assertFalse("resting, not hammering a catalogue that is down", art.wants(now(), prefs))
        art.look(now(), prefs)
        assertEquals(2, asked.size)
        clock += RETRY_AFTER_MS
        assertTrue("a failure is not an answer: it is tried again", art.wants(now(), prefs))
        art.look(now(), prefs)
        assertEquals(4, asked.size)
    }

    @Test
    fun aFoundPictureIsKnownAfterwards() = runBlocking {
        val art = DiscordArt(fetch = catalogue(
            "https://itunes.apple.com/search?media=music&entity=album" to
                """{"results":[{"artistName":"Daft Punk","collectionName":"Random Access Memories","artworkUrl100":"https://is1.example/ram/100x100bb.jpg"}]}""",
            "https://api.deezer.com/search/artist" to
                """{"data":[{"name":"Daft Punk","picture_medium":"https://e-cdns.example/artist/f3/500x500.jpg"}]}""",
        ))
        art.look(now(), prefs)
        assertEquals(DiscordArtwork("https://is1.example/ram/512x512bb.jpg", "https://e-cdns.example/artist/f3/500x500.jpg"), art.known(now()))
    }

    // Real tags: an album artist credited to two names, searched by the first.
    @Test
    fun aJoinedCreditIsSearchedByItsFirstArtist() = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        val art = DiscordArt(fetch = catalogue(
            "https://api.deezer.com/search/album" to
                """{"data":[{"title":"via crucis","cover_big":"https://e-cdns.example/via/1000x1000.jpg","artist":{"name":"Scrim"}}]}""",
            asked = asked,
        ))
        val scrim = ArtQuery("Father, Hold Me", "Scrim", "via crucis", "Scrim • \$crim")
        art.look(scrim, prefs)
        assertEquals("https://e-cdns.example/via/1000x1000.jpg", art.known(scrim).cover)
        assertTrue("a plain search for the first name", asked.any { it.startsWith("https://api.deezer.com/search/album") && it.endsWith("q=Scrim+via+crucis") })
    }

    // A single with no album tag is found by the song itself.
    @Test
    fun aSongWithNoAlbumIsFoundByItsTitle() = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        val art = DiscordArt(fetch = catalogue(
            "https://api.deezer.com/search/track" to
                """{"data":[{"title":"Nightcall","artist":{"name":"Kavinsky"},"album":{"title":"OutRun","cover_big":"https://e-cdns.example/outrun/1000x1000.jpg"}}]}""",
            asked = asked,
        ))
        val nightcall = ArtQuery("Nightcall", "Kavinsky & Lovefoxxx", "[Unknown Album]", "Kavinsky & Lovefoxxx")
        art.look(nightcall, prefs)
        assertEquals("https://e-cdns.example/outrun/1000x1000.jpg", art.known(nightcall).cover)
        assertFalse("no search for an album called Unknown", asked.any { "Unknown" in it })
    }

    @Test
    fun aSongStillToComeIsAskedByTheSameNames() {
        val song = Song("s9", "Teardrop", artist = "Massive Attack", album = "Mezzanine")
        assertEquals(ArtQuery("Teardrop", "Massive Attack", "Mezzanine", ""), artQueryOf(song))
    }

    @Test
    fun nothingIsLookedForThatTheSettingsDoNotShow() {
        val art = DiscordArt(fetch = { error("no lookups") })
        assertFalse(art.wants(now(), prefs.copy(picture = DiscordPicture.Icon, badge = DiscordBadge.Off)))
    }
}
