package app.winters.octo.desktop.imports

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.currentOs
import app.winters.octo.subsonic.IMPORT_FILE_MAX_BYTES
import app.winters.octo.ui.imports.FILE_TOO_BIG
import app.winters.octo.ui.imports.IMPORT_FILE_ENDINGS
import app.winters.octo.ui.imports.ImportModel
import app.winters.octo.ui.imports.NOT_A_LIST_FILE
import app.winters.octo.ui.imports.isImportFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Paths

// A list file on this computer, for the Import page: from the system's
// file window, which starts in Downloads, or dropped on the page.

// The folder a browser saves to, when there is one.
fun downloadsFolder(home: String = System.getProperty("user.home").orEmpty()): File? =
    File(home, "Downloads").takeIf(File::isDirectory)

// The system's file window for a list file, starting in Downloads. On
// Windows the window lists only list files; elsewhere the name filter does.
fun chooseImportFile(os: DesktopOs = currentOs()): File? {
    val dialog = FileDialog(null as Frame?, "Choose your list file", FileDialog.LOAD)
    downloadsFolder()?.let { dialog.directory = it.path }
    if (os == DesktopOs.Windows) dialog.file = IMPORT_FILE_ENDINGS.joinToString(";") { "*.$it" }
    dialog.setFilenameFilter { _, name -> isImportFileName(name) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

// The list files among the addresses a drop carries. Anything that is not
// a local file Octo reads lists from is left out.
fun droppedImportFiles(addresses: List<String>): List<File> = addresses.mapNotNull { address ->
    when {
        address.startsWith("file:", ignoreCase = true) -> runCatching { Paths.get(URI(address)).toFile() }.getOrNull()
        address.contains("://") -> null
        else -> File(address)
    }
}.filter { isImportFileName(it.name) }

// Reads the file and sends it, off the window's thread. A file too big or
// of another kind is never read; the page says why.
fun sendImportFile(scope: CoroutineScope, model: ImportModel, file: File) {
    if (!isImportFileName(file.name)) {
        model.tell(NOT_A_LIST_FILE)
        return
    }
    scope.launch {
        val bytes = withContext(Dispatchers.IO) {
            try {
                if (file.length() > IMPORT_FILE_MAX_BYTES) TOO_BIG else file.readBytes()
            } catch (e: IOException) {
                null
            }
        }
        when {
            bytes == null -> model.tell("Octo could not read ${file.name}.")
            bytes === TOO_BIG -> model.tell(FILE_TOO_BIG)
            else -> model.sendFile(file.name, bytes)
        }
    }
}

// The page's content, taking a list file dropped anywhere on it. While a
// file is held over it, a quiet card says what letting go does. Other
// files go on to the window, which plays audio dropped on it.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImportDropZone(enabled: Boolean, onFile: (File) -> Unit, content: @Composable () -> Unit) {
    var over by remember { mutableStateOf(false) }
    val send by rememberUpdatedState(onFile)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                over = true
            }

            override fun onExited(event: DragAndDropEvent) {
                over = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                over = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                over = false
                val data = event.dragData() as? DragData.FilesList ?: return false
                val file = droppedImportFiles(data.readFiles()).firstOrNull() ?: return false
                send(file)
                return true
            }
        }
    }
    Box(
        if (!enabled) Modifier.fillMaxSize() else Modifier.fillMaxSize().dragAndDropTarget(
            shouldStartDragAndDrop = { event -> holdsListFile(event) },
            target = target,
        ),
    ) {
        content()
        AnimatedVisibility(over, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    .border(1.dp, OctoColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Txt(DROP_TO_IMPORT, OctoType.headline, OctoColors.TextPrimary)
            }
        }
    }
}

const val DROP_TO_IMPORT = "Drop to import"

// Stands for a file too big to read.
private val TOO_BIG = ByteArray(0)

// Whether a drag carries a list file. Where a system cannot say until the
// drop, it is taken, and the drop sorts it out.
@OptIn(ExperimentalComposeUiApi::class)
private fun holdsListFile(event: DragAndDropEvent): Boolean {
    val data = event.dragData() as? DragData.FilesList ?: return false
    return runCatching { droppedImportFiles(data.readFiles()).isNotEmpty() }.getOrDefault(true)
}
