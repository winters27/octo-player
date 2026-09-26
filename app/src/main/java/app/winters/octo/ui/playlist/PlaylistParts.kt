package app.winters.octo.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ArtworkShape
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.artworkRim
import app.winters.octo.design.elevation3
import app.winters.octo.design.mix
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.CloudMark
import app.winters.octo.ui.common.asLength
import app.winters.octo.ui.common.songs
import coil3.compose.AsyncImage

// The Liked songs picture: a heart on a wash of the accent.
private val LikedFill = mix(OctoColors.BackgroundTertiary, OctoColors.Accent, 0.3f)

private val HeaderShape = RoundedCornerShape(10.dp)

// A playlist's picture: four covers in a square once it spans four albums,
// otherwise its first cover, or the empty tile.
@Composable
fun PlaylistCover(covers: List<String>, size: Dp, modifier: Modifier = Modifier, shape: Shape = ArtworkShape) {
    if (covers.size < 4) {
        Artwork(covers.firstOrNull(), size, modifier, shape)
        return
    }
    Box(modifier.size(size).clip(shape).background(OctoColors.BackgroundTertiary)) {
        Column {
            covers.chunked(2).forEach { pair ->
                Row {
                    pair.forEach { ref ->
                        val model = remember(ref) { ArtworkRef.decode(ref) }
                        AsyncImage(model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size / 2))
                    }
                }
            }
        }
        Box(Modifier.matchParentSize().artworkRim(shape))
    }
}

// A picture made of one icon on a flat fill, for Liked songs and New playlist.
@Composable
fun IconTile(icon: Painter, fill: Color, size: Dp, modifier: Modifier = Modifier, shape: Shape = ArtworkShape) {
    Box(modifier.size(size).clip(shape).background(fill), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(size * 0.4f))
        Box(Modifier.matchParentSize().artworkRim(shape))
    }
}

@Composable
fun LikedCover(size: Dp, modifier: Modifier = Modifier, shape: Shape = ArtworkShape) =
    IconTile(painterResource(OctoIcons.Liked), LikedFill, size, modifier, shape)

// A line in a list of playlists: picture, name, and a line under it, with
// the cloud mark when the playlist is kept on the server too. `onLongClick`
// opens the playlist's menu, where it has one.
@Composable
fun PlaylistLine(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onServer: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    picture: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClick = onClick,
                        onLongClickLabel = "More options",
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                    )
                },
            )
            // At least a row tall; taller when large text needs it.
            .heightIn(min = 72.dp)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        picture()
        Column(Modifier.weight(1f)) {
            Text(title, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(subtitle, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1)
                    if (onServer) CloudMark("On your server")
                }
            }
        }
    }
}

// The first line of a list of playlists, for making a new one.
@Composable
fun NewPlaylistLine(onClick: () -> Unit) {
    PlaylistLine("New playlist", null, onClick) {
        IconTile(rememberVectorPainter(Icons.Rounded.Add), OctoColors.BackgroundTertiary, 56.dp)
    }
}

// The top of a song-list page: its picture, name, size and length, and the
// Play and Shuffle buttons once there is something to play. `onMore`
// adds a more button beside them.
@Composable
fun ListHeader(
    title: String,
    songCount: Int,
    durationMs: Long,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onMore: (() -> Unit)? = null,
    picture: @Composable (Modifier, Shape) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        picture(Modifier.elevation3(HeaderShape), HeaderShape)
        Spacer(Modifier.height(20.dp))
        Text(title, style = OctoType.title, color = OctoColors.TextPrimary, textAlign = TextAlign.Center)
        Text(
            if (songCount == 0) songs(0) else "${songs(songCount)} · ${(durationMs / 1000).toInt().asLength()}",
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            Modifier.padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (songCount > 0) {
                AccentButton("Play", onClick = onPlay)
                GlazeButton("Shuffle", onClick = onShuffle)
            }
            if (onMore != null) {
                Glaze(
                    Modifier
                        .size(48.dp)
                        .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onMore)
                        .semantics { contentDescription = "More" },
                ) {
                    Icon(painterResource(OctoIcons.More), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

// A plain line under the header when a list has nothing in it.
@Composable
fun EmptyNote(text: String) {
    Text(
        text,
        style = OctoType.bodySmall,
        color = OctoColors.TextMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
    )
}
