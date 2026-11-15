package app.winters.octo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The long dash, which the app's words never use.
private val EM_DASH = Char(0x2014)

class SettingsIndexTest {
    private fun titles(query: String, entries: List<SettingEntry> = SettingsIndex.all) = searchSettings(query, entries).map { it.title }

    @Test
    fun everySettingHasItsOwnIdAndATitle() {
        val ids = SettingsIndex.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(SettingsIndex.all.all { it.title.isNotBlank() })
        assertEquals(SettingsIndex.BetweenSongs, SettingsIndex.byId("crossfade"))
    }

    @Test
    fun everyPageHasSettings() {
        SettingsPage.entries.forEach { page -> assertTrue("$page is empty", SettingsIndex.all.any { it.page == page }) }
    }

    @Test
    fun noEmDashesInWhatPeopleRead() {
        SettingsIndex.all.forEach { entry ->
            (listOf(entry.title, entry.summary, entry.place) + entry.keywords).forEach { assertTrue(it, EM_DASH !in it) }
        }
    }

    @Test
    fun nothingTypedFindsNothing() {
        assertEquals(emptyList<String>(), titles(""))
        assertEquals(emptyList<String>(), titles("   "))
    }

    @Test
    fun downloadFormatIsFoundByItsFormats() {
        listOf("opus", "aac", "codec", "download format").forEach { word -> assertEquals(word, "Download format", titles(word).first()) }
        assertTrue("Download format" in titles("mp3"))
        assertEquals(SettingsIndex.DownloadFormat, SettingsIndex.byId("download_format"))
    }

    @Test
    fun theDownloadQualityReadsInTheFormat() {
        assertEquals("128 kbps Opus", app.winters.octo.playback.StreamQuality.Kbps128.downloadLabel(app.winters.octo.playback.DownloadFormat.Opus))
        assertEquals("320 kbps AAC", app.winters.octo.playback.StreamQuality.Kbps320.downloadLabel(app.winters.octo.playback.DownloadFormat.Aac))
        assertEquals("Original", app.winters.octo.playback.StreamQuality.Original.downloadLabel(app.winters.octo.playback.DownloadFormat.Mp3))
    }

    @Test
    fun theTitleComesFirst() {
        assertEquals(listOf("Crossfade curve", "Between songs", "Length"), titles("crossfade").take(3))
        assertEquals("Crossfade curve", titles("CROSS").first())
    }

    // Between songs answers every word for what plays between two songs,
    // Smart transitions' among them, and its rows keep their ids.
    @Test
    fun betweenSongsAnswersEveryWordForIt() {
        listOf("smart transitions", "gapless", "automix", "blend", "between songs").forEach { word ->
            assertEquals(word, "Between songs", titles(word).first())
        }
        listOf("crossfade", "mix", "transition", "dj", "smart", "fade").forEach { word ->
            assertTrue(word, "Between songs" in titles(word))
        }
        assertEquals("crossfade", SettingsIndex.BetweenSongs.id)
        assertTrue(SettingsIndex.all.none { it.id == "smart_transitions" || it.title == "Smart transitions" })
        assertEquals("Length", SettingsIndex.CrossfadeLength.title)
        assertEquals(SettingsIndex.CrossfadeLength, SettingsIndex.byId("crossfade_length"))
        assertTrue("Length" in titles("longest blend"))
        listOf("crossfade_curve", "filter_sweeps", "match_tempo").forEach { id -> assertTrue(id, SettingsIndex.byId(id) != null) }
    }

    @Test
    fun otherWordsFindASetting() {
        assertEquals("Between songs", titles("gapless").first())
        assertEquals("Speed", titles("semitones").first())
        assertTrue("Speed" in titles("tempo"))
        assertTrue("Loudness" in titles("replaygain"))
    }

    @Test
    fun searchFindsTheAccentColor() {
        assertEquals("Accent color", titles("accent").first())
        assertTrue("Accent color" in titles("colour"))
        assertEquals(SettingsPage.Appearance, SettingsIndex.AccentColor.page)
        // The theme words belong to the theme now.
        assertTrue("Accent color" !in titles("palette"))
    }

    @Test
    fun searchFindsTheTheme() {
        assertEquals("Theme", titles("theme").first())
        listOf("light mode", "dark mode", "night", "day", "colors", "palette", "appearance").forEach { word ->
            assertTrue("$word finds the theme", "Theme" in titles(word))
        }
        assertEquals("Theme", titles("palette").first())
        assertEquals(SettingsPage.Appearance, SettingsIndex.Theme.page)
        listOf("match", "system", "automatic", "night").forEach { word ->
            assertTrue("$word finds Match the system", "Match the system" in titles(word))
        }
        assertEquals("Match the system", titles("match the system").first())
        assertEquals(SettingsPage.Appearance, SettingsIndex.MatchSystem.page)
    }

    @Test
    fun animatedArtIsFoundUnderItsOwnWords() {
        assertEquals("Animated art", titles("animated art").first())
        assertTrue("Animated art" in titles("video cover"))
        assertEquals(SettingsPage.Appearance, SettingsIndex.AnimatedArt.page)
        // Its words never call it moving.
        val words = listOf(SettingsIndex.AnimatedArt.title, SettingsIndex.AnimatedArt.summary) + SettingsIndex.AnimatedArt.keywords
        assertTrue(words.none { it.contains("moving", ignoreCase = true) || it.contains("motion", ignoreCase = true) })
    }

    @Test
    fun punctuationAndAccentsDoNotMatter() {
        assertTrue("Streaming on Wi-Fi" in titles("wifi"))
        assertTrue("What's new" in titles("whats new"))
        assertTrue("What's new" in titles("Whät's"))
    }

    @Test
    fun everyWordMustBeFound() {
        // Both words in the title first, then "mobile" with the page's name.
        assertEquals(
            listOf("Streaming on mobile data", "Fetch ahead on mobile data", "Download on Wi-Fi only"),
            titles("streaming mobile"),
        )
        assertEquals(emptyList<String>(), titles("crossfade bluetooth"))
    }

    @Test
    fun shortWordsOnlyMatchTheStartOfAWord() {
        // "eq" starts "equalizer" but sits inside no other word's start.
        assertEquals("Equalizer", titles("eq").first())
        assertTrue(titles("ss").isEmpty())
    }

    @Test
    fun homeSectionsAreFoundByWhatTheyDo() {
        assertEquals("Home sections", titles("rearrange").first())
        assertEquals("Home sections", titles("edit home").first())
        assertEquals("Appearance · Home", SettingsIndex.HomeSections.place)
    }

    @Test
    fun songRadioAndLiveRadioAreFoundApartByTheirWords() {
        // Song radio, mixed from your music.
        assertEquals(listOf("Discovery", "Adventure", "Artist variety", "Favorites more often"), titles("song radio").take(4))
        assertEquals("Radio · Song radio", SettingsIndex.RadioDiscovery.place)
        // Live radio, the stations on the air.
        assertEquals(listOf("Find stations near you", "Stations on your server"), titles("live radio").take(2))
        assertEquals("Radio · Live radio", SettingsIndex.RadioStations.place)
        assertTrue("Find stations near you" in titles("fm"))
        assertTrue("Stations on your server" in titles("internet radio"))
        // Radio alone finds both parts.
        assertTrue(titles("radio").containsAll(listOf("Discovery", "Find stations near you", "Stations on your server")))
    }

    @Test
    fun aResultSaysWhereItLives() {
        assertEquals("Appearance · Where it shows", SettingsIndex.AmbientHome.place)
        assertEquals("Lyrics", SettingsIndex.LyricsOnline.place)
    }

    @Test
    fun titlesRankAboveOtherWordsAboveTheSummary() {
        val entries = listOf(
            SettingEntry("summary", SettingsPage.Lyrics, "Other", summary = "screen stuff"),
            SettingEntry("keyword", SettingsPage.Lyrics, "Keep awake", keywords = listOf("screen")),
            SettingEntry("inside", SettingsPage.Lyrics, "Full screen mode"),
            SettingEntry("start", SettingsPage.Lyrics, "Screen"),
            SettingEntry("none", SettingsPage.Lyrics, "Nothing", summary = "at all"),
        )
        assertEquals(listOf("start", "inside", "keyword", "summary"), searchSettings("screen", entries).map { it.id })
    }

    @Test
    fun wordsCanBeSpreadOverTitleAndOtherWords() {
        val entries = listOf(
            SettingEntry("spread", SettingsPage.Playback, "Resume", keywords = listOf("bluetooth")),
            SettingEntry("title", SettingsPage.Playback, "Resume on bluetooth"),
        )
        assertEquals(listOf("title", "spread"), searchSettings("resume bluetooth", entries).map { it.id })
        // The page's name counts as another word; both rank the same, so
        // they keep the order they are written in.
        assertEquals(listOf("spread", "title"), searchSettings("playback resume", entries).map { it.id })
    }
}
