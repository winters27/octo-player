package app.winters.octo.playback

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.Discovered
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// A spoken request plays a list by what it is: an album, an artist album by
// album, every song, or the few songs found named one by one. Nothing reads
// a list whole to say what plays.
class CarVoiceTest {
    private inner class FakeVoice : VoiceSource {
        var albums = emptyList<AlbumEntity>()
        var artists = emptyList<ArtistEntity>()
        var songs = emptyList<TrackEntity>()
        var allowed = true
        var found: Discovered? = null
        var outsideFails = false
        var outsideAsks = 0
        var empty = emptySet<ListSpec>()
        val looked = mutableListOf<ListSpec>()

        override suspend fun hasSongs(spec: ListSpec): Boolean {
            looked += spec
            return spec !in empty
        }
        override suspend fun albums(key: String) = albums
        override suspend fun artists(key: String) = artists
        override suspend fun songs(key: String) = songs
        override suspend fun outsideAllowed() = allowed
        override suspend fun outside(words: String): Discovered? {
            outsideAsks++
            if (outsideFails) error("down")
            return found
        }
        override suspend fun outsideArtistTop(name: String, artistId: String) = listOf("find:top-$artistId")
    }

    private fun ask(words: String, focus: String? = null, artist: String? = null, album: String? = null, title: String? = null) =
        voiceAsk(words, focus, artist, album, title)

    @Test
    fun theAssistantsFocusPicksTheKind() {
        assertEquals(VoiceFocus.Artist, ask("x", FOCUS_ARTIST).focus)
        assertEquals(VoiceFocus.Album, ask("x", FOCUS_ALBUM).focus)
        assertEquals(VoiceFocus.Song, ask("x", FOCUS_SONG).focus)
        assertEquals(VoiceFocus.Any, ask("x", "vnd.android.cursor.item/genre").focus)
        assertNull(ask("x", artist = "  ").artist)
    }

    @Test
    fun anAlbumNamedExactlyPlaysFromTheLibrary() = runTest {
        val source = FakeVoice().apply { albums = listOf(album("album:9", "Nothing Was the Same", "Drake")) }
        val play = CarVoice(source).play(ask("nothing was the same"))!!
        assertEquals(ListSpec.Album("album:9", withFinds = false), play.spec)
        assertEquals(QueueSource.Played("Nothing Was the Same"), play.source)
        assertEquals(0, source.outsideAsks)
    }

    // As before: an album named exactly that has no songs left plays nothing
    // from the library, so the server is asked.
    @Test
    fun anAlbumWithNoSongsLeftAsksTheServer() = runTest {
        val source = FakeVoice().apply {
            albums = listOf(album("album:9", "Drake", "Drake"))
            empty = setOf(ListSpec.Album("album:9", withFinds = false))
            found = Discovered(listOf(track("find:5")), emptyList(), emptyList())
        }
        assertEquals(ListSpec.Ids(listOf("find:5")), CarVoice(source).play(ask("drake"))!!.spec)
        assertEquals(listOf<ListSpec>(ListSpec.Album("album:9", withFinds = false)), source.looked)
        assertEquals(1, source.outsideAsks)
    }

    @Test
    fun anArtistsFocusPlaysTheirSongsShuffledAlbumByAlbum() = runTest {
        val source = FakeVoice().apply { artists = listOf(artist("artist:3", "Drake")) }
        val play = CarVoice(source).play(ask("drake", FOCUS_ARTIST, artist = "Drake"))!!
        assertTrue(play.shuffle)
        assertEquals(ListSpec.Artist("artist:3", ArtistOrder.Albums), play.spec)
    }

    @Test
    fun withNothingInTheLibraryTheServerIsAsked() = runTest {
        val source = FakeVoice().apply { found = Discovered(listOf(track("find:5")), listOf(onlineAlbum("ext-a", "Rare Album")), emptyList()) }
        val play = CarVoice(source).play(ask("rare album"))!!
        assertEquals(ListSpec.Online(OnlineKind.Album, "ext-a"), play.spec)
        assertEquals(QueueSource.Played("Rare Album"), play.source)
        val songs = CarVoice(source.apply { found = Discovered(listOf(track("find:5")), emptyList(), emptyList()) }).play(ask("something else"))!!
        assertEquals(ListSpec.Ids(listOf("find:5")), songs.spec)
    }

    @Test
    fun anArtistOnlyTheServerHasPlaysTheirTopSongs() = runTest {
        val source = FakeVoice().apply { found = Discovered(emptyList(), emptyList(), listOf(onlineArtist("ext-r", "Newcomer"))) }
        assertEquals(ListSpec.Ids(listOf("find:top-ext-r")), CarVoice(source).play(ask("newcomer"))!!.spec)
    }

    @Test
    fun withLibrarySongsOnlyTheServerIsNotAsked() = runTest {
        val source = FakeVoice().apply {
            allowed = false
            found = Discovered(listOf(track("find:5")), emptyList(), emptyList())
        }
        assertNull(CarVoice(source).play(ask("anything")))
        assertEquals(0, source.outsideAsks)
    }

    @Test
    fun aServerThatFailsFindsNothing() = runTest {
        val source = FakeVoice().apply { outsideFails = true }
        assertNull(CarVoice(source).play(ask("anything")))
    }

    @Test
    fun sayingNothingShufflesTheLibrary() = runTest {
        val source = FakeVoice()
        val play = CarVoice(source).play(ask(""))!!
        assertTrue(play.shuffle)
        assertEquals(EVERY_SONG, play.spec)
        // An empty library has nothing to shuffle.
        assertNull(CarVoice(FakeVoice().apply { empty = setOf(EVERY_SONG) }).play(ask("")))
    }

    @Test
    fun songsFoundAreNamedAtMostAWindowOfThem() = runTest {
        val source = FakeVoice().apply { songs = List(QUEUE_WINDOW + 20) { track("device:$it", "Hello") } }
        val play = CarVoice(source).play(ask("hello"))!!
        assertEquals(QUEUE_WINDOW, (play.spec as ListSpec.Ids).ids.size)
    }
}
