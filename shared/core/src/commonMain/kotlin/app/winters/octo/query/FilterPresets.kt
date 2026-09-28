package app.winters.octo.query

import app.winters.octo.catalog.naturalSortKey

// The filters a song list offers in a click or a tap, the same on the phone
// and the desktop. Each is an ordinary rule, so a pill says it with
// `label()`.
object FilterPresets {
    val AddedThisWeek = QueryRule(QueryField.Added, QueryOp.InTheLast, days = 7)
    val AddedThisMonth = QueryRule(QueryField.Added, QueryOp.InTheLast, days = 30)
    val AddedThisYear = QueryRule(QueryField.Added, QueryOp.InTheLast, days = 365)
    val NeverPlayed = QueryRule(QueryField.LastPlayed, QueryOp.Never)
    val NotPlayedLately = QueryRule(QueryField.LastPlayed, QueryOp.NotInTheLast, days = 180)
    val Favourites = QueryRule(QueryField.Favourite, QueryOp.Is, flag = true)
    val Lossless = QueryRule(QueryField.Lossless, QueryOp.Is, flag = true)

    // The ones that need nothing chosen, in the order the phone's chips run.
    val simple = listOf(Favourites, AddedThisWeek, AddedThisMonth, AddedThisYear, NeverPlayed, NotPlayedLately, Lossless)

    fun ratingAtLeast(stars: Int) = QueryRule(QueryField.Rating, QueryOp.AtLeast, number = stars.toLong())

    // How a choice of ratingAtLeast reads in a list: "5 stars", "4 stars or
    // more".
    fun ratingWords(stars: Int): String = when (stars) {
        5 -> "5 stars"
        1 -> "1 star or more"
        else -> "$stars stars or more"
    }

    fun genre(name: String) = QueryRule(QueryField.Genre, QueryOp.Is, text = name)

    // A decade, from the year it starts: 1990 is 1990 to 1999.
    fun decade(start: Int) = QueryRule(QueryField.Year, QueryOp.Between, number = start.toLong(), to = start + 9L)
}

// The genres a list's songs are filed under, A to Z, each once however it
// is spelt in case or accents (the first spelling met is kept).
fun <T> genresIn(songs: List<T>, fields: SongFields<T>): List<String> {
    val seen = LinkedHashMap<String, String>()
    songs.forEach { song -> fields.genres(song).forEach { name -> if (name.isNotBlank()) seen.getOrPut(foldText(name)) { name.trim() } } }
    return seen.values.sortedBy(::naturalSortKey)
}

// The decades a list's songs come from, oldest first, as the year each
// starts: 1990 for the 1990s.
fun <T> decadesIn(songs: List<T>, fields: SongFields<T>): List<Int> =
    songs.mapNotNullTo(HashSet()) { song -> fields.year(song)?.takeIf { it > 0 }?.let { it / 10 * 10 } }.sorted()
