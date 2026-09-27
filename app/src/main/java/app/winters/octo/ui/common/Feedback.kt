package app.winters.octo.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.ui.nav.BarBottomGap
import app.winters.octo.ui.nav.BarHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

// How long a line stays up: a little longer when it offers an action.
private const val SHOW_MS = 3_500L
private const val SHOW_WITH_ACTION_MS = 6_000L

// One short line about what just happened, and what can be done about it,
// such as "Removed from the queue" with Undo, with an optional small icon
// before the words. Each one is its own object, so the same text shown
// twice still counts as a new message.
class FeedbackMessage(
    val text: String,
    val action: String? = null,
    val onAction: (() -> Unit)? = null,
    @DrawableRes val icon: Int? = null,
)

// The app's one place for short messages. Only for what the listener did
// and can undo, or something they asked for that did or did not happen;
// everything else stays quiet. A new message replaces the one showing.
@Singleton
class Feedback @Inject constructor() {
    private val _message = MutableStateFlow<FeedbackMessage?>(null)
    val message: StateFlow<FeedbackMessage?> = _message

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null, @DrawableRes icon: Int? = null) {
        _message.value = FeedbackMessage(text, action, onAction, icon)
    }

    // An undo for something already done.
    fun undoable(text: String, undo: () -> Unit) = show(text, "Undo", undo)

    // Something the listener asked for has now happened, with a check.
    fun done(text: String) = show(text, icon = OctoIcons.Check)

    fun dismiss(shown: FeedbackMessage) {
        _message.compareAndSet(shown, null)
    }
}

val LocalFeedback = staticCompositionLocalOf<Feedback> { error("No feedback host") }

// The gap between the message and the top of the bottom bar.
private val AboveBar = 10.dp

// Shows the current message as a small glass pill just above the bottom
// bar, in the same glass as the bar. It grows in from a little smaller and
// fades, all on the drawing layer, so nothing behind it is laid out again
// while it moves. Tapping a message with no action puts it away.
@Composable
fun FeedbackHost(feedback: Feedback, modifier: Modifier = Modifier) {
    val message by feedback.message.collectAsStateWithLifecycle()
    val calm = rememberSystemReduceMotion()
    val shown = remember { Animatable(0f) }
    // The message on screen, kept while it leaves.
    var last by remember { mutableStateOf<FeedbackMessage?>(null) }
    LaunchedEffect(message) {
        val next = message
        if (next != null) {
            last = next
            shown.animateTo(1f, if (calm) tween(120) else spring(dampingRatio = 0.85f, stiffness = 500f))
            delay(if (next.action != null) SHOW_WITH_ACTION_MS else SHOW_MS)
            feedback.dismiss(next)
        } else if (last != null) {
            shown.animateTo(0f, tween(if (calm) 120 else 160, easing = FastOutLinearInEasing))
            last = null
        }
    }
    val current = last ?: return
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BarBottomGap + BarHeight + AboveBar
    Box(modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = bottom), contentAlignment = Alignment.BottomCenter) {
        FloatingGlaze(
            LocalHaze.current,
            Modifier
                .graphicsLayer {
                    val v = shown.value
                    alpha = v.coerceIn(0f, 1f)
                    if (!calm) {
                        val scale = 0.92f + 0.08f * v
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - v) * 10.dp.toPx()
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                }
                .widthIn(max = 520.dp)
                .heightIn(min = 48.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .then(
                    if (current.action == null) {
                        Modifier.clickable(interactionSource = null, indication = null) { feedback.dismiss(current) }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Row(
                Modifier
                    .align(Alignment.Center)
                    .padding(
                        start = if (current.icon != null) 16.dp else 20.dp,
                        end = if (current.action != null) 6.dp else 20.dp,
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                current.icon?.let { icon ->
                    Icon(painterResource(icon), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(18.dp))
                }
                Text(
                    current.text,
                    style = OctoType.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = OctoColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val action = current.action
                if (action != null) {
                    Text(
                        action,
                        style = OctoType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = OctoColors.TextPrimary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                current.onAction?.invoke()
                                feedback.dismiss(current)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
