package app.winters.octo.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.Station
import app.winters.octo.ui.common.ArtworkFill

// Stations change once a day, so a list older than this is asked for again
// when Home comes back.
internal const val STATIONS_STALE_MS = 10 * 60 * 1000L

// Whether Home should ask for the stations again: never while a load is
// running, always after one failed, otherwise once the list is stale.
internal fun stationsDue(now: Long, loadedAt: Long?, failed: Boolean, loading: Boolean): Boolean = when {
    loading -> false
    failed || loadedAt == null -> true
    else -> now - loadedAt >= STATIONS_STALE_MS
}

// A station as its cover with its name under it, sized like an album card.
// It dims while its songs are on the way.
@Composable
fun StationCard(station: Station, starting: Boolean, onClick: () -> Unit) {
    val fade by animateFloatAsState(if (starting) 0.5f else 1f, label = "station")
    Column(
        Modifier
            .width(150.dp)
            .alpha(fade)
            .clickable(onClick = onClick),
    ) {
        ArtworkFill(station.artwork)
        Spacer(Modifier.height(8.dp))
        Text(
            station.name,
            style = OctoType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = OctoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
