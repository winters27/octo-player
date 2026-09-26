package app.winters.octo.sort

// The ways each list can be ordered, and which order each list starts in.

// One way a list can be ordered. Its id is what gets saved, so it never
// changes once shipped.
sealed interface SortOption {
    val id: String
    val label: String

    // Which way it runs when first picked: newest, most or longest first
    // for dates and counts, A to Z for names.
    val startsDescending: Boolean

    // Ordered by a name, so the list can be filed under letters.
    val byName: Boolean get() = false

    // Ordered by how much or how lately it was played.
    val byListening: Boolean get() = false
}

// An order for a list: what it goes by, and which way it runs.
data class SortOrder(val by: SortOption, val descending: Boolean) {
    // The order after picking another option, running its usual way.
    fun picking(option: SortOption): SortOrder =
        if (option == by) this else SortOrder(option, option.startsDescending)
}

enum class SongSort(
    override val label: String,
    override val startsDescending: Boolean,
    override val byName: Boolean = false,
    override val byListening: Boolean = false,
) : SortOption {
    Title("Title", startsDescending = false, byName = true),
    Artist("Artist", startsDescending = false, byName = true),
    Album("Album", startsDescending = false, byName = true),
    RecentlyAdded("Recently added", startsDescending = true),
    Year("Year", startsDescending = true),
    Length("Length", startsDescending = true),
    MostPlayed("Most played", startsDescending = true, byListening = true),
    RecentlyPlayed("Recently played", startsDescending = true, byListening = true),
    Rating("Rating", startsDescending = true),
    Liked("Liked", startsDescending = true),
    DateLiked("Date liked", startsDescending = true),

    // As the folder lists them: the server's order, or album by album on
    // the phone.
    FolderOrder("Folder order", startsDescending = false),
    ;

    override val id: String get() = name
}

enum class AlbumSort(
    override val label: String,
    override val startsDescending: Boolean,
    override val byName: Boolean = false,
    override val byListening: Boolean = false,
) : SortOption {
    Title("Title", startsDescending = false, byName = true),
    Artist("Artist", startsDescending = false, byName = true),
    Year("Year", startsDescending = true),
    RecentlyAdded("Recently added", startsDescending = true),
    MostPlayed("Most played", startsDescending = true, byListening = true),
    RecentlyPlayed("Recently played", startsDescending = true, byListening = true),
    SongCount("Song count", startsDescending = true),
    Length("Length", startsDescending = true),
    ;

    override val id: String get() = name
}

enum class ArtistSort(
    override val label: String,
    override val startsDescending: Boolean,
    override val byName: Boolean = false,
    override val byListening: Boolean = false,
) : SortOption {
    Name("Name", startsDescending = false, byName = true),
    SongCount("Song count", startsDescending = true),
    AlbumCount("Album count", startsDescending = true),
    MostPlayed("Most played", startsDescending = true, byListening = true),
    RecentlyPlayed("Recently played", startsDescending = true, byListening = true),

    // By the newest song of theirs in the library.
    RecentlyAdded("Recently added", startsDescending = true),
    ;

    override val id: String get() = name
}

enum class PlaylistSort(override val label: String, override val startsDescending: Boolean, override val byName: Boolean = false) : SortOption {
    Name("Name", startsDescending = false, byName = true),
    RecentlyChanged("Recently changed", startsDescending = true),
    RecentlyCreated("Recently created", startsDescending = true),
    SongCount("Song count", startsDescending = true),
    ;

    override val id: String get() = name
}

enum class DownloadSort(override val label: String, override val startsDescending: Boolean, override val byName: Boolean = false) : SortOption {
    RecentlyDownloaded("Recently downloaded", startsDescending = true),
    Title("Title", startsDescending = false, byName = true),
    Size("Size", startsDescending = true),
    ;

    override val id: String get() = name
}

// The Favourites page, for its albums and its artists alike.
enum class FavouriteSort(override val label: String, override val startsDescending: Boolean, override val byName: Boolean = false) : SortOption {
    DateAdded("Date added", startsDescending = true),
    Name("Name", startsDescending = false, byName = true),
    ;

    override val id: String get() = name
}

// The orders any full list of songs offers.
private val songSorts = listOf(
    SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.RecentlyAdded, SongSort.Year, SongSort.Length,
    SongSort.MostPlayed, SongSort.RecentlyPlayed, SongSort.Rating, SongSort.Liked,
)

// Every list that can be ordered: the name its order is saved under, the
// options it offers, and the order it starts in.
enum class SortList(val key: String, val options: List<SortOption>, val default: SortOrder) {
    Songs("songs", songSorts, SortOrder(SongSort.Title, descending = false)),
    Liked(
        "liked",
        listOf(SongSort.DateLiked, SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.RecentlyAdded),
        SortOrder(SongSort.DateLiked, descending = true),
    ),
    GenreSongs("genre_songs", songSorts, SortOrder(SongSort.Title, descending = false)),
    FolderSongs(
        "folder_songs",
        listOf(
            SongSort.FolderOrder, SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.RecentlyAdded, SongSort.Year,
            SongSort.Length,
        ),
        SortOrder(SongSort.FolderOrder, descending = false),
    ),
    Albums("albums", AlbumSort.entries, SortOrder(AlbumSort.Title, descending = false)),

    // The albums on an artist's page.
    ArtistAlbums(
        "artist_albums",
        listOf(AlbumSort.Year, AlbumSort.Title, AlbumSort.MostPlayed),
        SortOrder(AlbumSort.Year, descending = true),
    ),
    Artists("artists", ArtistSort.entries, SortOrder(ArtistSort.Name, descending = false)),
    Playlists("playlists", PlaylistSort.entries, SortOrder(PlaylistSort.RecentlyChanged, descending = true)),
    Downloads("downloads", DownloadSort.entries, SortOrder(DownloadSort.RecentlyDownloaded, descending = true)),
    Favourites("favourites", FavouriteSort.entries, SortOrder(FavouriteSort.DateAdded, descending = true)),
    ;

    // Saved as "<option>:asc" or "<option>:desc".
    fun encode(order: SortOrder): String = "${order.by.id}:${if (order.descending) "desc" else "asc"}"

    // A saved order, or the default when nothing is saved or it is not one
    // this list offers.
    fun decode(saved: String?): SortOrder {
        val parts = saved?.split(':') ?: return default
        if (parts.size != 2) return default
        val option = options.firstOrNull { it.id == parts[0] } ?: return default
        return when (parts[1]) {
            "asc" -> SortOrder(option, descending = false)
            "desc" -> SortOrder(option, descending = true)
            else -> default
        }
    }
}
