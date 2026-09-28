package app.winters.octo.query

import app.winters.octo.subsonic.Song
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

// How long filtering a big library takes. The bound is loose on purpose, a
// guard against something quadratic rather than a benchmark; the numbers
// are printed for the record.
class QuerySpeedTest {
    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()

    private fun library(count: Int): List<Song> {
        val random = Random(7)
        val genres = listOf("Rock", "Electronic", "Jazz", "Hip-Hop", "Classical", "Pop", "Ambient", "Folk")
        return List(count) { i ->
            val album = i / 10
            val artist = album / 8
            Song(
                id = "s$i",
                title = "Song ${random.nextInt(100_000)} of the ${if (i % 7 == 0) "Café" else "night"}",
                album = "Album $album",
                albumId = "a$album",
                artist = "Artist $artist",
                year = 1960 + random.nextInt(66),
                duration = 120 + random.nextInt(400),
                suffix = if (i % 3 == 0) "flac" else "mp3",
                genre = genres[artist % genres.size],
                created = Instant.ofEpochMilli(now - random.nextLong(3_000L * 86_400_000L)).toString(),
                played = if (i % 4 == 0) null else Instant.ofEpochMilli(now - random.nextLong(900L * 86_400_000L)).toString(),
                playCount = random.nextLong(40),
                userRating = random.nextInt(6),
                starred = if (i % 11 == 0) "2026-01-01T00:00:00Z" else null,
            )
        }
    }

    @Test
    fun aHundredThousandSongsFilterQuickly() {
        val songs = library(100_000)
        val queries = mapOf(
            "words" to LibraryQuery(text = "cafe artist 12"),
            "genre and rating" to LibraryQuery(listOf(FilterPresets.genre("Electronic"), FilterPresets.ratingAtLeast(4))),
            "added this year" to LibraryQuery(listOf(FilterPresets.AddedThisYear)),
            "not played lately, lossless" to LibraryQuery(listOf(FilterPresets.NotPlayedLately, FilterPresets.Lossless), text = "night"),
        )
        // Once through first, so the JIT has seen the code.
        queries.values.forEach { it.apply(songs, now) }
        // The best of five, so a busy machine skews it less.
        queries.forEach { (name, query) ->
            var found = 0
            val ms = (1..5).minOf {
                val start = System.nanoTime()
                found = query.apply(songs, now).size
                (System.nanoTime() - start) / 1_000_000
            }
            println("Filter over 100k songs, $name: $ms ms, $found found")
            assertTrue("$name took $ms ms", ms < 2_000)
        }
    }
}
