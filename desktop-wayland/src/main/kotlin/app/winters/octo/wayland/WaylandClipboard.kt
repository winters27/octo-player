@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package app.winters.octo.wayland

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import app.winters.octo.desktop.system.TextClipboard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.desktop.linux.Application
import org.jetbrains.desktop.linux.DataSource
import org.jetbrains.desktop.linux.Event
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

internal val TextMimes = listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING")

internal class WaylandClipboard(
    private val availableMimes: () -> List<String>,
    private val paste: (Int, List<String>) -> Unit,
    private val publish: (List<String>) -> Unit,
) : TextClipboard, Clipboard {
    constructor(application: Application) : this(application::clipboardGetAvailableMimeTypes, application::clipboardPaste, application::clipboardPut)

    private var cached: String? = null
    private var owned: String? = null
    private var serial = 0
    private var receivedSerial = 0
    private var generation = 0L
    private data class Read(val generation: Long, val reply: CompletableDeferred<String?>)
    private val requests = mutableMapOf<Int, Read>()

    override fun text(): String? = cached
    override fun put(text: String) {
        generation++
        owned = text
        cached = text
        publish(TextMimes)
    }

    override suspend fun read(): String? {
        owned?.let { return it }
        if (availableMimes().none { it in TextMimes }) {
            generation++
            cached = null
            return null
        }
        val id = ++serial
        val reply = CompletableDeferred<String?>()
        requests[id] = Read(generation, reply)
        try {
            paste(id, TextMimes)
            return withTimeoutOrNull(3_000) { reply.await() }
        } finally {
            requests.remove(id)
        }
    }

    fun receive(event: Event.DataTransfer) {
        val request = requests.remove(event.serial) ?: return
        val text = event.content?.data?.decodeToString()
        if (request.generation == generation && event.serial >= receivedSerial) {
            receivedSerial = event.serial
            cached = text
        }
        // A paste already requested from the old owner keeps its snapshot;
        // it must not publish that snapshot as the new owner's cached value.
        request.reply.complete(text)
    }

    // KDT reports offers for our own selection too. Only source cancellation
    // means ownership was lost; an offer alone must not discard data we serve.
    fun offered() { generation++; cached = owned }
    fun cancelled() { generation++; owned = null; cached = null }
    fun data(source: DataSource, mime: String): ByteArray? =
        if (source == DataSource.Clipboard && mime in TextMimes) owned?.toByteArray() else null

    // Transferable is a data container here; it never opens an AWT clipboard or window.
    override suspend fun getClipEntry(): ClipEntry? = read()?.let { ClipEntry(StringSelection(it)) }
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.asAwtTransferable?.let {
            if (it.isDataFlavorSupported(DataFlavor.stringFlavor)) it.getTransferData(DataFlavor.stringFlavor) as? String else null
        }
        put(text.orEmpty())
    }
    override val nativeClipboard: Any get() = this

    val legacy = object : ClipboardManager {
        override fun getText(): AnnotatedString? = cached?.let(::AnnotatedString)
        override fun setText(annotatedString: AnnotatedString) = put(annotatedString.text)
        override fun hasText(): Boolean = !cached.isNullOrEmpty()
    }
}
