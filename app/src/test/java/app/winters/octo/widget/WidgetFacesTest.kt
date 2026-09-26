package app.winters.octo.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFacesTest {
    private val song = WidgetPlayback(title = "Hold On, We're Going Home", artist = "Drake", isPlaying = true)

    @Test
    fun `size follows width first, then height`() {
        assertEquals(WidgetSize.Small, widgetSize(110f, 40f))
        assertEquals(WidgetSize.Small, widgetSize(180f, 40f))
        // Tall but narrow stays small: three buttons need the width.
        assertEquals(WidgetSize.Small, widgetSize(150f, 170f))
        assertEquals(WidgetSize.Medium, widgetSize(250f, 40f))
        assertEquals(WidgetSize.Medium, widgetSize(320f, 99f))
        assertEquals(WidgetSize.Large, widgetSize(250f, 110f))
        assertEquals(WidgetSize.Large, widgetSize(400f, 300f))
    }

    @Test
    fun `size thresholds are where each shape starts`() {
        assertEquals(WidgetSize.Medium, widgetSize(MEDIUM_MIN_WIDTH_DP, SMALL_MIN_HEIGHT_DP))
        assertEquals(WidgetSize.Large, widgetSize(MEDIUM_MIN_WIDTH_DP, LARGE_MIN_HEIGHT_DP))
        // No size reported yet counts as the smallest.
        assertEquals(WidgetSize.Small, widgetSize(0f, 0f))
    }

    @Test
    fun `small shows the title and play or pause only`() {
        val face = nowPlayingFace(song, WidgetSize.Small)
        assertEquals("Hold On, We're Going Home", face.title)
        assertNull(face.artist)
        assertEquals(1, face.titleLines)
        assertTrue(face.playing)
        assertFalse(face.showSkips)
        assertFalse(face.empty)
    }

    @Test
    fun `medium adds the artist and the skip buttons`() {
        val face = nowPlayingFace(song, WidgetSize.Medium)
        assertEquals("Drake", face.artist)
        assertEquals(1, face.titleLines)
        assertTrue(face.showSkips)
    }

    @Test
    fun `large gives the title a second line`() {
        val face = nowPlayingFace(song, WidgetSize.Large)
        assertEquals("Drake", face.artist)
        assertEquals(2, face.titleLines)
        assertTrue(face.showSkips)
    }

    @Test
    fun `paused shows play`() {
        assertFalse(nowPlayingFace(song.copy(isPlaying = false), WidgetSize.Medium).playing)
    }

    @Test
    fun `nothing loaded shows nothing playing and only play, at every size`() {
        WidgetSize.entries.forEach { size ->
            val face = nowPlayingFace(null, size)
            assertEquals(NOTHING_PLAYING, face.title)
            assertNull(face.artist)
            assertFalse(face.playing)
            assertFalse(face.showSkips)
            assertTrue(face.empty)
        }
    }

    @Test
    fun `a song with no title or artist still reads well`() {
        val face = nowPlayingFace(WidgetPlayback(title = "  ", artist = "", isPlaying = false), WidgetSize.Large)
        assertEquals(UNKNOWN_SONG, face.title)
        assertNull(face.artist)
        assertFalse(face.empty)
    }

    @Test
    fun `text is trimmed and runs of spaces become one`() {
        assertEquals("Take Care", clip("  Take \n  Care "))
        assertNull(clip(null))
        assertNull(clip(" \t "))
    }

    @Test
    fun `long text is cut with an ellipsis within the limit`() {
        val long = "a".repeat(200)
        val cut = clip(long)!!
        assertEquals(TEXT_LIMIT, cut.length)
        assertTrue(cut.endsWith("…"))
        val exact = "b".repeat(TEXT_LIMIT)
        assertEquals(exact, clip(exact))
    }

    @Test
    fun `cutting does not leave a space before the ellipsis`() {
        assertEquals("abc…", clip("abc  defgh", limit = 5))
    }

    @Test
    fun `commands survive the trip through an intent`() {
        val all = listOf(WidgetCommand.Play, WidgetCommand.Pause, WidgetCommand.Next, WidgetCommand.Previous) +
            QuickPick.entries.map(WidgetCommand::Quick)
        all.forEach { assertEquals(it, decodeCommand(encodeCommand(it))) }
        // Every button gets its own pending intent.
        assertEquals(all.size, all.map(::requestCode).toSet().size)
        assertNull(decodeCommand(null))
        assertNull(decodeCommand("quick:Nope"))
        assertNull(decodeCommand("stop"))
    }
}
