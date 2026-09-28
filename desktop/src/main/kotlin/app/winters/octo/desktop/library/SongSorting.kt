package app.winters.octo.desktop.library

import app.winters.octo.catalog.naturalSortKey
import app.winters.octo.query.SubsonicSongs
import app.winters.octo.query.sortSongsBy
import app.winters.octo.server.serverTime
import app.winters.octo.sort.AlbumSort
import app.winters.octo.sort.Listening
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.byListening
import app.winters.octo.sort.sortedByKey
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Song

// The columns of a song table. Each sortable one orders by the shared sort
// option of the same meaning, so a column runs its first way just as that
// order does on the phone: names A to Z, dates newest first, lengths and
// plays most first.
enum class SongColumn(val title: String, val sort: SongSort?) {
    Number("#", null),
    Title("Title", SongSort.Title),
    Artist("Artist", SongSort.Artist),
    Album("Album", SongSort.Album),
    Genre("Genre", null),
    Composer("Composer", null),
    Year("Year", SongSort.Year),
    Added("Added", SongSort.RecentlyAdded),
    Played("Last played", SongSort.RecentlyPlayed),
    Plays("Plays", SongSort.MostPlayed),
    Rating("Rating", SongSort.Rating),
    Format("Format", null),
    Bpm("BPM", null),
    Size("Size", null),
    Favourite("Favourite", SongSort.Liked),
    Length("Length", SongSort.Length),
    ;

    companion object {
        fun of(sort: SongSort): SongColumn? = entries.firstOrNull { it.sort == sort }

        fun named(name: String): SongColumn? = entries.firstOrNull { it.name == name }
    }
}

// The order after clicking a column's heading: the same column turns the
// order round; another starts that column its usual way.
fun SortOrder.clicking(column: SongColumn): SortOrder {
    val option = column.sort ?: return this
    return if (by == option) copy(descending = !descending) else picking(option)
}

// Each name's sort key, worked out once per sort. Working it out on every
// comparison froze the window on a big library.
private class SortKeys {
    private val known = HashMap<String, String>()

    fun of(text: String?): String = known.getOrPut(text.orEmpty()) { naturalSortKey(text.orEmpty()) }
}

// Songs in the chosen order, the shared way (names by their sort key,
// missing years and dates last, an album's songs in album order among
// ties, plays by the listening order), which live lists order by too.
fun sortSongs(songs: List<Song>, order: SortOrder): List<Song> = sortSongsBy(songs, order, SubsonicSongs)

// Albums in the chosen order, by the shared album orders: names by their
// sort key, missing years and dates last, plays by the listening order.
fun sortAlbums(albums: List<Album>, order: SortOrder): List<Album> {
    val by = order.by as? AlbumSort ?: return albums
    val down = order.descending
    val keys = SortKeys()
    val byName: Comparator<Album> = compareBy({ keys.of(it.name) }, { keys.of(it.artist) }, { it.id })
    return when (by) {
        AlbumSort.Title -> sortedByKey(albums, down, { keys.of(it.name) }, byName)
        AlbumSort.Artist -> sortedByKey(albums, down, { keys.of(it.artist) }, compareBy<Album>({ it.year ?: 0 }, { keys.of(it.name) }, { it.id }))
        AlbumSort.Year -> sortedByKey(albums, down, { it.year?.takeIf { y -> y > 0 } }, byName)
        AlbumSort.RecentlyAdded -> sortedByKey(albums, down, { serverTime(it.created) }, byName)
        AlbumSort.SongCount -> sortedByKey(albums, down, { it.songCount }, byName)
        AlbumSort.Length -> sortedByKey(albums, down, { it.duration.takeIf { d -> d > 0 } }, byName)
        AlbumSort.MostPlayed, AlbumSort.RecentlyPlayed -> {
            val base = sortedByKey(albums, false, { keys.of(it.name) }, byName)
            val listening = albums.associate { it.id to Listening(it.playCount.toInt(), serverTime(it.played) ?: 0) }
            byListening(base, { it.id }, listening, mostPlayed = by == AlbumSort.MostPlayed, descending = down)
        }
    }
}

// Things A to Z by the sort key of their name, each key worked out once.
fun <T> sortedByName(items: List<T>, name: (T) -> String): List<T> =
    items.map { naturalSortKey(name(it)) to it }.sortedBy { it.first }.map { it.second }

// Songs that have play data, most recently played first: the History page.
fun recentlyPlayed(songs: List<Song>): List<Song> =
    songs.mapNotNull { song -> serverTime(song.played)?.let { song to it } }.sortedByDescending { it.second }.map { it.first }

// "3:07", or "1:02:03" for an hour or more.
fun lengthText(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// "2 h 14 min" or "38 min", for a whole album or playlist.
fun totalLengthText(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}
