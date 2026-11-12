package app.winters.octo.livelists

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.playback.radioTrack
import app.winters.octo.query.sortSongsBy
import app.winters.octo.sort.Listening
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.TrackFields
import app.winters.octo.ui.paging.SqlOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

// A song's order key sorts as the song lists sort, every order both ways:
// songs sorted by their keys' bytes come in sortSongsBy's order, ties and
// all, with library order (sort key, then id, as the database orders them)
// last. Names and ids with a replacement character and an emoji, where
// Kotlin and the database order text differently; missing years, lengths
// and dates; equal names; songs never played among played ones.
class LiveOrderKeyTest {
    private fun library(r: Random): List<TrackEntity> {
        val names = listOf("a", "A", "b", "The B", "�", "😀", "a�", "a😀", "x10", "x9", "", "é")
        return (0 until 300).map { i ->
            radioTrack(
                id = names[r.nextInt(names.size)] + i,
                sortKey = names[r.nextInt(names.size)],
                title = names[r.nextInt(names.size)],
                artist = names[r.nextInt(names.size)],
                albumId = "al" + names[r.nextInt(names.size)],
                year = listOf(null, 0, 1999, 2001)[r.nextInt(4)],
                rating = r.nextInt(6),
                durationMs = listOf(0L, 400L, 61_000L, 120_000L)[r.nextInt(4)],
            ).copy(
                album = names[r.nextInt(names.size)],
                discNo = listOf(null, 1, 2)[r.nextInt(3)],
                trackNo = listOf(null, 0, 1, 2, 10)[r.nextInt(5)],
                addedAt = listOf(0L, 5L, 6L)[r.nextInt(3)],
            )
        }
    }

    @Test
    fun keysSortAsTheSongListsSort() {
        for (seed in 0 until 6) {
            val r = Random(seed)
            val songs = library(r).sortedWith { a, b -> SqlOrder.text(a.sortKey, b.sortKey).takeIf { it != 0 } ?: SqlOrder.text(a.id, b.id) }
            val listening = songs.filter { r.nextInt(3) == 0 }.associate { it.id to Listening(r.nextInt(3), listOf(0L, 10L, 20L)[r.nextInt(3)]) }
            val fields = TrackFields(songs.filter { r.nextBoolean() }.map { it.id }.toSet(), listening)
            for (sort in SongSort.entries) for (descending in listOf(false, true)) {
                val order = SortOrder(sort, descending)
                val expected = sortSongsBy(songs, order, fields).map { it.id }
                val keyed = songs.sortedWith { a, b -> unsigned(liveOrderKey(a, order, fields), liveOrderKey(b, order, fields)) }.map { it.id }
                assertEquals("$sort ${if (descending) "descending" else "ascending"}, seed $seed", expected, keyed)
            }
            val unsorted = songs.sortedWith { a, b -> unsigned(liveOrderKey(a, null, fields), liveOrderKey(b, null, fields)) }.map { it.id }
            assertEquals(songs.map { it.id }, unsorted)
        }
    }
}
