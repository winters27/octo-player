package app.winters.octo.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The height of a chrome button, and of the group that holds a row of them.
val ChromeHeight: Dp = 38.dp

// A chrome button's width, and its height inside a bar or a group.
val ChromeButtonWidth: Dp = 38.dp
val ChromeButtonInnerHeight: Dp = 30.dp

// Whether a chrome button sits inside a group, which wears the glass for
// it: glass inside glass gets no second glaze.
private val LocalInGroup = staticCompositionLocalOf { false }

// Buttons in one glazed capsule, 38 tall, 4 dp in from its ends and 4 dp
// apart, with the chrome shadow. The buttons inside drop their own glass.
@Composable
fun ButtonGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Glaze(modifier.chromeShadow(CircleShape).heightIn(min = ChromeHeight)) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalInGroup provides true) { content() }
        }
    }
}

// An icon-only button for bars and groups: a white glyph in a capsule.
// Alone it wears the glaze and the chrome shadow; inside a group it wears
// nothing until it is `active`, when it sits in the darker pill, the way a
// chosen item in a glaze does. Pressed, the capsule pops out while the icon
// dips in; under the pointer the glass catches more light and the icon
// grows a little. `accent` plays the icon's gesture on every tap.
@Composable
fun ChromeButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    accent: IconAccent? = null,
    iconSize: Dp = 18.dp,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val look = rememberChromeLook(interaction)
    val gesture = if (accent != null) rememberIconAccent(accent) else null
    val inGroup = LocalInGroup.current
    val frame = modifier
        .size(ChromeButtonWidth, if (inGroup) ChromeButtonInnerHeight else ChromeHeight)
        .graphicsLayer {
            scaleX = look.glass()
            scaleY = look.glass()
        }
        .focusRing(CircleShape, look.ring)
        .alpha(if (enabled) 1f else OctoInk.DisabledAlpha)
        .hoverable(interaction, enabled = enabled)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = {
                gesture?.play()
                onClick()
            },
        )
        .semantics { this.contentDescription = contentDescription }
    val glyph = @Composable {
        Image(
            icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(OctoColors.TextPrimary),
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = look.icon()
                    scaleY = look.icon()
                }
                .iconAccent(gesture),
        )
    }
    if (inGroup) {
        Box(frame, contentAlignment = Alignment.Center) {
            if (active) {
                GlazeSelected(Modifier.matchParentSize())
            } else if (look.lit) {
                // Under the pointer or the finger: a faint white lift, with
                // no edge, so it never reads as a bordered chip.
                Box(Modifier.matchParentSize().background(OctoInk.Quaternary, CircleShape))
            }
            glyph()
        }
    } else {
        Glaze(
            frame.chromeShadow(CircleShape),
            light = if (look.lit || active) GlazeLight.Lifted else GlazeLight.Rest,
        ) {
            if (active) GlazeSelected(Modifier.matchParentSize().padding(3.dp))
            glyph()
        }
    }
}
