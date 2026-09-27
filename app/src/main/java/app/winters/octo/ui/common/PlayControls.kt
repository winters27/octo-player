package app.winters.octo.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlazeLight
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType

// How tall Play and Shuffle's capsules are drawn, and the touch target
// around each, which is never under 48dp.
private val CapsuleHeight = 46.dp
private val TouchSize = 48.dp

// How far a capsule gives under the finger. Less than the player's round
// buttons, since a capsule this wide would travel too far at their scale.
private const val PressScale = 0.96f

// The part of a page's header that plays what the page holds: a quiet line
// with what it holds on the left (`details`, or `lead` for a lesser action)
// and any quieter controls on the right (`extras`, like the heart), then
// Play and Shuffle across the width under it. Every page that plays a whole
// list uses it, so they all look and behave alike.
@Composable
fun PlayRow(
    details: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    playable: Boolean = true,
    enabled: Boolean = true,
    loading: Boolean = false,
    lead: @Composable () -> Unit = {},
    extras: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) {
                details?.let {
                    Text(it, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                lead()
            }
            extras()
        }
        if (playable) PlayShuffle(onPlay, onShuffle, Modifier.padding(top = 10.dp), enabled = enabled, loading = loading)
    }
}

// Play and Shuffle as two glass capsules of equal width, side by side
// across the header, the same glaze as the bar. Play catches a little more
// light than Shuffle, so it reads as the one to reach for first, without a
// solid fill taking the colour from the cover and the page's glow.
@Composable
fun PlayShuffle(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayCapsule(OctoIcons.Play, "Play", onPlay, lit = true, enabled = enabled && !loading, loading = loading, modifier = Modifier.weight(1f))
        PlayCapsule(OctoIcons.Shuffle, "Shuffle", onShuffle, lit = false, enabled = enabled && !loading, modifier = Modifier.weight(1f))
    }
}

// One glass capsule with a white icon and word. A lit one wears the glaze
// as a control under the finger does; any capsule does while pressed, and
// gives a little, as the player's buttons do. A spinner takes the icon's
// place while the songs are gathered, keeping the word where it is.
@Composable
private fun PlayCapsule(
    @DrawableRes icon: Int,
    text: String,
    onClick: () -> Unit,
    lit: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) PressScale else 1f, spring(0.45f, 600f), label = "press")
    Box(
        modifier
            .height(TouchSize)
            .alpha(if (enabled || loading) 1f else 0.4f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Glaze(
            Modifier
                .fillMaxWidth()
                .height(CapsuleHeight)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                },
            light = if (lit || pressed) GlazeLight.Lifted else GlazeLight.Rest,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    if (loading) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                Text(text, style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 1)
            }
        }
    }
}

// A lesser action on the line above Play and Shuffle, like More: a plain
// white icon with no glass, a step quieter than the capsules below.
@Composable
fun QuietIconAction(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(TouchSize)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
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
