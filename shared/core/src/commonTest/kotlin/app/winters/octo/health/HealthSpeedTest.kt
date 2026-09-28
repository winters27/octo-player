package app.winters.octo.health

import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// How long the health checks take over a big library. The bound is loose
// on purpose, a guard against something quadratic rather than a benchmark;
// the numbers are printed for the record.
class HealthSpeedTest {
    private val words = listOf(
        "night", "light", "love", "heart", "fire", "rain", "city", "dream", "gold", "river", "ghost", "summer",
        "blue", "wild", "home", "run", "stay", "lost", "young", "falling", "stars", "wave", "echo", "silver",
    )

    // 100,000 songs, 10 to an album, 8 albums to an artist, with a copy of
    // every 50th song on another album and one album in 200 split in two.
    private fun library(count: Int): List<Song> {
        val random = Random(11)
        val songs = ArrayList<Song>(count)
        for (i in 0 until count) {
            val album = i / 10
            val artist = album / 8
            val title = if (i % 50 == 1) songs[i - 1].title else List(2 + random.nextInt(3)) { words[random.nextInt(words.size)] }.joinToString(" ") +
                if (i % 13 == 0) " (feat. Guest ${random.nextInt(500)})" else if (i % 17 == 0) " - Remastered 2011" else ""
            val seconds = if (i % 50 == 1) songs[i - 1].duration + 1 else 120 + random.nextInt(400)
            val split = album % 200 == 0 && i % 10 >= 5
            songs += Song(
                id = "s$i",
                title = title,
                album = "Album $album",
                albumId = if (split) "a$album-b" else "a$album",
                artist = if (i % 50 == 1) songs[i - 1].artist else "Artist $artist",
                displayAlbumArtist = "Artist $artist",
                track = if (i % 9 == 0) null else i % 10 + 1,
                year = if (i % 7 == 0) null else 1960 + artist % 60,
                genre = if (i % 5 == 0) null else "Rock",
                duration = seconds,
                suffix = if (i % 3 == 0) "mp3" else "flac",
                bitDepth = if (i % 3 == 0) null else 16,
                samplingRate = 44_100,
                bitRate = if (i % 3 == 0) 320 else 900,
                coverArt = "mf-s$i",
                isrc = if (i % 4 == 0) listOf("US" + "ABC" + (10 + i % 90) + (i / 4).toString().padStart(5, '0').takeLast(5)) else emptyList(),
            )
        }
        return songs
    }

    @Test
    fun aHundredThousandSongsAreCheckedQuickly() {
        val songs = library(100_000)
        // Once through first, so the JIT has seen the code.
        checkLibrary(songs.take(20_000), SubsonicHealth)
        var report: HealthReport<Song>? = null
        // The best of three, so a busy machine skews it less.
        val ms = (1..3).minOf {
            val start = System.nanoTime()
            report = checkLibrary(songs, SubsonicHealth)
            (System.nanoTime() - start) / 1_000_000
        }
        val found = report!!
        println(
            "Health over 100k songs: $ms ms; ${found.duplicates.size} duplicate sets, ${found.splitAlbums.size} split albums, " +
                found.missing.entries.joinToString { "${it.key} ${it.value.size}" },
        )
        assertTrue("took $ms ms", ms < 5_000)
        // Every planted copy is found, and every planted split.
        assertTrue(found.duplicates.size >= 2_000)
        assertEquals(50, found.splitAlbums.size)
    }
}
