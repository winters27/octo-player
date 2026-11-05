package app.winters.octo.ui.search

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRankTest {
    private data class Item(val id: String, val title: String, val other: String = "", val summary: String = "")

    private fun ids(query: String, items: List<Item>) = rankSettings(query, items, { it.title }, { it.other }, { it.summary }).map { it.id }

    @Test
    fun aTitleStartBeatsATitleBeatsOtherWordsBeatsTheSummary() {
        val items = listOf(
            Item("summary", "Brightness cap", summary = "How bright the screen may get"),
            Item("keyword", "Lock", other = "screen display"),
            Item("inside", "Keep the screen on"),
            Item("start", "Screen saver"),
        )
        assertEquals(listOf("start", "inside", "keyword", "summary"), ids("screen", items))
    }

    @Test
    fun everyTypedWordMustBeFoundSomewhere() {
        val items = listOf(
            Item("both", "Crossfade", other = "fade blend"),
            Item("one", "Crossfeed", other = "headphones"),
        )
        assertEquals(listOf("both"), ids("crossfade blend", items))
        assertEquals(emptyList(), ids("crossfade nothing", items))
    }

    @Test
    fun wordsCanBeSpreadOverTheTitleAndOtherWords() {
        val items = listOf(Item("spread", "Resume when headphones connect", other = "bluetooth"))
        assertEquals(listOf("spread"), ids("resume bluetooth", items))
    }

    @Test
    fun shortWordsOnlyMatchTheStartOfAWord() {
        val items = listOf(Item("eq", "Equalizer"), Item("inside", "Frequency"))
        assertEquals(listOf("eq"), ids("eq", items))
        assertEquals(listOf("eq", "inside"), ids("equ", items))
    }

    @Test
    fun accentsCaseAndPunctuationDoNotMatter() {
        val items = listOf(Item("wifi", "Streaming on Wi-Fi"), Item("cafe", "Café"))
        assertEquals(listOf("wifi"), ids("WIFI", items))
        assertEquals(listOf("cafe"), ids("cafe", items))
    }

    @Test
    fun nothingTypedFindsNothingAndTiesKeepTheirOrder() {
        val items = listOf(Item("a", "Fade in"), Item("b", "Fade out"))
        assertEquals(emptyList(), ids("  ", items))
        assertEquals(listOf("a", "b"), ids("fade", items))
    }
}
