package app.winters.octo.ui.livelists

import app.winters.octo.livelists.DefaultLiveListSort
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListRuleGroups
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The phone editor's plain logic: where it starts, when it offers starters,
// its Add a rule menu, and what it says.
class LiveListEditingTest {
    private val favourites = LibraryQuery(listOf(FilterPresets.Favourites))
    private val saved = LiveList.new("Hearts", favourites, 0, "h")

    @Test
    fun theEditorStartsFromTheListThePagesFiltersOrNothing() {
        assertEquals("Hearts", editorStart(saved, null).name)
        assertEquals(favourites, editorStart(saved, null).query)
        assertEquals(favourites, editorStart(null, favourites).query)
        assertEquals("", editorStart(null, favourites).name)
        assertEquals(LibraryQuery(sort = DefaultLiveListSort), editorStart(null, null).query)
        // The name it would get is the rules', until one is typed.
        assertEquals("Favorites", editorStart(null, favourites).savedName)
    }

    @Test
    fun startersShowOnlyOnANewEmptyEditorWhileThereAreNone() {
        assertTrue(offersStarters(emptyList(), null, null))
        assertFalse(offersStarters(listOf(saved), null, null))
        assertFalse(offersStarters(emptyList(), "h", null))
        assertFalse(offersStarters(emptyList(), null, favourites))
        assertTrue(offersStarters(emptyList(), null, LibraryQuery(sort = DefaultLiveListSort)))
    }

    @Test
    fun addARuleListsTheSameGroupsAsTheDesktop() {
        val words = ruleMenu().map { it.first }
        assertEquals(listOf("Added", "Played", "Times played", "Length", "Rating", "Genre", "Artist", "Year", "Favorites", "Lossless"), words)
        assertEquals(RulePick.Rule(FilterPresets.Lossless), ruleMenu().last().second)
        assertEquals(LiveListRuleGroups.size - 1 + 4 + 2, words.size)
        assertEquals(listOf("5 stars", "4 stars or more", "3 stars or more", "2 stars or more", "1 star or more"), RatingRules.map { it.first })
    }

    @Test
    fun theTickSitsOnTheRuleThatIsOn() {
        val added = LiveListRuleGroups.first { it.title == "Added" }.choices.map { it.rule }
        assertEquals(1, pickedIn(added, LibraryQuery(listOf(FilterPresets.AddedThisMonth))))
        assertEquals(-1, pickedIn(added, favourites))
    }

    @Test
    fun whatItSaysReadsPlainly() {
        assertEquals("Saved a copy of Hearts as a playlist, 42 songs", liveCopyMessage("Hearts", 42))
        assertEquals("Saved a copy of Hearts as a playlist, 1 song", liveCopyMessage("Hearts", 1))
        assertEquals("\"Hearts\" has no songs right now, so there is nothing to copy.", nothingToCopy("Hearts"))
        listOf(NO_MATCHES_NOTE, NO_RULE_MATCHES, LIVE_LIST).forEach { assertFalse(it.contains('—') || it.contains('–')) }
    }
}
