package app.winters.octo.livelists

import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryMatch
import app.winters.octo.query.QueryRule
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder

// Fields a live list can hold several rules on at once, as in "Genre is
// Rock or Genre is Metal", or two decades.
val QueryField.takesMany: Boolean
    get() = this == QueryField.Genre || this == QueryField.Artist || this == QueryField.AlbumArtist ||
        this == QueryField.Composer || this == QueryField.Year

// A rule picked in the live list editor: one already on comes off; one on
// a field that takes many joins the others; any other takes the place of
// the rule on its field, as "In the last month" replaces "In the last week".
fun LibraryQuery.toggling(rule: QueryRule): LibraryQuery = when {
    rule in rules -> without(rule)
    rule.field.takesMany -> copy(rules = rules + rule)
    else -> setting(rule)
}

// Whether the editor should offer "all of these" or "any of these": only
// once there are two rules to join.
fun LibraryQuery.asksMatch(): Boolean = rules.size >= 2

// One choice in the editor's Add a rule menu: the words the menu shows and
// the rule it adds. The rule's own label is what the list then shows.
data class RuleChoice(val words: String, val rule: QueryRule)

// A heading in the Add a rule menu and its choices.
data class RuleGroup(val title: String, val choices: List<RuleChoice>)

// The rules a live list offers without asking which, grouped as both apps'
// menus show them. Genre, artist, year and rating ask which from the
// library, as the filter bar does.
val LiveListRuleGroups: List<RuleGroup> = listOf(
    RuleGroup(
        "Added",
        listOf(
            RuleChoice("In the last week", FilterPresets.AddedThisWeek),
            RuleChoice("In the last month", FilterPresets.AddedThisMonth),
            RuleChoice("In the last year", FilterPresets.AddedThisYear),
        ),
    ),
    RuleGroup(
        "Played",
        listOf(
            RuleChoice("In the last week", FilterPresets.playedInTheLast(7)),
            RuleChoice("In the last month", FilterPresets.playedInTheLast(30)),
            RuleChoice("Never", FilterPresets.NeverPlayed),
            RuleChoice("Not in the last 6 months", FilterPresets.NotPlayedLately),
            RuleChoice("Not in the last year", FilterPresets.notPlayedInTheLast(365)),
        ),
    ),
    RuleGroup(
        "Times played",
        listOf(
            RuleChoice("At least once", FilterPresets.playedAtLeast(1)),
            RuleChoice("5 times or more", FilterPresets.playedAtLeast(5)),
            RuleChoice("10 times or more", FilterPresets.playedAtLeast(10)),
            RuleChoice("25 times or more", FilterPresets.playedAtLeast(25)),
        ),
    ),
    RuleGroup(
        "Length",
        listOf(
            RuleChoice("3 minutes or shorter", FilterPresets.shorterThan(180)),
            RuleChoice("6 minutes or longer", FilterPresets.longerThan(360)),
            RuleChoice("10 minutes or longer", FilterPresets.longerThan(600)),
        ),
    ),
    RuleGroup(
        "Other",
        listOf(
            RuleChoice("Favorites", FilterPresets.Favourites),
            RuleChoice("Lossless", FilterPresets.Lossless),
        ),
    ),
)

// How many songs a live list can be kept to: all, or the first of these.
val LiveListLimits: List<Int?> = listOf(null, 25, 50, 100, 250, 500)

fun limitWords(limit: Int?): String = if (limit == null) "All songs" else "The first $limit"

// The orders a live list can run in, as the song lists offer them.
val LiveListSorts: List<SongSort> = listOf(
    SongSort.RecentlyAdded, SongSort.MostPlayed, SongSort.RecentlyPlayed, SongSort.Rating,
    SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.Year, SongSort.Length,
)

// A new live list's order until the listener picks one: newest first.
val DefaultLiveListSort = QuerySort.of(SortOrder(SongSort.RecentlyAdded, descending = true))

// A live list made from a page's filters: the page's own rule when it is
// a genre or Favourites (the whole library is where a live list looks),
// then the filters, in the page's order.
fun liveListQueryFrom(page: List<QueryRule>, filters: LibraryQuery, order: SortOrder?): LibraryQuery {
    val rules = (page + filters.rules).distinct()
    return LibraryQuery(
        rules = rules,
        match = if (page.isEmpty()) filters.match else QueryMatch.All,
        text = filters.text.trim(),
        sort = order?.let(QuerySort::of) ?: DefaultLiveListSort,
        limit = filters.limit,
    )
}

// A live list the apps suggest while the listener has none. Made only when
// picked.
data class LiveListStarter(val name: String, val detail: String, val query: LibraryQuery)

val LiveListStarters: List<LiveListStarter> = listOf(
    LiveListStarter(
        "Recently added",
        "Songs added in the last month, newest first",
        LibraryQuery(listOf(FilterPresets.AddedThisMonth), sort = DefaultLiveListSort),
    ),
    LiveListStarter(
        "Not played in a year",
        "Songs you played before, not heard in a year",
        LibraryQuery(
            listOf(FilterPresets.playedAtLeast(1), FilterPresets.notPlayedInTheLast(365)),
            sort = QuerySort.of(SortOrder(SongSort.MostPlayed, descending = true)),
        ),
    ),
    LiveListStarter(
        "Most played this month",
        "Songs played this month, most played first",
        LibraryQuery(
            listOf(FilterPresets.playedInTheLast(30)),
            sort = QuerySort.of(SortOrder(SongSort.MostPlayed, descending = true)),
            limit = 50,
        ),
    ),
    LiveListStarter(
        "Lossless favorites",
        "Favorite songs in lossless files",
        LibraryQuery(listOf(FilterPresets.Favourites, FilterPresets.Lossless), sort = QuerySort.of(SortOrder(SongSort.Artist, descending = false))),
    ),
)
