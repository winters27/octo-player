package app.winters.octo.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter

// The painter the shared controls draw their icons with. On the phone, the
// vector as it is; the desktop has its own, which draws icons on whole
// pixels.
@Composable
fun rememberIconPainter(icon: ImageVector): Painter = rememberVectorPainter(icon)
