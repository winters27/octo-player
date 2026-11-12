package app.winters.octo.playback

import app.winters.octo.covers.CoverGlyph
import app.winters.octo.covers.PlaylistCoverStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlaylistArtTest {
    private val art = PlaylistArtRef(
        PlaylistCoverStyle.Designed, "3f2a|uuid", "Road trip: 2026 | the best", "Playlist", "12 songs", CoverGlyph.Heart,
        listOf("s:server:a|1", "d:content://media/2"),
    )

    @Test
    fun aPlaylistsPictureReadsBackAsItself() {
        assertEquals(art, playlistArtOf(art.encode()))
        assertEquals(art.copy(line = null, footer = null, glyph = null, covers = emptyList()), playlistArtOf(art.copy(line = null, footer = null, glyph = null, covers = emptyList()).encode()))
    }

    @Test
    fun anotherStyleIsAnotherAddress() {
        assertNotEquals(art.encode(), art.copy(style = PlaylistCoverStyle.Mosaic).encode())
    }

    @Test
    fun otherReferencesAreNotPlaylistArt() {
        assertNull(playlistArtOf("s:server:a"))
        assertNull(playlistArtOf("playlist-art:Nope|x|y|||"))
    }
}
