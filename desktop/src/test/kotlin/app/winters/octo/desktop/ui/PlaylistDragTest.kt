package app.winters.octo.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Playlists dragged from the sidebar land only on a folder (or the
// Playlists heading), and songs never land on a folder.
class PlaylistDragTest {
    @Test
    fun picksDraggedOntoAFolderGoThereAndSongsNeverDo() {
        val drag = DragState()
        var placed: List<Playlist>? = null
        var songsDropped = false
        drag.place("pf:road", DropTarget(Rect(0f, 0f, 200f, 40f), "Move to Road trips", takesSongs = false, onPlaylists = { placed = it }) {})
        drag.place("pl:x", DropTarget(Rect(0f, 40f, 200f, 80f), "Add to X") { songsDropped = true })
        val picked = listOf(Playlist("p1", "Late night"), Playlist("p2", "Drive"))
        drag.startPlaylists(picked, Offset(10f, 60f))
        assertTrue(drag.movingPlaylists)
        assertFalse(drag.active)
        assertNull("a song target takes no playlists", drag.over)
        drag.move(Offset(10f, 10f))
        assertEquals("pf:road", drag.over?.first)
        assertEquals(DragPayload.Playlists(picked), drag.payload)
        assertTrue(drag.drop())
        assertEquals(picked, placed)
        assertFalse(drag.movingPlaylists)
        assertNull(drag.payload)

        drag.start(listOf(Song("s1", "One")), Offset(10f, 10f))
        assertNull("a folder takes no songs", drag.over)
        assertFalse(drag.drop())
        assertFalse(songsDropped)
    }
}
