package app.winters.octo

import android.graphics.Bitmap
import android.os.Parcel
import android.os.Parcelable
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.Discovered
import app.winters.octo.discovery.OnlineAlbum
import app.winters.octo.discovery.OnlineArtist
import app.winters.octo.discovery.RankedTrack
import app.winters.octo.discovery.RankedTracks
import app.winters.octo.discovery.Station
import app.winters.octo.playback.CAR_ANSWER_BUDGET
import app.winters.octo.home.HomeSection
import app.winters.octo.playback.CarHomeParts
import app.winters.octo.playback.CarSearchAnswer
import app.winters.octo.playback.CarShelf
import app.winters.octo.playback.CarStyle
import app.winters.octo.playback.LibraryFound
import app.winters.octo.playback.carHomeAnswer
import app.winters.octo.server.RemoteQueue
import app.winters.octo.server.ResumeOffer
import dagger.hilt.android.EntryPointAccessors
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

    // Home with every shelf full of the longest real items, and the resume
    // row, fits one answer and Media3 keeps all of it.
    @Test
    fun homeOfTheLongestRealItemsFitsOneAnswerAndIsNeverCut() {
        val ref = "s:server:" + long(199)
        val album = AlbumEntity(
            id = "server:x:album:" + long(120), sourceId = "", nativeId = "", title = long(400), searchKey = "", sortKey = "",
            artist = long(400), artistId = "", year = 1, songCount = 1, durationMs = 1, addedAt = 0, artwork = "station-art:" + long(900),
        )
        val track = TrackEntity(
            id = "find:" + long(120), sourceId = "server:x", nativeId = long(120), title = long(400), searchKey = "", sortKey = "",
            artist = long(400), artistId = "", album = long(400), albumId = "", trackNo = 1, discNo = 1, year = 2020, durationMs = 1,
            addedAt = 0, mimeType = null, sizeBytes = null, artwork = ref, uri = null, explicit = true,
        )
        val artist = ArtistEntity("server:x:artist:" + long(120), "server:x", long(400), "", "", 1, 1, ref)
        val station = Station("or" + long(120), long(400), ref, songCount = 99)
        fun itemsFor(section: HomeSection): List<MediaItem> = List(20) {
            when (section) {
                HomeSection.MostPlayed, HomeSection.Charts -> look.song(track, CarNode.MostPlayed, outside = true, downloaded = true)
                HomeSection.MadeForYou, HomeSection.Stations, HomeSection.YearMixes, HomeSection.GenreMixes,
                HomeSection.ArtistMixes, HomeSection.CountryMixes, HomeSection.InternetRadio,
                -> look.homeStation(station)
                HomeSection.Artists -> look.artist(artist)
                else -> look.album(album)
            }
        }
        val parts = CarHomeParts(
            grouped = look::grouped,
            seeAll = { shelf -> look.seeAll(CarNode.Shelf(shelf.id), long(400), CarStyle.Grid, CarStyle.Grid) },
            row = { shelf -> look.folder(CarNode.Shelf(shelf.id), shelf.title, browsable = CarStyle.Grid, playable = CarStyle.Grid) },
        )
        val resume = look.resume(ResumeOffer(long(400), long(400), long(400), RemoteQueue(emptyList(), 0, 0, 1, null)))
        val answer = carHomeAnswer(resume, HomeSection.entries.map { CarShelf(it, itemsFor(it)) }, parts)
        assertEquals(resume.mediaId, answer.first().mediaId)
        assertTrue(answer.sumOf(::carBytes) <= CAR_ANSWER_BUDGET)
        val truncate = Class.forName("androidx.media3.session.MediaUtils")
            .getDeclaredMethod("truncateListBySize", List::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val sent = answer.map { convert.invoke(null, it, null) as Parcelable }
        assertEquals(sent.size, (truncate.invoke(null, sent, 256 * 1024) as List<*>).size)
    }

    // A search has no parts: its answer at its biggest, every kind full and
    // every item at its longest, fits one answer and Media3 keeps all of it.
    @Test
    fun theBiggestSearchAnswerFitsOneAnswerAndIsNeverCut() {
        val car = EntryPointAccessors.fromApplication(context.applicationContext, CarTestEntry::class.java).car()
        val ref = "s:server:" + long(199)
        fun song(id: String) = TrackEntity(
            id = id + long(120), sourceId = "server:x", nativeId = long(120), title = long(400), searchKey = "", sortKey = "",
            artist = long(400), artistId = "", album = long(400), albumId = "", trackNo = 1, discNo = 1, year = 2020, durationMs = 1,
            addedAt = 0, mimeType = null, sizeBytes = null, artwork = ref, uri = null, explicit = true,
        )
        fun album(n: Int) = AlbumEntity(
            id = "server:x:album:$n" + long(120), sourceId = "", nativeId = "", title = long(400), searchKey = "", sortKey = "",
            artist = long(400), artistId = "", year = 1, songCount = 1, durationMs = 1, addedAt = 0, artwork = "station-art:" + long(900),
        )
        fun outsideAlbum(n: Int) = OnlineAlbum("ext$n" + long(120), long(400), long(400), null, 2020, 12, "station-art:" + long(900), ownedCount = 3)
        val answer = CarSearchAnswer(
            token = "t0k3n",
            query = long(400),
            account = "a1",
            library = LibraryFound(
                artists = List(10) { ArtistEntity("server:x:artist:$it" + long(120), "server:x", long(400), "", "", 1, 1, ref) },
                albums = List(20, ::album),
                songs = List(50) { song("server:x:track:$it") },
                playlists = List(10) { PlaylistSummary("pl$it" + long(120), long(400), 99, 1, listOf(ref), family = true) },
            ),
            outside = Discovered(
                songs = List(50) { song("find:$it") },
                albums = List(20) { outsideAlbum(it) },
                artists = List(10) { OnlineArtist("ext-r$it" + long(120), long(400), 1, ref) },
                partAlbums = List(10) { outsideAlbum(100 + it) },
            ),
            outsideFailed = false,
            top = RankedTracks(long(400), "lastfm", List(20) { RankedTrack(it + 1, song("find:top$it"), 1) }),
        )
        val items = car.searchItems(answer)
        assertTrue(items.sumOf(::carBytes) <= CAR_ANSWER_BUDGET)
        val truncate = Class.forName("androidx.media3.session.MediaUtils")
            .getDeclaredMethod("truncateListBySize", List::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val sent = items.map { convert.invoke(null, it, null) as Parcelable }
        assertEquals(sent.size, (truncate.invoke(null, sent, 256 * 1024) as List<*>).size)
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
