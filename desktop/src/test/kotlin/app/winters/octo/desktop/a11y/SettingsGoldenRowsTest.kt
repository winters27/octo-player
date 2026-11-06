package app.winters.octo.desktop.a11y

import app.winters.octo.animated.AnimatedArtSetting
import app.winters.octo.covers.PLAYLIST_COVERS_SETTING
import app.winters.octo.desktop.liveradio.FIND_STATIONS_NEAR
import app.winters.octo.desktop.pages.ACCENT_COLOR
import app.winters.octo.desktop.pages.MATCH_THE_SYSTEM
import app.winters.octo.desktop.pages.THEME_CHOICE
import app.winters.octo.discovery.LIBRARY_ONLY_SETTING
import app.winters.octo.home.HOME_SECTIONS_TITLE
import app.winters.octo.playback.START_AFTER_SETTING
import app.winters.octo.sound.CROSSFEED_CUT_TITLE
import app.winters.octo.sound.CROSSFEED_FEED_TITLE
import app.winters.octo.sound.CROSSFEED_MONO
import app.winters.octo.ui.family.HANDOVER_TITLE
import app.winters.octo.ui.family.QUALITY_AT_HOME
import app.winters.octo.ui.family.QUALITY_AWAY
import app.winters.octo.ui.family.QUALITY_ON_THIS_DEVICE
import org.junit.Assert.assertTrue
import org.junit.Test

// Every row the Settings and Sound pages had before they became one
// Settings is still drawn somewhere on the new pages, by its title (rows
// whose title is data by their kind, in brackets), after the renames and
// removals made on purpose; and the new pages draw no row that is not on
// that list or named here as new.
class SettingsGoldenRowsTest {
    @Test(timeout = 300_000)
    fun everyRowTheOldPagesHadIsDrawnAndNoOther() {
        val drawn = SettingsWalk.windows.flatMap { window -> window.rows.map { it.title.stable } }.toSortedSet()
        val expected = (Old - Removed.keys).map { Renamed[it] ?: it }.toSortedSet() - NeverInTheWindow.keys
        val lost = expected - drawn
        val extra = drawn - expected - New.keys
        assertTrue("rows the old pages had that no page draws now:\n" + lost.joinToString("\n"), lost.isEmpty())
        assertTrue("rows drawn that the old pages did not have:\n" + extra.joinToString("\n"), extra.isEmpty())
        val stale = (Removed.keys + Renamed.keys + NeverInTheWindow.keys).filter { it in drawn }
        assertTrue("rows removed, renamed or never drawn are drawn:\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    private companion object {
        // Every row on the old Settings and Sound pages, page by page.
        val Old = setOf(
            // Servers
            HANDOVER_TITLE, "Add a server", "In your library", "Signed in as ...", "Read the library again", "Scan the server", "Its folders",
            "[each saved server]", "Sign in on another device",
            // Appearance
            ACCENT_COLOR, "Ambience", "Strength", "Movement", HOME_SECTIONS_TITLE, "[each Home shelf]", AnimatedArtSetting.TITLE, "Moving background",
            "Drift speed", "Move with the beat", "Brightness cap", PLAYLIST_COVERS_SETTING, "Calm motion", "Text size",
            // Playback
            "Autoplay", START_AFTER_SETTING, LIBRARY_ONLY_SETTING, "Equalizer, loudness, crossfade and speed",
            // Audio quality
            QUALITY_AT_HOME, QUALITY_AWAY, QUALITY_ON_THIS_DEVICE, "Stream quality",
            // Offline
            "Kept on this computer", "Folder", "Keep Liked songs", "Download quality", "[each playlist]", "Remove all",
            // Listening
            "Tell the server what you play", "Carry the queue between devices",
            // Lyrics
            "Find lyrics online", "Bloom fast singing", "Lyrics timing", "Lyrics timing on <device>",
            // Radio
            "Discovery", "Adventure", "Artist variety", "Favorites more often", FIND_STATIONS_NEAR, "No server connected", "Reading the stations",
            "Couldn't read the stations", "No stations yet", "[each station]", "Add a station", "Adding and changing stations",
            // Discord
            "Show what you're playing", "Include songs opened from files", "Friends' list shows", "First line", "Second line", "Lines as they were",
            "Time", "Picture", "Badge", "Album name on the cover", "Keep showing while paused", "Link to Last.fm",
            // System
            "Keep playing when the window closes", "Now playing notifications", "Use the system title bar", "Media keys", "NVIDIA overlay", "Start with Windows",
            "Start in the tray", "Mini player", "Keep it above other windows", "Pin it in place",
            // Keyboard
            "[each of Octo's keys]", "Use these keys from any app", "[each global shortcut]", "Use the usual keys",
            // About
            "Update ready: ...", "New versions", "Check for updates automatically", "Install updates", "Try early versions", "Details for a bug report",
            // Sound: output
            "Each output keeps its own sound",
            // Sound: the equalizer, its switch on its title
            "Equalizer", "Preset", "Bands", "Nothing to shape", "No filters yet", "Filter n of m", "Frequency", "Gain", "Width (Q)", "Filters",
            "Automatic preamp", "Preamp",
            // Sound: loudness
            "Match loudness", "Songs without loudness info", "Prevent clipping", "Limiter",
            // Sound: balance
            "Left and right", "Mono",
            // Sound: crossfeed, its switch on its title
            "Crossfeed", CROSSFEED_MONO, CROSSFEED_CUT_TITLE, CROSSFEED_FEED_TITLE,
            // Sound: crossfade and speed
            "Crossfade", "Longest blend", "Crossfade curve", "Smart transitions", "Filter sweeps", "Match tempo", "Speed", "Keep the pitch", "Pitch",
            "Speed or pitch changed",
            // Sound: headphone correction
            "[the loaded correction]", "Import a correction", "Import another", "Export my curve",
        )

        // Rows renamed, old title to new.
        val Renamed = mapOf("Moving background" to "Moving full player background")

        // Rows gone on purpose, each with why.
        val Removed = mapOf("Equalizer, loudness, crossfade and speed" to "Sound is a page of Settings now, so Playback has no link to it")

        // Rows the code still has that the window cannot draw, each with why.
        val NeverInTheWindow = mapOf("No server connected" to "drawn only without a server, and without one the window shows the sign-in page, not Settings")

        // Rows the new pages draw that the old ones did not have, each with why.
        val New = mapOf(
            "[the server's kind]" to "This server's first row names the server's kind and what it offers",
            THEME_CHOICE to "Appearance's Theme card offers the five themes",
            MATCH_THE_SYSTEM to "the Theme card's switch for day and night themes that follow the computer",
        )
    }
}
