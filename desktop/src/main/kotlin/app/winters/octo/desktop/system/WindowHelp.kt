package app.winters.octo.desktop.system

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File
import java.net.URI
import java.nio.file.Paths

// Files dropped on the window, from the addresses a drop carries. Anything
// that is not a local audio file is left out.
fun droppedAudioFiles(addresses: List<String>): List<File> = addresses.mapNotNull { address ->
    when {
        address.startsWith("file:", ignoreCase = true) -> runCatching { Paths.get(URI(address)).toFile() }.getOrNull()
        // Web addresses and the like are not files on this machine.
        address.contains("://") -> null
        else -> File(address)
    }
}.filter(::isAudioFile)

// The window's content, taking audio files dropped on it. While files are
// held over it, a quiet line says what letting go will do.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AudioDropZone(onFiles: (List<File>) -> Unit, content: @Composable () -> Unit) {
    var over by remember { mutableStateOf(false) }
    val target = remember(onFiles) {
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
                val files = droppedAudioFiles(data.readFiles())
                if (files.isEmpty()) return false
                onFiles(files)
                return true
            }
        }
    }
    Box(
        Modifier.fillMaxSize().dragAndDropTarget(
            shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
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
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Txt("Drop to play", OctoType.headline, OctoColors.TextPrimary)
            }
        }
    }
}

@Suppress("FunctionName")
private interface ForegroundRights : Library {
    fun AllowSetForegroundWindow(processId: Int): Boolean
}

// On Windows, a program in the background may not bring its window to the
// front on its own. A second launch, which is in the front, lets the
// running Octo do so before handing over.
fun letRunningOctoComeForward() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
    runCatching { Native.load("user32", ForegroundRights::class.java).AllowSetForegroundWindow(ASFW_ANY) }
}

private const val ASFW_ANY = -1
