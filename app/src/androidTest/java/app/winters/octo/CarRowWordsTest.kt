package app.winters.octo

import android.graphics.Bitmap
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.OnlineAlbum
import app.winters.octo.discovery.albumShareLine
import app.winters.octo.playback.CarLook
import app.winters.octo.playback.CarNode
import app.winters.octo.playback.carResumeLine
import app.winters.octo.server.RemoteQueue
import app.winters.octo.server.ResumeOffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// The words a car shows for a row, as Media3 turns the item into the old
// browser's description: the title, and the second line.
@RunWith(AndroidJUnit4::class)
class CarRowWordsTest {
    private val look = CarLook(InstrumentationRegistry.getInstrumentation().targetContext)

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
}
