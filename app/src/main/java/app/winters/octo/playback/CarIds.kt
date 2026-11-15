package app.winters.octo.playback

import java.net.URLDecoder
import java.net.URLEncoder
import java.text.BreakIterator

// Everything a car can browse to, and the id each is known by. A song keeps
// the list it was found in, so choosing it plays that list from that song.
sealed interface CarNode {
    data object Root : CarNode
    data object Home : CarNode
    data object Radio : CarNode
    data object Playlists : CarNode
    data object Library : CarNode

    // The tabs that did not fit, as rows, for a car that shows fewer than four.
    data object More : CarNode

    // "Pick up where you left off" on another device.
    data object Resume : CarNode

    // The listener's charts, each opening its songs.
    data object Charts : CarNode
    data object Albums : CarNode
    data object Artists : CarNode
    data object Songs : CarNode
    data object Genres : CarNode
    data object Favorites : CarNode
    data object FavoriteAlbums : CarNode
    data object FavoriteArtists : CarNode
    data object Downloads : CarNode
    data object Liked : CarNode
    data object MostPlayed : CarNode

    // The overall chart: Home's Charts shelf plays from it.
    data object Chart : CarNode

    // One of Home's shelves whole, by its section id.
    data class Shelf(val section: String) : CarNode
    data class Album(val id: String) : CarNode
    data class Artist(val id: String) : CarNode
    data class Playlist(val id: String) : CarNode

    // A playlist folder: its folders, then its playlists.
    data class PlaylistFolder(val id: String) : CarNode
    data class LiveList(val id: String) : CarNode
    data class Genre(val name: String) : CarNode

    // A list Octo made for the listener, and a station Octo runs. The name
    // heads the queue.
    data class OctoList(val id: String, val name: String) : CarNode
    data class Station(val id: String, val name: String) : CarNode
    data class ChartList(val id: String, val name: String) : CarNode

    // A station on the Live radio shelf, by its shelf key, and one near the
    // listener, by its catalog id.
    data class Live(val key: String) : CarNode
    data class Near(val uuid: String) : CarNode

    // An album or artist the server has beyond the library.
    data class OnlineAlbum(val id: String) : CarNode
    data class OnlineArtist(val id: String) : CarNode

    // A search's whole list of one kind, its Top songs, and its "try again".
    data class Found(val token: String, val kind: FoundKind) : CarNode
    data class Top(val token: String) : CarNode
    data class Retry(val token: String) : CarNode

    // The songs or albums of a long list under one of the phone's headings,
    // and one part of a long list.
    data class Heading(val heading: String, val parent: CarNode) : CarNode
    data class Part(val index: Int, val parent: CarNode) : CarNode
    data class Song(val trackId: String, val list: CarNode?) : CarNode
}

// The kinds a search lists, by the word its id uses.
enum class FoundKind(val word: String) {
    Artists("artists"),
    Songs("songs"),
    Albums("albums"),
    Playlists("playlists"),
}

private const val SONG = "song:"
private const val IN = "|in:"

// Names in ids are only for the queue's heading, so they are kept short.
internal const val ID_NAME_MAX = 80

fun carId(node: CarNode): String = when (node) {
    CarNode.Root -> "root"
    CarNode.Home -> "tab:home"
    CarNode.Radio -> "tab:radio"
    CarNode.Playlists -> "tab:playlists"
    CarNode.Library -> "tab:library"
    CarNode.More -> "tab:more"
    CarNode.Resume -> "resume"
    CarNode.Charts -> "charts"
    CarNode.Albums -> "tab:albums"
    CarNode.Artists -> "tab:artists"
    CarNode.Songs -> "lib:songs"
    CarNode.Genres -> "lib:genres"
    CarNode.Favorites -> "lib:favorites"
    CarNode.FavoriteAlbums -> "fav:albums"
    CarNode.FavoriteArtists -> "fav:artists"
    CarNode.Downloads -> "lib:downloads"
    CarNode.Liked -> "liked"
    CarNode.MostPlayed -> "mostplayed"
    CarNode.Chart -> "chart"
    is CarNode.Shelf -> "shelf:" + encode(node.section)
    is CarNode.Album -> "album:${node.id}"
    is CarNode.Artist -> "artist:${node.id}"
    is CarNode.Playlist -> "playlist:${node.id}"
    is CarNode.PlaylistFolder -> "folder:${node.id}"
    is CarNode.LiveList -> "livelist:${node.id}"
    is CarNode.Genre -> "genre:" + encode(node.name)
    is CarNode.OctoList -> "list:" + encode(node.id) + ":" + carIdName(node.name)
    is CarNode.Station -> "station:" + encode(node.id) + ":" + carIdName(node.name)
    is CarNode.ChartList -> "chartlist:" + encode(node.id) + ":" + carIdName(node.name)
    is CarNode.Live -> "live:" + encode(node.key)
    is CarNode.Near -> "near:" + encode(node.uuid)
    is CarNode.OnlineAlbum -> "oalbum:${node.id}"
    is CarNode.OnlineArtist -> "oartist:${node.id}"
    is CarNode.Found -> "found:${node.token}:${node.kind.word}"
    is CarNode.Top -> "top:${node.token}"
    is CarNode.Retry -> "retry:${node.token}"
    is CarNode.Heading -> "az:" + encode(node.heading) + ":" + carId(node.parent)
    is CarNode.Part -> "part:${node.index}:" + carId(node.parent)
    is CarNode.Song -> SONG + node.trackId + (node.list?.let { IN + carId(it) } ?: "")
}

// Reads an id back. A plain song id, as the app itself sends, is not a car
// id, so this returns null for it. An old car may still ask for the Recent
// tab, which is Home now.
fun parseCarId(id: String): CarNode? = when {
    id == "root" -> CarNode.Root
    id == "tab:home" || id == "tab:recent" -> CarNode.Home
    id == "tab:radio" -> CarNode.Radio
    id == "tab:playlists" -> CarNode.Playlists
    id == "tab:library" -> CarNode.Library
    id == "tab:more" -> CarNode.More
    id == "resume" -> CarNode.Resume
    id == "charts" -> CarNode.Charts
    id == "tab:albums" -> CarNode.Albums
    id == "tab:artists" -> CarNode.Artists
    id == "lib:songs" -> CarNode.Songs
    id == "lib:genres" -> CarNode.Genres
    id == "lib:favorites" -> CarNode.Favorites
    id == "fav:albums" -> CarNode.FavoriteAlbums
    id == "fav:artists" -> CarNode.FavoriteArtists
    id == "lib:downloads" -> CarNode.Downloads
    id == "liked" -> CarNode.Liked
    id == "mostplayed" -> CarNode.MostPlayed
    id == "chart" -> CarNode.Chart
    id.startsWith(SONG) -> songOf(id.removePrefix(SONG))
    id.startsWith("shelf:") -> decode(id.removePrefix("shelf:"))?.let { CarNode.Shelf(it) }
    id.startsWith("album:") -> CarNode.Album(id.removePrefix("album:"))
    id.startsWith("artist:") -> CarNode.Artist(id.removePrefix("artist:"))
    id.startsWith("playlist:") -> CarNode.Playlist(id.removePrefix("playlist:"))
    id.startsWith("folder:") -> CarNode.PlaylistFolder(id.removePrefix("folder:"))
    id.startsWith("livelist:") -> CarNode.LiveList(id.removePrefix("livelist:"))
    id.startsWith("genre:") -> decode(id.removePrefix("genre:"))?.let { CarNode.Genre(it) }
    id.startsWith("list:") -> named(id.removePrefix("list:"))?.let { (key, name) -> CarNode.OctoList(key, name) }
    id.startsWith("station:") -> named(id.removePrefix("station:"))?.let { (key, name) -> CarNode.Station(key, name) }
    id.startsWith("chartlist:") -> named(id.removePrefix("chartlist:"))?.let { (key, name) -> CarNode.ChartList(key, name) }
    id.startsWith("live:") -> decode(id.removePrefix("live:"))?.let { CarNode.Live(it) }
    id.startsWith("near:") -> decode(id.removePrefix("near:"))?.let { CarNode.Near(it) }
    id.startsWith("oalbum:") -> CarNode.OnlineAlbum(id.removePrefix("oalbum:"))
    id.startsWith("oartist:") -> CarNode.OnlineArtist(id.removePrefix("oartist:"))
    id.startsWith("found:") -> foundOf(id.removePrefix("found:"))
    id.startsWith("top:") -> id.removePrefix("top:").takeIf(::isToken)?.let { CarNode.Top(it) }
    id.startsWith("retry:") -> id.removePrefix("retry:").takeIf(::isToken)?.let { CarNode.Retry(it) }
    id.startsWith("az:") -> headingOf(id.removePrefix("az:"))
    id.startsWith("part:") -> partOf(id.removePrefix("part:"))
    else -> null
}

// Whether the car can open it as a list. One of Octo's stations only plays.
val CarNode.isBrowsable: Boolean
    get() = when (this) {
        is CarNode.Song, CarNode.Resume, is CarNode.Live, is CarNode.Near, is CarNode.Station -> false
        else -> true
    }

private fun songOf(rest: String): CarNode.Song {
    val cut = rest.indexOf(IN)
    if (cut < 0) return CarNode.Song(rest, null)
    return CarNode.Song(rest.substring(0, cut), parseCarId(rest.substring(cut + IN.length)))
}

// "<encoded id>:<encoded name>", for lists and stations.
private fun named(rest: String): Pair<String, String>? {
    val cut = rest.indexOf(':')
    if (cut <= 0) return null
    val key = decode(rest.substring(0, cut)) ?: return null
    val name = decode(rest.substring(cut + 1)) ?: return null
    return key to name
}

private fun foundOf(rest: String): CarNode.Found? {
    val cut = rest.indexOf(':')
    if (cut <= 0) return null
    val token = rest.substring(0, cut).takeIf(::isToken) ?: return null
    val kind = FoundKind.entries.firstOrNull { it.word == rest.substring(cut + 1) } ?: return null
    return CarNode.Found(token, kind)
}

private fun headingOf(rest: String): CarNode.Heading? {
    val cut = rest.indexOf(':')
    if (cut <= 0) return null
    val heading = decode(rest.substring(0, cut)) ?: return null
    val parent = parseCarId(rest.substring(cut + 1)) ?: return null
    return CarNode.Heading(heading, parent)
}

private fun partOf(rest: String): CarNode.Part? {
    val cut = rest.indexOf(':')
    if (cut <= 0) return null
    val index = rest.substring(0, cut).toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val parent = parseCarId(rest.substring(cut + 1)) ?: return null
    return CarNode.Part(index, parent)
}

// A search token: lower-case letters and digits.
internal fun isToken(text: String): Boolean = text.isNotEmpty() && text.all { it in 'a'..'z' || it in '0'..'9' }

// A name as it goes in an id: encoded, then cut to ID_NAME_MAX characters
// between whole characters, so it always reads back as the start of the name.
internal fun carIdName(name: String): String {
    val out = StringBuilder()
    val breaks = BreakIterator.getCharacterInstance().apply { setText(name) }
    var start = breaks.first()
    var end = breaks.next()
    while (end != BreakIterator.DONE) {
        val piece = encode(name.substring(start, end))
        if (out.length + piece.length > ID_NAME_MAX) break
        out.append(piece)
        start = end
        end = breaks.next()
    }
    return out.toString()
}

private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

private fun decode(text: String): String? = runCatching { URLDecoder.decode(text, Charsets.UTF_8.name()) }.getOrNull()

// Where to start in a list of songs: at the chosen song, or the top if it
// is no longer there.
fun startIndex(trackIds: List<String>, chosen: String?): Int =
    chosen?.let { trackIds.indexOf(it) }?.takeIf { it >= 0 } ?: 0

// The server's library action playlists, named with a leading 🛠 (U+1F6E0).
// A song added to one is acted on, so a car, which only plays, leaves them out.
fun isToolPlaylist(name: String): Boolean = name.startsWith(String(Character.toChars(0x1F6E0)))

// What a car's lists are built from, so a change reaches only the lists
// that read it.
enum class CarInput {
    Library,
    Playlists,
    Liked,
    Favorites,
    LiveLists,
    Kept,
    Shelves,
    HomeOrder,
    Sorts,
    Pins,
    Adopted,
    Downloads,
    LibraryOnly,
    CoverStyle,
    Offer,
    Copy,

    // Whether the stored play counts could be brought up to date: a list
    // that reads them shows the words for why not, until they are.
    Totals,
}

// The inputs a list reads. Plays themselves are none of them, so Recently
// played and Most played stay as they are while the car shows them.
fun carInputsOf(node: CarNode): Set<CarInput> = when (node) {
    CarNode.Home -> setOf(
        CarInput.Library, CarInput.Playlists, CarInput.Favorites, CarInput.Kept, CarInput.Shelves,
        CarInput.HomeOrder, CarInput.Pins, CarInput.Adopted, CarInput.Downloads, CarInput.Offer, CarInput.CoverStyle, CarInput.Copy,
        CarInput.Totals,
    )
    is CarNode.Shelf -> setOf(
        CarInput.Library, CarInput.Playlists, CarInput.Favorites, CarInput.Kept, CarInput.Shelves,
        CarInput.HomeOrder, CarInput.Pins, CarInput.Adopted, CarInput.Downloads, CarInput.Offer, CarInput.CoverStyle, CarInput.Totals,
    )
    CarNode.Radio -> setOf(CarInput.Kept, CarInput.Shelves)
    CarNode.Playlists -> setOf(CarInput.Playlists, CarInput.Liked, CarInput.LiveLists, CarInput.Shelves, CarInput.Sorts, CarInput.CoverStyle)
    CarNode.Albums, CarNode.Artists -> setOf(CarInput.Library, CarInput.Sorts, CarInput.Copy, CarInput.Totals)
    CarNode.Genres -> setOf(CarInput.Library, CarInput.Sorts, CarInput.Copy)
    is CarNode.Genre -> setOf(CarInput.Library, CarInput.Sorts, CarInput.Totals)
    CarNode.Songs -> setOf(CarInput.Library, CarInput.Sorts, CarInput.Downloads, CarInput.Copy, CarInput.Totals)
    CarNode.FavoriteAlbums, CarNode.FavoriteArtists -> setOf(CarInput.Favorites, CarInput.Sorts)
    CarNode.Liked -> setOf(CarInput.Liked, CarInput.Sorts, CarInput.Downloads, CarInput.Totals)
    CarNode.Downloads -> setOf(CarInput.Downloads, CarInput.Sorts)
    is CarNode.Album -> setOf(CarInput.Library, CarInput.Adopted, CarInput.LibraryOnly, CarInput.Downloads)
    is CarNode.Artist -> setOf(CarInput.Library, CarInput.Sorts, CarInput.Adopted, CarInput.LibraryOnly, CarInput.Totals)
    is CarNode.Playlist -> setOf(CarInput.Playlists, CarInput.Downloads, CarInput.Adopted)
    is CarNode.PlaylistFolder -> setOf(CarInput.Playlists, CarInput.Sorts, CarInput.CoverStyle)
    is CarNode.LiveList -> setOf(CarInput.Library, CarInput.LiveLists, CarInput.Liked, CarInput.Totals)
    CarNode.Charts, CarNode.Chart, is CarNode.OctoList, is CarNode.Station, is CarNode.ChartList -> setOf(CarInput.Shelves, CarInput.Adopted)
    is CarNode.OnlineAlbum, is CarNode.OnlineArtist, is CarNode.Found, is CarNode.Top, is CarNode.Retry -> setOf(CarInput.Adopted, CarInput.LibraryOnly)
    is CarNode.Heading -> carInputsOf(node.parent)
    is CarNode.Part -> carInputsOf(node.parent)
    CarNode.Root, CarNode.Library, CarNode.More, CarNode.Favorites, CarNode.MostPlayed -> emptySet()
    CarNode.Resume, is CarNode.Song, is CarNode.Live, is CarNode.Near -> emptySet()
}
