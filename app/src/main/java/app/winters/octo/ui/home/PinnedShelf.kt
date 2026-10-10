package app.winters.octo.ui.home

import app.winters.octo.covers.playlistGlyph
import app.winters.octo.ui.common.phonePlaylistFooter
import app.winters.octo.ui.common.PlaylistArtwork
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.favourites.PinnedItem
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.menu.CollectionTarget
import app.winters.octo.ui.menu.LocalSongMenu
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.nav.PlaylistRoute
import app.winters.octo.ui.playlist.PlaylistCover

private val CardWidth = 150.dp

// The first shelf on Home: albums, artists and playlists pinned there, in
// their order. A long press opens the item's menu, which can unpin it or
// move it to the front.
fun LazyListScope.pinnedShelf(pins: List<PinnedItem>, onOpen: (NavKey) -> Unit) {
    if (pins.isEmpty()) return
    item(key = "title:Pinned") { SectionTitle("Pinned", Modifier.padding(top = 18.dp)) }
    item(key = "row:Pinned") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(pins, key = { "${it.key.kind.id}:${it.key.id}" }) { pin ->
                when (pin) {
                    is PinnedItem.Album -> AlbumCard(pin.album, onClick = { onOpen(AlbumRoute(pin.album.id)) }, modifier = Modifier.animateItem())
                    is PinnedItem.Artist -> PinnedCard(
                        title = pin.artist.name,
                        subtitle = "Artist",
                        onClick = { onOpen(ArtistRoute(pin.artist.id)) },
                        target = CollectionTarget.Artist(pin.artist.id),
                        modifier = Modifier.animateItem(),
                    ) { Artwork(pin.artist.artwork, CardWidth, shape = CircleShape) }
                    is PinnedItem.Playlist -> PinnedCard(
                        title = pin.playlist.name,
                        subtitle = "Playlist",
                        onClick = { onOpen(PlaylistRoute(pin.playlist.id)) },
                        target = CollectionTarget.Playlist(pin.playlist.id),
                        modifier = Modifier.animateItem(),
                    ) {
                        PlaylistArtwork(pin.playlist.id, pin.playlist.name, pin.playlist.covers, CardWidth, footer = phonePlaylistFooter(pin.playlist.songCount), glyph = playlistGlyph(pin.playlist.octoNotice, null)) {
                            PlaylistCover(pin.playlist.covers, CardWidth)
                        }
                    }
                }
            }
        }
    }
}

// An artist or playlist on the shelf, as wide as an album card: its picture,
// its name, and what it is.
@Composable
private fun PinnedCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    target: CollectionTarget,
    modifier: Modifier = Modifier,
    picture: @Composable () -> Unit,
) {
    val menus = LocalSongMenu.current.collections
    val haptics = LocalHapticFeedback.current
    Column(
        modifier
            .width(CardWidth)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "More options",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menus.open(target)
                },
            ),
        horizontalAlignment = Alignment.Start,
    ) {
        picture()
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = OctoType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = OctoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(subtitle, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1)
    }
}
