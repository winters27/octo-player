package app.winters.octo.discovery

// What to say when downloads asked for in this run of the app reach the
// library: one line for a song, and for an album one line once its last
// song lands, not one per song. Downloads asked for before the app started
// arrive quietly.
class Arrivals {
    // An album asked for, its songs still to come. One asked for a page of
    // songs at a time stays open until its last page joins, so it never
    // settles between two pages.
    class AlbumAsk internal constructor(val title: String, val waiting: MutableSet<String>) {
        val landed = HashSet<String>()
        val failed = HashSet<String>()
        internal var open = false
    }

    private val songs = HashMap<String, String>()
    private val albums = mutableListOf<AlbumAsk>()

    // One song was asked for. A song of an album already asked for counts
    // with its album again.
    @Synchronized
    fun askedSong(findId: String, title: String) {
        val album = albums.firstOrNull { findId in it.failed }
        if (album != null) {
            album.failed.remove(findId)
            album.waiting.add(findId)
        } else if (albums.none { findId in it.waiting }) {
            songs[findId] = title
        }
    }

    // A whole album was asked for, with the songs from it still to come.
    @Synchronized
    fun askedAlbum(title: String, findIds: Collection<String>) {
        if (findIds.isEmpty()) return
        findIds.forEach { id -> songs.remove(id) }
        albums.forEach { it.waiting.removeAll(findIds.toSet()) }
        albums.removeAll { !it.open && it.waiting.isEmpty() && it.landed.isEmpty() }
        albums += AlbumAsk(title.ifBlank { "The album" }, findIds.toMutableSet())
    }

    // A whole album asked for a page of songs at a time: `opened` starts
    // its one ask, each page joins it, and `closed` ends it once the last
    // page has, answering what to say if every song landed meanwhile.
    @Synchronized
    fun opened(title: String): AlbumAsk = AlbumAsk(title.ifBlank { "The album" }, HashSet()).also {
        it.open = true
        albums += it
    }

    @Synchronized
    fun joined(ask: AlbumAsk, findIds: Collection<String>) {
        if (findIds.isEmpty()) return
        findIds.forEach { id -> songs.remove(id) }
        albums.forEach { if (it !== ask) it.waiting.removeAll(findIds.toSet()) }
        albums.removeAll { it !== ask && !it.open && it.waiting.isEmpty() && it.landed.isEmpty() }
        if (ask !in albums) albums += ask
        ask.waiting += findIds
    }

    @Synchronized
    fun closed(ask: AlbumAsk): String? {
        ask.open = false
        if (ask !in albums) return null
        if (ask.waiting.isEmpty() && ask.landed.isEmpty() && ask.failed.isEmpty()) {
            albums.remove(ask)
            return null
        }
        return settle(ask)
    }

    // A song reached the library. Answers what to say, if anything.
    @Synchronized
    fun landed(findId: String): String? {
        songs.remove(findId)?.let { return "$it is in your library" }
        val album = albums.firstOrNull { findId in it.waiting } ?: return null
        album.waiting.remove(findId)
        album.landed.add(findId)
        return settle(album)
    }

    // A song could not be downloaded. A song on its own says nothing here,
    // as the button shows it; the last song of an album settles the album.
    @Synchronized
    fun failed(findId: String): String? {
        val album = albums.firstOrNull { findId in it.waiting } ?: return null
        album.waiting.remove(findId)
        album.failed.add(findId)
        return settle(album)
    }

    private fun settle(album: AlbumAsk): String? {
        if (album.waiting.isNotEmpty() || album.open) return null
        albums.remove(album)
        return when {
            album.landed.isEmpty() -> null
            album.failed.isEmpty() -> "${album.title} is in your library"
            album.failed.size == 1 -> "${album.title} is in your library, but 1 song could not be downloaded"
            else -> "${album.title} is in your library, but ${album.failed.size} songs could not be downloaded"
        }
    }
}
