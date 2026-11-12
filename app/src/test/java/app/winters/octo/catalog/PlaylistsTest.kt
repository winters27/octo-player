package app.winters.octo.catalog

import app.winters.octo.catalog.today.appendedItems
import app.winters.octo.catalog.today.movedItem
import app.winters.octo.catalog.today.ordAt
import app.winters.octo.catalog.today.place
import app.winters.octo.catalog.today.renumbered
import app.winters.octo.catalog.today.restoredItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaylistsTest {
    private fun item(id: Long, position: Int) =
        PlaylistItemEntity(id = id, playlistId = "p", trackId = "device:$id", relinkKey = "key$id", ord = ordAt(position))

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
        assertEquals(listOf(0, 1, 2, 3), moved.map { it.place })
    }

    @Test
    fun movingUpTakesTheTargetsPlace() {
        val moved = movedItem(items, itemId = 13, targetId = 11)
        assertEquals(listOf(10L, 13L, 11L, 12L), order(moved))
        assertEquals(listOf(0, 1, 2, 3), moved.map { it.place })
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
        assertEquals(listOf(0, 1, 2), fixed.map { it.place })
        // A row already in place is the same row, so it is not written again.
        assertSame(gappy[0], fixed[0])
    }

    @Test
    fun appendedSongsNumberOnAndKeepTheirRelinkKeys() {
        val rows = appendedItems("p", start = 4, tracks = listOf(track("device:1", "a"), track("device:2", "b")))
        assertEquals(listOf(4, 5), rows.map { it.place })
        assertEquals(listOf(4L shl 20, 5L shl 20), rows.map { it.ord })
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
    fun anUndoPutsARowBackInItsPlace() {
        // 11 was taken out from place 1; the rest were renumbered around it.
        val rest = renumbered(listOf(item(10, 0), item(12, 2), item(13, 3)))
        val restored = restoredItems(rest, item(11, 1))
        assertEquals(listOf(10L, 11L, 12L, 13L), order(restored))
        assertEquals(listOf(0, 1, 2, 3), restored.map { it.place })
    }

    @Test
    fun anUndoPutsTheFirstAndLastRowsBack() {
        assertEquals(listOf(10L, 11L, 12L, 13L), order(restoredItems(renumbered(items.drop(1)), item(10, 0))))
        assertEquals(listOf(10L, 11L, 12L, 13L), order(restoredItems(renumbered(items.dropLast(1)), item(13, 3))))
    }

    @Test
    fun anUndoAfterTheListShrankPutsTheRowLast() {
        val restored = restoredItems(listOf(item(10, 0)), item(13, 3))
        assertEquals(listOf(10L, 13L), order(restored))
        assertEquals(listOf(0, 1), restored.map { it.place })
    }

    @Test
    fun anUndoForARowAlreadyBackChangesNothing() {
        assertSame(items, restoredItems(items, item(12, 0)))
    }

    private fun row(id: String, name: String, at: Long, sourceId: String? = null, notice: String? = null) =
        PlaylistRow(id, name, at, at, sourceId, notice, false, false, false, false, false, itemSet = 0)

    @Test
    fun summariesCountLengthAndCoversPerPlaylist() {
        assertEquals(
            PlaylistSummary("new", "Empty", songCount = 0, durationMs = 0, covers = emptyList(), createdAt = 2, updatedAt = 2),
            summaryOf(row("new", "Empty", 2), 0, 0, emptyList()),
        )
        assertEquals(
            PlaylistSummary("p", "Road", songCount = 2, durationMs = 3_000, covers = listOf("cover:a", "cover:b"), createdAt = 1, updatedAt = 1),
            summaryOf(row("p", "Road", 1), 2, 3_000, listOf("cover:a", "cover:b")),
        )
    }

    @Test
    fun aSummaryCarriesTheServersNoticeMark() {
        val summary = summaryOf(row("r", "Review", 1, sourceId = "s", notice = "review"), 0, 0, emptyList())
        assertEquals("review", summary.octoNotice)
        assertEquals(true, summary.onServer)
    }

    // Picked a song at a time, the covers are the ones the whole list gives.
    @Test
    fun thePickerTakesTheMosaicsCovers() {
        val art = listOf("a" to null, "a" to "cover:a1", "c" to "cover:c", "a" to "cover:a2", "b" to "cover:b", "e" to null, "d" to "cover:d", "f" to "cover:f")
        val picker = MosaicPicker()
        art.forEach { (album, cover) -> picker.offer(album, cover) }
        assertEquals(mosaicCovers(art), picker.covers)
        assertEquals(true, picker.full)
    }
}
