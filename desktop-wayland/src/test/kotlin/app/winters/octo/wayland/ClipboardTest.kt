@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
// KDT's event constructors are Kotlin-internal; these are incoming wire fixtures.
@file:Suppress("DEPRECATION", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package app.winters.octo.wayland

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.desktop.linux.DataTransferContent
import org.jetbrains.desktop.linux.Event
import org.junit.Test
import kotlin.test.*

class ClipboardTest {
    @Test fun ownSelectionOfferKeepsTheDataWeMustServe() = runTest {
        val clipboard = WaylandClipboard({ TextMimes }, { _, _ -> error("A local copy needs no pipe read") }, {})
        clipboard.put("local")
        clipboard.offered()
        assertEquals("local", clipboard.read())
        assertEquals("local", clipboard.text())
        assertEquals("local", clipboard.data(org.jetbrains.desktop.linux.DataSource.Clipboard, TextMimes.first())?.decodeToString())
    }

    @Test fun lateExternalPasteKeepsItsSnapshotButCannotReplaceLocalCopy() = runTest {
        var serial = 0
        val clipboard = WaylandClipboard({ TextMimes }, { id, _ -> serial = id }, {})
        val paste = async { clipboard.read() }
        runCurrent()
        clipboard.put("NEW local copy")
        clipboard.receive(Event.DataTransfer(serial, DataTransferContent("text/plain", "OLD external copy".toByteArray())))
        assertEquals("OLD external copy", paste.await())
        assertEquals("NEW local copy", clipboard.read())
        assertEquals("NEW local copy", clipboard.text())
        assertEquals("NEW local copy", clipboard.legacy.getText()?.text)
    }

    @Test fun replacedOfferInvalidatesLateRepliesIncludingAfterTheNewReply() = runTest {
        val serials = mutableListOf<Int>()
        val clipboard = WaylandClipboard({ TextMimes }, { id, _ -> serials += id }, {})
        val old = async { clipboard.read() }
        runCurrent()
        clipboard.offered()
        assertNull(clipboard.text())
        val current = async { clipboard.read() }
        runCurrent()
        clipboard.receive(Event.DataTransfer(serials[1], DataTransferContent("text/plain", "current".toByteArray())))
        clipboard.receive(Event.DataTransfer(serials[0], DataTransferContent("text/plain", "old".toByteArray())))
        assertEquals("old", old.await())
        assertEquals("current", current.await())
        assertEquals("current", clipboard.text())
        clipboard.cancelled()
        assertNull(clipboard.text())
    }

    @Test fun oldReplyBeforeNewOfferReadDoesNotRepopulateTheCache() = runTest {
        var serial = 0
        val clipboard = WaylandClipboard({ TextMimes }, { id, _ -> serial = id }, {})
        val paste = async { clipboard.read() }
        runCurrent()
        clipboard.cancelled()
        clipboard.receive(Event.DataTransfer(serial, DataTransferContent("text/plain", "old".toByteArray())))
        assertEquals("old", paste.await())
        assertNull(clipboard.text())
    }

    @Test fun emptyOfferReadAlsoInvalidatesPendingCachePublication() = runTest {
        var mimes = TextMimes
        var serial = 0
        val clipboard = WaylandClipboard({ mimes }, { id, _ -> serial = id }, {})
        val old = async { clipboard.read() }
        runCurrent()
        mimes = emptyList()
        assertNull(clipboard.read())
        clipboard.receive(Event.DataTransfer(serial, DataTransferContent("text/plain", "old".toByteArray())))
        assertEquals("old", old.await())
        assertNull(clipboard.text())
    }
}
