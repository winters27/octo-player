package app.winters.octo.discovery

// The switch that keeps songs found online out of sight, its words the same
// on the phone and the desktop, and the album page's own toggle.
const val LIBRARY_ONLY_SETTING = "Library songs only"
const val LIBRARY_ONLY_HELP = "Shows only songs in your library: albums, search, artists and radio leave out outside songs."
const val ONLY_MY_SONGS = "Only my songs"

// The songs to show: all of them, or with `hide` only those `outside` does
// not mark, in their order.
fun <T> librarySongsOnly(songs: List<T>, hide: Boolean, outside: (T) -> Boolean): List<T> =
    if (hide) songs.filterNot(outside) else songs
