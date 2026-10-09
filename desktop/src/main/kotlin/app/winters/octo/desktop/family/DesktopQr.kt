package app.winters.octo.desktop.family

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
import app.winters.octo.ui.family.readQr
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// A link drawn as a QR code, dark on white with a quiet border, for a
// phone's camera to read off the screen. Made on this computer.
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier, side: Dp = 220.dp, label: String = "QR code") {
    val code = remember(text) { qrCode(text) }
    Box(
        modifier
            .size(side)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(side / 14)
            .semantics { contentDescription = label },
    ) {
        Canvas(Modifier.size(side - side / 7)) {
            val cell = size.width / code.size
            for (y in 0 until code.size) for (x in 0 until code.size) {
                if (code.isDark(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

// The text of a QR code in a picture, or null when there is none.
fun readQrImage(image: BufferedImage): String? {
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return null
    val pixels = image.getRGB(0, 0, width, height, null, 0, width)
    return readQr(pixels, width, height)
}

// The same from a picture file (PNG, JPEG, GIF or BMP).
fun readQrFile(file: File): String? = runCatching { ImageIO.read(file) }.getOrNull()?.let(::readQrImage)

// What the clipboard holds that could be a family link: its text, or the
// text of a QR code in a picture copied there (a screenshot, an image
// copied from a chat), or of the first picture file copied.
fun readClipboardForLink(clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard): String? {
    val contents = runCatching { clipboard.getContents(null) }.getOrNull() ?: return null
    if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        runCatching { contents.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()?.takeIf(String::isNotBlank)?.let { return it.trim() }
    }
    if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        val image = runCatching { contents.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull()
        image?.let(::toBuffered)?.let(::readQrImage)?.let { return it }
    }
    if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val files = runCatching { contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> }.getOrNull()
        files?.filterIsInstance<File>()?.firstNotNullOfOrNull(::readQrFile)?.let { return it }
    }
    return null
}

// Just the clipboard's text, for the quiet check when the app opens: it
// never reads pictures without being asked.
fun clipboardText(clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard): String? = runCatching {
    val contents = clipboard.getContents(null)
    if (contents?.isDataFlavorSupported(DataFlavor.stringFlavor) == true) contents.getTransferData(DataFlavor.stringFlavor) as? String else null
}.getOrNull()?.trim()?.takeIf(String::isNotEmpty)

private fun toBuffered(image: Image): BufferedImage? {
    if (image is BufferedImage) return image
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    if (width <= 0 || height <= 0) return null
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { buffered ->
        val graphics = buffered.createGraphics()
        graphics.drawImage(image, 0, 0, null)
        graphics.dispose()
    }
}
