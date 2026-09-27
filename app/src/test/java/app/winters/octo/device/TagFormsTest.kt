package app.winters.octo.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Every common way files write each tag, as the tag library hands them
// over: its own names for FLAC, MP3 and MP4, and the raw frame and atom
// names older files and other writers leave behind.
class TagFormsTest {
    private fun tags(vararg pairs: Pair<String, String>) =
        parseTags(pairs.groupBy({ it.first }, { it.second }).mapValues { it.value.toTypedArray() })

    private val recording = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"
    private val release = "3f0a4f44-1f1b-4b3c-9c62-7a7d7e2c1a10"
    private val group = "7c3218d7-75e0-4e8c-971f-f097b6c308c5"
    private val artistA = "056e4f3e-d505-4dad-8ec1-d04f521cbb56"
    private val artistB = "a8e8e3c4-9f0b-4d8e-9a1f-6b5b0d6f2e77"

    @Test
    fun aFlacFileAsItsCommentsName() {
        val t = tags(
            "TITLE" to "Get Lucky",
            "ARTIST" to "Daft Punk feat. Pharrell Williams",
            "ARTISTS" to "Daft Punk",
            "ARTISTS" to "Pharrell Williams",
            "ALBUMARTIST" to "Daft Punk",
            "ALBUM" to "Random Access Memories",
            "DATE" to "2013-05-17",
            "ORIGINALDATE" to "2013-05-17",
            "TRACKNUMBER" to "8",
            "TRACKTOTAL" to "13",
            "DISCNUMBER" to "1",
            "DISCTOTAL" to "1",
            "GENRE" to "Disco",
            "GENRE" to "Funk",
            "COMPOSER" to "Thomas Bangalter",
            "COMPOSER" to "Pharrell Williams",
            "BPM" to "116",
            "MUSICBRAINZ_TRACKID" to recording,
            "MUSICBRAINZ_ALBUMID" to release,
            "MUSICBRAINZ_RELEASEGROUPID" to group,
            "MUSICBRAINZ_ARTISTID" to artistA,
            "MUSICBRAINZ_ARTISTID" to artistB,
            "REPLAYGAIN_TRACK_GAIN" to "-9.12 dB",
            "REPLAYGAIN_TRACK_PEAK" to "0.988251",
            "REPLAYGAIN_ALBUM_GAIN" to "-8.40 dB",
            "REPLAYGAIN_ALBUM_PEAK" to "1.000000",
        )
        assertEquals("Get Lucky", t.title)
        assertEquals("Daft Punk feat. Pharrell Williams", t.artist)
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), t.artists)
        assertEquals("Daft Punk", t.albumArtist)
        assertEquals(2013, t.year)
        assertEquals(2013, t.originalYear)
        assertEquals(8, t.trackNo)
        assertEquals(1, t.discNo)
        assertEquals(listOf("Disco", "Funk"), t.genres)
        assertEquals("Thomas Bangalter, Pharrell Williams", t.composer)
        assertEquals(116, t.bpm)
        assertEquals(recording, t.mbRecordingId)
        assertEquals(release, t.mbAlbumId)
        assertEquals(group, t.mbReleaseGroupId)
        assertEquals(listOf(artistA, artistB), t.mbArtistIds)
        assertEquals(-9.12f, t.trackGain!!, 0.001f)
        assertEquals(0.988f, t.trackPeak!!, 0.001f)
        assertEquals(-8.4f, t.albumGain!!, 0.001f)
        assertEquals(1f, t.albumPeak!!, 0.001f)
    }

    @Test
    fun anMp3AsTheTagLibraryTranslatesIt() {
        val t = tags(
            "TITLE" to "Song",
            "ARTIST" to "Artist",
            "ALBUMARTIST" to "Band",
            "DATE" to "2017-06-16",
            "ORIGINALDATE" to "1977",
            "TRACKNUMBER" to "3/12",
            "DISCNUMBER" to "2/2",
            "COMPILATION" to "1",
            "GENRE" to "Rock",
            "ARTISTSORT" to "Artist, The",
            "ALBUMSORT" to "Album, An",
            "TITLESORT" to "Song, A",
            "COMMENT" to "Bought on vinyl",
            "MUSICBRAINZ_TRACKID" to recording,
            "MUSICBRAINZ_ALBUMID" to release,
        )
        assertEquals(2017, t.year)
        assertEquals(1977, t.originalYear)
        assertEquals(3, t.trackNo)
        assertEquals(2, t.discNo)
        assertTrue(t.compilation)
        assertEquals("Song, A", t.sortTitle)
        assertEquals("Album, An", t.sortAlbum)
        // Artist sort only files the album when there is no album-artist tag.
        assertNull(t.sortAlbumArtist)
        assertEquals("Bought on vinyl", t.comment)
        assertEquals(recording, t.mbRecordingId)
    }

    @Test
    fun rawId3FrameNamesAndUserTextFrames() {
        val t = tags(
            "TIT2" to "Frame Title",
            "TPE1" to "Frame Artist",
            "TPE2" to "Frame Band",
            "TALB" to "Frame Album",
            "TYER" to "1999",
            "TORY" to "1985",
            "TRCK" to "07/10",
            "TPOS" to "1/1",
            "TCON" to "(17)",
            "TCMP" to "1",
            "TBPM" to "98",
            "TCOM" to "Writer",
            "TSOP" to "Artist, Frame",
            "TSST" to "Side A",
            "TXXX:MusicBrainz Album Id" to release,
            "MUSICBRAINZ RELEASE GROUP ID" to group,
            "TXXX:MusicBrainz Artist Id" to "$artistA/$artistB",
            "UFID:http://musicbrainz.org" to recording,
            "TXXX:REPLAYGAIN_TRACK_GAIN" to "+1.5 dB",
        )
        assertEquals("Frame Title", t.title)
        assertEquals("Frame Artist", t.artist)
        assertEquals("Frame Band", t.albumArtist)
        assertEquals("Frame Album", t.album)
        assertEquals(1999, t.year)
        assertEquals(1985, t.originalYear)
        assertEquals(7, t.trackNo)
        assertEquals(1, t.discNo)
        assertEquals(listOf("Rock"), t.genres)
        assertTrue(t.compilation)
        assertEquals(98, t.bpm)
        assertEquals("Writer", t.composer)
        assertEquals("Side A", t.discTitle)
        assertEquals(release, t.mbAlbumId)
        assertEquals(group, t.mbReleaseGroupId)
        assertEquals(listOf(artistA, artistB), t.mbArtistIds)
        assertEquals(recording, t.mbRecordingId)
        assertEquals(1.5f, t.trackGain!!, 0.001f)
        // A band tag means the artist sort does not file the album.
        assertNull(t.sortAlbumArtist)
    }

    @Test
    fun anMp4AsTheTagLibraryTranslatesIt() {
        val t = tags(
            "TITLE" to "Track",
            "DATE" to "2017-06-16T07:00:00Z",
            "TRACKNUMBER" to "3/12",
            "DISCNUMBER" to "1/2",
            "ALBUMARTIST" to "Band",
            "COMPILATION" to "1",
            "BPM" to "120",
            "ALBUMARTISTSORT" to "Band, The",
            "----:com.apple.iTunes:MusicBrainz Track Id" to recording,
            "----:com.apple.iTunes:replaygain_album_gain" to "-7.00 dB",
            "ITUNESADVISORY" to "1",
        )
        assertEquals(2017, t.year)
        assertEquals(3, t.trackNo)
        assertEquals(1, t.discNo)
        assertTrue(t.compilation)
        assertEquals(120, t.bpm)
        assertEquals("Band, The", t.sortAlbumArtist)
        assertEquals(recording, t.mbRecordingId)
        assertEquals(-7f, t.albumGain!!, 0.001f)
        assertEquals(true, t.explicit)
    }

    @Test
    fun rawMp4AtomNames() {
        val t = tags(
            "©nam" to "Atom Title",
            "©ART" to "Atom Artist",
            "aART" to "Atom Band",
            "©alb" to "Atom Album",
            "©day" to "2020",
            "trkn" to "4/9",
            "disk" to "2/3",
            "©gen" to "Jazz",
            "cpil" to "1",
            "tmpo" to "90",
            "©wrt" to "Atom Writer",
            "soaa" to "Band, Atom",
            "rtng" to "2",
        )
        assertEquals("Atom Title", t.title)
        assertEquals("Atom Artist", t.artist)
        assertEquals("Atom Band", t.albumArtist)
        assertEquals("Atom Album", t.album)
        assertEquals(2020, t.year)
        assertEquals(4, t.trackNo)
        assertEquals(2, t.discNo)
        assertEquals(listOf("Jazz"), t.genres)
        assertTrue(t.compilation)
        assertEquals(90, t.bpm)
        assertEquals("Atom Writer", t.composer)
        assertEquals("Band, Atom", t.sortAlbumArtist)
        assertEquals(false, t.explicit)
    }

    @Test
    fun everyWayOfWritingAYear() {
        val years = mapOf(
            "2017" to 2017,
            "2017-06-16" to 2017,
            "2017-06" to 2017,
            "20170616" to 2017,
            "2017/06/16" to 2017,
            " 2017 " to 2017,
            "(2017)" to 2017,
            "2017-06-16T10:00:00Z" to 2017,
            "2017-06-16 10:00:00" to 2017,
            "\uFEFF2017" to 2017,
            "16/06/2017" to 2017,
            "June 16, 2017" to 2017,
            "c. 1965" to 1965,
        )
        years.forEach { (text, year) -> assertEquals(text, year, yearOf(text)) }
        listOf("", "  ", "0000", "17", "unknown", "0").forEach { assertNull(it, yearOf(it)) }
        assertNull(yearOf(null))
    }

    @Test
    fun theYearTagsInOrder() {
        assertEquals(2001, tags("YEAR" to "2001").year)
        assertEquals(2002, tags("RELEASEDATE" to "2002-01-01").year)
        assertEquals(2003, tags("TDRC" to "2003").year)
        assertEquals(1970, tags("ORIGINALYEAR" to "1970", "DATE" to "2003").originalYear)
        assertEquals(1971, tags("TDOR" to "1971-02").originalYear)
    }

    @Test
    fun anOriginalYearAfterTheEditionIsAMistake() {
        val t = tags("DATE" to "1990", "ORIGINALDATE" to "2005")
        assertEquals(1990, t.year)
        assertNull(t.originalYear)
        // With no edition year, the original alone is kept.
        assertEquals(1977, tags("ORIGINALDATE" to "1977").originalYear)
    }

    @Test
    fun everyWayOfWritingAPlace() {
        assertEquals(Position(3, null), position("3"))
        assertEquals(Position(3, null), position("03"))
        assertEquals(Position(3, 12), position("3/12"))
        assertEquals(Position(3, 12), position(" 3 / 12 "))
        assertEquals(Position(3, 12), position("3 of 12"))
        assertEquals(Position(3, null), position("(3)"))
        assertEquals(Position(null, 12), position("0/12"))
        assertEquals(Position(null, null), position("A1"))
        assertEquals(Position(null, null), position(""))
        assertEquals(Position(null, null), position(null))
    }

    @Test
    fun aTotalIsNeverTakenForTheNumber() {
        val t = tags("TRACKTOTAL" to "12", "TOTALDISCS" to "2")
        assertNull(t.trackNo)
        assertNull(t.discNo)
        assertEquals(5, tags("TRACK" to "5", "TOTALTRACKS" to "10").trackNo)
        assertEquals(2, tags("DISC" to "2").discNo)
    }

    @Test
    fun oldGenreNumbers() {
        assertEquals(listOf("Rock"), parseGenres(listOf("17")))
        assertEquals(listOf("Rock"), parseGenres(listOf("(17)")))
        assertEquals(listOf("Rock", "Pop"), parseGenres(listOf("(17)(13)")))
        assertEquals(listOf("Rock and Roll"), parseGenres(listOf("(17)Rock and Roll")))
        assertEquals(listOf("Remix"), parseGenres(listOf("(RX)")))
        assertEquals(listOf("Blues", "Psybient"), parseGenres(listOf("0; 191")))
        // Numbers past the list, and names with digits, stay as written.
        assertEquals(listOf("1990s", "2step", "999"), parseGenres(listOf("1990s", "2step", "999")))
    }

    @Test
    fun genresJoinedByNullsOrSeparators() {
        assertEquals(listOf("Rock", "Pop", "Soul"), tags("GENRE" to "Rock\u0000Pop", "GENRE" to "Soul").genres)
        assertEquals(listOf("House", "Techno"), tags("GENRE" to "House, Techno").genres)
    }

    @Test
    fun keysInAnyCaseAndSpellingAreOneTag() {
        assertEquals("X", tags("album artist" to "X").albumArtist)
        assertEquals("X", tags("Album_Artist" to "X").albumArtist)
        assertEquals("X", tags("\uFEFFTITLE" to "X").title)
        assertEquals(recording, tags("musicbrainz_trackid" to recording.uppercase()).mbRecordingId)
    }

    @Test
    fun valuesLoseByteOrderMarksSpacesAndNulls() {
        val t = tags("TITLE" to "\uFEFF  Title \u0000", "ARTIST" to "   ", "ARTIST" to "Real")
        assertEquals("Title", t.title)
        assertEquals("Real", t.artist)
        assertEquals("First", tags("TITLE" to "First\u0000Second").title)
    }

    @Test
    fun compilationForms() {
        assertTrue(tags("COMPILATION" to "true").compilation)
        assertTrue(tags("TCMP" to "yes").compilation)
        assertFalse(tags("COMPILATION" to "0").compilation)
    }

    @Test
    fun tempoForms() {
        assertEquals(128, bpmOf("128"))
        assertEquals(129, bpmOf("128.6"))
        assertEquals(128, bpmOf("128 BPM"))
        assertNull(bpmOf("0"))
        assertNull(bpmOf("fast"))
    }

    @Test
    fun explicitForms() {
        assertEquals(true, explicitOf("4"))
        assertEquals(true, explicitOf("Explicit"))
        assertEquals(false, explicitOf("clean"))
        assertNull(explicitOf("0"))
    }

    @Test
    fun musicBrainzIdsHoweverTheyAreJoined() {
        assertEquals(listOf(artistA, artistB), musicIds(listOf("$artistA; $artistB")))
        assertEquals(listOf(artistA), musicIds(listOf(" ${artistA.uppercase()} ", artistA)))
        assertEquals(emptyList<String>(), musicIds(listOf("not an id", "")))
    }

    @Test
    fun r128GainIsUsedWhenThereIsNoOther() {
        val t = tags("R128_TRACK_GAIN" to "-512")
        assertEquals(3f, t.trackGain!!, 0.001f)
    }

    @Test
    fun theOnlyTagsSomeFilesHave() {
        // Most of the owner's files: title, artist and album, nothing else.
        val t = tags("TITLE" to "T", "ARTIST" to "A", "ALBUM" to "B")
        assertEquals(FileTags(title = "T", artist = "A", album = "B", artists = listOf("A")), t)
    }
}
