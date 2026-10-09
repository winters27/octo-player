package app.winters.octo.desktop.family

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.ui.family.familyLinkInQr
import app.winters.octo.ui.family.readQr
import com.sun.jna.Library
import com.sun.jna.Native
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

// The webcam calls of the system library (system-shim), version 4 on.
internal interface CameraLibrary : Library {
    fun octo_system_version(): Int
    fun octo_camera_names(buffer: ByteArray?, capacity: Long): Long
    fun octo_camera_open(index: Int): Long
    fun octo_camera_picture(handle: Long, seen: Long, buffer: ByteArray, capacity: Long, size: IntArray): Long
    fun octo_camera_close(handle: Long)
}

// Where the scanner's pictures come from: the cameras by name, one opened
// by its place in that list, and its newest picture as RGB.
interface CameraSource {
    fun names(): List<String>

    // A handle above 0, or 0 and below when it did not start.
    fun open(index: Int): Long

    // The picture's number above 0 (copied into `buffer`, its size into
    // `size`), 0 when there is no newer one, below 0 once it stopped.
    fun picture(handle: Long, seen: Long, buffer: ByteArray, size: IntArray): Long

    fun close(handle: Long)
}

// A webcam through the system library. Null from load() when the library
// has no camera (an older or missing one).
class Webcam internal constructor(private val library: CameraLibrary) : CameraSource {
    override fun names(): List<String> {
        val size = library.octo_camera_names(null, 0)
        if (size <= 0) return emptyList()
        val buffer = ByteArray(size.toInt())
        library.octo_camera_names(buffer, buffer.size.toLong())
        return String(buffer, Charsets.UTF_8).lines().filter(String::isNotBlank)
    }

    override fun open(index: Int): Long = library.octo_camera_open(index)

    override fun picture(handle: Long, seen: Long, buffer: ByteArray, size: IntArray): Long =
        library.octo_camera_picture(handle, seen, buffer, buffer.size.toLong(), size)

    override fun close(handle: Long) = library.octo_camera_close(handle)

    companion object {
        fun load(): Webcam? = runCatching {
            Native.load("octo_system", CameraLibrary::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
                .takeIf { it.octo_system_version() >= 4 }
                ?.let(::Webcam)
        }.getOrNull()
    }
}

// The camera scanner on the join screen: the picture as it comes, and the
// family link once a QR code in it holds one. Pictures are read a few
// times a second, off the window's thread.
@Stable
class CameraScanner(private val camera: () -> CameraSource?, private val scope: CoroutineScope) {
    var picture by mutableStateOf<ImageBitmap?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var cameras by mutableStateOf<List<String>>(emptyList())
        private set
    var chosen by mutableStateOf(0)
        private set
    var found by mutableStateOf<FamilyLink?>(null)
        private set

    // A QR code read that holds something other than a family link.
    var notALink by mutableStateOf(false)
        private set

    private var running: Job? = null

    val scanning: Boolean get() = running?.isActive == true

    fun start(index: Int = chosen) {
        stop()
        found = null
        notALink = false
        problem = null
        chosen = index
        running = scope.launch(Dispatchers.IO) {
            val webcam = camera()
            if (webcam == null) {
                problem = "This computer's camera can't be used by Octo. Read the QR code from a picture instead."
                return@launch
            }
            cameras = runCatching { webcam.names() }.getOrDefault(emptyList())
            if (cameras.isEmpty()) {
                problem = "No camera found. Plug one in, or read the QR code from a picture."
                return@launch
            }
            val handle = webcam.open(index.coerceIn(0, cameras.lastIndex))
            if (handle <= 0) {
                problem = "The camera did not start."
                return@launch
            }
            try {
                watch(webcam, handle)
            } finally {
                webcam.close(handle)
            }
        }
    }

    private suspend fun watch(webcam: CameraSource, handle: Long) {
        var buffer = ByteArray(1280 * 720 * 3)
        val size = IntArray(2)
        var seen = 0L
        var frames = 0
        while (currentCoroutineContextActive()) {
            val got = webcam.picture(handle, seen, buffer, size)
            when {
                got < 0 -> {
                    problem = "The camera stopped. Another app may be using it."
                    return
                }
                got == 0L -> {
                    val needed = size[0] * size[1] * 3
                    if (needed > buffer.size) buffer = ByteArray(needed)
                    delay(30)
                }
                else -> {
                    seen = got
                    val width = size[0]
                    val height = size[1]
                    val argb = toArgb(buffer, width, height)
                    picture = withContext(Dispatchers.Default) { bitmapOf(argb, width, height) }
                    // Reading every third picture keeps the preview smooth.
                    if (frames++ % 3 == 0) {
                        val text = readQr(argb, width, height)
                        if (text != null) {
                            val link = familyLinkInQr(text)
                            if (link != null) {
                                found = link
                                return
                            }
                            notALink = true
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        running?.cancel()
        running = null
    }

    // Done with what was found; the scanner can start again.
    fun forget() {
        stop()
        found = null
        picture = null
    }

    private suspend fun currentCoroutineContextActive(): Boolean = kotlin.coroutines.coroutineContext.isActive
}

// RGB, three bytes a pixel, as ARGB pixels.
internal fun toArgb(rgb: ByteArray, width: Int, height: Int): IntArray {
    val out = IntArray(width * height)
    for (i in out.indices) {
        val at = i * 3
        out[i] = (0xFF shl 24) or ((rgb[at].toInt() and 0xFF) shl 16) or ((rgb[at + 1].toInt() and 0xFF) shl 8) or (rgb[at + 2].toInt() and 0xFF)
    }
    return out
}

private fun bitmapOf(argb: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(argb.size * 4)
    for (i in argb.indices) {
        val p = argb[i]
        val at = i * 4
        // Skia's N32 on these systems is BGRA.
        bytes[at] = (p and 0xFF).toByte()
        bytes[at + 1] = ((p shr 8) and 0xFF).toByte()
        bytes[at + 2] = ((p shr 16) and 0xFF).toByte()
        bytes[at + 3] = 0xFF.toByte()
    }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE))
    bitmap.installPixels(bytes)
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}
