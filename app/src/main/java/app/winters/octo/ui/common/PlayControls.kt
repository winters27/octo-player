package app.winters.octo.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.winters.octo.design.Glaze
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType

private val PlaySize = 52.dp
private val ShuffleSize = 44.dp

// Every touch target is at least this big, whatever is drawn.
private val TouchSize = 48.dp

// The line under a page's title that plays what the page holds: what it
// holds on the left, then any quieter controls (`extras`, like the heart),
// then Shuffle and Play at the end. Every page that plays a whole list uses
// it, so they all look and behave alike.
@Composable
fun PlayRow(
    details: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    playable: Boolean = true,
    enabled: Boolean = true,
    loading: Boolean = false,
    extras: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            details?.let {
                Text(it, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        extras()
        if (playable) PlayShuffle(onPlay, onShuffle, enabled = enabled, loading = loading)
    }
}

// Shuffle as a small glass disc, and Play as a larger white one beside it.
// White rather than the accent: the one solid thing in the header, with no
// colour of its own, so the cover and the glow keep theirs.
@Composable
fun PlayShuffle(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ShuffleButton(onShuffle, enabled = enabled && !loading)
        PlayButton(onPlay, enabled = enabled && !loading, loading = loading)
    }
}

// The white disc with a dark play mark. A spinner takes the mark's place
// while the songs are gathered.
@Composable
fun PlayButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Pressable(onClick, "Play", enabled, modifier.size(PlaySize)) { press ->
        Box(
            Modifier
                .size(PlaySize)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
                .dropShadow(CircleShape, Shadow(radius = 10.dp, color = Color.Black.copy(alpha = 0.35f), offset = DpOffset(0.dp, 3.dp)))
                .clip(CircleShape)
                .background(OctoColors.TextPrimary),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(color = OctoColors.Background, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                // Nudged right, so the triangle looks centred rather than
                // measures centred.
                Icon(
                    painterResource(OctoIcons.Play),
                    contentDescription = null,
                    tint = OctoColors.Background,
                    modifier = Modifier.offset(x = 1.dp).size(30.dp),
                )
            }
        }
    }
}

// The small glass disc with the shuffle mark, the same glaze as the player's
// capsules.
@Composable
fun ShuffleButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    GlassIconButton(OctoIcons.Shuffle, "Shuffle", onClick, modifier, enabled)
}

// A small round glass button holding one white icon, to sit beside Play.
@Composable
fun GlassIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = ShuffleSize,
) {
    Pressable(onClick, description, enabled, modifier.size(maxOf(TouchSize, size))) { press ->
        Glaze(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                },
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

// A round control that gives a little under the finger and springs back,
// as the player's transport buttons do. The touch target is the whole box,
// which is never under 48dp.
@Composable
private fun Pressable(
    onClick: () -> Unit,
    description: String,
    enabled: Boolean,
    modifier: Modifier,
    content: @Composable (press: Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.9f else 1f, spring(0.45f, 600f), label = "press")
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content(press)
    }
}

// A lesser action under or beside the Play line, like "Download album": a
// small icon and a word in the quiet accent, with no border and no fill.
@Composable
fun QuietAction(
    @DrawableRes icon: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.6f)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(18.dp))
        Text(text, style = OctoType.label, color = OctoColors.TextSecondary, maxLines = 1)
    }
}

// Spaces a line of quiet actions so their words line up with the page's
// 20dp edge, since each carries 10dp of its own padding.
@Composable
fun QuietActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
