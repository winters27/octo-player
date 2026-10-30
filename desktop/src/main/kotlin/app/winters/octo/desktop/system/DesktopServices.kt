package app.winters.octo.desktop.system

import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.currentOs
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser

// Window-system services used outside composition. A native host installs its
// implementation before Startup; the regular desktop keeps the AWT services.
object DesktopServices {
    var clipboard: TextClipboard = AwtTextClipboard
    var files: FilePicker = AwtFilePicker
    var nativeFileDrops: NativeFileDrops? = null
}

// Native hosts receive URI lists after the compositor completes the transfer.
// The visible import page gets first refusal; other drops open as audio files.
class NativeFileDrops {
    private val targets = mutableListOf<(List<String>) -> Boolean>()
    fun register(target: (List<String>) -> Boolean): AutoCloseable {
        targets += target
        return AutoCloseable { targets -= target }
    }
    fun receive(addresses: List<String>): Boolean = targets.toList().asReversed().any { it(addresses) }
}

interface TextClipboard {
    fun text(): String?
    fun put(text: String)
    suspend fun read(): String? = text()
}

data class FileRequest(
    val title: String,
    val save: Boolean = false,
    val suggested: String? = null,
    val folder: File? = null,
    val extensions: Set<String> = emptySet(),
    val directory: Boolean = false,
)

fun interface FilePicker {
    suspend fun choose(request: FileRequest): File?
}

private object AwtTextClipboard : TextClipboard {
    override fun text(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun put(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

private object AwtFilePicker : FilePicker {
    override suspend fun choose(request: FileRequest): File? {
        if (request.directory) {
            val chooser = JFileChooser(request.folder).apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = request.title
            }
            return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
        val dialog = FileDialog(null as Frame?, request.title, if (request.save) FileDialog.SAVE else FileDialog.LOAD)
        try {
            request.folder?.let { dialog.directory = it.path }
            request.suggested?.let { dialog.file = it }
            if (request.suggested == null && currentOs() == DesktopOs.Windows && request.extensions.isNotEmpty()) {
                dialog.file = request.extensions.joinToString(";") { "*.$it" }
            }
            if (!request.save && request.extensions.isNotEmpty()) {
                dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in request.extensions }
            }
            dialog.isVisible = true
            return dialog.file?.let { File(dialog.directory, it) }
        } finally {
            dialog.dispose()
        }
    }
}
