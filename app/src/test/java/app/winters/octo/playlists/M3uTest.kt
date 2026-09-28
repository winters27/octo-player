package app.winters.octo.playlists

import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {
    private fun track(id: String, title: String, artist: String, ms: Long = 200_000, album: String = "Album") = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = artist, artistId = "a", album = album, albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = ms, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    @Test
    fun readsExtinfLinesAndLocations() {
        val entries = parseM3u(
            """
            #EXTM3U
            #EXTINF:258,Kavinsky - Nightcall
            Music/Kavinsky/01 Nightcall.mp3
            #EXTINF:-1,Daft Punk - Digital Love
            /storage/emulated/0/Music/Digital Love.flac
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        assertEquals(M3uEntry("Music/Kavinsky/01 Nightcall.mp3", 258, "Kavinsky", "Nightcall", 3), entries[0])
        // A length of -1 means unknown.
        assertEquals(M3uEntry("/storage/emulated/0/Music/Digital Love.flac", null, "Daft Punk", "Digital Love", 5), entries[1])
    }

    @Test
    fun anExtinfWithoutAnArtistIsJustATitle() {
        val entry = parseM3u("#EXTM3U\n#EXTINF:180,Nightcall\nnightcall.mp3\n").single()
        assertEquals(null, entry.artist)
        assertEquals("Nightcall", entry.title)
        assertEquals(180, entry.seconds)
    }

    @Test
    fun takesAByteOrderMarkAndWindowsLineEndings() {
        val entries = parseM3u("\uFEFF#EXTM3U\r\n#EXTINF:200,A - B\r\nC:\\Music\\b.mp3\r\n\r\nd.mp3\r\n")
        assertEquals(listOf("C:\\Music\\b.mp3", "d.mp3"), entries.map { it.location })
        assertEquals("B", entries[0].title)
        // The second line had no #EXTINF of its own.
        assertEquals(null, entries[1].title)
        assertEquals(5, entries[1].line)
    }

    @Test
    fun aPlainListOfPathsWithNoHeaderReads() {
        assertEquals(listOf("a.mp3", "b.mp3"), parseM3u("a.mp3\nb.mp3").map { it.location })
    }

    @Test
    fun skipsOtherTagsAndReadsAttributesBeforeTheComma() {
        val entry = parseM3u("#EXTM3U\n#PLAYLIST:Mix\n#EXTINF:-1 tvg-name=\"a,b\",Artist - Title\n#EXTGRP:x\nhttp://host/song.mp3").single()
        assertEquals("Artist", entry.artist)
        assertEquals("Title", entry.title)
        assertEquals("http://host/song.mp3", entry.location)
    }

    @Test
    fun anEmptyFileHasNoEntries() {
        assertTrue(parseM3u("").isEmpty())
        assertTrue(parseM3u("#EXTM3U\n\n").isEmpty())
    }

    @Test
    fun pathsSplitWhateverTheirSlashes() {
        assertEquals(listOf("Music", "Kavinsky", "01 Nightcall.mp3"), pathParts("C:\\Music\\Kavinsky\\01 Nightcall.mp3"))
        assertEquals(listOf("Music", "a.mp3"), pathParts("..\\Music\\a.mp3"))
        assertEquals(listOf("Music", "a.mp3"), pathParts("./Music/a.mp3"))
        assertEquals(listOf("storage", "emulated", "0", "Music", "a b.mp3"), pathParts("/storage/emulated/0/Music/a b.mp3"))
        assertEquals(listOf("server", "share", "a.mp3"), pathParts("\\\\server\\share\\a.mp3"))
        assertEquals(listOf("home", "me", "a b+c.mp3"), pathParts("file:///home/me/a%20b+c.mp3"))
        assertEquals(listOf("music", "song.mp3"), pathParts("https://host/music/song.mp3?x=1"))
        assertEquals("01 Nightcall", baseName("C:\\Music\\01 Nightcall.mp3"))
    }

    @Test
    fun writesTheHeaderAndAnExtinfForEachSong() {
        val text = writeM3u(
            listOf(
                M3uLine(258, "Kavinsky", "Nightcall", "Music/Kavinsky/01 Nightcall.mp3"),
                M3uLine(0, "", "Untitled", fallbackLocation("", "Untitled")),
            ),
        )
        assertEquals(
            "#EXTM3U\n#EXTINF:258,Kavinsky - Nightcall\nMusic/Kavinsky/01 Nightcall.mp3\n#EXTINF:-1,Untitled\nUntitled\n",
            text,
        )
        assertEquals("Daft Punk - One More Time", fallbackLocation("Daft Punk", "One More Time"))
    }

    @Test
    fun whatIsWrittenReadsBack() {
        val lines = listOf(M3uLine(215, "Justice", "D.A.N.C.E.", "Music/Justice/D.A.N.C.E..mp3"))
        val back = parseM3u(writeM3u(lines)).single()
        assertEquals(M3uEntry("Music/Justice/D.A.N.C.E..mp3", 215, "Justice", "D.A.N.C.E.", 3), back)
    }

    @Test
    fun aLineBreakInATitleCannotBreakTheFile() {
        val back = parseM3u(writeM3u(listOf(M3uLine(100, "A", "Two\nLines", "x.mp3"))))
        assertEquals(1, back.size)
        assertEquals("Two Lines", back.single().title)
    }

    @Test
    fun matchesByFileBeforeAnythingElse() {
        val library = listOf(
            track("p1", "Nightcall", "Kavinsky"),
            track("p2", "Nightcall", "Kavinsky", album = "Live"),
        )
        val paths = mapOf(
            "p1" to "Music/Kavinsky/OutRun/01 Nightcall.mp3",
            "p2" to "Music/Kavinsky/Live/01 Nightcall.mp3",
        )
        // A Windows path from another computer: the folders that agree pick the copy.
        val match = matchM3u(parseM3u("D:\\Kavinsky\\Live\\01 Nightcall.mp3"), library, paths)
        assertEquals(listOf("p2"), match.trackIds)
        // A relative path, and different letter case.
        assertEquals(listOf("p1"), matchM3u(parseM3u("../OutRun/01 NIGHTCALL.MP3"), library, paths).trackIds)
    }

    @Test
    fun thenByTheExtinfArtistAndTitle() {
        val library = listOf(track("p1", "Digital Love", "Daft Punk", ms = 301_000), track("p2", "Digital Love", "Someone Else"))
        val match = matchM3u(parseM3u("#EXTINF:300,Daft Punk - Digital Love\nhttp://elsewhere/x.mp3"), library, emptyMap())
        assertEquals(listOf("p1"), match.trackIds)
    }

    @Test
    fun aTitleWithExtrasStillMatches() {
        val library = listOf(track("p1", "One More Time", "Daft Punk"))
        val match = matchM3u(parseM3u("#EXTINF:200,Daft Punk - One More Time (Radio Edit)\nx.mp3"), library, emptyMap())
        // A radio edit is a different recording.
        assertTrue(match.trackIds.isEmpty())
        val remastered = matchM3u(parseM3u("#EXTINF:200,Daft Punk - One More Time [Remastered]\nx.mp3"), library, emptyMap())
        assertEquals(listOf("p1"), remastered.trackIds)
    }

    @Test
    fun anExtinfWithoutAnArtistNeedsAnUnmistakableTitle() {
        val one = listOf(track("p1", "Nightcall", "Kavinsky"))
        assertEquals(listOf("p1"), matchM3u(parseM3u("#EXTINF:200,Nightcall\nx.mp3"), one, emptyMap()).trackIds)
        val two = one + track("p2", "Nightcall", "London Grammar", ms = 260_000)
        // Two songs of that name, and no length to tell them apart.
        assertTrue(matchM3u(parseM3u("#EXTINF:-1,Nightcall\nx.mp3"), two, emptyMap()).trackIds.isEmpty())
        // The length tells them apart.
        assertEquals(listOf("p2"), matchM3u(parseM3u("#EXTINF:261,Nightcall\nx.mp3"), two, emptyMap()).trackIds)
    }

    @Test
    fun lastByTheFileNameAsATitle() {
        val library = listOf(track("p1", "Nightcall", "Kavinsky"), track("p2", "Genesis", "Justice"))
        val match = matchM3u(parseM3u("C:\\x\\01 - Nightcall.mp3\nD:\\Justice - Genesis.flac\nD:\\missing.mp3"), library, emptyMap())
        assertEquals(listOf("p1", "p2"), match.trackIds)
        assertEquals(listOf("D:\\missing.mp3"), match.missed.map { it.shown() })
    }

    @Test
    fun missedLinesShowWhatTheFileSaid() {
        val missed = matchM3u(parseM3u("#EXTINF:100,A - B\nx.mp3\n#EXTINF:100,C\ny.mp3"), emptyList<TrackEntity>(), emptyMap()).missed
        assertEquals(listOf("A - B", "C"), missed.map { it.shown() })
    }

    @Test
    fun songsCanRepeat() {
        val library = listOf(track("p1", "Nightcall", "Kavinsky"))
        val match = matchM3u(parseM3u("#EXTINF:200,Kavinsky - Nightcall\na.mp3\n#EXTINF:200,Kavinsky - Nightcall\na.mp3"), library, emptyMap())
        assertEquals(listOf("p1", "p1"), match.trackIds)
    }

    @Test
    fun fileNamesAreMadeSafe() {
        assertEquals("Road trip  2026.m3u", playlistFileName("Road trip: 2026"))
        assertEquals("Playlist.m3u", playlistFileName("///"))
    }
}
