package app.winters.octo

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlassPanelDark
import app.winters.octo.design.OctoColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private class Tab(val icon: ImageVector, @StringRes val label: Int)

private val tabs = listOf(
    Tab(Icons.Rounded.Home, R.string.tab_home),
    Tab(Icons.Rounded.Search, R.string.tab_search),
    Tab(Icons.AutoMirrored.Rounded.List, R.string.tab_library),
    Tab(Icons.Rounded.Settings, R.string.tab_settings),
)

private val BarHeight = 56.dp

@Composable
fun AppShell() {
    val haze = rememberHazeState()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(OctoColors.Background)) {
        // Colourful rows so the bar's blur is easy to see.
        LazyColumn(
            modifier = Modifier.fillMaxSize().hazeSource(haze),
            // Rows start below the status bar and clear the floating bar.
            contentPadding = PaddingValues(top = topInset + 8.dp, bottom = 120.dp),
        ) {
            items(40) { i ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.hsv((i * 37f) % 360f, 0.6f, 0.6f),
                                    OctoColors.BackgroundTertiary,
                                ),
                            ),
                            RoundedCornerShape(24.dp),
                        ),
                )
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
                .widthIn(max = 520.dp)
                .height(BarHeight),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TabBar(haze, selected, Modifier.weight(1f)) { selected = it }
            // Same materials as a selected tab: the bar's dark glass, lit by
            // the glaze. The glaze fills the whole circle, so it is one surface.
            GlassPanelDark(haze, Modifier.size(BarHeight)) {
                Glaze(Modifier.matchParentSize()) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.now_playing),
                        tint = OctoColors.TextPrimary,
                    )
                }
            }
        }
    }
}

// The tab bar: a dark glass panel, with the selected tab marked by a lit
// capsule that glides between tabs.
@Composable
private fun TabBar(
    haze: HazeState,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    GlassPanelDark(haze, modifier.fillMaxHeight()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            val column = maxWidth / tabs.size
            val capsuleWidth = 56.dp
            val capsuleX by animateDpAsState(
                targetValue = column * selected + (column - capsuleWidth) / 2,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 350f),
                label = "tab capsule",
            )
            // Flat: the panel under it is already frosted.
            Glaze(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = capsuleX)
                    .size(width = capsuleWidth, height = 40.dp),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, tab ->
                    TabButton(tab, index == selected, Modifier.weight(1f)) { onSelect(index) }
                }
            }
        }
    }
}

@Composable
private fun TabButton(tab: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) OctoColors.TextPrimary else OctoColors.TextMuted,
        label = "tab tint",
    )
    Box(
        modifier
            .fillMaxHeight()
            .clickable(
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
            modifier = Modifier.size(22.dp),
        )
    }
}
