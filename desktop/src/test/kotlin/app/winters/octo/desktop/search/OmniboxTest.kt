package app.winters.octo.desktop.search

import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniboxTest {
    private var ran = ""
    private val commands = listOf(
        Command("Songs", "Go to", "open show page library tracks") { ran = "songs" },
        Command("Sleep in 30 minutes", "Player", "sleep timer stop") { ran = "sleep" },
        Command("Show lyrics", "View", "words panel") { ran = "lyrics" },
        Command("Play on Speakers", "Output", "output device") { ran = "speakers" },
    )

    @Test
    fun everyWordTypedMustStartAWordOfTheCommand() {
        assertEquals(listOf("Sleep in 30 minutes"), matchCommands(commands, "sle ti").map { it.title })
        assertEquals(listOf("Play on Speakers"), matchCommands(commands, "speak").map { it.title })
        assertTrue(matchCommands(commands, "leep").isEmpty())
        assertEquals(commands, matchCommands(commands, " "))
    }

    @Test
    fun aTitleStartingWithWhatWasTypedComesFirst() {
        val both = listOf(Command("Show song details", "View", "info") {}, Command("Songs", "Go to") {})
        assertEquals("Songs", matchCommands(both, "song").first().title)
    }

    @Test
    fun aMarkFirstListsCommandsOnlyByGroup() {
        val sections = omniSections(">lyr", found = found(), commands = commands, recent = listOf("old"))
        assertEquals(listOf("View"), sections.map { it.title })
        (sections.single().items.single() as OmniItem.Run).command.run()
        assertEquals("lyrics", ran)
    }

    @Test
    fun nothingTypedListsTheRecentSearches() {
        assertEquals(listOf("Recent searches"), omniSections("", null, commands, listOf("radiohead", "bjork")).map { it.title })
        assertTrue(omniSections("", null, commands, emptyList()).isEmpty())
    }

    @Test
    fun resultsComeGroupedWithAFewCommandsAndSeeAll() {
        val sections = omniSections("songs", found(), commands, emptyList())
        assertEquals(listOf("Commands", "Songs", "Artists", "Albums", "Not in your library", ""), sections.map { it.title })
        assertTrue(sections.last().items.single() is OmniItem.SeeAll)
        val song = sections[1].items[1] as OmniItem.SongHit
        assertEquals("playing a result plays on from it", 1, song.index)
        assertEquals(6, sections[1].items.size)
    }

    @Test
    fun beforeAnyAnswerOnlyCommandsShow() {
        assertEquals(listOf("Commands"), omniSections("songs", null, commands, emptyList()).map { it.title })
    }

    private fun found() = SearchFound(
        LibraryResults(
            artists = listOf(Artist("r1", "Radiohead")),
            albums = listOf(Album("a1", "OK Computer")),
            songs = (1..8).map { Song("s$it", "Song $it") },
        ),
        OutsideResults(songs = listOf(Song("x1", "Found online"))),
    )
}
