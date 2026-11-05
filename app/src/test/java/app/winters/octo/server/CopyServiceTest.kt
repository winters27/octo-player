package app.winters.octo.server

import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// The copy's service is in the foreground before it ever stops, stops only
// with its latest start's id, and follows the copy and the app's place.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CopyServiceTest {
    private val run = MutableStateFlow<CopyRunState?>(null)
    private val front = MutableStateFlow(false)
    private val kept = CopyService.source

    init {
        CopyService.source = {
            object : CopySource {
                override val copyRun = run
                override val inFront = front
            }
        }
    }

    @After
    fun restore() {
        CopyService.source = kept
    }

    private fun whole() = CopyRunState(1, 1, "acc", "Home", true, true, CopyStep.Read, 500, null, null, null, 0, 0, false)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // The copy ended before the service came up: it still enters the
    // foreground, then stops with the start's own id.
    @Test
    fun aCopyThatEndedFirstStillPutsTheServiceInTheForegroundBeforeItStops() {
        val controller = Robolectric.buildService(CopyService::class.java).create()
        val service = shadowOf(controller.get())
        assertNotNull(service.lastForegroundNotification)
        controller.startCommand(0, 1)
        idle()
        assertTrue(service.isStoppedBySelf)
        assertEquals(1, service.stopSelfId)
        controller.destroy()
        assertFalse(CopyService.up)
    }

    // The app came back to the front before the service's start landed.
    @Test
    fun theAppBackInFrontFirstStillPutsTheServiceInTheForegroundBeforeItStops() {
        run.value = whole()
        front.value = true
        val controller = Robolectric.buildService(CopyService::class.java).create()
        val service = shadowOf(controller.get())
        assertNotNull(service.lastForegroundNotification)
        controller.startCommand(0, 1)
        idle()
        assertTrue(service.isStoppedBySelf)
        assertEquals(1, service.stopSelfId)
        controller.destroy()
    }

    @Test
    fun aWholeCopyKeepsTheServiceUntilTheAppComesBack() {
        run.value = whole()
        val controller = Robolectric.buildService(CopyService::class.java).create()
        assertTrue(CopyService.up)
        controller.startCommand(0, 1)
        idle()
        val service = shadowOf(controller.get())
        assertFalse(service.isStoppedBySelf)
        controller.startCommand(0, 2)
        front.value = true
        idle()
        assertTrue(service.isStoppedBySelf)
        assertEquals(2, service.stopSelfId)
        controller.destroy()
    }

    @Test
    fun aCopyEndingStopsTheService() {
        run.value = whole()
        val controller = Robolectric.buildService(CopyService::class.java).create().startCommand(0, 1)
        idle()
        run.value = null
        idle()
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }
}
