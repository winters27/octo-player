package app.winters.octo.desktop.family

import app.winters.octo.design.Txt
import app.winters.octo.design.OctoColors
import app.winters.octo.design.DesktopType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.ui.family.qrCode
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor

// A link drawn as a QR code, dark on white with a quiet border, for a
// phone's camera to read off the screen. Made on this computer.
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier, side: Dp = 220.dp, label: String = "QR code", quiet: Dp = side / 14, corner: Dp = 12.dp) {
    val code = remember(text) { qrCode(text) }
    Box(
        modifier
            .size(side)
            .background(Color.White, RoundedCornerShape(corner))
            .padding(quiet)
            .semantics { contentDescription = label },
    ) {
        Canvas(Modifier.size(side - quiet * 2)) {
            val cell = size.width / code.size
            for (y in 0 until code.size) for (x in 0 until code.size) {
                if (code.isDark(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

// The clipboard's text, for a family link pasted or offered when the app
// opens.
fun clipboardText(clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard): String? = runCatching {
    val contents = clipboard.getContents(null)
    if (contents?.isDataFlavorSupported(DataFlavor.stringFlavor) == true) contents.getTransferData(DataFlavor.stringFlavor) as? String else null
}.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
