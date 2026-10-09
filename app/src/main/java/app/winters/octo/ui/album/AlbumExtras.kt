package app.winters.octo.ui.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.design.OctoColors
import app.winters.octo.discovery.librarySongsOnly
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.nav.AlbumRoute
import java.util.Locale

// The songs an album page lists: all of them, or with `libraryOnly` only
// those in the library, in album order.
fun albumSongsShown(tracks: List<TrackEntity>, libraryOnly: Boolean): List<TrackEntity> =
    librarySongsOnly(tracks, libraryOnly) { isFind(it.id) }

// The album's genre: the one most of its songs have, the first to appear
// when two tie. Nothing when no song has one.
fun albumGenre(tracks: List<TrackEntity>): String? =
    tracks.map { it.genre }
        .filter { it.isNotBlank() }
        .groupBy { it.lowercase() }
        .values
        .maxByOrNull { it.size }
        ?.first()

// The average of the ratings its songs have, leaving out songs with none.
// Nothing when no song is rated.
fun averageRating(tracks: List<TrackEntity>): Double? =
    tracks.map { it.rating }.filter { it in 1..5 }.takeIf { it.isNotEmpty() }?.average()

// Stars to one place, dropping a whole number's ".0": "4.3", "4".
fun starsText(average: Double, locale: Locale = Locale.getDefault()): String {
    val rounded = Math.round(average * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else "%.1f".format(locale, rounded)
}

// What a screen reader says for the average.
fun averageRatingSpoken(average: Double, locale: Locale = Locale.getDefault()): String {
    val stars = starsText(average, locale)
    return "Songs rated $stars ${if (stars == "1") "star" else "stars"} on average"
}

// A quiet line under the header: the genre, which opens that genre's page,
// and the songs' average rating when any are rated.
@Composable
internal fun AlbumAbout(genre: String?, average: Double?, onGenre: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (genre != null) {
            Text(
                genre,
                style = OctoType.caption,
                color = OctoColors.Accent,
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = "Open genre") { onGenre(genre) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }
        if (average != null) {
            val spoken = averageRatingSpoken(average)
            Row(
                Modifier.clearAndSetSemantics { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(painterResource(OctoIcons.StarFilled), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(12.dp))
                Text("${starsText(average)} average", style = OctoType.caption, color = OctoColors.TextMuted)
            }
        }
    }
}

// The artist's other albums in a row, at the foot of the page.
@Composable
internal fun MoreByArtist(artist: String, albums: List<AlbumEntity>, onOpen: (NavKey) -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        SectionTitle("More by $artist")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(albums, key = { it.id }) { other ->
                AlbumCard(other, onClick = { onOpen(AlbumRoute(other.id)) })
            }
        }
    }
}
