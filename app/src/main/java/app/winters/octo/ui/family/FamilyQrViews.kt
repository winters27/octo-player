package app.winters.octo.ui.family

import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.core.net.toUri
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.OverArt
import app.winters.octo.subsonic.FamilyLink
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

// The text of a QR code in a camera frame: its brightness plane, read
// whatever the frame's rotation.
internal fun readFrame(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
    return readQrFromLuminance(bytes, plane.rowStride, image.width, image.height)
}

// The camera, full screen, reading family QR codes. A code that holds a
// family link closes it with the link; any other code says so and scanning
// goes on. Asks for the camera first, and says plainly when it cannot. It is
// a modal over the page: presses and focus stay in it, and its Cancel takes
// `cancelFocus`.
@Composable
fun QrScanner(onFound: (FamilyLink) -> Unit, onClose: () -> Unit, cancelFocus: FocusRequester) {
    OverArt { ScannerView(onFound, onClose, cancelFocus) }
}

// How a page draws the scanner. The app never provides another; a test
// provides one that reads a link without a camera.
val LocalQrScanner = staticCompositionLocalOf<@Composable (onFound: (FamilyLink) -> Unit, onClose: () -> Unit, cancelFocus: FocusRequester) -> Unit> {
    { found, close, focus -> QrScanner(found, close, focus) }
}

@Composable
private fun ScannerView(onFound: (FamilyLink) -> Unit, onClose: () -> Unit, cancelFocus: FocusRequester) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    var notALink by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        asked = true
    }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    // Presses on it never reach the page under it (only fresh downs are
    // taken, so a finger's small movement still clicks Cancel), and Tab and
    // the arrows stay on its own controls, while a request for focus
    // elsewhere and a clear still leave it.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { if (it.changedToDown()) it.consume() } } }
            .focusProperties { onExit = { if (requestedFocusDirection != FocusDirection.Enter && requestedFocusDirection != FocusDirection.Exit) cancelFocusChange() } }
            .focusGroup(),
    ) {
        if (allowed) {
            val analysis = remember { Executors.newSingleThreadExecutor() }
            val future = remember { ProcessCameraProvider.getInstance(context) }
            val camera = remember { ScannerCamera(ProviderBinder(future, owner)) }
            var found by remember { mutableStateOf(false) }
            // Closing lets the camera go at once (its light goes out), and a
            // camera that arrives only after closing is never started.
            DisposableEffect(Unit) { onDispose { camera.close(); analysis.shutdown() } }
            AndroidView(
                factory = { ctx ->
                    val view = PreviewView(ctx)
                    future.addListener({
                        runCatching {
                            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                            val reader = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            reader.setAnalyzer(analysis) { image ->
                                image.use {
                                    if (found) return@use
                                    val text = readFrame(it) ?: return@use
                                    val link = familyLinkInQr(text)
                                    if (link != null) {
                                        found = true
                                        view.post { onFound(link) }
                                    } else {
                                        notALink = true
                                    }
                                }
                            }
                            camera.bind(preview, reader)
                        }.onFailure { problem = "The camera did not start. Type the code instead." }
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Where to hold the code.
            Box(Modifier.align(Alignment.Center).size(240.dp).border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(20.dp)))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val line = when {
                problem != null -> problem!!
                !allowed && asked -> "Octo can't use the camera. Allow it in the phone's settings, or type the code."
                !allowed -> "Allow the camera to scan the code."
                notALink -> "That QR code is not a family link. Show the one from Devices or an invite."
                else -> "Hold the family QR code inside the square."
            }
            Text(line, style = OctoType.body, color = OctoColors.TextPrimary, textAlign = TextAlign.Center)
            GlazeButton("Cancel", onClose, Modifier.focusRequester(cancelFocus), size = ButtonSize.Medium)
        }
    }
}

// What the scanner asks of the camera: let go of every use, take the
// preview and the reader, let go of those two, and stop reading frames.
internal interface CameraBinder<P, R> {
    fun unbindAll()

    fun bind(preview: P, reader: R)

    fun unbind(preview: P, reader: R)

    fun clearAnalyzer(reader: R)
}

// The scanner's hold on the camera. A bind after closing does nothing (the
// camera arrived too late); a bind that fails throws to the caller and
// leaves nothing to let go of; closing lets go of the last pair bound and
// stops its reader, once.
internal class ScannerCamera<P : Any, R : Any>(private val binder: CameraBinder<P, R>) {
    private var closed = false
    private var bound: Pair<P, R>? = null

    fun bind(preview: P, reader: R) {
        if (closed) return
        binder.unbindAll()
        binder.bind(preview, reader)
        bound = preview to reader
    }

    fun close() {
        if (closed) return
        closed = true
        val (preview, reader) = bound ?: return
        binder.unbind(preview, reader)
        binder.clearAnalyzer(reader)
    }
}

// The phone's camera, through CameraX, for the page's lifecycle. Reached
// only once the provider is in (from its listener, or after a bind).
internal class ProviderBinder(
    private val future: ListenableFuture<ProcessCameraProvider>,
    private val owner: LifecycleOwner,
) : CameraBinder<Preview, ImageAnalysis> {
    override fun unbindAll() {
        future.get().unbindAll()
    }

    override fun bind(preview: Preview, reader: ImageAnalysis) {
        future.get().bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, reader)
    }

    override fun unbind(preview: Preview, reader: ImageAnalysis) { future.get().unbind(preview, reader) }

    override fun clearAnalyzer(reader: ImageAnalysis) {
        reader.clearAnalyzer()
    }
}
