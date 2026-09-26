package app.winters.octo.ui.home

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.AccentButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.glassPanel
import app.winters.octo.device.Access
import app.winters.octo.discovery.Station
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistCircle
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SongCard
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (NavKey) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val access by vm.library.access.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val count by vm.songCount.collectAsStateWithLifecycle()
    val recentlyPlayed by vm.recentlyPlayed.collectAsStateWithLifecycle()
    val mostPlayed by vm.mostPlayed.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.onShown()
        onPauseOrDispose { }
    }

    PullToRefreshBox(
        isRefreshing = vm.refreshing,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding()) {
            item { ScreenTitle("Home") }
            when {
                access != Access.Granted -> item { AccessCard(access, vm) }
                count == 0 -> item { EmptyCard() }
                else -> {
                    shelf("Recently played", recentlyPlayed, onOpen)
                    stationShelf(vm.stations, vm.startingStation, vm::playStation)
                    shelf("Recently added", recent.orEmpty(), onOpen)
                    songShelf("Most played", mostPlayed, vm::play)
                    shelf("Something different", vm.surprise, onOpen)
                    if (vm.artists.isNotEmpty()) {
                        item { SectionTitle("Artists", Modifier.padding(top = 18.dp)) }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(vm.artists, key = { it.id }) { artist ->
                                    ArtistCircle(artist) { onOpen(ArtistRoute(artist.id)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shelf(
    title: String,
    albums: List<AlbumEntity>,
    onOpen: (NavKey) -> Unit,
) {
    if (albums.isEmpty()) return
    item(key = "title:$title") { SectionTitle(title, Modifier.padding(top = 18.dp)) }
    item(key = "row:$title") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) })
            }
        }
    }
}

// A row of songs; tapping one plays the row from that song.
private fun androidx.compose.foundation.lazy.LazyListScope.songShelf(
    title: String,
    tracks: List<TrackEntity>,
    onPlay: (List<TrackEntity>, Int) -> Unit,
) {
    if (tracks.isEmpty()) return
    item(key = "title:$title") { SectionTitle(title, Modifier.padding(top = 18.dp)) }
    item(key = "row:$title") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                SongCard(track, onClick = { onPlay(tracks, index) })
            }
        }
    }
}

// The server's stations; tapping one plays what it has lined up.
private fun androidx.compose.foundation.lazy.LazyListScope.stationShelf(
    stations: List<Station>,
    starting: String?,
    onPlay: (Station) -> Unit,
) {
    if (stations.isEmpty()) return
    item(key = "title:Stations") { SectionTitle("Stations", Modifier.padding(top = 18.dp)) }
    item(key = "row:Stations") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(stations, key = { it.id }) { station ->
                StationCard(station, starting = station.id == starting, onClick = { onPlay(station) })
            }
        }
    }
}

// Asks for the music on the phone. After a second refusal the system stops
// showing the prompt, so the button opens the app's settings instead.
@Composable
private fun AccessCard(access: Access, vm: HomeViewModel) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context as? Activity
        val canAskAgain = activity?.shouldShowRequestPermissionRationale(vm.library.permissionName) ?: true
        vm.library.onPermissionResult(granted, canAskAgain)
    }
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(20.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Your music, on this phone", style = OctoType.headline, color = OctoColors.TextPrimary)
        Text(
            "Octo plays the music already on your phone. Allow access to see it here.",
            style = OctoType.bodySmall,
            color = OctoColors.TextSecondary,
        )
        AccentButton(
            text = if (access == Access.DeniedForever) "Open settings" else "Allow access",
            onClick = {
                if (access == Access.DeniedForever) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                    )
                } else {
                    ask.launch(vm.library.permissionName)
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun EmptyCard() {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("No music on this phone yet", style = OctoType.headline, color = OctoColors.TextPrimary)
        Text("Songs you add show up here on their own.", style = OctoType.bodySmall, color = OctoColors.TextMuted)
    }
}
