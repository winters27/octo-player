package app.winters.octo.desktop.library

import app.winters.octo.catalog.naturalSortKey
import app.winters.octo.sort.AlbumSort
import app.winters.octo.sort.Listening
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.byListening
import app.winters.octo.sort.sortedByKey
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Song
import java.time.Instant
import java.time.OffsetDateTime

// The columns of a song table. Each sortable one orders by the shared sort
// option of the same meaning, so a column runs its first way just as that
// order does on the phone: names A to Z, dates newest first, lengths and
// plays most first.
enum class SongColumn(val title: String, val sort: SongSort?) {
    Number("#", null),
    Title("Title", SongSort.Title),
    Artist("Artist", SongSort.Artist),
    Album("Album", SongSort.Album),
    Year("Year", SongSort.Year),
    Length("Length", SongSort.Length),
    Plays("Plays", SongSort.MostPlayed),
    Added("Added", SongSort.RecentlyAdded),
    Played("Last played", SongSort.RecentlyPlayed),
    ;

    companion object {
        fun of(sort: SongSort): SongColumn? = entries.firstOrNull { it.sort == sort }
    }
}

// The order after clicking a column's heading: the same column turns the
// order round; another starts that column its usual way.
fun SortOrder.clicking(column: SongColumn): SortOrder {
    val option = column.sort ?: return this
    return if (by == option) copy(descending = !descending) else picking(option)
}

// When a song came in, or was last played, in milliseconds, from the
// server's ISO date. Unreadable dates count as missing.
fun serverTime(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}

// Each name's sort key, worked out once per sort. Working it out on every
// comparison froze the window on a big library.
private class SortKeys {
    private val known = HashMap<String, String>()

    fun of(text: String?): String = known.getOrPut(text.orEmpty()) { naturalSortKey(text.orEmpty()) }
}

// An album's songs in the album's own order: disc, then track.
private fun inAlbum(keys: SortKeys): Comparator<Song> = compareBy<Song>({ keys.of(it.album) }, { it.albumId.orEmpty() }, { it.discNumber ?: 0 }, { it.track ?: 0 }, { keys.of(it.title) }, { it.id })

private fun byTitle(keys: SortKeys): Comparator<Song> = compareBy({ keys.of(it.title) }, { keys.of(it.artist) }, { it.id })

// Songs in the chosen order, with the same keys and tie-breaks the phone's
// song lists use: names by their sort key (so "The Beatles" files under B),
// missing years and dates last whichever way, an album's songs kept in
// album order among ties, and plays by the shared listening order.
fun sortSongs(songs: List<Song>, order: SortOrder): List<Song> {
    val by = order.by as? SongSort ?: return songs
    val down = order.descending
    val keys = SortKeys()
    val inAlbum = inAlbum(keys)
    val byTitle = byTitle(keys)
    return when (by) {
        SongSort.Title -> sortedByKey(songs, down, { keys.of(it.title) }, byTitle)
        SongSort.Artist -> sortedByKey(songs, down, { keys.of(it.artist) }, inAlbum)
        SongSort.Album -> sortedByKey(songs, down, { keys.of(it.album) }, compareBy<Song> { keys.of(it.artist) }.then(inAlbum))
        SongSort.Year -> sortedByKey(songs, down, { it.year?.takeIf { y -> y > 0 } }, inAlbum)
        SongSort.Length -> sortedByKey(songs, down, { it.duration.takeIf { d -> d > 0 } }, byTitle)
        SongSort.RecentlyAdded -> sortedByKey(songs, down, { serverTime(it.created) }, inAlbum)
        SongSort.MostPlayed, SongSort.RecentlyPlayed -> {
            val base = sortedByKey(songs, false, { keys.of(it.title) }, byTitle)
            val listening = songs.associate { it.id to Listening((it.playCount ?: 0).toInt(), serverTime(it.played) ?: 0) }
            byListening(base, { it.id }, listening, mostPlayed = by == SongSort.MostPlayed, descending = down)
        }
        SongSort.Rating -> sortedByKey(songs, down, { it.userRating?.takeIf { r -> r > 0 } }, byTitle)
        SongSort.Liked, SongSort.DateLiked -> sortedByKey(songs, down, { serverTime(it.starred) }, byTitle)
        SongSort.FolderOrder -> if (down) songs.reversed() else songs
    }
}

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
