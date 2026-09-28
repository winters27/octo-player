package app.winters.octo.query

import app.winters.octo.catalog.naturalSortKey
import app.winters.octo.sort.Listening
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.byListening
import app.winters.octo.sort.sortedByKey

// Each name's sort key, worked out once per sort. Working it out on every
// comparison froze the desktop's window on a big library.
private class SortKeys {
    private val known = HashMap<String, String>()

    fun of(text: String?): String = known.getOrPut(text.orEmpty()) { naturalSortKey(text.orEmpty()) }
}

// Songs in the chosen order, with the same keys and tie-breaks the phone's
// song lists use: names by their sort key (so "The Beatles" files under B),
// missing years and dates last whichever way, an album's songs kept in
// album order among ties, and plays by the shared listening order.
fun <T> sortSongsBy(songs: List<T>, order: SortOrder, fields: SongFields<T>): List<T> {
    val by = order.by as? SongSort ?: return songs
    val down = order.descending
    val keys = SortKeys()
    // An album's songs in the album's own order: disc, then track.
    val inAlbum = compareBy<T>(
        { keys.of(fields.album(it)) },
        { fields.albumId(it).orEmpty() },
        { fields.disc(it) ?: 0 },
        { fields.track(it) ?: 0 },
        { keys.of(fields.title(it)) },
        { fields.id(it) },
    )
    val byTitle = compareBy<T>({ keys.of(fields.title(it)) }, { keys.of(fields.artist(it)) }, { fields.id(it) })
    return when (by) {
        SongSort.Title -> sortedByKey(songs, down, { keys.of(fields.title(it)) }, byTitle)
        SongSort.Artist -> sortedByKey(songs, down, { keys.of(fields.artist(it)) }, inAlbum)
        SongSort.Album -> sortedByKey(songs, down, { keys.of(fields.album(it)) }, compareBy<T> { keys.of(fields.artist(it)) }.then(inAlbum))
        SongSort.Year -> sortedByKey(songs, down, { fields.year(it)?.takeIf { y -> y > 0 } }, inAlbum)
        SongSort.Length -> sortedByKey(songs, down, { fields.seconds(it).takeIf { d -> d > 0 } }, byTitle)
        SongSort.RecentlyAdded -> sortedByKey(songs, down, { fields.addedAt(it) }, inAlbum)
        SongSort.MostPlayed, SongSort.RecentlyPlayed -> {
            val base = sortedByKey(songs, false, { keys.of(fields.title(it)) }, byTitle)
            val listening = songs.associate { fields.id(it) to Listening(fields.plays(it).toInt(), fields.lastPlayedAt(it) ?: 0) }
            byListening(base, fields::id, listening, mostPlayed = by == SongSort.MostPlayed, descending = down)
        }
        SongSort.Rating -> sortedByKey(songs, down, { fields.rating(it).takeIf { r -> r > 0 } }, byTitle)
        SongSort.Liked, SongSort.DateLiked -> sortedByKey(songs, down, { fields.likedAt(it) }, byTitle)
        SongSort.FolderOrder -> if (down) songs.reversed() else songs
    }
}
