package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons

// A song's rating, shown quietly: one small star for each it was given.
@Composable
fun RatingStars(rating: Int, modifier: Modifier = Modifier, size: Dp = 11.dp, color: Color = OctoColors.TextMuted) {
    val stars = rating.coerceIn(0, 5)
    if (stars == 0) return
    Row(
        modifier.semantics { contentDescription = if (stars == 1) "Rated 1 star" else "Rated $stars stars" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(stars) {
            Icon(painterResource(OctoIcons.StarFilled), contentDescription = null, tint = color, modifier = Modifier.size(size))
        }
    }
}
