package app.winters.octo

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.glass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private class Tab(val icon: ImageVector, @StringRes val label: Int)

private val tabs = listOf(
    Tab(Icons.Rounded.Home, R.string.tab_home),
    Tab(Icons.Rounded.Search, R.string.tab_search),
    Tab(Icons.AutoMirrored.Rounded.List, R.string.tab_library),
    Tab(Icons.Rounded.Settings, R.string.tab_settings),
)

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
                                    OctoColors.SurfaceRaised,
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
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier
                    .height(58.dp)
                    .glass(haze)
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    TabIcon(tab, index == selected) { selected = index }
                }
            }
            Box(
                Modifier.size(58.dp).glass(haze, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.now_playing),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun TabIcon(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .background(
                if (selected) OctoColors.Accent.copy(alpha = 0.18f)
                else Color.Transparent,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            tab.icon,
            contentDescription = stringResource(tab.label),
            tint = if (selected) OctoColors.Accent else OctoColors.IconIdle,
            modifier = Modifier.size(22.dp),
        )
    }
}
