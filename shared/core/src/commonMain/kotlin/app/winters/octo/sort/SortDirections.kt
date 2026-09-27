package app.winters.octo.sort

// What the two directions of an order mean, in words: "A to Z" and "Z to A"
// for names, "Newest" and "Oldest" for dates, and so on. The sort list shows
// these rather than "Ascending" and "Descending", so which way a list runs
// reads at a glance.
enum class SortScale(val ascending: String, val descending: String) {
    Name("A to Z", "Z to A"),
    Date("Oldest", "Newest"),
    Amount("Least", "Most"),
    Length("Shortest", "Longest"),
    Rating("Lowest", "Highest"),
    Size("Smallest", "Largest"),
    Liked("Liked last", "Liked first"),

    // An order kept as something else lists it, like a folder's.
    Listed("In order", "Reversed"),

    // For an order no plainer word fits.
    Plain("Ascending", "Descending"),
    ;

    fun label(descending: Boolean): String = if (descending) this.descending else ascending
}

// The words for an option's directions. Every option is named here, so a new
// one has to choose its words rather than quietly falling back to "Ascending".
fun sortScale(option: SortOption): SortScale = when (option) {
    is SongSort -> when (option) {
        SongSort.Title, SongSort.Artist, SongSort.Album -> SortScale.Name
        SongSort.RecentlyAdded, SongSort.Year, SongSort.RecentlyPlayed, SongSort.DateLiked -> SortScale.Date
        SongSort.Length -> SortScale.Length
        SongSort.MostPlayed -> SortScale.Amount
        SongSort.Rating -> SortScale.Rating
        SongSort.Liked -> SortScale.Liked
        SongSort.FolderOrder -> SortScale.Listed
    }
    is AlbumSort -> when (option) {
        AlbumSort.Title, AlbumSort.Artist -> SortScale.Name
        AlbumSort.Year, AlbumSort.RecentlyAdded, AlbumSort.RecentlyPlayed -> SortScale.Date
        AlbumSort.MostPlayed, AlbumSort.SongCount -> SortScale.Amount
        AlbumSort.Length -> SortScale.Length
    }
    is ArtistSort -> when (option) {
        ArtistSort.Name -> SortScale.Name
        ArtistSort.SongCount, ArtistSort.AlbumCount, ArtistSort.MostPlayed -> SortScale.Amount
        ArtistSort.RecentlyPlayed, ArtistSort.RecentlyAdded -> SortScale.Date
    }
    is PlaylistSort -> when (option) {
        PlaylistSort.Name -> SortScale.Name
        PlaylistSort.RecentlyChanged, PlaylistSort.RecentlyCreated -> SortScale.Date
        PlaylistSort.SongCount -> SortScale.Amount
    }
    is DownloadSort -> when (option) {
        DownloadSort.RecentlyDownloaded -> SortScale.Date
        DownloadSort.Title -> SortScale.Name
        DownloadSort.Size -> SortScale.Size
    }
    is FavouriteSort -> when (option) {
        FavouriteSort.DateAdded -> SortScale.Date
        FavouriteSort.Name -> SortScale.Name
    }
}

// One of the two directions offered for an order, and its words.
data class DirectionChoice(val descending: Boolean, val label: String)

// The two directions for an option, the way it runs when first picked on
// the left: "A to Z" before "Z to A", but "Newest" before "Oldest".
fun directionChoices(option: SortOption): List<DirectionChoice> {
    val scale = sortScale(option)
    val first = option.startsDescending
    return listOf(DirectionChoice(first, scale.label(first)), DirectionChoice(!first, scale.label(!first)))
}
