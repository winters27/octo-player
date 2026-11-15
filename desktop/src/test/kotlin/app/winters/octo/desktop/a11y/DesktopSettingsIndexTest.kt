package app.winters.octo.desktop.a11y

import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.pages.DesktopSettingsIndex
import app.winters.octo.desktop.pages.SettingsHere
import app.winters.octo.desktop.pages.searchDesktopSettings
import app.winters.octo.desktop.pages.settingsCardOf
import app.winters.octo.desktop.pages.settingsIndexFor
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.playback.DOWNLOAD_FORMAT_TITLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Settings' search index: how it ranks, where a hit lands, what each
// computer and build shows, and that every row the pages draw is in it.
class DesktopSettingsIndexTest {
    // A window with everything: Windows, a tray, notifications, Discord, the
    // sound engine and a family server.
    private val everything = SettingsHere(DesktopOs.Windows, tray = true, notifications = true, discord = true, engine = true, family = true)

    private fun titles(query: String, here: SettingsHere = everything) = searchDesktopSettings(query, here).map { it.entry.title }

    private fun shown(here: SettingsHere) = settingsIndexFor(here).map { it.title }.toSet()

    @Test
    fun aTitleStartBeatsATitleBeatsOtherWordsBeatsTheSummary() {
        val found = titles("speed")
        fun List<String>.at(title: String) = indexOf(title).also { assertTrue("$title is found: $this", it >= 0) }
        // "Speed" starts its title; "Drift speed" holds the word; "Match
        // tempo" has it among its other words.
        assertTrue(found.toString(), found.at("Speed") < found.at("Drift speed"))
        assertTrue(found.toString(), found.at("Drift speed") < found.at("Match tempo"))
        assertEquals("Speed and pitch", found.first())
        // "Find lyrics online" has "words" among its other words; "Brightness
        // cap" only in its line.
        val words = titles("words")
        assertTrue(words.toString(), words.at("Find lyrics online") < words.at("Brightness cap"))
    }

    @Test
    fun theWordsPeopleTypeFindTheirSettings() {
        assertEquals("Accent color", titles("accent").first())
        assertTrue("the colour spelling", "Accent color" in titles("colour"))
        assertTrue("Accent color" in titles("dark"))
        assertTrue("Between songs" in titles("gap"))
        assertTrue("Between songs" in titles("fade"))
        assertTrue("Equalizer" in titles("eq"))
        assertTrue(START_WITH in titles("autostart"))
        assertEquals(emptyList<String>(), titles("   "))
    }

    // Red when the Download format row is missing from the index.
    @Test
    fun theDownloadFormatIsFoundByItsWords() {
        for (word in listOf("opus", "aac", "mp3", "codec", "download format")) {
            assertTrue(word, DOWNLOAD_FORMAT_TITLE in titles(word))
        }
    }

    @Test
    fun aHitCarriesItsPageAndRow() {
        val calm = searchDesktopSettings("calm motion", everything).first()
        assertEquals(SettingsKey.Look, calm.entry.page)
        assertEquals("motion.calm-motion", calm.entry.row)
        assertEquals("Appearance · Motion", calm.place)
        val eq = searchDesktopSettings("equalizer", everything).first()
        assertEquals(SettingsKey.Sound to "eq", eq.entry.page to eq.entry.row)
        val page = searchDesktopSettings("keyboard", everything).first { it.entry.row == null }
        assertEquals(SettingsKey.Keys, page.entry.page)
        // A row a setting keeps hidden is still found, by its own id.
        val strength = searchDesktopSettings("strength", everything).first()
        assertEquals("window-background.strength", strength.entry.row)
    }

    @Test
    fun eachComputerAndBuildFindsOnlyWhatItShows() {
        val windows = shown(everything)
        val mac = shown(everything.copy(os = DesktopOs.Mac))
        val linux = shown(everything.copy(os = DesktopOs.Linux))
        assertTrue(START_WITH in windows)
        assertFalse(START_WITH in mac || START_WITH in linux)
        assertTrue("Install updates" in windows && "Install updates" !in linux)
        assertTrue("Use these keys from any app" in windows && "Use these keys from any app" !in mac)
        assertTrue("Use the system title bar" in linux && "Use the system title bar" !in mac)
        assertTrue("Window" in mac && "Window and startup" !in mac && "Window and startup" in windows)

        val noTray = shown(everything.copy(tray = false, notifications = false))
        assertFalse("Start in the tray" in noTray || "Keep playing when the window closes" in noTray || "Now playing notifications" in noTray)
        assertTrue("Start in the tray" in windows && "Keep playing when the window closes" in windows && "Now playing notifications" in windows)

        val noDiscord = shown(everything.copy(discord = false))
        assertTrue("Show what you're playing" in windows && "Discord" in windows)
        assertFalse("Show what you're playing" in noDiscord || "Discord" in noDiscord)

        val noEngine = shown(everything.copy(engine = false))
        assertTrue("Equalizer" in windows && "Length" in windows && "Between songs" in windows && "Nothing to shape" !in windows)
        assertFalse("Equalizer" in noEngine || "Length" in noEngine || "Between songs" in noEngine || "Speed" in noEngine)
        assertTrue("Nothing to shape" in noEngine)

        val plain = shown(everything.copy(family = false))
        assertTrue("Away from home" in windows && "Away from home" !in plain)
    }

    @Test
    fun eachRowIsListedOnce() {
        val places = DesktopSettingsIndex.map { it.page to it.row }
        assertEquals(places.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toString(), places.size, places.toSet().size)
    }

    // Every page drawn in the real window under each computer, build and
    // state (SettingsWalk), each row found in the index under its own id.
    @Test(timeout = 300_000)
    fun everyRowIsFindable() {
        val missing = sortedSetOf<String>()
        val seen = mutableSetOf<String>()
        for (window in SettingsWalk.windows) {
            val index = settingsIndexFor(window.here)
            for (row in window.rows) {
                val title = row.title
                seen += title.stable
                val id = row.id
                if (id == null) {
                    missing += "${row.page}: \"${title.title}\" has no id (${window.name})"
                    continue
                }
                // A row whose title is data is found by its card.
                val wanted = if (title.stable.startsWith("[")) settingsCardOf(id) ?: id else id
                if (index.none { it.page == row.page && it.row == wanted }) {
                    missing += "${row.page} $wanted \"${title.stable}\" (${window.name})"
                }
            }
        }
        assertTrue("rows with no index entry:\n" + missing.joinToString("\n"), missing.isEmpty())
        // The walk reached the rows that need the system, the engine,
        // Discord and every hidden row shown.
        listOf(
            "Mini player", "Media keys", "Use these keys from any app", "[each global shortcut]", "Show what you're playing", "Friends' list shows",
            "Bands", "No filters yet", "Preset", "Length", "Crossfade curve", "Filter sweeps", "Match tempo", "Movement", "Drift speed",
            "Use the system title bar", "Now playing notifications",
        ).forEach { assertTrue("$it was drawn: $seen", it in seen) }
    }

    // Between songs answers every word people type for it, Smart
    // transitions among them, which has no entry of its own any more; the
    // card keeps the Crossfade card's id and Length the Longest blend
    // row's.
    @Test
    fun betweenSongsAnswersItsWordsAndKeepsTheOldIds() {
        for (words in listOf("smart transitions", "gapless", "automix", "blend", "between songs", "crossfade", "mix", "transition", "dj")) {
            val hit = searchDesktopSettings(words, everything).firstOrNull { it.entry.title == "Between songs" }
            assertTrue("\"$words\" finds Between songs: ${titles(words)}", hit != null)
            assertEquals(SettingsKey.Playback to "crossfade", hit!!.entry.page to hit.entry.row)
        }
        assertTrue("no Smart transitions entry is left", DesktopSettingsIndex.none { it.title == "Smart transitions" || it.row?.endsWith("smart-transitions") == true })
        val length = DesktopSettingsIndex.single { it.title == "Length" }
        assertEquals("crossfade.longest-blend", length.row)
        assertEquals("Playback · Between songs", searchDesktopSettings("crossfade length", everything).first { it.entry.title == "Length" }.place)
    }

    private companion object {
        const val START_WITH = "Start with Windows"
    }
}
