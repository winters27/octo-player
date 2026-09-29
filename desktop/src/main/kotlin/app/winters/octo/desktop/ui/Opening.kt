package app.winters.octo.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.painter.Painter
import app.winters.octo.design.FrameSize
import app.winters.octo.design.OctoColors

// How much of Octo's mark shows while the window opens: quiet.
private const val MarkOpacity = 0.35f

// The window's first frame, while the app behind it gets ready: the page
// colour the app then draws over, so nothing flashes, and Octo's mark,
// still. Cheap to draw, so the window shows at once.
@Composable
fun Opening(mark: Painter?) {
    Box(Modifier.fillMaxSize().background(OctoColors.Background), contentAlignment = Alignment.Center) {
        if (mark != null) Image(mark, contentDescription = "Octo is opening", modifier = Modifier.size(FrameSize.OpeningMark).alpha(MarkOpacity))
    }
}
