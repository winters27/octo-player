package app.winters.octo.desktop.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

// Puts a song into the real system media controls through the system
// library and reads back what Windows says is playing. It shows briefly in
// the volume flyout, so it runs only when asked:
//
//   OCTO_LIVE_SYSTEM=1 ./gradlew :desktop:test --tests '*LiveMediaControlsTest*'
class LiveMediaControlsTest {
    @Test
    fun windowsShowsTheSongOctoPublished() {
        assumeTrue(System.getenv("OCTO_LIVE_SYSTEM") == "1")
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val controls = NativeMediaControls.load("System media controls")
        assertNotNull("the system library loads from the class path", controls)
        controls!!.use {
            assertTrue("the media controls start", it.start {})
            val now = NowPlaying(1, "live-1", "Octo live check", "Octo", "Checks", "Octo", 200_000, null, 3, 1, emptyList(), playing = true, canPrevious = true, canNext = true)
            val png = pngBytes(roundedIcon(javax.imageio.ImageIO.read(File("icons/octo.png")), 64, FlatShape))
            it.showTrack(now, CoverArt(png, File("unused")))
            it.showPlayback(now, 42_000, jumped = false)
            Thread.sleep(1_500)
            val sessions = it.describeSessions().orEmpty()
            println("The system shows:\n$sessions")
            val ours = sessions.lines().map { line -> line.split('\t') }.firstOrNull { fields -> fields.getOrNull(1) == "Octo live check" }
            assertNotNull("Windows lists the song", ours)
            assertEquals("Octo", ours!![2])
            assertEquals("Checks", ours[3])
            assertEquals("playing (4)", "4", ours[4])
            assertEquals("42000", ours[5])
            assertEquals("200000", ours[6])
            assertEquals("with a cover", "1", ours[7])
            it.clear()
        }
    }
}
