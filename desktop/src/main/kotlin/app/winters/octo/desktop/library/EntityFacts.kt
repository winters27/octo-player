package app.winters.octo.desktop.library

import app.winters.octo.desktop.nav.FolderStep
import app.winters.octo.desktop.player.PlayFormat
import app.winters.octo.desktop.player.SongFormat
import app.winters.octo.desktop.player.formatLabel
import app.winters.octo.desktop.player.libraryFormat
import app.winters.octo.sort.SortList
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.DiscTitle
import app.winters.octo.subsonic.Song

// What the album, artist, genre and folder pages work out from the library
// and the server's answers, kept apart from the drawing so it can be tested.

// Albums.

// What an album's files are, in one short phrase: "FLAC 24/96" when every
// song shares it, the format alone when only their quality differs ("MP3"
// for songs at several bit rates), "Mixed formats" when the formats
// differ. Null when the library does not say.
fun formatSummary(songs: List<Song>): String? {
    val formats = songs.mapNotNull(::libraryFormat)
    if (formats.isEmpty()) return null
    formats.mapNotNull { formatLabel(PlayFormat(it, null)) }.distinct().singleOrNull()?.let { return it }
    val codecs = formats.map { it.codec }.distinct()
    return if (codecs.size == 1) formatLabel(PlayFormat(SongFormat(codecs.single(), formats.first().lossless), null)) else "Mixed formats"
}

// A disc's heading, with the album's own name for it when it has one:
// "Disc 2", "Disc 2 · Live at Wembley".
fun discHeading(disc: Int, titles: List<DiscTitle>): String {
    val title = titles.firstOrNull { it.disc == disc }?.title?.trim()?.takeIf(String::isNotEmpty)
    return if (title == null) "Disc $disc" else "Disc $disc · $title"
}

// The heading above each row of an album's table: one where a disc starts,
// null elsewhere. An album shows them when it has more than one disc, or
// when its one disc has a name.
fun discHeadings(songs: List<Song>, titles: List<DiscTitle>): List<String?> {
    val discs = songs.mapNotNull { it.discNumber }.distinct()
    val named = titles.any { it.title.isNotBlank() }
    if (discs.size < 2 && !named) return songs.map { null }
    return songs.mapIndexed { index, song ->
        val disc = song.discNumber ?: return@mapIndexed null
        if (index == 0 || songs[index - 1].discNumber != disc) discHeading(disc, titles) else null
    }
}

// Artists.

// Albums by someone else that the artist is on: songs credited to them,
// as the song's artist or one of its artists, on an album filed under
// another artist. Newest first, as their own albums are.
fun appearsOn(index: LibraryIndex, artistId: String, own: Set<String>): List<Album> {
    val ids = HashSet<String>()
    index.songs.forEach { song ->
        val albumId = song.albumId ?: return@forEach
        if (albumId in own) return@forEach
        if (song.artistId == artistId || song.artists.any { it.id == artistId }) ids += albumId
    }
    if (ids.isEmpty()) return emptyList()
    val albums = index.albums.filter { it.id in ids && it.artistId != artistId }
    return sortAlbums(albums, SortList.ArtistAlbums.default)
}

// An artist's songs album by album in the order their albums are shown,
// each album in disc and track order, from the songs the library holds.
fun songsAlbumByAlbum(songs: List<Song>, albums: List<Album>): List<Song> {
    val byAlbum = songs.groupBy { it.albumId }
    return albums.flatMap { album ->
        byAlbum[album.id].orEmpty().sortedWith(compareBy<Song>({ it.discNumber ?: 1 }, { it.track ?: Int.MAX_VALUE }))
    }
}

// The letter drawn in place of an artist's picture: the first letter or
// digit of their name.
fun monogramOf(name: String): String =
    name.firstOrNull(Char::isLetterOrDigit)?.uppercaseChar()?.toString() ?: "?"

// Genres.

// What the library has in one genre: its songs, its albums A to Z, and the
// artists of its songs, the most songs first.
class GenreContents(val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>)

fun genreContents(index: LibraryIndex, name: String): GenreContents {
    val songs = index.songsInGenre(name)
    val albumIds = songs.mapNotNullTo(HashSet()) { it.albumId }
    val albums = sortedByName(index.albums.filter { it.id in albumIds }) { it.name }
    val known = index.artists.associateBy { it.id }
    val counts = LinkedHashMap<String, Int>()
    val names = HashMap<String, String>()
    songs.forEach { song ->
        val id = song.artistId?.takeIf(String::isNotEmpty) ?: return@forEach
        counts[id] = (counts[id] ?: 0) + 1
        names.putIfAbsent(id, song.artist.orEmpty())
    }
    val artists = counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { names[it.key].orEmpty().lowercase() })
        .map { (id, _) -> known[id] ?: Artist(id, names[id].orEmpty()) }
    return GenreContents(songs, albums, artists)
}

// The covers for a genre's picture, most played albums first: four
// different ones make a 2 by 2 mosaic; with fewer than four, the first
// alone, so a mosaic never repeats a cover. None when no album has one.
fun mosaicCovers(albums: List<Album>): List<String> {
    val covers = albums.sortedByDescending { it.playCount }.mapNotNull { it.coverArt?.takeIf(String::isNotEmpty) }.distinct()
    return if (covers.size >= 4) covers.take(4) else covers.take(1)
}

// Every genre's picture at once, by the genre's name in lower case, in one
// pass over the library.
fun genreCovers(index: LibraryIndex): Map<String, List<String>> {
    val albumIds = HashMap<String, LinkedHashSet<String>>()
    index.songs.forEach { song ->
        val albumId = song.albumId ?: return@forEach
        LibraryIndex.genresOf(song).forEach { genre -> albumIds.getOrPut(genre.lowercase()) { LinkedHashSet() } += albumId }
    }
    val albums = index.albums.associateBy { it.id }
    return albumIds.mapValues { (_, ids) -> mosaicCovers(ids.mapNotNull(albums::get)) }
}

// Folders.

// The folders above one, from the top down, found by asking for each
// folder's parent in turn. `look` gives a folder's name and its parent, or
// null when the server will not list it. Stops at the top, at a folder seen
// twice, or after `limit` steps.
suspend fun climbFolders(parent: String?, look: suspend (String) -> Pair<String, String?>?, limit: Int = 12): List<FolderStep> {
    val steps = ArrayList<FolderStep>()
    val seen = HashSet<String>()
    var next = parent
    while (next != null && next.isNotEmpty() && seen.add(next) && steps.size < limit) {
        val (name, above) = look(next) ?: break
        steps += FolderStep(next, name)
        next = above
    }
    return steps.reversed()
}

// Where a folder's songs are, as the server gives their paths:
// "Radiohead/Kid A". Null when it gives none, or they are not in one place.
fun folderPath(songs: List<Song>): String? {
    val folders = songs.mapNotNull { song ->
        song.path?.replace('\\', '/')?.takeIf { it.contains('/') }?.substringBeforeLast('/')
    }.distinct()
    return folders.singleOrNull()?.takeIf(String::isNotEmpty)
}

// "12 songs", "3 albums", or nothing, for a folder's line.
fun folderCount(songs: Int?, albums: Int?): String? = when {
    songs != null && songs > 0 -> if (songs == 1) "1 song" else "$songs songs"
    albums != null && albums > 0 -> if (albums == 1) "1 album" else "$albums albums"
    else -> null
}

// Grids.

// How many cards fit across a width, each at least `card` wide.
fun gridColumns(width: Float, card: Float): Int = if (card <= 0f) 1 else (width / card).toInt().coerceAtLeast(1)
