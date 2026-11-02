package app.winters.octo.playback

import app.winters.octo.covers.CoverGlyph
import app.winters.octo.covers.PlaylistCoverStyle
import kotlinx.coroutines.test.runTest
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

    @Test
    fun songRadioPlaysTheSongThenThoseLikeIt() = runTest {
        val seed = track("device:1", "Midnight City")
        val play = (carRadio(seed) { listOf("device:2", "find:3") } as CarPlayAnswer.Play).play
        assertEquals(listOf("device:1", "device:2", "find:3"), play.ids)
        assertEquals(QueueSource.Played("Midnight City radio"), play.source)
        assertEquals(true, play.network)
    }

    @Test
    fun songRadioWithNothingLikeItSaysSo() = runTest {
        assertEquals(CarPlayAnswer.Refused(NO_SIMILAR_SONGS), carRadio(track("device:1")) { emptyList() })
        assertEquals(CarPlayAnswer.Refused(CAR_NOTHING_TO_PLAY), carRadio(null) { listOf("x") })
    }
}
