package app.winters.octo.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The shared playlist file parts. The phone's M3uTest reads and matches
// files through them too, against its own catalog.
class M3uTextTest {
    private val library = listOf(
        M3uSong("s1", "Nightcall", "Kavinsky", 258_000),
        M3uSong("s2", "Digital Love", "Daft Punk", 301_000),
        M3uSong("s3", "Genesis", "Justice", 234_000),
    )

    @Test
    fun whatIsWrittenFindsTheSameSongsAgain() {
        val text = writeM3u(
            listOf(
                M3uLine(258, "Kavinsky", "Nightcall", "Kavinsky/OutRun/01 - Nightcall.flac"),
                M3uLine(301, "Daft Punk", "Digital Love", fallbackLocation("Daft Punk", "Digital Love")),
            ),
        )
        val match = matchM3u(parseM3u(text), library, emptyMap())
        assertEquals(listOf("s1", "s2"), match.trackIds)
        assertTrue(match.missed.isEmpty())
    }

    @Test
    fun aServerPathFindsItsSongBeforeTheTitle() {
        val twins = library + M3uSong("s9", "Nightcall", "Kavinsky", 258_000)
        val paths = mapOf("s9" to "Kavinsky/Live/01 - Nightcall.flac", "s1" to "Kavinsky/OutRun/01 - Nightcall.flac")
        val match = matchM3u(parseM3u("#EXTINF:258,Kavinsky - Nightcall\nD:\\Music\\Kavinsky\\Live\\01 - Nightcall.flac"), twins, paths)
        assertEquals(listOf("s9"), match.trackIds)
    }

    @Test
    fun linesNotInTheLibraryAreMissed() {
        val match = matchM3u(parseM3u("#EXTINF:200,Nobody - Nothing\nx.mp3\nC:\\Justice - Genesis.mp3"), library, emptyMap())
        assertEquals(listOf("s3"), match.trackIds)
        assertEquals(listOf("Nobody - Nothing"), match.missed.map { it.shown() })
    }

    @Test
    fun aFileIsReadAsUtf8OrElseAsWindowsWestern() {
        assertEquals("Beyoncé - Halo", playlistText("Beyoncé - Halo".toByteArray(Charsets.UTF_8)))
        assertEquals("Beyoncé - Halo", playlistText("Beyoncé - Halo".toByteArray(charset("windows-1252"))))
    }

    @Test
    fun aPlaylistIsNamedAfterItsFile() {
        assertEquals("Road trip", playlistNameOf("Road trip.m3u8"))
        assertEquals("Road trip", playlistNameOf("C:\\Users\\me\\Road trip.m3u"))
        assertEquals("Imported playlist", playlistNameOf(".m3u"))
        assertEquals("AC DC.m3u", playlistFileName("AC/DC"))
    }
}
