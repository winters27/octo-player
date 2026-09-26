package app.winters.octo.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.design.Glaze
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

// How long a line stays up: a little longer when it offers an action.
private const val SHOW_MS = 3_500L
private const val SHOW_WITH_ACTION_MS = 6_000L

// One short line about what just happened, and what can be done about it,
// such as "Removed from the queue" with Undo. Each one is its own object,
// so the same text shown twice still counts as a new message.
class FeedbackMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

// The app's one place for short messages. Only for what the listener did
// and can undo, or something they asked for that did not work; everything
// else stays quiet. A new message replaces the one showing.
@Singleton
class Feedback @Inject constructor() {
    private val _message = MutableStateFlow<FeedbackMessage?>(null)
    val message: StateFlow<FeedbackMessage?> = _message

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        _message.value = FeedbackMessage(text, action, onAction)
    }

    // An undo for something already done.
    fun undoable(text: String, undo: () -> Unit) = show(text, "Undo", undo)

    fun dismiss(shown: FeedbackMessage) {
        _message.compareAndSet(shown, null)
    }
}

val LocalFeedback = staticCompositionLocalOf<Feedback> { error("No feedback host") }

// Shows the current message as a glass line above the bottom bar.
@Composable
fun FeedbackHost(feedback: Feedback, modifier: Modifier = Modifier) {
    val message by feedback.message.collectAsStateWithLifecycle()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp
    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        delay(if (shown.action != null) SHOW_WITH_ACTION_MS else SHOW_MS)
        feedback.dismiss(shown)
    }
    Box(modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = bottom), contentAlignment = Alignment.BottomCenter) {
        // Keeps the last message drawn while it slides away.
        var last by remember { mutableStateOf<FeedbackMessage?>(null) }
        if (message != null) last = message
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            val shown = last ?: return@AnimatedVisibility
            Glaze(
                modifier = Modifier.widthIn(max = 520.dp).semantics { liveRegion = LiveRegionMode.Polite },
                shape = RoundedCornerShape(18.dp),
                backdrop = LocalHaze.current,
            ) {
                Row(
                    Modifier.padding(start = 18.dp, end = if (shown.action != null) 6.dp else 18.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        shown.text,
                        style = OctoType.bodySmall,
                        color = OctoColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val action = shown.action
                    if (action != null) {
                        Text(
                            action,
                            style = OctoType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = OctoColors.TextPrimary,
                            modifier = Modifier
                                .clickable {
                                    shown.onAction?.invoke()
                                    feedback.dismiss(shown)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
