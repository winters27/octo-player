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
import app.winters.octo.subsonic.FamilyLink
import java.util.concurrent.Executors

// A link drawn as a QR code, dark on white with a quiet border, for
// another phone's camera to read off this screen. Made on the phone.
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

// The link a QR code holds, right under it, to tap: what to do with it in
// plain words, then the link itself, opened in the browser (an Octo
// server's join page hands it on to Octo).
@Composable
fun QrLink(url: String, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = OctoType.caption, color = OctoColors.TextSecondary)
        Text(
            url,
            style = OctoType.caption.copy(textDecoration = TextDecoration.Underline),
            color = OctoColors.Accent,
            textAlign = TextAlign.Center,
            maxLines = 3,
            modifier = Modifier
                .clickable(role = Role.Button, onClickLabel = label) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                .semantics { contentDescription = "$label: $url" },
        )
    }
}

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
// goes on. Asks for the camera first, and says plainly when it cannot.
@Composable
fun QrScanner(onFound: (FamilyLink) -> Unit, onClose: () -> Unit) {
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (allowed) {
            val analysis = remember { Executors.newSingleThreadExecutor() }
            var found by remember { mutableStateOf(false) }
            DisposableEffect(Unit) { onDispose { analysis.shutdown() } }
            AndroidView(
                factory = { ctx ->
                    val view = PreviewView(ctx)
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        runCatching {
                            val provider = future.get()
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
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, reader)
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
            GlazeButton("Cancel", onClose, size = ButtonSize.Medium)
        }
    }
}
