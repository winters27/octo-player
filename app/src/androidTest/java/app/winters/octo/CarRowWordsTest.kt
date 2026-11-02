package app.winters.octo

import android.graphics.Bitmap
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.OnlineAlbum
import app.winters.octo.discovery.Station
import app.winters.octo.discovery.albumShareLine
import app.winters.octo.playback.ArtworkProvider
import app.winters.octo.playback.CarGlyph
import app.winters.octo.playback.CarLook
import app.winters.octo.playback.CarNode
import app.winters.octo.playback.CarStyle
import app.winters.octo.playback.carResumeLine
import app.winters.octo.server.RemoteQueue
import app.winters.octo.server.ResumeOffer
import app.winters.octo.subsonic.ChartChoice
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// The words a car shows for a row, as Media3 turns the item into the old
// browser's description: the title, and the second line.
@RunWith(AndroidJUnit4::class)
class CarRowWordsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val look = CarLook(context)

    private val convert = Class.forName("androidx.media3.session.LegacyConversions")
        .getDeclaredMethod("convertToBrowserItem", MediaItem::class.java, Bitmap::class.java)
        .apply { isAccessible = true }

    private fun shown(item: MediaItem): Pair<String?, String?> {
        val legacy = convert.invoke(null, item, null)!!
        val description = legacy.javaClass.getMethod("getDescription").invoke(legacy)!!
        fun text(name: String) = (description.javaClass.getMethod(name).invoke(description) as CharSequence?)?.toString()
        return text("getTitle") to text("getSubtitle")
    }

    @Test
    fun theQueueToPickUpSaysWhereItWasLeft() {
        val offer = ResumeOffer("Desk PC", "Song Title", "Some Artist", RemoteQueue(emptyList(), 0, 0, 1, null))
        assertEquals("Song Title" to carResumeLine("Desk PC"), shown(look.resume(offer)))
    }

    @Test
    fun anAlbumTheLibraryHoldsPartOfSaysHowMuch() {
        val album = OnlineAlbum("ext-1", "Album Title", "Some Artist", null, 2020, songCount = 12, artwork = null, ownedCount = 3)
        assertEquals("Album Title" to albumShareLine(3, 12), shown(look.onlineAlbum(album)))
    }

    @Test
    fun anAlbumBeyondTheLibraryNamesItsArtist() {
        val album = OnlineAlbum("ext-2", "Album Title", "Some Artist", null, 2020, songCount = 12, artwork = null)
        assertEquals("Album Title" to "Some Artist", shown(look.onlineAlbum(album)))
    }

    @Test
    fun songsAndAlbumsNameTheirArtist() {
        val track = TrackEntity(
            id = "device:1", sourceId = "device", nativeId = "1", title = "Song Title", searchKey = "", sortKey = "",
            artist = "Some Artist", artistId = "", album = "Album Title", albumId = "", trackNo = 1, discNo = 1, year = 2020,
            durationMs = 1_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
        )
        assertEquals("Song Title" to "Some Artist", shown(look.song(track, CarNode.Liked, outside = false, downloaded = false)))
        val album = AlbumEntity(
            id = "device:a1", sourceId = "device", nativeId = "a1", title = "Album Title", searchKey = "", sortKey = "",
            artist = "Some Artist", artistId = "", year = 2020, songCount = 1, durationMs = 1, addedAt = 0, artwork = null,
        )
        assertEquals("Album Title" to "Some Artist", shown(look.album(album)))
    }

    private fun opens(item: MediaItem) = item.mediaMetadata.isBrowsable == true && item.mediaMetadata.isPlayable == false

    private fun extra(item: MediaItem, key: String) = item.mediaMetadata.extras?.getInt(key, -1) ?: -1

    // A car plays anything it can also open, on a tap: a list opens to its
    // songs, and only a station plays.
    @Test
    fun listsAndAlbumsOpenAndAStationOnlyPlays() {
        val album = AlbumEntity(
            id = "device:a1", sourceId = "device", nativeId = "a1", title = "Album Title", searchKey = "", sortKey = "",
            artist = "Some Artist", artistId = "", year = 2020, songCount = 1, durationMs = 1, addedAt = 0, artwork = null,
        )
        assertTrue(opens(look.album(album)))
        assertTrue(opens(look.onlineAlbum(OnlineAlbum("ext-1", "Album Title", "Some Artist", null, 2020, songCount = 12, artwork = null))))
        assertTrue(opens(look.liked(3, emptyList())))
        assertTrue(opens(look.chart(ChartChoice(id = "us", name = "Top 50"))))
        assertTrue(opens(look.station(Station("og1", "Deep Cuts", null, songCount = 50))))
        val radio = look.station(Station(RADIO_ID, "Logic Radio", null, songCount = 47))
        assertTrue(radio.mediaMetadata.isPlayable == true && radio.mediaMetadata.isBrowsable == false)
        assertEquals("Logic Radio" to "47 songs", shown(radio))
        assertTrue(look.radio(CarNode.Station(RADIO_ID, "Logic Radio")).mediaMetadata.isBrowsable == false)
    }

    // Home lists a station among covers, as a tile; a list is a tile by
    // Home's own style.
    @Test
    fun homeShowsAStationAsATile() {
        assertEquals(CarStyle.Grid.value, extra(look.homeStation(Station(RADIO_ID, "Logic Radio", null)), MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM))
        assertEquals(-1, extra(look.homeStation(Station("og1", "Deep Cuts", null)), MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM))
    }

    // Anything that can show as a tile has a picture, never the car's
    // broken-picture sign.
    @Test
    fun whatCanBeATileAlwaysHasAPicture() {
        val album = AlbumEntity(
            id = "device:a2", sourceId = "device", nativeId = "a2", title = "No Cover", searchKey = "", sortKey = "",
            artist = "Some Artist", artistId = "", year = 2020, songCount = 1, durationMs = 1, addedAt = 0, artwork = null,
        )
        assertEquals(ArtworkProvider.carGlyphUri(CarGlyph.Album), look.album(album).mediaMetadata.artworkUri)
        assertEquals(ArtworkProvider.carGlyphUri(CarGlyph.Playlist), look.chart(ChartChoice(id = "us", name = "Top 50")).mediaMetadata.artworkUri)
        assertEquals(ArtworkProvider.carGlyphUri(CarGlyph.Radio), look.radio(CarNode.Station(RADIO_ID, "Logic Radio")).mediaMetadata.artworkUri)
        assertNotNull(look.part(CarNode.Part(0, CarNode.Albums), 0..95, "Album 0001", CarStyle.Grid, emptyList()).mediaMetadata.artworkUri)
    }

    // A car heads the page a row opens with the row's title: "See all" is
    // titled with what it opens.
    @Test
    fun seeAllIsARowOnAQuietTileNamedForWhatItOpens() {
        val item = look.seeAll(CarNode.Shelf("recentlyAdded"), "Recently added", CarStyle.Grid, CarStyle.List)
        assertEquals("Recently added" to "See all", shown(item))
        assertEquals(CarStyle.List.value, extra(item, MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM))
        assertEquals(CarStyle.Grid.value, extra(item, MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        assertEquals(CarStyle.List.value, extra(item, MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
        assertEquals(ArtworkProvider.carGlyphUri(CarGlyph.More), item.mediaMetadata.artworkUri)
    }

    @Test
    fun aLetterSaysHowManyItHoldsAndOpensAsItsListShows() {
        val item = look.heading(CarNode.Heading("A", CarNode.Albums), CarStyle.Grid, listOf("s:server:a", "s:server:b"), "42 albums")
        assertEquals("A" to "42 albums", shown(item))
        assertEquals(CarStyle.Grid.value, extra(item, MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        assertNotNull(item.mediaMetadata.artworkUri)
        val part = look.part(CarNode.Part(1, CarNode.Albums), 96..191, "Album 0097", CarStyle.Grid, emptyList())
        assertEquals("97 to 192" to "Album 0097", shown(part))
        assertEquals(CarStyle.Grid.value, extra(part, MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
    }

    // The Library's Favorites row has the phone's outline heart, and the
    // Playlists tab lists its lists as rows.
    @Test
    fun theLibraryAndPlaylistsLookAsOnThePhone() = runBlocking {
        val car = EntryPointAccessors.fromApplication(context.applicationContext, CarTestEntry::class.java).car()
        assertTrue(car.lookup("lib:favorites", null)!!.mediaMetadata.artworkUri.toString().endsWith("/sym_favorite"))
        assertEquals(CarStyle.List.value, extra(car.lookup("tab:playlists", null)!!, MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
    }

    private companion object {
        // One of Octo's stations: 22 characters starting "or".
        const val RADIO_ID = "or01234567890123456789"
    }
}
