package app.winters.octo.ui.livelists

import app.winters.octo.livelists.DefaultLiveListSort
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListDraft
import app.winters.octo.livelists.LiveListRuleGroups
import app.winters.octo.livelists.RuleGroup
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryRule

// The editor's plain logic, apart from the screen so it can be tested.

// What the editor starts from: the saved list, a page's filters, or nothing
// picked yet, newest first.
internal fun editorStart(existing: LiveList?, start: LibraryQuery?): LiveListDraft = when {
    existing != null -> LiveListDraft.of(existing)
    start != null -> LiveListDraft("", start)
    else -> LiveListDraft("", LibraryQuery(sort = DefaultLiveListSort))
}

// The starters show on a new, empty editor while the listener has no live
// lists at all.
internal fun offersStarters(lists: List<LiveList>, id: String?, start: LibraryQuery?): Boolean =
    lists.isEmpty() && id == null && (start == null || !start.filters)

// Where a pick on the first page of Add a rule leads: a group of choices,
// a page that asks which from the library, or a rule straight away.
internal sealed interface RulePick {
    data class Group(val group: RuleGroup) : RulePick
    data object Rating : RulePick
    data object Genre : RulePick
    data object Artist : RulePick
    data object Year : RulePick
    data class Rule(val rule: QueryRule) : RulePick
}

// The first page of Add a rule, as words and where each leads: the same
// order the desktop's menu has.
internal fun ruleMenu(): List<Pair<String, RulePick>> {
    val other = LiveListRuleGroups.firstOrNull { it.title == "Other" }?.choices.orEmpty()
    return LiveListRuleGroups.filter { it.title != "Other" }.map { it.title to RulePick.Group(it) } +
        listOf("Rating" to RulePick.Rating, "Genre" to RulePick.Genre, "Artist" to RulePick.Artist, "Year" to RulePick.Year) +
        other.map { it.words to RulePick.Rule(it.rule) }
}

// The ratings on offer, highest first.
internal val RatingRules: List<Pair<String, QueryRule>> = (5 downTo 1).map { FilterPresets.ratingWords(it) to FilterPresets.ratingAtLeast(it) }

// Which of `rules` is on, for a choice list's tick, or -1.
internal fun pickedIn(rules: List<QueryRule>, query: LibraryQuery): Int = rules.indexOfFirst { it in query.rules }

// Said once a copy of a live list is made as a playlist on the phone.
internal fun liveCopyMessage(name: String, count: Int): String =
    "Saved a copy of $name as a playlist, ${if (count == 1) "1 song" else "%,d songs".format(count)}"

internal fun nothingToCopy(name: String): String = "\"$name\" has no songs right now, so there is nothing to copy."
