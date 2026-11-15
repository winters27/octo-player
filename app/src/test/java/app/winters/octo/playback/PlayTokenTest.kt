package app.winters.octo.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Every stream request of one play names it: the entry, from the item's
// own address, and its restart count, kept on the phone; a restart is the
// only thing that moves it, so Repeat One, Previous and a resume after the
// app was closed are the same play.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayTokenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aPlaysTokenIsItsEntryAndItsRestartCount() {
        val ref = StreamRef("server:a", "ext-1", "audio/webm", null, entry = "e7")
        assertEquals("e7.0", playTokenOf(ref) { 0 })
        assertEquals("e7.2", playTokenOf(ref) { if (it == "e7") 2 else 0 })
        assertNull(playTokenOf(ref.copy(entry = null)) { 0 })
    }

    @Test
    fun aPlaysRequestNamesItAndOtherReadsDoNot() {
        assertEquals(mapOf("id" to "ext-1", "format" to "raw", "octoPlay" to "e7.1"), streamParams("ext-1", StreamRequest(null), "e7.1"))
        assertEquals(mapOf("id" to "ext-1", "format" to "raw"), streamParams("ext-1", StreamRequest(null), null))
    }

    @Test
    fun aServerSongCarriesItsEntryAndItsFindMarkInItsAddress() {
        val song = MediaItem.Builder().setMediaId("find:4fJk2mQx9ZbW7tYc1RnD0a").setUri(streamUri(StreamRef("server:a", "4fJk2mQx9ZbW7tYc1RnD0a", "audio/webm", null))).build().withEntry("e7")
        val played = song.withPlayEntry()
        val ref = parseStreamUri(played.localConfiguration!!.uri.toString())!!
        assertEquals("e7", ref.entry)
        assertTrue(ref.find)
        assertSame(played, played.withPlayEntry())
        // A library song on the server is no find.
        val library = MediaItem.Builder().setMediaId("t9").setUri(streamUri(StreamRef("server:a", "tr-9", "audio/flac", null))).build().withEntry("e9")
        assertFalse(parseStreamUri(library.withPlayEntry().localConfiguration!!.uri.toString())!!.find)
        // A phone file and a song with no entry are left as they are.
        val file = MediaItem.Builder().setMediaId("t1").setUri("content://media/external/audio/media/1").build().withEntry("e8")
        assertSame(file, file.withPlayEntry())
        val none = MediaItem.Builder().setMediaId("find:4fJk2mQx9ZbW7tYc1RnD0a").setUri(streamUri(StreamRef("server:a", "4fJk2mQx9ZbW7tYc1RnD0a", null, null))).build()
        assertSame(none, none.withPlayEntry())
    }

    @Test
    fun aRestartCountIsKeptForTheNextStartOfTheApp() {
        val prefs = context.getSharedPreferences("play_restarts_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val restarts = PlayRestarts(prefs)
        val entry = "q:$QUEUE_RUN:7"
        assertEquals(0, restarts.of(entry))
        assertEquals(1, restarts.restarted(entry))
        assertEquals(2, restarts.restarted(entry))
        assertEquals(2, restarts.of(entry))
        assertEquals(2, PlayRestarts(prefs).of(entry))
        // The same number given out by another run is another entry.
        assertEquals(0, restarts.of("q:otherrun:7"))
    }

    @Test
    fun onlyTheNewestRestartCountsAreKept() {
        val prefs = context.getSharedPreferences("play_restarts_kept", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val restarts = PlayRestarts(prefs)
        repeat(PLAY_RESTARTS_KEPT + 1) { restarts.restarted("e$it") }
        assertEquals(0, restarts.of("e0"))
        assertEquals(1, restarts.of("e$PLAY_RESTARTS_KEPT"))
        assertEquals(0, PlayRestarts(prefs).of("e0"))
    }
}
