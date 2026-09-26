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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.OnlineAlbum
import app.winters.octo.discovery.OnlineArtist
import app.winters.octo.ui.menu.LocalSongMenu

// An album as a picture with its name and artist under it. No card
// outline: the picture carries the edge.
@Composable
fun AlbumCard(album: AlbumEntity, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 150.dp) =
    AlbumCard(album.artwork, album.title, album.artist, onClick, modifier, width)

// An album on the server, not in the library, drawn the same way.
@Composable
fun AlbumCard(album: OnlineAlbum, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 150.dp) =
    AlbumCard(album.artwork, album.title, album.artist, onClick, modifier, width)

@Composable
private fun AlbumCard(artwork: String?, title: String, artist: String, onClick: () -> Unit, modifier: Modifier, width: Dp?) {
    Column(
        (if (width != null) modifier.width(width) else modifier.fillMaxWidth())
            .clickable(onClick = onClick),
    ) {
        ArtworkFill(artwork)
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = OctoType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = OctoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            artist,
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                track.artist,
                style = OctoType.caption,
                color = OctoColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!track.onPhone) CloudMark()
        }
    }
}

// Marks a song that is not on the phone, so it streams from the server.
@Composable
private fun CloudMark() {
    Icon(
        painterResource(OctoIcons.Cloud),
        contentDescription = "Streams from your server",
        tint = OctoColors.TextMuted,
        modifier = Modifier.size(14.dp),
    )
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
    subtitle: String? = listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" • "),
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // A song found online carries a download button instead, and
            // often has no known length.
            if (!track.onPhone && !isFind(track.id)) CloudMark()
            if (track.durationMs > 0) {
                Text((track.durationMs / 1000).toInt().asClock(), style = OctoType.caption, color = OctoColors.TextMuted)
            }
        }
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
fun ArtistCircle(artist: ArtistEntity, onClick: () -> Unit) = ArtistCircle(artist.artwork, artist.name, onClick)

// An artist on the server, not in the library, drawn the same way.
@Composable
fun ArtistCircle(artist: OnlineArtist, onClick: () -> Unit) = ArtistCircle(artist.artwork, artist.name, onClick)

@Composable
private fun ArtistCircle(artwork: String?, name: String, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artwork, 96.dp, shape = CircleShape)
        Spacer(Modifier.height(8.dp))
        Text(
            name,
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
