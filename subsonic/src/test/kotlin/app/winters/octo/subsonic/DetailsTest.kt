package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// The OpenSubsonic details a library copy keeps: every genre, credits,
// MusicBrainz ids, loudness, dates and the rest, as a server sends them,
// and odd shapes that must not stop a library from loading.
class DetailsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun serve(name: String) {
        val body = requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText(Charsets.UTF_8)
        server.enqueue(MockResponse.Builder().body(body).build())
    }

    @Test
    fun aSongCarriesEveryDetailTheServerSends() = runTest {
        serve("search3Details")
        val song = client().songPage(size = 500, offset = 0).first()
        assertEquals(listOf("Disco", "Funk", "Pop"), song.genres)
        assertEquals(listOf("Daft Punk", "Pharrell Williams", "Nile Rodgers"), song.artists.map { it.name })
        assertEquals(listOf("ar1", "ar2", "ar3"), song.artists.map { it.id })
        assertEquals(listOf("Daft Punk"), song.albumArtists.map { it.name })
        assertEquals("get lucky", song.sortName)
        assertEquals("54a2f9c3-6c1a-4d2b-8f1e-1a2b3c4d5e6f", song.musicBrainzId)
        assertEquals(116, song.bpm)
        assertEquals("Single edit", song.comment)
        assertEquals("Thomas Bangalter, Guy-Manuel de Homem-Christo", song.displayComposer)
        assertEquals("clean", song.explicitStatus)
        assertEquals(listOf("USQX91300108"), song.isrc)
        assertEquals("2026-08-14T09:12:45.123Z", song.created)
        val gain = song.replayGain!!
        assertEquals(-9.12f, gain.trackGain!!, 0.001f)
        assertEquals(-8.4f, gain.albumGain!!, 0.001f)
        assertEquals(0.988f, gain.trackPeak!!, 0.001f)
        assertEquals(1.0f, gain.albumPeak!!, 0.001f)
        assertNull(gain.baseGain)
    }

    @Test
    fun oddShapesReadAsWhatTheyMeanOrAsNothing() = runTest {
        serve("search3Details")
        val songs = client().songPage(size = 500, offset = 0)
        // Genres as plain names, with a blank one dropped; a tempo as text.
        val odd = songs[1]
        assertEquals(listOf("House", "Garage"), odd.genres)
        assertEquals(128, odd.bpm)
        assertNull(odd.replayGain?.trackGain)
        assertEquals("", odd.musicBrainzId)
        // One ISRC on its own, kept as written.
        assertEquals(listOf("us-rc1-76-07839"), odd.isrc)
        // One genre sent on its own, and a tempo that is not a number.
        val broken = songs[2]
        assertEquals(listOf("Techno"), broken.genres)
        assertEquals(0, broken.bpm)
        // An ISRC in a shape that is neither text nor a list of it.
        assertEquals(emptyList<String>(), broken.isrc)
    }

    @Test
    fun songsCarryTheIsrcsOctoLists() = runTest {
        // An outside song with its code, one with none, and a library song
        // with the codes Navidrome gave it, exactly as sent.
        val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","openSubsonic":true,"searchResult3":{"song":[
            {"id":"ext-deezer-song-3135556","title":"Gurenge","artist":"LiSA","duration":239,"isrc":["JPU901901234"]},
            {"id":"ext-deezer-song-42","title":"Unknown","artist":"Someone","duration":180,"isrc":[]},
            {"id":"s9","title":"Get Lucky","artist":"Daft Punk","duration":369,"isrc":["GBA1B9800001","us-rc1-76-07839"]},
            {"id":"s10","title":"Old Server","artist":"Someone","duration":200}]}}}"""
        server.enqueue(MockResponse.Builder().body(body).build())
        val songs = client().search("lisa").song
        assertEquals(listOf("JPU901901234"), songs[0].isrc)
        assertEquals(emptyList<String>(), songs[1].isrc)
        assertEquals(listOf("GBA1B9800001", "us-rc1-76-07839"), songs[2].isrc)
        // A server that sends no isrc at all.
        assertEquals(emptyList<String>(), songs[3].isrc)
    }

    @Test
    fun anAlbumCarriesItsDatesIdsAndGenres() = runTest {
        serve("getAlbumList2")
        val albums = client().albumList(AlbumListType.ALPHABETICAL, 500, 0)
        val matrimony = albums[0]
        assertEquals(listOf("Hip Hop", "Hip-Hop", "Rap"), matrimony.genres)
        // Empty dates arrive as empty objects.
        assertNull(matrimony.originalReleaseDate?.year)
        assertNull(matrimony.releaseDate?.year)
        assertEquals("", matrimony.musicBrainzId)
        assertFalse(matrimony.isCompilation)

        val nightcall = albums[1]
        assertEquals("c0efd7f4-3fc6-4e18-9ec8-ee4fd1a36b8c", nightcall.musicBrainzId)
        assertEquals(ItemDate(2024, 9, 20), nightcall.originalReleaseDate)
        assertEquals(2024, nightcall.releaseDate?.year)
        assertEquals("nightcall", nightcall.sortName)
    }

    @Test
    fun discTitlesAndExplicitMarksAreRead() = runTest {
        val body = """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"a","name":"Live",
            "discTitles":[{"disc":1,"title":"Night One"},{"disc":2,"title":"Night Two"}],
            "explicitStatus":"explicit","isCompilation":true}]}}}"""
        server.enqueue(MockResponse.Builder().body(body).build())
        val album = client().albumList(AlbumListType.ALPHABETICAL, 500, 0).single()
        assertEquals(listOf(DiscTitle(1, "Night One"), DiscTitle(2, "Night Two")), album.discTitles)
        assertEquals("explicit", album.explicitStatus)
        assertEquals(true, album.isCompilation)
    }

    @Test
    fun releaseTypesAreReadFromAListOrAWord() = runTest {
        val body = """{"subsonic-response":{"status":"ok","artist":{"id":"r1","name":"Radiohead","album":[
            {"id":"a","name":"OK Computer","releaseTypes":["Album"]},
            {"id":"b","name":"I Might Be Wrong","releaseTypes":["Album","Live"]},
            {"id":"c","name":"Creep","releaseTypes":"Single"},
            {"id":"d","name":"Pablo Honey"}]}}}"""
        server.enqueue(MockResponse.Builder().body(body).build())
        val albums = client().artist("r1").album
        assertEquals(listOf(listOf("Album"), listOf("Album", "Live"), listOf("Single"), emptyList()), albums.map { it.releaseTypes })
    }

    @Test
    fun anAlbumPageCarriesItsDiscTitlesAndKind() = runTest {
        val body = """{"subsonic-response":{"status":"ok","album":{"id":"a","name":"Live","starred":"2026-01-01T00:00:00Z",
            "isCompilation":true,"releaseTypes":["Album","Compilation"],"genres":[{"name":"Rock"}],
            "discTitles":[{"disc":2,"title":"Encore"}],"song":[{"id":"s","title":"One","discNumber":2}]}}}"""
        server.enqueue(MockResponse.Builder().body(body).build())
        val album = client().album("a")
        assertEquals(listOf(DiscTitle(2, "Encore")), album.discTitles)
        assertEquals(listOf("Album", "Compilation"), album.releaseTypes)
        assertEquals(listOf("Rock"), album.genres)
        assertEquals(true, album.isCompilation)
        assertEquals("2026-01-01T00:00:00Z", album.starred)
    }

    @Test
    fun anArtistCarriesItsMusicBrainzId() = runTest {
        val body = """{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"D","artist":[
            {"id":"ar1","name":"Daft Punk","musicBrainzId":"056e4f3e-d505-4dad-8ec1-d04f521cbb56","sortName":"daft punk"}]}]}}}"""
        server.enqueue(MockResponse.Builder().body(body).build())
        val artist = client().artists().single().artist.single()
        assertEquals("056e4f3e-d505-4dad-8ec1-d04f521cbb56", artist.musicBrainzId)
        assertEquals("daft punk", artist.sortName)
    }
}
