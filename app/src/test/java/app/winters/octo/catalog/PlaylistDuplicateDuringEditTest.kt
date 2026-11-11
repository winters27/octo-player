package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.playback.ListSpec
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.playlist.AddCounts
import app.winters.octo.ui.playlist.AddSource
import app.winters.octo.ui.playlist.AddSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Duplicate copies a playlist through the bulk writer, reading the source's
// pinned set a page at a time through the baseline statement. A move across
// its cursor and a removal with its Undo ahead of it, at page 7 of a
// 3,000-row source, leave the copy equal to the source as it stood when the
// copy pinned it.
@RunWith(RobolectricTestRunner::class)
class PlaylistDuplicateDuringEditTest {
    private val db = openTestCatalog()
    private val sets = PlaylistSets(db)
    private val edits = Edits(db, sets, "p")

    @After
    fun close() = db.close()

    @Test
    fun aCopyIsTheSourceAsItStood() = timed(60) {
        sets.playlistOf("p", 3_000)
        val asItStood = edits.rows().map { it.trackId }
        val source = sets.pin(WorkOwners.marked("walk:1"), "p")!!
        try {
            sets.create(PlaylistEntity("copy", "Copy", 1, 1)) { emit ->
                var page = 0
                sets.walk(source, 7) { rows ->
                    emit(rows.map { NewItem(it.trackId, it.relinkKey, it.serverSongId) })
                    if (++page == 7) {
                        val now = edits.rows()
                        edits.move(now[2_000].itemKey, now[0].ord - 1)
                        edits.move(now[3].itemKey, now[2_999].ord + 1)
                        edits.restore(edits.remove(now[1_000].itemKey))
                        edits.remove(now[1_001].itemKey)
                    }
                }
            }
        } finally {
            sets.releasePin(WorkOwners.marked("walk:1"), "p")
        }
        assertEquals(asItStood, Edits(db, sets, "copy").rows().map { it.trackId })
        assertEquals("the source's log went with the copy's pin", 0L, db.longOf("SELECT COUNT(*) FROM playlist_edit_log"))
    }

    // The picker's count of a playlist added to another reads the source's
    // pinned set a chunk at a time: removals with and without their Undo
    // and moves landing between its chunks leave the count as it stood.
    @Test
    fun thePickersCountIsTheSourceAsItStood() = timed(60) {
        db.dao().insertTracks((0 until 3_000).map { track("song$it") })
        // 3,000 rows of 2,500 songs: the first 500 songs come twice.
        sets.create(PlaylistEntity("p", "P", 1, 1)) { emit ->
            (List(2_500) { "song$it" } + List(500) { "song$it" }).map { NewItem(it, "k$it") }.chunked(SET_PAGE).forEach { emit(it) }
        }
        sets.playlistOf("q", 100)
        val sources = AddSources(db, dagger.Lazy<ListLoaders> { error("a playlist is counted from its set") }, WorkOwners(), sets)
        sources.counted = { chunk ->
            if (chunk == 1) {
                val now = edits.rows()
                // Rows of songs not counted yet: gone, gone and back, moved.
                edits.remove(now.first { it.trackId == "song2400" }.itemKey)
                edits.restore(edits.remove(now.first { it.trackId == "song2300" }.itemKey))
                edits.move(now.first { it.trackId == "song999" }.itemKey, now[0].ord - 1)
                Edits(db, sets, "p").add("song2999")
            }
        }
        val (counts, _) = sources.check("q", AddSource.Spec(ListSpec.Playlist("p")))
        assertEquals(AddCounts(2_500, 2_400, 100), counts)
    }

    private fun track(id: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    // An M3U export reads its pinned set the same way: a move across its
    // cursor and a removal with its Undo ahead of it, at page 7 of 3,000
    // rows, leave the file the playlist as it stood when the export began.
    @Test
    fun anM3uExportIsThePlaylistAsItStood() = timed(60) {
        db.dao().insertTracks((0 until 3_000).map { track("song$it") })
        sets.playlistOf("p", 3_000)
        val asItStood = edits.rows().map { it.trackId }
        val files = app.winters.octo.playlists.PlaylistFiles(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(), db, db.testMerge(null), sets, WorkOwners(),
            app.winters.octo.playlists.MatchJobs(db, WorkOwners(), FreeSpace { Long.MAX_VALUE }, 50),
            app.winters.octo.data.Exports(app.winters.octo.data.Drains(java.nio.file.Files.createTempDirectory("export").toFile())),
            FreeSpace { Long.MAX_VALUE },
            create = { _, _ -> error("an export makes no playlist") },
        )
        files.page = 7
        files.wrote = { page ->
            if (page == 7) {
                val now = edits.rows()
                edits.move(now[2_000].itemKey, now[0].ord - 1)
                edits.move(now[3].itemKey, now[2_999].ord + 1)
                edits.restore(edits.remove(now[1_000].itemKey))
                edits.remove(now[1_001].itemKey)
            }
        }
        var text = ""
        files.copy = { file, _ ->
            text = file.readText()
            true
        }
        assertEquals(null, files.export("p", android.net.Uri.parse("content://picked/p.m3u")))
        val locations = text.lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(asItStood, locations.map { it.substringAfter(" - ").ifEmpty { it } })
        assertEquals("the log went with the export's pin", 0L, db.longOf("SELECT COUNT(*) FROM playlist_edit_log"))
    }
}
