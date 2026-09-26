package app.winters.octo.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons

// The rating a tap on a star gives: that many stars, or none when the song
// already has exactly that many.
fun nextRating(current: Int, tapped: Int): Int = if (tapped == current) 0 else tapped

// Five stars for rating a song. A tap sets the rating; tapping the star it
// already has takes it off. The sheet stays open, so the stars show what
// was chosen.
@Composable
internal fun RatingSheet(state: SongMenuState, vm: SongMenuViewModel) {
    GlassSheet(visible = state.ratingTrackId != null, onDismiss = state::closeRating) {
        val trackId = state.lastRatingTrackId ?: return@GlassSheet
        val track by remember(trackId) { vm.track(trackId) }.collectAsStateWithLifecycle(null)
        val song = track ?: return@GlassSheet
        SongHeader(song.copy(rating = 0))
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            for (star in 1..5) {
                val lit = star <= song.rating
                Box(
                    Modifier
                        .size(52.dp)
                        .clickable(interactionSource = null, indication = null, role = Role.Button) {
                            vm.rate(trackId, nextRating(song.rating, star))
                        }
                        .semantics {
                            contentDescription = if (star == 1) "1 star" else "$star stars"
                            selected = star == song.rating
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (lit) OctoIcons.StarFilled else OctoIcons.Star),
                        contentDescription = null,
                        tint = if (lit) OctoColors.TextPrimary else OctoColors.TextMuted,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
