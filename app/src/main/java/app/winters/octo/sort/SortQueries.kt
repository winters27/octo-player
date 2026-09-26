package app.winters.octo.sort

// The SQL behind each list's order. Every piece of it is a constant here,
// picked by the option; nothing the listener types or the library holds is
// ever written into the text. Values such as a genre's name are bound to
// ? marks instead.
//
// Only the first key follows the chosen direction. The keys after it break
// ties the same way whichever way the list runs, so an album's songs stay in
// album order in a list of newest first.

// A query and the values for its ? marks, in order.
data class SqlQuery(val sql: String, val args: List<String>, val readsLikes: Boolean)

// Which songs a song query lists.
sealed interface SongScope {
    data object All : SongScope
    data class Genre(val name: String) : SongScope
    data object Liked : SongScope
}

// One key of an ORDER BY. A key that follows the direction runs the way the
// listener chose; any other always runs as written.
private class Key(val expr: String, val follows: Boolean = false, val descending: Boolean = false)

private fun chosen(expr: String) = Key(expr, follows = true)

// Missing values (no year, no rating) go last whichever way the list runs.
private fun missingLast(test: String) = Key(test)

private fun orderBy(keys: List<Key>, descending: Boolean): String =
    keys.joinToString(", ") { key ->
        val down = if (key.follows) descending else key.descending
        if (down) "${key.expr} DESC" else key.expr
    }

// Songs. `t` is the song; `al` and `ar` its album and album artist.
private const val SONG_TITLE = "t.sortKey"
private const val SONG_ARTIST = "COALESCE(ar.sortKey, LOWER(t.artist))"
private const val SONG_ALBUM = "COALESCE(al.sortKey, LOWER(t.album))"
private const val SONG_LIKED = "EXISTS(SELECT 1 FROM liked_track lk WHERE lk.trackId = t.id)"
private const val SONG_LIKED_AT = "(SELECT lk.likedAt FROM liked_track lk WHERE lk.trackId = t.id)"

// An album's songs in the album's own order.
private val inAlbumOrder = listOf(Key("t.albumId"), Key("t.albumOrder"), Key(SONG_TITLE), Key("t.id"))

private fun songKeys(sort: SongSort): List<Key> = when (sort) {
    // Orders by plays are put together from the library in title order
    // and the listening history (see Listening.kt); so is a folder's.
    SongSort.Title, SongSort.MostPlayed, SongSort.RecentlyPlayed, SongSort.FolderOrder ->
        listOf(chosen(SONG_TITLE), Key(SONG_ARTIST), Key("t.id"))
    SongSort.Artist -> listOf(chosen(SONG_ARTIST), Key(SONG_ALBUM)) + inAlbumOrder
    SongSort.Album -> listOf(chosen(SONG_ALBUM), Key(SONG_ARTIST)) + inAlbumOrder
    SongSort.RecentlyAdded -> listOf(chosen("t.addedAt")) + inAlbumOrder
    SongSort.Year -> listOf(missingLast("t.year IS NULL"), chosen("t.year"), Key(SONG_ALBUM)) + inAlbumOrder
    SongSort.Length -> listOf(chosen("t.durationMs"), Key(SONG_TITLE), Key("t.id"))
    SongSort.Rating -> listOf(
        missingLast("t.rating = 0"),
        chosen("t.rating"),
        Key(SONG_LIKED, descending = true),
        Key(SONG_TITLE),
        Key("t.id"),
    )
    SongSort.Liked -> listOf(chosen(SONG_LIKED), Key(SONG_TITLE), Key("t.id"))
    SongSort.DateLiked -> listOf(missingLast("$SONG_LIKED_AT IS NULL"), chosen(SONG_LIKED_AT), Key(SONG_TITLE), Key("t.id"))
}

// The name a song files under in a list ordered by name.
private fun songHeading(sort: SongSort): String = when (sort) {
    SongSort.Title -> SONG_TITLE
    SongSort.Artist -> SONG_ARTIST
    SongSort.Album -> SONG_ALBUM
    else -> "NULL"
}

fun songQuery(sort: SongSort, descending: Boolean, scope: SongScope): SqlQuery {
    val from = when (scope) {
        SongScope.Liked -> "liked_track l JOIN track t ON t.id = l.trackId"
        else -> "track t"
    }
    val (where, args) = when (scope) {
        is SongScope.Genre -> "WHERE t.genre = ? COLLATE NOCASE" to listOf(scope.name)
        else -> "" to emptyList()
    }
    val sql = "SELECT t.*, ${songHeading(sort)} AS heading FROM $from " +
        "LEFT JOIN album al ON al.id = t.albumId LEFT JOIN artist ar ON ar.id = t.artistId " +
        (if (where.isEmpty()) "" else "$where ") +
        "ORDER BY ${orderBy(songKeys(sort), descending)}"
    val readsLikes = scope == SongScope.Liked || sort == SongSort.Rating || sort == SongSort.Liked || sort == SongSort.DateLiked
    return SqlQuery(sql, args, readsLikes)
}

// Albums. `a` is the album; `ar` its artist.
private const val ALBUM_TITLE = "a.sortKey"
private const val ALBUM_ARTIST = "COALESCE(ar.sortKey, LOWER(a.artist))"

private fun albumKeys(sort: AlbumSort): List<Key> = when (sort) {
    AlbumSort.Title, AlbumSort.MostPlayed, AlbumSort.RecentlyPlayed ->
        listOf(chosen(ALBUM_TITLE), Key(ALBUM_ARTIST), Key("a.id"))
    AlbumSort.Artist -> listOf(chosen(ALBUM_ARTIST), Key(ALBUM_TITLE), Key("a.id"))
    AlbumSort.Year -> listOf(missingLast("a.year IS NULL"), chosen("a.year"), Key(ALBUM_ARTIST), Key(ALBUM_TITLE), Key("a.id"))
    AlbumSort.RecentlyAdded -> listOf(chosen("a.addedAt"), Key(ALBUM_TITLE), Key("a.id"))
    AlbumSort.SongCount -> listOf(chosen("a.songCount"), Key(ALBUM_TITLE), Key("a.id"))
    AlbumSort.Length -> listOf(chosen("a.durationMs"), Key(ALBUM_TITLE), Key("a.id"))
}

private fun albumHeading(sort: AlbumSort): String = when (sort) {
    AlbumSort.Title -> ALBUM_TITLE
    AlbumSort.Artist -> ALBUM_ARTIST
    else -> "NULL"
}

// Every album, or one artist's.
fun albumQuery(sort: AlbumSort, descending: Boolean, artistId: String? = null): SqlQuery {
    val where = if (artistId != null) "WHERE a.artistId = ? " else ""
    val sql = "SELECT a.*, ${albumHeading(sort)} AS heading FROM album a " +
        "LEFT JOIN artist ar ON ar.id = a.artistId " + where +
        "ORDER BY ${orderBy(albumKeys(sort), descending)}"
    return SqlQuery(sql, listOfNotNull(artistId), readsLikes = false)
}

// Artists. `r` is the artist.
private const val ARTIST_NAME = "r.sortKey"

// When the newest song by the artist came into the library.
private const val ARTIST_ADDED = "(SELECT MAX(t.addedAt) FROM track t WHERE t.artistId = r.id)"

private fun artistKeys(sort: ArtistSort): List<Key> = when (sort) {
    ArtistSort.Name, ArtistSort.MostPlayed, ArtistSort.RecentlyPlayed -> listOf(chosen(ARTIST_NAME), Key("r.id"))
    ArtistSort.SongCount -> listOf(chosen("r.songCount"), Key(ARTIST_NAME), Key("r.id"))
    ArtistSort.AlbumCount -> listOf(chosen("r.albumCount"), Key(ARTIST_NAME), Key("r.id"))
    ArtistSort.RecentlyAdded -> listOf(chosen(ARTIST_ADDED), Key(ARTIST_NAME), Key("r.id"))
}

fun artistQuery(sort: ArtistSort, descending: Boolean): SqlQuery {
    val heading = if (sort == ArtistSort.Name) ARTIST_NAME else "NULL"
    val sql = "SELECT r.*, $heading AS heading FROM artist r ORDER BY ${orderBy(artistKeys(sort), descending)}"
    return SqlQuery(sql, emptyList(), readsLikes = false)
}
