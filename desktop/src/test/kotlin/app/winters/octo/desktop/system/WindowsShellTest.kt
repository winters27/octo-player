package app.winters.octo.desktop.system

import app.winters.octo.desktop.player.SilentPlayer
import com.sun.jna.Native
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WindowsShellTest {
    private fun alpha(pixels: ByteArray, size: Int, icon: Int, x: Int, y: Int) =
        pixels[icon * size * size * 4 + (y * size + x) * 4 + 3].toInt() and 0xFF

    @Test
    fun theFourIconsAreDrawnAtTheSizeAskedForInTheTaskbarsColour() {
        for (size in listOf(16, 20, 24, 32)) {
            val pixels = taskbarIconPixels(size, taskbarIconColour(lightTaskbar = false))
            assertEquals(size * size * 4 * 4, pixels.size)
            // Each glyph covers its middle and leaves its corners clear.
            for (icon in 0 until 4) {
                assertTrue(alpha(pixels, size, icon, 0, 0) == 0, "icon $icon at $size has a clear corner")
                val covered = (0 until size * size).count { alpha(pixels, size, icon, it % size, it / size) > 128 }
                assertTrue(covered > size * size / 10, "icon $icon at $size is drawn ($covered pixels)")
            }
        }
        val dark = taskbarIconPixels(16, taskbarIconColour(lightTaskbar = true))
        val lit = (0 until 16 * 16).first { alpha(dark, 16, 1, it % 16, it / 16) > 200 } * 4 + 16 * 16 * 4
        assertEquals(0x1F, dark[lit].toInt() and 0xFF, "dark on a light taskbar")
        // Play and pause differ: the middle button changes as it should.
        val size = 16
        val white = taskbarIconPixels(size, taskbarIconColour(lightTaskbar = false))
        val play = white.copyOfRange(size * size * 4, size * size * 8)
        val pause = white.copyOfRange(size * size * 8, size * size * 12)
        assertTrue(!play.contentEquals(pause))
    }

    // A picture of the icons, eight times over, on a dark and a light
    // taskbar, for looking at: OCTO_SHOTS=1 writes build/shots/taskbar-icons.png.
    @Test
    fun theIconsCanBeLookedAt() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val size = 16
        val zoom = 8
        val sheet = java.awt.image.BufferedImage(size * zoom * 4, size * zoom * 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        listOf(false, true).forEachIndexed { row, light ->
            val pixels = taskbarIconPixels(size, taskbarIconColour(light))
            val back = if (light) 0xFFEEEEEE.toInt() else 0xFF202020.toInt()
            for (icon in 0 until 4) for (y in 0 until size * zoom) for (x in 0 until size * zoom) {
                val at = icon * size * size * 4 + ((y / zoom) * size + x / zoom) * 4
                val a = (pixels[at + 3].toInt() and 0xFF) / 255f
                fun mix(channel: Int, shift: Int) = ((pixels[at + channel].toInt() and 0xFF) * a + ((back shr shift) and 0xFF) * (1 - a)).toInt()
                val rgb = (0xFF shl 24) or (mix(2, 16) shl 16) or (mix(1, 8) shl 8) or mix(0, 0)
                sheet.setRGB(icon * size * zoom + x, row * size * zoom + y, rgb)
            }
        }
        val out = File("build/shots/taskbar-icons.png").apply { parentFile.mkdirs() }
        javax.imageio.ImageIO.write(sheet, "png", out)
        println("Taskbar icons: ${out.absolutePath}")
    }

    @Test
    fun buttonNumbersMatchTheLibrary() {
        assertEquals(TaskbarButton.Previous, taskbarButtonOf(1))
        assertEquals(TaskbarButton.Toggle, taskbarButtonOf(2))
        assertEquals(TaskbarButton.Next, taskbarButtonOf(3))
        assertEquals(null, taskbarButtonOf(0))
        assertEquals(listOf(0, 1, 2, 3), TaskbarProgressKind.entries.map { it.code })
    }

    // The library's taskbar calls on a window of the test's own, made but
    // never shown, so it has no taskbar button and nothing appears on
    // screen. Octo's own window is never touched.
    @Test
    fun theLibraryTakesTheTaskbarCallsOnAHiddenWindowOfOurOwn() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeFalse(GraphicsEnvironment.isHeadless())
        val library = loadShellLibrary()
        assumeNotNull(library)
        val frame = Frame("Octo taskbar test")
        try {
            frame.addNotify()
            val handle = Native.getWindowID(frame)
            assertNotEquals(0L, handle)
            assertEquals(0, library!!.octo_taskbar_attach(handle, ButtonCallback { }))
            val size = library.octo_taskbar_icon_size()
            assertTrue(size in 16..64, "icons at $size")
            val pixels = taskbarIconPixels(size, taskbarIconColour(taskbarIsLight()))
            assertEquals(0, library.octo_taskbar_set_icons(pixels, pixels.size.toLong(), size))
            assertEquals(0, library.octo_taskbar_set_buttons(1, 1, 1, 1, 0, "Previous", "Pause", "Next"))
            assertEquals(0, library.octo_taskbar_set_progress(TaskbarProgressKind.Normal.code, 30_000, 200_000))
            assertEquals(0, library.octo_taskbar_sync_now(), "the taskbar took the buttons and progress")
            assertEquals(0, library.octo_taskbar_set_progress(TaskbarProgressKind.Error.code, 30_000, 200_000))
            assertEquals(0, library.octo_taskbar_sync_now())
            assertEquals(-3, library.octo_taskbar_set_progress(9, 0, 0), "an unknown kind is refused")
        } finally {
            library?.octo_taskbar_detach()
            frame.dispose()
        }
        assertEquals(-2, library!!.octo_taskbar_set_progress(0, 0, 0), "let go: nothing is taken")
    }

    // The same through the app's own driver: the player's state reaches the
    // hidden window's taskbar, and the taskbar takes it.
    @Test
    fun theDriverFollowsThePlayer() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeFalse(GraphicsEnvironment.isHeadless())
        val library = loadShellLibrary()
        assumeNotNull(library)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = SilentPlayer(scope = scope)
        val frame = Frame("Octo taskbar test")
        val taskbar = WindowsTaskbar(library!!, player, scope, lightTaskbar = { false })
        try {
            frame.addNotify()
            taskbar.attach(Native.getWindowID(frame)) {}
            player.play(listOf(app.winters.octo.subsonic.Song("t1", "Test", duration = 200)))
            Thread.sleep(700)
            assertEquals(0, library.octo_taskbar_sync_now())
        } finally {
            taskbar.close()
            player.close()
            scope.cancel()
            frame.dispose()
        }
    }

    // A jump list under an id no app uses, set and then removed. Octo's
    // own jump list is never touched.
    @Test
    fun theLibrarySetsAJumpListOfOurOwn() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val library = loadShellLibrary()
        assumeNotNull(library)
        val id = "Octo.KotlinJumpListTest." + ProcessHandle.current().pid()
        val program = File(System.getProperty("java.home"), "bin/java.exe").absolutePath
        val list = WindowsJumpList(library!!, program, appId = id)
        try {
            var removed: List<String>? = null
            val entries = JumpEntries()
                .played(JumpTarget(JumpKind.Album, "t-1", "Test album", "Test artist"))
                .played(JumpTarget(JumpKind.Playlist, "t-2", "Test playlist"))
                .pinning(JumpTarget(JumpKind.Album, "t-3", "Pinned test"), pin = true)
            val buffer = ByteArray(1024)
            assertEquals(0L, library.octo_jump_list_set(id, program, jumpListText(entries.items()), buffer, buffer.size.toLong()), "set, with nothing taken out")
            list.publish(entries.items()) { removed = it }
            list.settle()
            assertEquals(null, removed, "nothing taken out of a new list")
            assertEquals(0L, library.octo_jump_list_removed(id, buffer, buffer.size.toLong()))
        } finally {
            list.clear()
            list.settle()
            list.close()
        }
    }
}
