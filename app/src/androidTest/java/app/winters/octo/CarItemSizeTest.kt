package app.winters.octo

import android.graphics.Bitmap
import android.os.Parcel
import android.os.Parcelable
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.Station
import app.winters.octo.playback.CAR_ANSWER_BUDGET
import app.winters.octo.playback.CarLook
import app.winters.octo.playback.CarNode
import app.winters.octo.playback.carBytes
import app.winters.octo.playback.carParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// What a car is sent, measured as Media3 measures it before cutting an
// answer: each item turned into the old browser's item and written to a
// real parcel. The app's estimate must never be under it, so an answer the
// app keeps under budget is never cut.
@RunWith(AndroidJUnit4::class)
class CarItemSizeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val look = CarLook(context)

    // Media3's own conversion, as its legacy bridge calls it.
    private val convert = Class.forName("androidx.media3.session.LegacyConversions")
        .getDeclaredMethod("convertToBrowserItem", MediaItem::class.java, Bitmap::class.java)
        .apply { isAccessible = true }

    private fun parcelled(item: MediaItem): Int {
        val legacy = convert.invoke(null, item, null) as Parcelable
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(legacy, 0)
            return parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    private val reserved = "%:/?#[]@!$&'()*+,;= |" + "🎧東京ü"
    private fun long(n: Int) = (reserved.repeat(n / reserved.length + 1)).take(n)

    // The biggest items the car's builders make: every text at its longest,
    // a server id and a cover reference full of characters that grow when
    // encoded, every extra set.
    private fun worstItems(): List<MediaItem> {
        val ref = "s:server:" + long(199)
        val longRef = "station-art:" + long(900)
        val track = TrackEntity(
            id = "find:" + long(120), sourceId = "server:x", nativeId = long(120), title = long(400), searchKey = "", sortKey = "",
            artist = long(400), artistId = "", album = long(400), albumId = "", trackNo = 1, discNo = 1, year = 2020, durationMs = 1,
            addedAt = 0, mimeType = null, sizeBytes = null, artwork = ref, uri = null, explicit = true,
        )
        val album = AlbumEntity(
            id = "server:x:album:" + long(120), sourceId = "", nativeId = "", title = long(400), searchKey = "", sortKey = "",
            artist = long(400), artistId = "", year = 1, songCount = 1, durationMs = 1, addedAt = 0, artwork = longRef,
        )
        val station = Station("or" + long(120), long(400), ref, songCount = 99)
        val listNode = CarNode.OctoList("og" + long(120), long(400))
        return listOf(
            look.grouped(look.song(track, listNode, outside = true, downloaded = true), long(400)),
            look.grouped(look.album(album), long(400)),
            look.grouped(look.station(station), long(400)),
            look.shownAs(look.grouped(look.song(track, CarNode.Part(12, CarNode.Heading(long(80), CarNode.Songs)), outside = false, downloaded = false), long(400)), app.winters.octo.playback.CarStyle.Grid),
        )
    }

    @Test
    fun theEstimateIsNeverUnderWhatIsSent() {
        worstItems().forEach { item ->
            val real = parcelled(item)
            val estimate = carBytes(item)
            assertTrue("estimate $estimate under $real for ${item.mediaId.take(20)}", estimate >= real)
        }
    }

    @Test
    fun anAnswerKeptUnderBudgetIsNeverCut() {
        val items = List(3_000) { worstItems()[it % 4] }
        val parts = carParts(items.map(::carBytes))
        val truncate = Class.forName("androidx.media3.session.MediaUtils")
            .getDeclaredMethod("truncateListBySize", List::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val limit = 256 * 1024
        parts.forEach { range ->
            val answer = items.subList(range.first, range.last + 1).map { convert.invoke(null, it, null) as Parcelable }
            val kept = truncate.invoke(null, answer, limit) as List<*>
            assertEquals(answer.size, kept.size)
            assertTrue(range.sumOf { carBytes(items[it]) } <= CAR_ANSWER_BUDGET)
        }
    }
}
