package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.queueQuery
import app.winters.octo.health.HealthCheck
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.PhoneFile
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.ui.library.health.HealthShown
import app.winters.octo.ui.library.health.healthLibrary
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Every copy a fill reads is read as it was made: a song renamed while the
// list is being written (a merge's relink) is followed to the song it is
// now, and every row is queued once. Covers the sort copy, a folder's
// ordered copy, a live list's copy and a health check's copy; the album
// copy is AlbumCopyTest's.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class CopyRenameDuringBuildTest {
    private var rig = QueueRig(page = 20)

    @After
    fun close() = rig.close()

    // Renames `ids` before the build's second page, then plays `spec` big
    // and answers the queue's songs once it joined.
    private fun playRenaming(spec: ListSpec, ids: List<String>): List<String> {
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 1) ids.forEach { rig.db.rename(it, "$it-new") }
            }
        }
        rig.play(PlayRequest(spec, null, shownOverWindow = true))
        rig.joined()
        return rig.songs()
    }

    private fun assertFollowed(before: List<String>, renamed: List<String>, songs: List<String>) {
        val expected = before.map { if (it in renamed) "$it-new" else it }
        assertEquals("words: ${rig.words}", expected.toSet(), songs.toSet())
        assertEquals("each row once", expected.size, songs.size)
        assertTrue(renamed.all { "$it-new" in songs })
    }

    @Test
    fun aSortCopy() {
        val songs = rig.db.songs(120).map { it.id }
        val renamed = songs.takeLast(10)
        val queued = playRenaming(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Rating, true)), renamed)
        assertFollowed(songs, renamed, queued)
    }

    @Test
    fun aFoldersOrderedCopy() {
        val files = PhoneFiles(120) { i ->
            PhoneFile(
                id = i + 1L, displayName = "%04d Song.flac".format(i), relativePath = "Music/Z/",
                title = "Song $i", artist = "Band", album = "Many", albumArtist = "Band", track = i, year = 2001,
            )
        }
        val phone = rig.db.phoneLibrary(files, rig.merge)
        rig.run { phone.rescan() }
        val songs = rig.run { rig.db.queueDao().ids(queueQuery("SELECT id FROM track ORDER BY id")) }
        assertEquals(120, songs.size)
        val renamed = songs.takeLast(10)
        val queued = playRenaming(ListSpec.Folder("", recursive = true), renamed)
        assertFollowed(songs, renamed, queued)
    }

    @Test
    fun aLiveListsCopy() {
        val rock = LibraryQuery(rules = listOf(QueryRule(QueryField.Genre, QueryOp.Is, text = "Rock")))
        val songs = rig.db.songs(240, genre = { if (it % 2 == 0) "Rock" else "Jazz" }).filter { it.genre == "Rock" }.map { it.id }
        // Live lists wait for the merge's keys; the editor shows the list
        // before it plays.
        rig.run {
            rig.merge.start()
            rig.live.watch(app.winters.octo.catalog.WorkOwners.marked("draft:1"), null, kotlinx.coroutines.flow.flowOf(rock)).first { it is app.winters.octo.livelists.LiveShown.Shown }
        }
        val renamed = songs.takeLast(10)
        val queued = playRenaming(ListSpec.Live(null, rock, "Rock"), renamed)
        assertFollowed(songs, renamed, queued)
    }

    @Test
    fun aHealthChecksCopy() {
        rig.close()
        rig = QueueRig(page = 5)
        rig.db.healthLibrary(albums = 60)
        // A report waits for the merge's keys.
        val shown = rig.run {
            rig.merge.start()
            rig.health.buildNow()
        } as HealthShown.Shown
        val check = shown.counts.filterKeys { it != HealthCheck.NoLength }.maxBy { it.value }.key
        val spec = ListSpec.Health(shown.gen, check)
        // The published report's lines, as the check's page reads them.
        val before = rig.run { rig.health.loader(check, "probe").after(null, 100_000).map { it.row } }.distinct()
        assertTrue("$check has songs to rename", before.size > 15)
        val renamed = before.takeLast(3)
        val queued = playRenaming(spec, renamed)
        assertEquals(before.map { if (it in renamed) "$it-new" else it }.toSet(), queued.toSet())
        assertTrue(renamed.all { "$it-new" in queued })
    }
}
