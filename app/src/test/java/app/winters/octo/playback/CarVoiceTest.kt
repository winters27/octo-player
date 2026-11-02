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

class CarVoiceTest {
    private inner class FakeVoice : VoiceSource {
        var albums = emptyList<AlbumEntity>()
        var artists = emptyList<ArtistEntity>()
        var songs = emptyList<TrackEntity>()
        var allowed = true
        var found: Discovered? = null
        var outsideFails = false
        var outsideAsks = 0

        override suspend fun allTrackIds() = listOf("device:1", "device:2")
        override suspend fun albums(key: String) = albums
        override suspend fun artists(key: String) = artists
        override suspend fun songs(key: String) = songs
        override suspend fun albumTrackIds(albumId: String) = listOf("$albumId:t1", "$albumId:t2")
        override suspend fun artistTrackIds(artistId: String) = listOf("$artistId:t1")
        override suspend fun outsideAllowed() = allowed
        override suspend fun outside(words: String): Discovered? {
            outsideAsks++
            if (outsideFails) error("down")
            return found
        }
        override suspend fun outsideAlbumSongs(albumId: String) = listOf("find:$albumId")
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
        assertEquals(listOf("album:9:t1", "album:9:t2"), play.ids)
        assertEquals(QueueSource.Played("Nothing Was the Same"), play.source)
        assertEquals(0, source.outsideAsks)
    }

    @Test
    fun anArtistsFocusPlaysTheirSongsShuffled() = runTest {
        val source = FakeVoice().apply { artists = listOf(artist("artist:3", "Drake")) }
        val play = CarVoice(source).play(ask("drake", FOCUS_ARTIST, artist = "Drake"))!!
        assertTrue(play.shuffle)
    }

    @Test
    fun withNothingInTheLibraryTheServerIsAsked() = runTest {
        val source = FakeVoice().apply { found = Discovered(listOf(track("find:5")), listOf(onlineAlbum("ext-a", "Rare Album")), emptyList()) }
        val play = CarVoice(source).play(ask("rare album"))!!
        assertEquals(listOf("find:ext-a"), play.ids)
        val songs = CarVoice(source.apply { found = Discovered(listOf(track("find:5")), emptyList(), emptyList()) }).play(ask("something else"))!!
        assertEquals(listOf("find:5"), songs.ids)
    }

    @Test
    fun anArtistOnlyTheServerHasPlaysTheirTopSongs() = runTest {
        val source = FakeVoice().apply { found = Discovered(emptyList(), emptyList(), listOf(onlineArtist("ext-r", "Newcomer"))) }
        assertEquals(listOf("find:top-ext-r"), CarVoice(source).play(ask("newcomer"))!!.ids)
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
        val play = CarVoice(FakeVoice()).play(ask(""))!!
        assertTrue(play.shuffle)
        assertEquals(2, play.ids.size)
    }
}
