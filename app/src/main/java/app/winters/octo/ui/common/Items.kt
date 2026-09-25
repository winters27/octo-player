package app.winters.octo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.ui.menu.LocalSongMenu

// An album as a picture with its name and artist under it. No card
// outline: the picture carries the edge.
@Composable
fun AlbumCard(album: AlbumEntity, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 150.dp) {
    Column(
        (if (width != null) modifier.width(width) else modifier.fillMaxWidth())
            .clickable(onClick = onClick),
    ) {
        ArtworkFill(album.artwork)
        Spacer(Modifier.height(8.dp))
        Text(
            album.title,
            style = OctoType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = OctoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            album.artist,
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// A song as a picture with its title and artist under it, for rows of
// songs. A tap plays; a long press opens the song's menu.
@Composable
fun SongCard(track: TrackEntity, onClick: () -> Unit) {
    val menu = LocalSongMenu.current
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .width(150.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu.open(track.id)
                },
            ),
    ) {
        ArtworkFill(track.artwork)
        Spacer(Modifier.height(8.dp))
        Text(
            track.title,
            style = OctoType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = OctoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artist,
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// What sits at the start of a song row.
sealed interface SongLead {
    data object Artwork : SongLead
    data class Number(val track: Int?) : SongLead
}

// A song line; tapping it plays it. `trailing` goes after the length, such
// as a drag handle.
@Composable
fun SongRow(
    track: TrackEntity,
    lead: SongLead = SongLead.Artwork,
    subtitle: String? = "${track.artist} • ${track.album}",
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val menu = LocalSongMenu.current
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            // A tap plays; a long press opens the song's menu.
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu.open(track.id)
                },
            )
            .height(56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (lead) {
            SongLead.Artwork -> Artwork(track.artwork, 44.dp, shape = RoundedCornerShape(6.dp))
            is SongLead.Number -> Text(
                lead.track?.toString() ?: "",
                style = OctoType.caption,
                color = OctoColors.TextMuted,
                textAlign = TextAlign.End,
                modifier = Modifier.width(28.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text((track.durationMs / 1000).toInt().asClock(), style = OctoType.caption, color = OctoColors.TextMuted)
        trailing?.invoke()
    }
}

// An artist line: round picture, name, album count.
@Composable
fun ArtistRow(artist: ArtistEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(64.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(artist.artwork, 48.dp, shape = CircleShape)
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(albums(artist.albumCount), style = OctoType.caption, color = OctoColors.TextMuted)
        }
    }
}

// An artist as a round picture with the name under it, for rows.
@Composable
fun ArtistCircle(artist: ArtistEntity, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artist.artwork, 96.dp, shape = CircleShape)
        Spacer(Modifier.height(8.dp))
        Text(
            artist.name,
            style = OctoType.caption,
            color = OctoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// The first letter a list is grouped under; anything else goes under "#".
fun indexLetter(sortKey: String): Char =
    sortKey.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
