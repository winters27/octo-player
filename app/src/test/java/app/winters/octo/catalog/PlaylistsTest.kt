package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaylistsTest {
    private fun item(id: Long, position: Int) =
        PlaylistItemEntity(id = id, playlistId = "p", trackId = "device:$id", relinkKey = "key$id", position = position)

    private fun order(items: List<PlaylistItemEntity>) = items.map { it.id }

    private fun track(id: String, relinkKey: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
        relinkKey = relinkKey,
    )

    private val items = listOf(item(10, 0), item(11, 1), item(12, 2), item(13, 3))

    @Test
    fun movingDownTakesTheTargetsPlace() {
        val moved = movedItem(items, itemId = 10, targetId = 12)
        assertEquals(listOf(11L, 12L, 10L, 13L), order(moved))
        assertEquals(listOf(0, 1, 2, 3), moved.map { it.position })
    }

    @Test
    fun movingUpTakesTheTargetsPlace() {
        val moved = movedItem(items, itemId = 13, targetId = 11)
        assertEquals(listOf(10L, 13L, 11L, 12L), order(moved))
        assertEquals(listOf(0, 1, 2, 3), moved.map { it.position })
    }

    @Test
    fun movingToTheEnds() {
        assertEquals(listOf(11L, 12L, 13L, 10L), order(movedItem(items, 10, 13)))
        assertEquals(listOf(13L, 10L, 11L, 12L), order(movedItem(items, 13, 10)))
    }

    @Test
    fun unknownIdsChangeNothing() {
        assertSame(items, movedItem(items, 99, 10))
        assertSame(items, movedItem(items, 10, 99))
    }

    @Test
    fun renumberingClosesGapsAndKeepsOrder() {
        val gappy = listOf(item(10, 0), item(12, 2), item(13, 5))
        val fixed = renumbered(gappy)
        assertEquals(listOf(10L, 12L, 13L), order(fixed))
        assertEquals(listOf(0, 1, 2), fixed.map { it.position })
        // A row already in place is the same row, so it is not written again.
        assertSame(gappy[0], fixed[0])
    }

    @Test
    fun appendedSongsNumberOnAndKeepTheirRelinkKeys() {
        val rows = appendedItems("p", start = 4, tracks = listOf(track("device:1", "a"), track("device:2", "b")))
        assertEquals(listOf(4, 5), rows.map { it.position })
        assertEquals(listOf("device:1", "device:2"), rows.map { it.trackId })
        assertEquals(listOf("a", "b"), rows.map { it.relinkKey })
        assertEquals(listOf("p", "p"), rows.map { it.playlistId })
    }

    @Test
    fun mosaicTakesOneCoverPerAlbumUpToFour() {
        val art = listOf(
            "a" to "cover:a1",
            "a" to "cover:a2",
            "b" to null,
            "c" to "cover:c",
            "b" to "cover:b",
            "d" to "cover:d",
            "e" to "cover:e",
            "f" to "cover:f",
        )
        assertEquals(listOf("cover:a1", "cover:c", "cover:b", "cover:d"), mosaicCovers(art))
    }

    @Test
    fun mosaicWithFewAlbums() {
        assertEquals(listOf("cover:a"), mosaicCovers(listOf("a" to "cover:a", "a" to "cover:a")))
        assertEquals(emptyList<String>(), mosaicCovers(listOf("a" to null)))
    }

    @Test
    fun summariesCountLengthAndCoversPerPlaylist() {
        val playlists = listOf(
            PlaylistEntity("new", "Empty", createdAt = 2, updatedAt = 2),
            PlaylistEntity("p", "Road", createdAt = 1, updatedAt = 1),
        )
        val entries = listOf(
            PlaylistEntry("p", albumId = "a", durationMs = 1_000, artwork = "cover:a"),
            PlaylistEntry("p", albumId = "b", durationMs = 2_000, artwork = "cover:b"),
        )
        assertEquals(
            listOf(
                PlaylistSummary("new", "Empty", songCount = 0, durationMs = 0, covers = emptyList()),
                PlaylistSummary("p", "Road", songCount = 2, durationMs = 3_000, covers = listOf("cover:a", "cover:b")),
            ),
            summarize(playlists, entries),
        )
    }
}
