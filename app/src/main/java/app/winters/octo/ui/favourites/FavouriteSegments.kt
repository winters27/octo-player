package app.winters.octo.ui.favourites

// The parts of the Favourites page, in the order they show.
enum class FavouriteSegment(val label: String) {
    Songs("Songs"),
    Albums("Albums"),
    Artists("Artists"),
}

// Which part the page opens on, once all three are read: liked songs when
// there are any, since a heart on a song is the usual way to keep one;
// otherwise the first part that has something; Songs, with its note on how
// to add, when nothing does.
fun firstSegment(songs: Int, albums: Int, artists: Int): FavouriteSegment =
    when {
        songs > 0 -> FavouriteSegment.Songs
        albums > 0 -> FavouriteSegment.Albums
        artists > 0 -> FavouriteSegment.Artists
        else -> FavouriteSegment.Songs
    }
