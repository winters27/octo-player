package app.winters.octo.playback

import app.winters.octo.health.HealthCheck
import app.winters.octo.query.LibraryQuery
import app.winters.octo.sort.AlbumSort
import app.winters.octo.sort.ArtistSort
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.search.SearchFilter
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ListSpecJsonTest {
    private val songs = ListSpec.Songs(SongScope.All, SortOrder(SongSort.MostPlayed, descending = true), LibraryQuery(text = "Untitled"))

    private val every: List<ListSpec> = listOf(
        ListSpec.Ids(listOf("device:1", "server:h:2"), source = "Songs"),
        ListSpec.Ids(emptyList()),
        songs,
        ListSpec.Songs(SongScope.Liked, SortOrder(SongSort.DateLiked, descending = true)),
        ListSpec.Songs(SongScope.Genre("Electronic"), SortOrder(SongSort.Year, descending = false)),
        ListSpec.Album("al1", withFinds = true),
        ListSpec.Album("al1", withFinds = false),
        ListSpec.AlbumList(listOf("al1", "al2")),
        ListSpec.Artist("ar1", ArtistOrder.Page),
        ListSpec.Artist("ar1", ArtistOrder.Albums),
        ListSpec.Groups(GroupKind.Albums, SortOrder(AlbumSort.RecentlyAdded, descending = true)),
        ListSpec.Groups(GroupKind.Artists, SortOrder(ArtistSort.MostPlayed, descending = true)),
        ListSpec.Playlist("p1"),
        ListSpec.Live("l1", LibraryQuery(text = "rain"), "Rainy days"),
        ListSpec.Live(null, LibraryQuery(), null),
        ListSpec.Folder("Music/Kavinsky", recursive = true),
        ListSpec.Folder("Music/Kavinsky", recursive = false, SortOrder(SongSort.FolderOrder, descending = false)),
        ListSpec.ServerFolder("d1"),
        ListSpec.ServerFolder(null),
        ListSpec.ServerFolder("d1", recursive = false, SortOrder(SongSort.Title, descending = true)),
        ListSpec.ServerFolder(null, recursive = false),
        ListSpec.Downloads(SortOrder(DownloadSort.Size, descending = true)),
        ListSpec.Online(OnlineKind.Album, "x1"),
        ListSpec.Online(OnlineKind.Album, "x1", libraryOnly = true),
        ListSpec.Online(OnlineKind.List, "x2"),
        ListSpec.Online(OnlineKind.Station, "x3"),
        ListSpec.Selection(ListSpec.Playlist("p1"), listOf("4", "9")),
        ListSpec.Selection(songs, listOf("device:3")),
        ListSpec.Health(7, HealthCheck.NoLength),
        ListSpec.ServerQueue,
        ListSpec.Radio("device:1", source = "Radio"),
        ListSpec.Search("untitled", SearchFilter.Songs),
    )

    // Every case reads back as the same spec.
    @Test
    fun everyCaseRoundTrips() {
        every.forEach { spec -> assertEquals(spec.toString(), spec, ListSpec.fromJson(spec.toJson())) }
    }

    // Orders are kept in the sort settings' own words, read back against the
    // list each case orders.
    @Test
    fun ordersAreTheSortSettingsWords() {
        assertTrue(songs.toJson(), "\"order\":\"${SortList.Songs.encode(songs.order)}\"" in songs.toJson())
        assertTrue("\"order\":\"MostPlayed:desc\"" in songs.toJson())
        val groups = ListSpec.Groups(GroupKind.Artists, SortOrder(ArtistSort.Name, descending = false))
        assertTrue("\"order\":\"Name:asc\"" in groups.toJson())
        // An option of the other kind reads back as the order its own list
        // offers under that word.
        assertEquals(AlbumSort.MostPlayed, (ListSpec.fromJson(ListSpec.Groups(GroupKind.Albums, SortOrder(AlbumSort.MostPlayed, true)).toJson()) as ListSpec.Groups).order.by)
        assertEquals(ArtistSort.MostPlayed, (ListSpec.fromJson(ListSpec.Groups(GroupKind.Artists, SortOrder(ArtistSort.MostPlayed, true)).toJson()) as ListSpec.Groups).order.by)
    }

    // An order the list does not offer is refused when written, never read
    // back as another one.
    @Test
    fun anOrderTheListDoesNotOfferIsRefused() {
        assertThrows(SerializationException::class.java) { ListSpec.Songs(SongScope.All, SortOrder(SongSort.DateLiked, true)).toJson() }
        assertThrows(SerializationException::class.java) { ListSpec.Groups(GroupKind.Artists, SortOrder(AlbumSort.Title, false)).toJson() }
        assertThrows(SerializationException::class.java) { ListSpec.Downloads(SortOrder(SongSort.Title, false)).toJson() }
    }

    // The written form names each case, so a queue saved by one run reads
    // back in the next.
    @Test
    fun theFormIsFixed() {
        assertEquals("""{"type":"ids","ids":["a"],"source":"S"}""", ListSpec.Ids(listOf("a"), "S").toJson())
        assertEquals("""{"type":"serverQueue"}""", ListSpec.ServerQueue.toJson())
        // A server folder's gather and a whole online list keep their short
        // form; a folder's own songs name their order.
        assertEquals("""{"type":"serverFolder","folderId":"d1"}""", ListSpec.ServerFolder("d1").toJson())
        assertEquals(ListSpec.ServerFolder("d1"), ListSpec.fromJson("""{"type":"serverFolder","folderId":"d1"}"""))
        assertEquals("""{"type":"online","kind":"Album","id":"x1"}""", ListSpec.Online(OnlineKind.Album, "x1").toJson())
        assertEquals(
            """{"type":"serverFolder","folderId":"d1","recursive":false,"order":"Title:desc"}""",
            ListSpec.ServerFolder("d1", recursive = false, SortOrder(SongSort.Title, descending = true)).toJson(),
        )
        assertEquals(
            """{"type":"songs","scope":"genre","genre":"Jazz","order":"Title:asc"}""",
            ListSpec.Songs(SongScope.Genre("Jazz"), SortOrder(SongSort.Title, false)).toJson(),
        )
    }

    // Every start reads back as the same start, a row's key included.
    @Test
    fun everyStartRoundTrips() {
        val key = RowKey(1, listOf(SqlValue.Int64(3), SqlValue.Text("😀"), SqlValue.of(byteArrayOf(1, 2))), "device:1")
        listOf(
            ListStart.Row("device:1", key),
            ListStart.Row("device:1", null),
            ListStart.Track("device:1"),
            ListStart.At(700, 12_345),
            ListStart.First("device:1", key),
        ).forEach { assertEquals(it, ListStart.fromJson(it.toJson())) }
    }
}
