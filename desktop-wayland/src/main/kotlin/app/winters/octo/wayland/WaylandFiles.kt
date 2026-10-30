package app.winters.octo.wayland

import app.winters.octo.desktop.system.FilePicker
import app.winters.octo.desktop.system.FileRequest
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.desktop.linux.Event
import org.jetbrains.desktop.linux.FileDialog
import org.jetbrains.desktop.linux.RequestId
import org.jetbrains.desktop.linux.Window
import java.io.File
import java.net.URI

// KDT uses xdg-desktop-portal's native asynchronous file chooser.
internal class WaylandFiles(private val report: (String) -> Unit, private val window: () -> Window?) : FilePicker {
    private val requests = mutableMapOf<RequestId, CompletableDeferred<File?>>()

    override suspend fun choose(request: FileRequest): File? {
        val window = window() ?: return null
        val common = FileDialog.CommonDialogParams(true, request.title, null, request.folder?.absolutePath)
        val id = if (request.save) window.showSaveFileDialog(common, FileDialog.SaveDialogParams(request.suggested))
        else window.showOpenFileDialog(common, FileDialog.OpenDialogParams(request.directory, false))
        if (id == null) {
            report("The desktop file chooser could not be opened. Check that xdg-desktop-portal is running.")
            return null
        }
        val response = CompletableDeferred<File?>()
        requests[id] = response
        try {
            return response.await()
        } finally {
            requests.remove(id)
        }
    }

    fun receive(event: Event.FileChooserResponse) {
        val file = event.files.firstOrNull()?.let { runCatching { File(URI(it)) }.getOrNull() }
        requests.remove(event.requestId)?.complete(file)
    }
}
