package app.winters.octo.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class SongInfoTest {
    private val utc = ZoneOffset.UTC
    private val us = Locale.US

    private fun lines(facts: SongFacts) = infoLines(facts, utc, us).associate { it.label to it.value }

    @Test
    fun aPhoneFileSaysEverythingItKnows() {
        val facts = SongFacts(
            title = "Hold On",
            artist = "Mira",
            album = "Long Way",
            albumArtist = "Various Artists",
            year = 2021,
            genre = "Soul",
            trackNo = 3,
            discNo = 2,
            durationMs = 245_000,
            mimeType = "audio/flac",
            bitrate = 1_021_400,
            sampleRate = 44_100,
            bitDepth = 24,
            sizeBytes = 31_400_000,
            source = SongSource.Phone("Music/Mira/Long Way/03 Hold On.flac"),
            addedAtSeconds = 1_700_000_000,
            plays = 1_234,
            lastPlayedAt = 1_750_000_000_000,
            rating = 4,
            trackGain = -6.2f,
            trackPeak = 0.98765f,
            albumGain = 1.5f,
        )
        val shown = lines(facts)
        assertEquals("Various Artists", shown["Album artist"])
        assertEquals("3", shown["Track"])
        assertEquals("2", shown["Disc"])
        assertEquals("4:05", shown["Length"])
        assertEquals("FLAC, lossless", shown["Format"])
        assertEquals("audio/flac", shown["File type"])
        assertEquals("1021 kbps", shown["Bitrate"])
        assertEquals("44.1 kHz", shown["Sample rate"])
        assertEquals("24-bit", shown["Bit depth"])
        assertEquals("31.4 MB", shown["File size"])
        assertEquals("This phone", shown["Source"])
        assertEquals("Music/Mira/Long Way/03 Hold On.flac", shown["Path"])
        assertEquals("14 Nov 2023", shown["Added"])
        assertEquals("1,234", shown["Plays"])
        assertEquals("15 Jun 2025", shown["Last played"])
        assertEquals("4 stars", shown["Rating"])
        assertEquals("-6.20 dB", shown["Track gain"])
        assertEquals("0.988", shown["Track peak"])
        assertEquals("+1.50 dB", shown["Album gain"])
        // Only the path can be copied.
        assertEquals(listOf("Path"), infoLines(facts, utc, us).filter { it.copyable }.map { it.label })
    }

    @Test
    fun theTagsAndServerDetailsShowWhenKnown() {
        val facts = SongFacts(
            title = "Get Lucky",
            artist = "Daft Punk",
            album = "RAM",
            year = 2013,
            originalYear = 1979,
            genre = "Disco",
            genres = listOf("Disco", "Funk"),
            composer = "Thomas Bangalter",
            bpm = 116,
            comment = "Single edit",
            explicit = false,
            discNo = 2,
            discTitle = "Night Two",
            mbRecordingId = "rec",
            mbAlbumId = "rel",
            mbReleaseGroupId = "grp",
            mbArtistIds = listOf("a1", "a2"),
        )
        val shown = lines(facts)
        assertEquals("2013", shown["Year"])
        assertEquals("1979", shown["Original year"])
        assertEquals("Disco, Funk", shown["Genres"])
        assertEquals("Thomas Bangalter", shown["Composer"])
        assertEquals("116", shown["BPM"])
        assertEquals("Single edit", shown["Comment"])
        assertEquals("Clean", shown["Lyrics"])
        assertEquals("2 · Night Two", shown["Disc"])
        assertEquals("rec", shown["Recording MBID"])
        assertEquals("rel", shown["Release MBID"])
        assertEquals("grp", shown["Release group MBID"])
        assertEquals("a1\na2", shown["Artist MBIDs"])
        val copyable = infoLines(facts, utc, us).filter { it.copyable }.map { it.label }
        assertEquals(listOf("Recording MBID", "Release MBID", "Release group MBID", "Artist MBIDs"), copyable)
    }

    @Test
    fun anOriginalYearLikeTheYearIsShownOnce() {
        val shown = lines(SongFacts("t", "a", "b", year = 1979, originalYear = 1979, genre = "Disco", genres = listOf("disco")))
        assertEquals("1979", shown["Year"])
        assertNull(shown["Original year"])
        assertEquals("Disco", shown["Genre"])
    }

    @Test
    fun aRadioSongSaysWhichSourceSuggestedIt_RightAfterWhereItIs() {
        val keys = infoLines(SongFacts(title = "Roads", artist = "Portishead", album = "Dummy", suggestedBy = "Sounds alike"), utc, us)
            .map { it.label }
        assertEquals(keys.indexOf("Source") + 1, keys.indexOf("Suggested by"))
        assertEquals("Sounds alike", lines(SongFacts(title = "Roads", artist = "", album = "", suggestedBy = "Sounds alike"))["Suggested by"])
    }

    @Test
    fun whatIsNotKnownIsLeftOut() {
        val shown = lines(SongFacts(title = "Untitled", artist = "", album = ""))
        assertEquals(listOf("Title", "Source", "Plays"), shown.keys.toList())
        assertEquals("Found online", shown["Source"])
        assertEquals("0", shown["Plays"])
    }

    @Test
    fun aServerSongNamesTheServerAndWhereItWasDownloaded() {
        val shown = lines(SongFacts(title = "t", artist = "a", album = "b", source = SongSource.Server("Octo", "/data/octo/t.mp3")))
        assertEquals("Octo", shown["Source"])
        assertEquals("/data/octo/t.mp3", shown["Downloaded to"])
        assertEquals("Your server", lines(SongFacts("t", "a", "b", source = SongSource.Server(null, null)))["Source"])
    }

    @Test
    fun theFormatComesFromTheFileType() {
        assertEquals("MP3", formatName("audio/mpeg"))
        assertEquals("AAC", formatName("audio/mp4"))
        assertEquals("ALAC, lossless", formatName("audio/alac"))
        assertNull(formatName("application/octet-stream"))
        assertNull(formatName(null))
    }

    @Test
    fun numbersReadTheWayPeopleSayThem() {
        assertEquals("320 kbps", bitrateText(320_000))
        assertEquals("48 kHz", sampleRateText(48_000, us))
        assertEquals("96 kHz", sampleRateText(96_000, us))
        assertEquals("850 KB", sizeText(850_000, us))
        assertEquals("1.25 GB", sizeText(1_250_000_000, us))
        assertTrue(gainText(0f, us).startsWith("+"))
        assertEquals("1 star", lines(SongFacts("t", "a", "b", rating = 1))["Rating"])
    }
}
