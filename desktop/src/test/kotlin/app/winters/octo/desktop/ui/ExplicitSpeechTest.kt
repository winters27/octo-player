package app.winters.octo.desktop.ui

import app.winters.octo.desktop.player.SectionKind
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Test

// A screen reader hears "explicit" after the title of a song whose row
// shows the small "E", and nothing for a clean edit.
class ExplicitSpeechTest {
    @Test
    fun anExplicitSongSaysSoAfterItsTitle() {
        val song = Song("s", "Dracula", artist = "Tame Impala", album = "Deadbeat", duration = 200, explicitStatus = "explicit")
        assertEquals("Dracula, explicit, Tame Impala, Deadbeat, 3:20", rowSpeech(song, playing = false, sounding = false, picked = false, outside = false, failed = null))
        assertEquals("Dracula, Tame Impala, Deadbeat, 3:20", rowSpeech(song.copy(explicitStatus = "clean"), playing = false, sounding = false, picked = false, outside = false, failed = null))
    }

    @Test
    fun theQueueSaysExplicitToo() {
        assertEquals("Dracula, explicit, Tame Impala, 3:20", queueSpeech("Dracula", "Tame Impala", "3:20", SectionKind.Coming, picked = false, failed = false, explicit = true))
    }
}
