package app.winters.octo.ui.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

// The scanner's hold on the camera: it is let go when the scanner closes,
// once, and a camera that arrives after closing, or fails to start, leaves
// nothing to let go of.
class ScannerCameraTest {
    private class Recorder : CameraBinder<String, String> {
        val calls = mutableListOf<String>()
        var fails = false

        override fun unbindAll() {
            calls += "unbindAll"
        }

        override fun bind(preview: String, reader: String) {
            if (fails) throw IllegalStateException("no camera")
            calls += "bind $preview $reader"
        }

        override fun unbind(preview: String, reader: String) {
            calls += "unbind $preview $reader"
        }

        override fun clearAnalyzer(reader: String) {
            calls += "clearAnalyzer $reader"
        }
    }

    // Red when a camera that arrives after the scanner closed is started.
    @Test
    fun aBindAfterClosingDoesNothing() {
        val binder = Recorder()
        val camera = ScannerCamera(binder)
        camera.close()
        camera.bind("p", "r")
        assertEquals(emptyList<String>(), binder.calls)
    }

    // Red when closing leaves the camera bound (its light stays on).
    @Test
    fun closingLetsGoOfWhatWasBound() {
        val binder = Recorder()
        val camera = ScannerCamera(binder)
        camera.bind("p", "r")
        camera.close()
        assertEquals(listOf("unbindAll", "bind p r", "unbind p r", "clearAnalyzer r"), binder.calls)
    }

    // Red when a second close lets go again.
    @Test
    fun closingTwiceLetsGoOnce() {
        val binder = Recorder()
        val camera = ScannerCamera(binder)
        camera.bind("p", "r")
        camera.close()
        camera.close()
        assertEquals(1, binder.calls.count { it.startsWith("unbind ") })
        assertEquals(1, binder.calls.count { it.startsWith("clearAnalyzer") })
    }

    // Red when closing lets go of an older pair, or a bind stops clearing
    // what was bound before it.
    @Test
    fun closingLetsGoOfTheLastPairOnly() {
        val binder = Recorder()
        val camera = ScannerCamera(binder)
        camera.bind("a", "ra")
        camera.bind("b", "rb")
        camera.close()
        assertEquals(listOf("unbindAll", "bind a ra", "unbindAll", "bind b rb", "unbind b rb", "clearAnalyzer rb"), binder.calls)
    }

    // Red when a failed bind is swallowed (the scanner would never say the
    // camera did not start) or recorded as bound.
    @Test
    fun aFailedBindThrowsAndLeavesNothingToLetGo() {
        val binder = Recorder().apply { fails = true }
        val camera = ScannerCamera(binder)
        assertThrows(IllegalStateException::class.java) { camera.bind("p", "r") }
        camera.close()
        assertFalse(binder.calls.any { it.startsWith("unbind ") })
    }
}
