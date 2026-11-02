package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class CarIdsTest {
    private fun roundTrip(node: CarNode) = assertEquals(node, parseCarId(carId(node)))

    // An id read back prints as itself: the contract a car holds the app to.
    private fun readsBack(id: String) = assertEquals(id, parseCarId(id)?.let(::carId))

    @Test
    fun everyNodeReadsBackAsItself() {
        listOf(
            CarNode.Root, CarNode.Home, CarNode.Radio, CarNode.Playlists, CarNode.Library, CarNode.More,
            CarNode.Resume, CarNode.Charts, CarNode.Albums, CarNode.Artists, CarNode.Songs, CarNode.Genres,
            CarNode.Favorites, CarNode.FavoriteAlbums, CarNode.FavoriteArtists, CarNode.Downloads,
            CarNode.Liked, CarNode.MostPlayed, CarNode.Chart,
            CarNode.Album("device:album:12"), CarNode.Artist("device:artist:4"), CarNode.Playlist("3f2a-uuid"),
            CarNode.LiveList("ll-1"), CarNode.Genre("Hip-Hop/Rap"), CarNode.Shelf("madeForYou"),
            CarNode.OctoList("og0123456789abcdefghij", "Deep Cuts"), CarNode.Station("or0123456789abcdefghij", "Discovery Radio"),
            CarNode.ChartList("trending", "Trending Songs"), CarNode.Live("server:st-1"), CarNode.Near("9617a958-0601-11e8-ae97-52543be04c81"),
            CarNode.OnlineAlbum("ext-deezer-album-302127"), CarNode.OnlineArtist("ext-deezer-artist-27"),
            CarNode.Found("k3x9", FoundKind.Songs), CarNode.Top("k3x9"), CarNode.Retry("k3x9"),
            CarNode.Heading("A", CarNode.Albums), CarNode.Part(3, CarNode.Playlist("3f2a-uuid")),
            CarNode.Part(0, CarNode.Heading("S", CarNode.Songs)),
        ).forEach(::roundTrip)
    }

    @Test
    fun theNewIdsKeepTheirWords() {
        listOf(
            "tab:home", "tab:radio", "tab:playlists", "tab:library", "tab:more", "resume", "charts", "chart",
            "lib:songs", "lib:genres", "lib:favorites", "lib:downloads", "fav:albums", "fav:artists",
            "shelf:madeForYou", "livelist:ll-1", "genre:Hip-Hop%2FRap", "list:og1:Deep+Cuts", "station:or1:Discovery+Radio",
            "chartlist:trending:Trending+Songs", "live:server%3Ast-1", "near:abc", "oalbum:ext-1", "oartist:ext-2",
            "found:k3x9:songs", "top:k3x9", "retry:k3x9", "az:A:tab:albums", "part:3:playlist:3f2a-uuid",
            "song:find:ext-1|in:list:og1:Deep+Cuts", "song:device:4054|in:az:S:lib:songs",
        ).forEach(::readsBack)
    }

    @Test
    fun anOldCarsRecentTabOpensHome() {
        assertEquals(CarNode.Home, parseCarId("tab:recent"))
        assertEquals("tab:home", parseCarId("tab:recent")?.let(::carId))
    }

    @Test
    fun aSongKeepsTheListItWasFoundIn() {
        roundTrip(CarNode.Song("device:4054", CarNode.Album("device:album:12")))
        roundTrip(CarNode.Song("device:4054", CarNode.Playlist("3f2a-uuid")))
        roundTrip(CarNode.Song("device:4054", CarNode.Liked))
        roundTrip(CarNode.Song("device:4054", CarNode.MostPlayed))
        roundTrip(CarNode.Song("find:ext-1", CarNode.Station("or1", "Discovery Radio")))
        roundTrip(CarNode.Song("find:ext-1", CarNode.Found("k3x9", FoundKind.Songs)))
        roundTrip(CarNode.Song("device:4054", null))
    }

    @Test
    fun namesWithMarksOfTheirOwnReadBack() {
        listOf("A|in:B", "50% Off", "Rock @ Night", "#1s", "Ünïcödé: Mix", "東京 Radio", "🎧 Late night 🌙").forEach { name ->
            roundTrip(CarNode.OctoList("og1", name))
            roundTrip(CarNode.Genre(name))
            roundTrip(CarNode.Heading(name, CarNode.Songs))
        }
    }

    @Test
    fun aLongNameIsCutBetweenWholeCharacters() {
        val name = "a".repeat(77) + "🎧🎧🎧"
        val cut = carIdName(name)
        assertTrue(cut.length <= ID_NAME_MAX)
        val back = URLDecoder.decode(cut, Charsets.UTF_8.name())
        assertTrue(name.startsWith(back))
        assertFalse(back.contains('�'))
        val pairs = "🎧".repeat(10)
        val back2 = URLDecoder.decode(carIdName(pairs), Charsets.UTF_8.name())
        assertTrue(pairs.startsWith(back2))
        assertEquals(0, back2.length % 2)
    }

    @Test
    fun theAppsOwnSongIdsAreNotCarIds() {
        assertNull(parseCarId("device:4054"))
        assertNull(parseCarId("found:K3X9:songs"))
        assertNull(parseCarId("found:k3x9:nothing"))
        assertNull(parseCarId("part:-1:tab:albums"))
    }

    @Test
    fun onlyListsOpenAndAStationOnlyPlays() {
        assertFalse(CarNode.Station("or1", "x").isBrowsable)
        assertTrue(CarNode.OctoList("og1", "x").isBrowsable)
        assertTrue(CarNode.Shelf("pinned").isBrowsable)
        roundTrip(CarNode.Shelf(NEAR_SHELF))
        assertTrue(CarNode.Shelf(NEAR_SHELF).isBrowsable)
        assertTrue(CarInput.Kept in carInputsOf(CarNode.Shelf(NEAR_SHELF)) && CarInput.Shelves in carInputsOf(CarNode.Shelf(NEAR_SHELF)))
        assertTrue(CarNode.Part(0, CarNode.Songs).isBrowsable)
        assertFalse(CarNode.Song("device:1", null).isBrowsable)
        assertFalse(CarNode.Resume.isBrowsable)
        assertFalse(CarNode.Live("kept:1").isBrowsable)
    }

    @Test
    fun aListReadsWhatItShows() {
        assertTrue(CarInput.HomeOrder in carInputsOf(CarNode.Home))
        assertTrue(CarInput.LibraryOnly in carInputsOf(CarNode.Album("a")))
        assertFalse(CarInput.Library in carInputsOf(CarNode.Radio))
        assertEquals(carInputsOf(CarNode.Songs), carInputsOf(CarNode.Part(2, CarNode.Heading("S", CarNode.Songs))))
        assertTrue(carInputsOf(CarNode.MostPlayed).isEmpty())
    }

    @Test
    fun playingStartsAtTheChosenSongOrTheTop() {
        val list = listOf("a", "b", "c")
        assertEquals(1, startIndex(list, "b"))
        assertEquals(0, startIndex(list, "gone"))
        assertEquals(0, startIndex(list, null))
    }

    @Test
    fun theServersToolPlaylistsAreKnownByTheirMark() {
        assertTrue(isToolPlaylist("🛠 Delete"))
        assertTrue(isToolPlaylist("🛠️ Better quality"))
        assertFalse(isToolPlaylist("Road trip"))
        assertFalse(isToolPlaylist("Fixing up 🛠"))
    }
}
