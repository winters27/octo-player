package app.winters.octo.sort

// Orders for lists that are short, or not in the database at all. These
// sort in memory. The playlists, the downloads and the songs of one folder
// are sorted in the app (ShortListRows.kt), since their rows are Room's.

// Sorts by one key the chosen way, with items missing it last either way,
// then by `ties`, which always run the same way. Each key is worked out once.
fun <T, K : Comparable<K>> sortedByKey(
    items: List<T>,
    descending: Boolean,
    key: (T) -> K?,
    ties: Comparator<T>,
): List<T> {
    val keys = items.map(key)
    return items.indices.sortedWith { a, b ->
        val ka = keys[a]
        val kb = keys[b]
        val byKey = when {
            ka == null && kb == null -> 0
            ka == null -> 1
            kb == null -> -1
            descending -> kb.compareTo(ka)
            else -> ka.compareTo(kb)
        }
        if (byKey != 0) byKey else ties.compare(items[a], items[b])
    }.map { items[it] }
}

// Favourite albums or artists: by when they became favourites, or by name
// (the sort key of an album's title or an artist's name).
fun <T> sortFavourites(items: List<T>, order: SortOrder, likedAt: (T) -> Long, name: (T) -> String, id: (T) -> String): List<T> =
    when (order.by) {
        FavouriteSort.Name -> sortedByKey(items, order.descending, name, compareBy(id))
        else -> sortedByKey(items, order.descending, likedAt, compareBy(name, id))
    }
