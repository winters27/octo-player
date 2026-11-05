package app.winters.octo.desktop.system

import app.winters.octo.audio.NvidiaChange
import app.winters.octo.audio.NvidiaOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// The NVIDIA overlay row: what each reading and each try shows, that a run
// from source or a build without the helper shows nothing, and that the
// button asks Windows once however often it is pressed.
class NvidiaOverlayTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun aReadingPicksTheRow() {
        assertEquals(NvidiaRow.Offer(null), rowFor(NvidiaOverlay.ON))
        assertEquals(NvidiaRow.Offer(NVIDIA_UNDONE), rowFor(NvidiaOverlay.UNDONE))
        assertEquals(NvidiaRow.Kept(justNow = false), rowFor(NvidiaOverlay.KEPT_OFF))
        assertEquals(NvidiaRow.Hidden, rowFor(NvidiaOverlay.NO_NVIDIA))
        assertEquals(NvidiaRow.Hidden, rowFor(null))
    }

    @Test
    fun aTryEndsInTheRowItCameTo() {
        assertEquals(NvidiaRow.Kept(justNow = true), rowAfter(NvidiaChange.DONE) { NvidiaOverlay.KEPT_OFF })
        // Done, yet the settings read otherwise afterwards: it did not hold.
        assertEquals(NvidiaRow.Offer(NVIDIA_FAILED), rowAfter(NvidiaChange.DONE) { NvidiaOverlay.ON })
        assertEquals(NvidiaRow.Offer(NVIDIA_NOT_ALLOWED), rowAfter(NvidiaChange.CANCELLED) { NvidiaOverlay.ON })
        assertEquals(NvidiaRow.Offer(NVIDIA_NOT_ALLOWED), rowAfter(NvidiaChange.REFUSED) { NvidiaOverlay.ON })
        assertEquals(NvidiaRow.Offer(NVIDIA_FAILED), rowAfter(NvidiaChange.ELSEWHERE) { NvidiaOverlay.ON })
        assertEquals(NvidiaRow.Offer(NVIDIA_FAILED), rowAfter(NvidiaChange.FAILED) { NvidiaOverlay.ON })
        assertEquals(NvidiaRow.Hidden, rowAfter(NvidiaChange.NO_NVIDIA) { NvidiaOverlay.NO_NVIDIA })
    }

    @Test
    fun runFromSourceThereIsNoOctoExe() {
        assertNull(octoExe(null))
        assertNull(octoExe(""))
        assertNull(octoExe(File(temp.root, "missing.exe").path))
        val exe = temp.newFile("Octo.exe")
        assertEquals(exe.path, octoExe(exe.path))
    }

    @Test
    fun aBuildWithoutTheHelperHasNone() {
        assertNull(nvidiaHelper(null))
        assertNull(nvidiaHelper(temp.root.path))
        File(temp.root, "octo-nvidia.exe").createNewFile()
        assertNotNull(nvidiaHelper(temp.root.path))
    }

    @Test
    fun withNoExeOrNoHelperTheRowStaysHidden() {
        val asked = AtomicInteger()
        val noExe = NvidiaOverlayHolder({ null }, { temp.root }, { NvidiaOverlay.ON }, { _, _ -> asked.incrementAndGet(); NvidiaChange.DONE }, CoroutineScope(Dispatchers.IO))
        noExe.refresh()
        noExe.keepOff()
        val noHelper = NvidiaOverlayHolder({ "C:/Octo.exe" }, { null }, { NvidiaOverlay.ON }, { _, _ -> asked.incrementAndGet(); NvidiaChange.DONE }, CoroutineScope(Dispatchers.IO))
        noHelper.refresh()
        noHelper.keepOff()
        Thread.sleep(200)
        assertEquals(NvidiaRow.Hidden, noExe.row.value)
        assertEquals(NvidiaRow.Hidden, noHelper.row.value)
        assertEquals(0, asked.get())
    }

    @Test
    fun theButtonAsksOnceAndAPageReopenedWhileWaitingKeepsTheWait() {
        val gate = CountDownLatch(1)
        val asked = AtomicInteger()
        val holder = NvidiaOverlayHolder(
            exe = { "C:/Users/Jane Doe/AppData/Local/OctoPlayer/Octo.exe" },
            helper = { File(temp.root, "octo-nvidia.exe") },
            read = { if (asked.get() > 0 && gate.count == 0L) NvidiaOverlay.KEPT_OFF else NvidiaOverlay.ON },
            change = { _, _ ->
                asked.incrementAndGet()
                gate.await(5, TimeUnit.SECONDS)
                NvidiaChange.DONE
            },
            scope = CoroutineScope(Dispatchers.IO),
        )
        holder.refresh()
        runBlocking { withTimeout(5_000) { holder.row.first { it == NvidiaRow.Offer(null) } } }
        holder.keepOff()
        assertEquals(NvidiaRow.Waiting, holder.row.value)
        // A second press, and Settings opened again, while Windows asks.
        holder.keepOff()
        holder.refresh()
        Thread.sleep(200)
        assertEquals(NvidiaRow.Waiting, holder.row.value)
        gate.countDown()
        runBlocking { withTimeout(5_000) { holder.row.first { it is NvidiaRow.Kept } } }
        assertEquals(NvidiaRow.Kept(justNow = true), holder.row.value)
        // Opening Settings again keeps the "reopen Octo" line until it does.
        holder.refresh()
        Thread.sleep(200)
        assertEquals(NvidiaRow.Kept(justNow = true), holder.row.value)
        assertEquals(1, asked.get())
    }
}
