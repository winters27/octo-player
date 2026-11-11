package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A build's copy keys its rows as the page's copy does, so a row tapped on
// the page names the same row in the build: played from row 1,200 of a big
// album, the queue holds the tapped row once and starts there.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class CopyKeyTest {
    private val rig = QueueRig(page = 200)

    @After
    fun close() = rig.close()

    @Suppress("UNCHECKED_CAST")
    private fun walk(owner: String): List<Keyed<Any>> = rig.run {
        val loader = rig.loaders.albumCopy(owner) as ListLoader<Any>
        val rows = ArrayList<Keyed<Any>>()
        var from: RowKey? = null
        while (true) {
            val page = loader.after(from, 500)
            rows += page
            if (page.size < 500) break
            from = page.last().key
        }
        rows
    }

    @Test
    fun aRowTappedOnThePageIsTheSameRowInTheBuild() {
        val songs = rig.db.songs(3_000, album = "big").map { it.id }
        rig.run {
            rig.loaders.buildAlbum(WorkOwners.marked("vm:1"), "big")
            rig.loaders.buildAlbum(WorkOwners.marked("queue:999"), "big")
        }
        val screen = walk(WorkOwners.marked("vm:1"))
        val copy = walk(WorkOwners.marked("queue:999"))
        assertEquals(screen.map { it.key }, copy.map { it.key })
        val tapped = screen[1_200]
        val tappedId = songOf(rig.loaders.albumCopy(WorkOwners.marked("vm:1")) as ListLoader<Any>, tapped.row)!!
        rig.play(PlayRequest(ListSpec.Album("big", withFinds = true), ListStart.Row(tappedId, tapped.key), shownOverWindow = true))
        assertEquals(tappedId, rig.current())
        rig.joined()
        assertEquals(songs, rig.songs())
        assertEquals(1, rig.songs().count { it == tappedId })
    }
}
