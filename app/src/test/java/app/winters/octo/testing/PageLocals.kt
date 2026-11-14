package app.winters.octo.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.winters.octo.ui.common.ChoiceSheet
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.LocalFeedback
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.LocalIconVectors
import dev.chrisbanes.haze.HazeState

// What the app's shell gives every page, which a test composing a page by
// itself gives in its place: the message host, the blur source, the choice
// sheet, and stand-in icons (the unit tests run without the app's
// resources, which hold the icons' vector files). `feedback` is the host the
// page's messages reach.
@Composable
fun PageLocals(feedback: Feedback = remember { Feedback() }, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalFeedback provides feedback,
        LocalHaze provides remember { HazeState() },
        LocalChoiceSheet provides remember { ChoiceSheet() },
        LocalIconVectors provides { _: Int -> BlankIcon },
        content = content,
    )
}

// An icon with nothing in it, the size of Octo's.
val BlankIcon: ImageVector = ImageVector.Builder("blank", 24.dp, 24.dp, 24f, 24f).build()

// A keyboard controller that only counts how often it was asked to hide.
class CountingKeyboard : SoftwareKeyboardController {
    var hides = 0

    override fun show() = Unit

    override fun hide() {
        hides++
    }
}
