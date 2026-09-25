package app.winters.octo.ui.nav

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import app.winters.octo.R
import app.winters.octo.design.GlassPanelDark
import app.winters.octo.design.Glaze
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.playback.NowPlaying
import app.winters.octo.player.fractionAt
import app.winters.octo.player.rememberPositionMs
import app.winters.octo.ui.common.Artwork
import dev.chrisbanes.haze.HazeState

private class Tab(val icon: ImageVector, @StringRes val label: Int)

private val tabs = listOf(
    Tab(Icons.Rounded.Home, R.string.tab_home),
    Tab(Icons.Rounded.Search, R.string.tab_search),
    Tab(Icons.AutoMirrored.Rounded.List, R.string.tab_library),
    Tab(Icons.Rounded.Settings, R.string.tab_settings),
)

val BarHeight = 56.dp
private val Gap = 10.dp

// What the bar's buttons do.
class BarActions(
    val onSelect: (Int) -> Unit,
    val onRoundButton: () -> Unit,
    val onShowTabs: () -> Unit,
    val onOpenPlayer: () -> Unit,
    val onPrevious: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
)

// The floating bar. It has two shapes: tabs on the left with a round
// now-playing button, or, once that button is tapped, the tabs folded into
// one circle and the button grown into a small player.
@Composable
fun BottomBar(
    haze: HazeState,
    selected: Int,
    now: NowPlaying,
    positionMs: () -> Long,
    playerShown: Boolean,
    actions: BarActions,
    artModifier: Modifier,
    artShape: Shape,
    modifier: Modifier = Modifier,
) {
    val grown by animateFloatAsState(if (playerShown) 1f else 0f, spring(0.75f, 200f), label = "bar shape")
    val position = rememberPositionMs(now, positionMs)
    val progress = { now.fractionAt(position.longValue) }

    BoxWithConstraints(
        modifier
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .height(BarHeight),
    ) {
        val fullTabs = maxWidth - BarHeight - Gap
        val tabsWidth = lerp(fullTabs, BarHeight, grown)
        TabBar(haze, selected, grown, playerShown, fullTabs, Modifier.width(tabsWidth), actions)
        GlassPanelDark(
            haze,
            Modifier
                .offset(x = tabsWidth + Gap)
                .width(maxWidth - tabsWidth - Gap)
                .fillMaxHeight(),
        ) {
            if (grown < 1f) {
                Box(
                    Modifier
                        .size(BarHeight)
                        .alpha(1f - grown)
                        .clickable(
                            enabled = !playerShown,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = actions.onRoundButton,
                        )
                        .semantics { contentDescription = "Now playing" },
                ) {
                    NowPlayingFace(now, progress)
                }
            }
            if (grown > 0f) {
                PlayerCapsule(
                    now,
                    progress,
                    width = fullTabs,
                    artModifier = artModifier,
                    artShape = artShape,
                    actions = actions,
                    modifier = Modifier.matchParentSize().clip(CircleShape).alpha(grown),
                )
            }
        }
    }
}

// A dark glass panel, with the selected tab marked by a lit capsule that
// glides between tabs. Folded, it is one circle showing the current tab,
// which unfolds the tabs again.
@Composable
private fun TabBar(
    haze: HazeState,
    selected: Int,
    folded: Float,
    isFolded: Boolean,
    fullWidth: Dp,
    modifier: Modifier,
    actions: BarActions,
) {
    GlassPanelDark(
        haze,
        modifier
            .fillMaxHeight()
            .clickable(
                enabled = isFolded,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = actions.onShowTabs,
            ),
    ) {
        val column = (fullWidth - 12.dp) / tabs.size
        val capsuleWidth = 56.dp
        val capsuleX by animateDpAsState(
            targetValue = 6.dp + column * selected + (column - capsuleWidth) / 2,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 350f),
            label = "tab capsule",
        )
        // Folding, the lit capsule slides left and swells into the whole
        // circle, carrying the selected tab's icon, so the folded tab reads as
        // the same glazed button as the one at the other end of the bar.
        val glazeX = lerp(capsuleX, 0.dp, folded)
        Box(Modifier.matchParentSize().clip(CircleShape)) {
            // Flat: the panel under it is already frosted.
            Glaze(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(glazeX.roundToPx(), 0) }
                    .size(width = capsuleWidth, height = lerp(40.dp, BarHeight, folded)),
            )
            if (folded < 1f) {
                // Laid out at full width and clipped, so the tabs slide under
                // the folding edge instead of squeezing together.
                Row(
                    Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(fullWidth)
                        .fillMaxHeight()
                        .padding(horizontal = 6.dp)
                        .alpha(1f - folded),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        TabButton(
                            tab,
                            selected = index == selected,
                            // While folding, the selected icon rides the glaze instead.
                            showIcon = folded == 0f || index != selected,
                            enabled = !isFolded,
                            modifier = Modifier.weight(1f),
                        ) { actions.onSelect(index) }
                    }
                }
            }
            if (folded > 0f) {
                Icon(
                    tabs[selected].icon,
                    contentDescription = "Show tabs",
                    tint = OctoColors.TextPrimary,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset((glazeX + (capsuleWidth - 22.dp) / 2).roundToPx(), 0) }
                        .size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun TabButton(
    tab: Tab,
    selected: Boolean,
    showIcon: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        if (selected) OctoColors.TextPrimary else OctoColors.TextMuted,
        label = "tab tint",
    )
    Box(
        modifier
            .fillMaxHeight()
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            tab.icon,
            contentDescription = stringResource(tab.label),
            tint = tint,
            modifier = Modifier.size(22.dp).alpha(if (showIcon) 1f else 0f),
        )
    }
}

// The small player: artwork with its progress ring, the song, and back,
// play and next. Tapping anywhere else opens the full player.
@Composable
private fun PlayerCapsule(
    now: NowPlaying,
    progress: () -> Float,
    width: Dp,
    artModifier: Modifier,
    artShape: Shape,
    actions: BarActions,
    modifier: Modifier,
) {
    Box(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClickLabel = "Open player",
            onClick = actions.onOpenPlayer,
        ),
    ) {
        Row(
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(width)
                .fillMaxHeight()
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Artwork(now.artwork, 36.dp, artModifier, shape = artShape)
                ProgressRing(progress, Modifier.matchParentSize().padding(1.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    now.title.orEmpty(),
                    style = OctoType.label,
                    color = OctoColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    now.artist.orEmpty(),
                    style = OctoType.caption.copy(fontSize = 11.sp),
                    color = OctoColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CapsuleButton(OctoIcons.Previous, "Previous", actions.onPrevious)
            CapsuleButton(if (now.isPlaying) OctoIcons.Pause else OctoIcons.Play, if (now.isPlaying) "Pause" else "Play", actions.onPlayPause)
            CapsuleButton(OctoIcons.Next, "Next", actions.onNext)
        }
    }
}

@Composable
private fun CapsuleButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(28.dp))
    }
}
